#include <jni.h>
#include "mozilla_contract.h"
#include "translator/service.h"
#include "translator/byte_array_util.h"
#include "tensors/cpu/aligned.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <csignal>
#include <exception>
#include <map>
#include <memory>
#include <mutex>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <system_error>
#include <unistd.h>

namespace {
namespace mt = marian::bergamot;
using mozilla::Failure;
// Marian's named loggers are process-global within this hidden static closure.
// All upstream work, including disposal, is serialized; no async service is used.
std::mutex upstreamMutex;
constexpr std::array<const char*, 9> codes = {"input_limit", "token_limit", "output_limit",
  "invalid_encoding", "memory", "model_missing", "artifact_corrupt", "runtime_load", "closed"};
std::array<jthrowable, codes.size()> exceptions{};

const char* failureCode() noexcept {
  try { throw; }
  catch(const Failure& e) { return e.what(); }
  catch(const std::bad_alloc&) { return "memory"; }
  catch(const std::system_error& e) {
    return e.code() == std::errc::not_enough_memory ? "memory" : "runtime_load";
  }
  catch(const YAML::Exception&) { return "artifact_corrupt"; }
  catch(...) { return "runtime_load"; }
}

void failJava(JNIEnv* env, const char* code) noexcept {
  if(env->ExceptionCheck()) env->ExceptionClear();
  size_t index = 7;
  for(size_t i = 0; i < codes.size(); ++i)
    if(std::string_view(codes[i]) == code) { index = i; break; }
  env->Throw(exceptions[index]);
}

struct Owner {
  std::map<std::string, std::shared_ptr<mt::TranslationModel>> models;
  std::unique_ptr<mt::BlockingService> service;
  bool poisoned = false;
  void dispose() noexcept {
    // The pool may retain requests referring to the failed call's response stack.
    // Destruction drops them, never invokes callbacks or processes another batch.
    service.reset();
    models.clear();
  }
  void poison() noexcept { poisoned = true; dispose(); }
  ~Owner() { dispose(); }
};

Owner& owner(jlong handle) {
  if(!handle) throw Failure("closed");
  auto& value = *reinterpret_cast<Owner*>(handle);
  if(value.poisoned) throw Failure("closed");
  return value;
}

std::string bytes(JNIEnv* env, jbyteArray input, size_t maximum = 16384) {
  if(!input) throw Failure("invalid_encoding");
  jsize size = env->GetArrayLength(input);
  if(static_cast<size_t>(size) > maximum) throw Failure("input_limit");
  std::string result(static_cast<size_t>(size), '\0');
  env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(result.data()));
  if(env->ExceptionCheck()) throw Failure("memory");
  return result;
}

std::string element(JNIEnv* env, jobjectArray values, jsize index) {
  auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(values, index));
  if(env->ExceptionCheck()) throw Failure("memory");
  try {
    auto result = bytes(env, value);
    mozilla::validateUtf8(result, "input_limit");
    env->DeleteLocalRef(value);
    return result;
  } catch(...) { env->DeleteLocalRef(value); throw; }
}

bool validRoute(std::string_view route) {
  constexpr std::array<std::string_view, 16> languages = {
    "vi", "es", "id", "it", "zh", "ko", "de", "pl", "pt", "ru", "th", "tr", "fr", "hi", "ja", "nl"};
  if(route.size() != 5 || route[2] != '-') return false;
  const auto source = route.substr(0, 2), target = route.substr(3, 2);
  const auto language = source == "en" ? target : target == "en" ? source : std::string_view();
  return std::find(languages.begin(), languages.end(), language) != languages.end();
}

void requireFile(const std::string& path, bool allowEmpty = false) {
  if(path.empty() || path[0] != '/') throw Failure("artifact_corrupt");
  struct stat info{};
  if(stat(path.c_str(), &info)) {
    if(errno == ENOENT || errno == ENOTDIR) throw Failure("model_missing");
    if(errno == ENOMEM) throw std::bad_alloc();
    throw Failure("runtime_load");
  }
  if(!S_ISREG(info.st_mode) || (!allowEmpty && info.st_size <= 0)) throw Failure("artifact_corrupt");
}

std::shared_ptr<mt::TranslationModel> loadModel(const std::string& path) {
  requireFile(path);
  auto options = mt::parseOptionsFromFilePath(path, false);
  auto models = options->get<std::vector<std::string>>("models");
  auto vocabs = options->get<std::vector<std::string>>("vocabs");
  auto shortlist = options->get<std::vector<std::string>>("shortlist");
  if(models.size() != 1 || vocabs.size() != 2 || shortlist.empty()) throw Failure("artifact_corrupt");
  for(auto& file : models) requireFile(file);
  for(auto& file : vocabs) requireFile(file);
  requireFile(shortlist.front());
  requireFile(options->get<std::string>("ssplit-prefix-file", ""), true);
  // Fixed decoding policy; topology still comes from embedded special:model.yml.
  options->set("beam-size", 1);
  options->set("normalize", 1.0f);
  options->set("word-penalty", 0.0f);
  options->set("max-length-factor", 2.0f);
  options->set("max-length-break", 128);
  options->set("mini-batch-words", 1024);
  options->set("workspace", 128);
  options->set("cpu-threads", 4);
  options->set("gemm-precision", std::string("int8shiftAll"));
  options->set("skip-cost", true);
  options->set("check-bytearray", true);
  auto memory = mt::getMemoryBundleFromConfig(options);
  for(auto& model : memory.models)
    if(!mt::validateBinaryModel(model, model.size())) throw Failure("artifact_corrupt");
  auto model = std::make_shared<mt::TranslationModel>(options, std::move(memory), 1);
  model->initializeBackends();
  return model;
}

jbyteArray resultBytes(JNIEnv* env, const std::string& value) {
  auto result = env->NewByteArray(static_cast<jsize>(value.size()));
  if(!result) throw std::bad_alloc();
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()),
                          reinterpret_cast<const jbyte*>(value.data()));
  if(env->ExceptionCheck()) throw std::bad_alloc();
  return result;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  // Must precede every fallible Marian operation, including logger creation.
  marian::setThrowExceptionOnAbort(true);
  JNIEnv* env = nullptr;
  if(vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  auto type = env->FindClass("io/github/ngoroth/offlinetranslator/mozilla/MozillaNativeException");
  if(!type) return JNI_ERR;
  auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;)V");
  if(!constructor) { env->DeleteLocalRef(type); return JNI_ERR; }
  std::array<jthrowable, codes.size()> prepared{};
  auto discard = [&]() {
    for(auto exception : prepared) if(exception) env->DeleteGlobalRef(exception);
    env->DeleteLocalRef(type);
    return JNI_ERR;
  };
  for(size_t i = 0; i < codes.size(); ++i) {
    auto code = env->NewStringUTF(codes[i]);
    if(!code) return discard();
    auto exception = env->NewObject(type, constructor, code);
    env->DeleteLocalRef(code);
    if(!exception) return discard();
    prepared[i] = static_cast<jthrowable>(env->NewGlobalRef(exception));
    env->DeleteLocalRef(exception);
    if(!prepared[i]) return discard();
  }
  exceptions = prepared;
  env->DeleteLocalRef(type);
  return JNI_VERSION_1_6;
}

#define JNI_METHOD(name) Java_io_github_ngoroth_offlinetranslator_mozilla_MozillaNativeRuntime_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreate)(JNIEnv* env, jobject) {
  try { return reinterpret_cast<jlong>(new Owner()); }
  catch(...) { failJava(env, failureCode()); return 0; }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeLoad)(JNIEnv* env, jobject, jlong handle,
                                                         jobjectArray routes, jobjectArray paths) {
  std::unique_lock<std::mutex> lock(upstreamMutex, std::defer_lock);
  Owner* state = nullptr;
  try {
    lock.lock();
    state = &owner(handle);
    if(state->service || !routes || !paths) throw Failure("runtime_load");
    auto count = env->GetArrayLength(routes);
    if(count < 1 || count > 32 || env->GetArrayLength(paths) != count) throw Failure("runtime_load");
    mt::BlockingService::Config config;
    config.cacheSize = 0;
    state->service = std::make_unique<mt::BlockingService>(config);
    for(jsize i = 0; i < count; ++i) {
      auto route = element(env, routes, i);
      if(!validRoute(route) || state->models.count(route)) throw Failure("runtime_load");
      auto model = loadModel(element(env, paths, i));
      state->models.emplace(std::move(route), std::move(model));
    }
  } catch(...) {
    const char* code = failureCode();
    if(state) state->poison();
    failJava(env, code);
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeTranslate)(JNIEnv* env, jobject, jlong handle,
                                                                    jbyteArray route, jbyteArray text) {
  std::unique_lock<std::mutex> lock(upstreamMutex, std::defer_lock);
  Owner* state = nullptr;
  try {
    lock.lock();
    state = &owner(handle);
    auto key = bytes(env, route, 16);
    auto model = state->models.find(key);
    if(!state->service || model == state->models.end()) throw Failure("runtime_load");
    auto input = bytes(env, text);
    mozilla::validateUtf8(input, "input_limit");
    std::vector<std::string> sources;
    sources.emplace_back(std::move(input));
    auto responses = state->service->translateMultiple(model->second, std::move(sources), {mt::ResponseOptions()});
    if(responses.size() != 1) throw Failure("runtime_load");
    auto& response = responses.front();
    if(response.sentenceEos.empty() || response.sentenceEos.size() != response.source.numSentences())
      throw Failure("token_limit");
    for(bool eos : response.sentenceEos) mozilla::requireEos(eos);
    mozilla::validateUtf8(response.target.text, "output_limit");
    return resultBytes(env, response.target.text);
  } catch(...) {
    const char* code = failureCode();
    if(state) state->poison();
    failJava(env, code);
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv* env, jobject, jlong handle) {
  try {
    std::lock_guard<std::mutex> lock(upstreamMutex);
    delete reinterpret_cast<Owner*>(handle);
  } catch(...) { failJava(env, failureCode()); }
}

// Private instrumentation entry points: no model substitution or fault injection
// exists in the production load/translate interface.
#define TEST_METHOD(name) Java_io_github_ngoroth_offlinetranslator_mozilla_MozillaNativeRuntimeTest_##name
extern "C" JNIEXPORT void JNICALL TEST_METHOD(nativeCheck)(JNIEnv* env, jobject, jint kind, jbyteArray value) {
  try {
    std::lock_guard<std::mutex> lock(upstreamMutex);
    if(kind == 0 || kind == 1) {
      auto input = bytes(env, value, 65536);
      mozilla::validateUtf8(input, kind == 0 ? "input_limit" : "output_limit");
    } else if(kind == 2) {
      mozilla::requireEos(false);
    } else if(kind == 3 || kind == 4) {
      // Child-only address-space cap provokes genuine upstream posix_memalign
      // ENOMEM without allocating/exhausting the shared ART process.
      pid_t child = fork();
      if(child < 0) throw std::system_error(errno, std::generic_category());
      if(child == 0) {
        struct rlimit limit{64 * 1024 * 1024, 64 * 1024 * 1024};
        if(setrlimit(RLIMIT_AS, &limit)) _exit(71);
        try {
          if(kind == 3) { mt::AlignedMemory memory(128 * 1024 * 1024, 256); }
          else { void* p = marian::cpu::genericMalloc(256, 128 * 1024 * 1024); marian::cpu::genericFree(p); }
          _exit(72);
        } catch(...) { _exit(std::string_view(failureCode()) == "memory" ? 73 : 74); }
      }
      int status = 0;
      pid_t waited;
      do { waited = waitpid(child, &status, 0); } while(waited < 0 && errno == EINTR);
      if(waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 73) throw Failure("runtime_load");
      throw std::bad_alloc();
    } else if(kind == 5) {
      ABORT("Native contract upstream throwing-mode check");
    } else throw Failure("runtime_load");
  } catch(...) { failJava(env, failureCode()); }
}

extern "C" JNIEXPORT jlongArray JNICALL TEST_METHOD(nativeHandlers)(JNIEnv* env, jobject) {
  try {
    std::lock_guard<std::mutex> lock(upstreamMutex);
    std::array<jlong, 2 * (NSIG + 2) + 1> values{};
    size_t index = 0;
    values[index++] = reinterpret_cast<jlong>(std::get_terminate());
    for(int signal : {SIGSEGV, SIGFPE}) {
      struct sigaction action{};
      if(sigaction(signal, nullptr, &action)) throw Failure("runtime_load");
      values[index++] = reinterpret_cast<jlong>(action.sa_sigaction);
      values[index++] = action.sa_flags;
      for(int member = 1; member < NSIG; ++member) values[index++] = sigismember(&action.sa_mask, member);
    }
    auto result = env->NewLongArray(static_cast<jsize>(index));
    if(!result) throw std::bad_alloc();
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(index), values.data());
    if(env->ExceptionCheck()) throw std::bad_alloc();
    return result;
  } catch(...) { failJava(env, failureCode()); return nullptr; }
}
