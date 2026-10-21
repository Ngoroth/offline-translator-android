#pragma once

#include "llama.h"
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <string_view>

namespace tencent {
class Failure final : public std::runtime_error {
public:
    explicit Failure(const char *code) : std::runtime_error(code) {}
};

// Provider-private decoding primitives, also exercised by the native smoke program.
namespace detail {
class Utf8Prefix final {
public:
    // Transactional: an invalid piece leaves this prefix unchanged.
    bool append(std::string_view piece) noexcept;
    bool complete() const noexcept { return remaining_ == 0; }
    size_t units() const noexcept { return units_; }
    size_t complete_bytes() const noexcept { return complete_bytes_; }
private:
    size_t units_ = 0;
    size_t bytes_ = 0;
    size_t complete_bytes_ = 0;
    unsigned char remaining_ = 0;
    unsigned char next_min_ = 0x80;
    unsigned char next_max_ = 0xbf;
    unsigned char scalar_units_ = 1;
};

// Match llama.cpp's raw-logit maximum and first-token tie order. Only examine
// alternative pieces when that maximum is ineligible; never allocate candidates.
template<class Eligible>
llama_token constrained_greedy(const float *logits, int count, const Eligible &eligible) {
    if (!logits || count <= 0) throw Failure("invalid_encoding");
    llama_token maximum = 0;
    for (llama_token token = 1; token < count; ++token) {
        if (logits[token] > logits[maximum]) maximum = token;
    }
    if (eligible(maximum)) return maximum;
    llama_token selected = -1;
    for (llama_token token = 0; token < count; ++token) {
        if (token != maximum && (selected < 0 || logits[token] > logits[selected]) && eligible(token)) {
            selected = token;
        }
    }
    if (selected < 0) throw Failure("invalid_encoding");
    return selected;
}
}

// Canonical UTF-8; incomplete final scalars are allowed only during token assembly.
size_t utf16_units(std::string_view text, bool allow_incomplete = false, size_t *complete_bytes = nullptr);
std::string normalize_newlines(const std::string &text);

class Runtime final {
public:
    Runtime();
    void load(const std::string &path);
    std::string translate(const std::string &target, const std::string &text);
    void reset_cancellation() noexcept { cancelled_.store(false, std::memory_order_release); }
    void cancel() noexcept { cancelled_.store(true, std::memory_order_release); }
private:
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model_{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context_{nullptr, llama_free};
    const llama_vocab *vocab_ = nullptr;
    std::vector<char> piece_buffer_;
    std::atomic<bool> cancelled_{false};
    static bool abort_callback(void *state) noexcept;
    static bool progress_callback(float, void *state) noexcept;
    void check_cancelled() const;
    std::string metadata(const char *key) const;
    std::vector<llama_token> tokenize(const std::string &text, bool special) const;
    std::string detokenize(const std::vector<llama_token> &tokens) const;
    void ensure_context(size_t required);
    void decode(llama_token *tokens, int count);
};
}
