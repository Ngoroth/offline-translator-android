#include "tencent_runtime.h"
#include <algorithm>
#include <cstring>
#include <fstream>
#include <mutex>

namespace tencent {
namespace {
constexpr size_t max_units = 4096;
constexpr size_t max_tokens = 4096;
constexpr size_t max_context = 16896;
const std::string bos = "<｜hy_begin▁of▁sentence｜>";
const std::string user = "<｜hy_User｜>";
const std::string assistant = "<｜hy_Assistant｜>";
const std::string exact_template = "{% if messages[0]['role'] == 'system' %}{% set loop_messages = messages[1:] %}{% set system_message = messages[0]['content'] %}<｜hy_begin▁of▁sentence｜>{{ system_message }}<｜hy_place▁holder▁no▁3｜>{% else %}{% set loop_messages = messages %}<｜hy_begin▁of▁sentence｜>{% endif %}{% for message in loop_messages %}{% if message['role'] == 'user' %}<｜hy_User｜>{{ message['content'] }}{% elif message['role'] == 'assistant' %}<｜hy_Assistant｜>{{ message['content'] }}<｜hy_place▁holder▁no▁2｜>{% endif %}{% endfor %}{% if add_generation_prompt %}<｜hy_Assistant｜>{% else %}<｜hy_place▁holder▁no▁8｜>{% endif %}";

void discard_log(ggml_log_level, const char *, void *) {}

struct ClearMemory {
    llama_context *context;
    ~ClearMemory() { llama_memory_clear(llama_get_memory(context), true); }
};
}

bool detail::Utf8Prefix::append(std::string_view piece) noexcept {
    auto next = *this;
    for (const unsigned char byte : piece) {
        if (next.remaining_ != 0) {
            if (byte < next.next_min_ || byte > next.next_max_) return false;
            --next.remaining_;
            next.next_min_ = 0x80;
            next.next_max_ = 0xbf;
            if (next.complete()) next.units_ += next.scalar_units_;
        } else if (byte < 0x80) {
            ++next.units_;
        } else {
            next.scalar_units_ = 1;
            if (byte >= 0xc2 && byte <= 0xdf) {
                next.remaining_ = 1;
            } else if (byte >= 0xe0 && byte <= 0xef) {
                next.remaining_ = 2;
                if (byte == 0xe0) next.next_min_ = 0xa0;
                if (byte == 0xed) next.next_max_ = 0x9f;
            } else if (byte >= 0xf0 && byte <= 0xf4) {
                next.remaining_ = 3;
                next.scalar_units_ = 2;
                if (byte == 0xf0) next.next_min_ = 0x90;
                if (byte == 0xf4) next.next_max_ = 0x8f;
            } else return false;
        }
        ++next.bytes_;
        if (next.complete()) next.complete_bytes_ = next.bytes_;
    }
    *this = next;
    return true;
}

size_t utf16_units(std::string_view text, bool allow_incomplete, size_t *complete_bytes) {
    detail::Utf8Prefix prefix;
    if (complete_bytes) *complete_bytes = 0;
    if (!prefix.append(text) || (!allow_incomplete && !prefix.complete())) throw Failure("invalid_encoding");
    if (complete_bytes) *complete_bytes = prefix.complete_bytes();
    return prefix.units();
}

std::string normalize_newlines(const std::string &text) {
    std::string normalized;
    normalized.reserve(text.size());
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] == '\r') {
            normalized.push_back('\n');
            if (i + 1 < text.size() && text[i + 1] == '\n') ++i;
        } else normalized.push_back(text[i]);
    }
    return normalized;
}

Runtime::Runtime() {
    static std::once_flag initialized;
    std::call_once(initialized, [] { llama_log_set(discard_log, nullptr); llama_backend_init(); });
}

bool Runtime::abort_callback(void *state) noexcept {
    return static_cast<Runtime *>(state)->cancelled_.load(std::memory_order_acquire);
}
bool Runtime::progress_callback(float, void *state) noexcept { return !abort_callback(state); }
void Runtime::check_cancelled() const {
    if (cancelled_.load(std::memory_order_acquire)) throw Failure("cancelled");
}

std::string Runtime::metadata(const char *key) const {
    char buffer[4096];
    const int count = llama_model_meta_val_str(model_.get(), key, buffer, sizeof buffer);
    if (count < 0 || count >= static_cast<int>(sizeof buffer)) throw Failure("model_corrupt");
    return std::string(buffer, count);
}

void Runtime::load(const std::string &path) {
    check_cancelled();
    utf16_units(path);
    if (path.find('\0') != std::string::npos) throw Failure("invalid_encoding");
    if (model_) return;
    std::ifstream file(path, std::ios::binary);
    if (!file) throw Failure("model_missing");
    char magic[4]{};
    file.read(magic, sizeof magic);
    if (file.gcount() != 4 || std::memcmp(magic, "GGUF", 4) != 0) throw Failure("model_corrupt");
    file.close();
    auto parameters = llama_model_default_params();
    parameters.n_gpu_layers = 0;
    parameters.progress_callback = progress_callback;
    parameters.progress_callback_user_data = this;
    model_.reset(llama_model_load_from_file(path.c_str(), parameters));
    try {
        check_cancelled();
        if (!model_) throw Failure("model_load");
        vocab_ = llama_model_get_vocab(model_.get());
        const char *chat_template = llama_model_chat_template(model_.get(), nullptr);
        if (!chat_template || exact_template != chat_template ||
            metadata("general.architecture") != "hunyuan-dense" ||
            metadata("general.file_type") != "15" || llama_vocab_n_tokens(vocab_) != 120818 ||
            llama_vocab_bos(vocab_) != 120000 || llama_vocab_eos(vocab_) != 120020 ||
            tokenize(bos + user, true) != std::vector<llama_token>({120000, 120006}) ||
            tokenize(assistant, true) != std::vector<llama_token>({120007})) throw Failure("model_corrupt");
        // Size reusable piece storage once from exact model pieces, not from
        // C-string lengths or an arbitrary token-size assumption.
        int capacity = 1;
        char unused;
        for (llama_token token = 0; token < llama_vocab_n_tokens(vocab_); ++token) {
            if (token % 256 == 0) check_cancelled();
            const int count = llama_token_to_piece(vocab_, token, &unused, 0, 0, false);
            if (count > 0) throw Failure("model_corrupt");
            capacity = std::max(capacity, -count);
        }
        piece_buffer_.resize(capacity);
        check_cancelled();
    } catch (...) {
        vocab_ = nullptr;
        piece_buffer_.clear();
        model_.reset();
        throw;
    }
}

std::vector<llama_token> Runtime::tokenize(const std::string &text, bool special) const {
    std::vector<llama_token> tokens(text.size() + 16);
    const int count = llama_tokenize(vocab_, text.data(), static_cast<int>(text.size()), tokens.data(),
                                     static_cast<int>(tokens.size()), false, special);
    if (count < 0) throw Failure("token_limit");
    tokens.resize(count);
    return tokens;
}
std::string Runtime::detokenize(const std::vector<llama_token> &tokens) const {
    std::string text(tokens.size() * 32 + 128, '\0');
    int count = llama_detokenize(vocab_, tokens.data(), static_cast<int>(tokens.size()), text.data(),
                                static_cast<int>(text.size()), false, false);
    if (count < 0) {
        text.resize(-count);
        count = llama_detokenize(vocab_, tokens.data(), static_cast<int>(tokens.size()), text.data(),
                                static_cast<int>(text.size()), false, false);
    }
    if (count < 0) throw Failure("invalid_encoding");
    text.resize(count);
    return text;
}

void Runtime::ensure_context(size_t required) {
    const auto rounded = static_cast<uint32_t>((required + 255) / 256 * 256);
    if (rounded > max_context) throw Failure("token_limit");
    check_cancelled();
    if (!context_ || llama_n_ctx(context_.get()) < rounded) {
        context_.reset();
        auto parameters = llama_context_default_params();
        parameters.n_ctx = rounded;
        parameters.n_batch = 256;
        parameters.n_ubatch = 128;
        parameters.n_threads = 4;
        parameters.n_threads_batch = 4;
        parameters.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        parameters.abort_callback = abort_callback;
        parameters.abort_callback_data = this;
        context_.reset(llama_init_from_model(model_.get(), parameters));
        check_cancelled();
        if (!context_) throw Failure("memory");
    }
    llama_memory_clear(llama_get_memory(context_.get()), true);
}
void Runtime::decode(llama_token *tokens, int count) {
    check_cancelled();
    const int code = llama_decode(context_.get(), llama_batch_get_one(tokens, count));
    check_cancelled();
    if (code == 2) throw Failure("cancelled");
    if (code == -2) throw Failure("memory");
    if (code != 0) throw Failure("model_load");
}

std::string Runtime::translate(const std::string &target, const std::string &text) {
    check_cancelled();
    if (utf16_units(text) > max_units) throw Failure("input_limit");
    if (!model_) throw Failure("model_missing");
    // The backend supplies catalog English names. Bound this instruction component too.
    if (target.empty() || target.size() > 64 || !std::all_of(target.begin(), target.end(), [](char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ';
    })) throw Failure("invalid_encoding");
    std::string normalized;
    const bool changed = text.find('\r') != std::string::npos;
    if (changed) normalized = normalize_newlines(text);
    const auto &source = changed ? normalized : text;
    const std::string content = "Translate the following segment into " + target +
        ", without additional explanation.\n\n" + source;
    auto body = tokenize(content, false);
    if (detokenize(body) != content || std::any_of(body.begin(), body.end(), [this](llama_token token) {
        return llama_vocab_is_control(vocab_, token);
    })) throw Failure("invalid_encoding");
    std::vector<llama_token> prompt;
    prompt.reserve(body.size() + 3);
    prompt.push_back(120000);
    prompt.push_back(120006);
    prompt.insert(prompt.end(), body.begin(), body.end());
    prompt.push_back(120007);
    ensure_context(prompt.size() + max_tokens + 1);
    ClearMemory reset{context_.get()};
    for (size_t offset = 0; offset < prompt.size(); offset += 256) {
        decode(prompt.data() + offset, static_cast<int>(std::min<size_t>(256, prompt.size() - offset)));
    }
    std::string output;
    output.reserve(max_units * 3);
    detail::Utf8Prefix prefix;
    const auto read_piece = [&](llama_token token) {
        const int count = llama_token_to_piece(vocab_, token, piece_buffer_.data(),
                                               static_cast<int>(piece_buffer_.size()), 0, false);
        if (count < 0 || static_cast<size_t>(count) > piece_buffer_.size()) throw Failure("invalid_encoding");
        return count;
    };
    for (size_t sampled = 0; sampled < max_tokens; ++sampled) {
        check_cancelled();
        auto next_prefix = prefix;
        int selected_bytes = 0;
        llama_token buffered_token = -1;
        const auto eligible = [&](llama_token candidate) {
            check_cancelled();
            if (candidate == 120020) return prefix.complete();
            if (llama_vocab_is_eog(vocab_, candidate) || llama_vocab_is_control(vocab_, candidate)) return false;
            const int count = read_piece(candidate);
            buffered_token = candidate;
            auto tentative = prefix;
            if (!tentative.append(std::string_view(piece_buffer_.data(), count))) return false;
            // The selector only evaluates alternatives that would become the
            // current best, so retain that candidate's scalar state and length.
            next_prefix = tentative;
            selected_bytes = count;
            return true;
        };
        auto token = detail::constrained_greedy(llama_get_logits_ith(context_.get(), -1),
                                               llama_vocab_n_tokens(vocab_), eligible);
        check_cancelled();
        if (token == 120020) return output;
        if (next_prefix.units() > max_units) throw Failure("output_limit");
        // A rejected alternative may overwrite storage, but never prefix state.
        // The ordinary eligible-maximum path converts and validates just once.
        if (buffered_token != token && read_piece(token) != selected_bytes) throw Failure("invalid_encoding");
        output.append(piece_buffer_.data(), selected_bytes);
        prefix = next_prefix;
        if (sampled + 1 < max_tokens) decode(&token, 1);
    }
    throw Failure("token_limit");
}
}
