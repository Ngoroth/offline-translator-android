#include "tencent_runtime.h"
#include <chrono>
#include <future>
#include <iostream>
#include <thread>
#include <limits>

namespace {
void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}
template<class F> void expect(const char *code, F action) {
    try { action(); }
    catch (const tencent::Failure &error) {
        require(std::string(error.what()) == code, "Wrong typed native failure");
        return;
    }
    throw std::runtime_error("Expected typed native failure");
}

void decoding_boundaries() {
    using tencent::detail::Utf8Prefix;
    using tencent::detail::constrained_greedy;
    const std::string_view invalid[] = {
        "\x80", "\xc0\x80", "\xc1\xbf", "\xe0\x80", "\xe0\x9f\xbf",
        "\xed\xa0", "\xed\xbf\xbf", "\xf0\x80", "\xf0\x8f\xbf\xbf",
        "\xf4\x90", "\xf4\xbf\xbf\xbf", "\xf5", "\xff", "A\xe0\x41"
    };
    for (auto piece : invalid) {
        expect("invalid_encoding", [&] { tencent::utf16_units(piece, true); });
        Utf8Prefix prefix;
        require(!prefix.append(piece) && prefix.complete() && prefix.units() == 0 &&
                prefix.complete_bytes() == 0, "Invalid piece committed partial scalar state");
    }
    const std::string_view valid[] = {
        "\xc2\x80", "\xdf\xbf", "\xe0\xa0\x80", "\xed\x9f\xbf",
        "\xee\x80\x80", "\xef\xbf\xbf", "\xf0\x90\x80\x80", "\xf4\x8f\xbf\xbf"
    };
    for (auto piece : valid) {
        const size_t units = piece.size() == 4 ? 2 : 1;
        require(tencent::utf16_units(piece) == units, "Canonical scalar boundary count changed");
        for (size_t split = 1; split < piece.size(); ++split) {
            Utf8Prefix prefix;
            require(prefix.append(piece.substr(0, split)) && !prefix.complete() && prefix.units() == 0,
                    "Viable partial scalar rejected or counted too early");
            require(!prefix.append("A") && !prefix.complete(), "Non-continuation accepted across pieces");
            require(prefix.append(piece.substr(split)) && prefix.complete() &&
                    prefix.units() == units && prefix.complete_bytes() == piece.size(),
                    "Split scalar completion or rejection rollback changed");
        }
    }
    struct Restricted { std::string_view lead, invalid, completion; };
    const Restricted restricted[] = {
        {"\xe0", "\x9f", "\xa0\x80"}, {"\xed", "\xa0", "\x9f\xbf"},
        {"\xf0", "\x8f", "\x90\x80\x80"}, {"\xf4", "\x90", "\x8f\xbf\xbf"}
    };
    for (const auto &boundary : restricted) {
        Utf8Prefix prefix;
        require(prefix.append(boundary.lead) && !prefix.append(boundary.invalid) &&
                prefix.append(boundary.completion) && prefix.complete(),
                "Restrictive next-byte range or transactional rejection changed");
    }
    Utf8Prefix prefix;
    require(prefix.append(std::string_view("\0\xe0\xb8", 3)) && !prefix.complete() &&
            prefix.units() == 1 && prefix.complete_bytes() == 1, "Length-delimited NUL/pending scalar changed");
    require(!prefix.append("\xe0") && prefix.append("\x81\xf0") && !prefix.complete() &&
            prefix.units() == 2 && prefix.complete_bytes() == 4, "Cross-piece scalar state was lost");
    require(prefix.append("\x9f") && prefix.append("\x98\x80") && prefix.complete() &&
            prefix.units() == 4 && prefix.complete_bytes() == 8, "Split supplementary UTF-16 count changed");

    struct Candidate { std::string_view piece; bool eos, forbidden; };
    const Candidate candidates[] = {
        {"A", false, false}, {"\x81", false, false}, {"\x82", false, false},
        {{}, true, false}, {"\x83", false, true}
    };
    const auto eligible = [&](llama_token token) {
        const auto &candidate = candidates[token];
        if (candidate.eos) return prefix.complete();
        if (candidate.forbidden) return false;
        auto tentative = prefix;
        return tentative.append(candidate.piece);
    };
    const float maximum[] = {10, 4, 3, 8, 9};
    require(constrained_greedy(maximum, 5, eligible) == 0, "Eligible ordinary maximum changed");
    const float tie[] = {10, 4, 3, 10, 9};
    require(constrained_greedy(tie, 5, eligible) == 0, "Eligible ordinary tie order changed");
    const auto negative_infinity = -std::numeric_limits<float>::infinity();
    const float tied_infinities[] = {
        negative_infinity, negative_infinity, negative_infinity, negative_infinity, negative_infinity
    };
    require(constrained_greedy(tied_infinities, 5, eligible) == 0, "Infinite-logit tie order changed");
    const float eos[] = {9, 4, 3, 10, 8};
    require(constrained_greedy(eos, 5, eligible) == 3, "Complete-boundary EOS rejected");
    const float forbidden[] = {9, 4, 3, 8, 10};
    require(constrained_greedy(forbidden, 5, eligible) == 0, "Forbidden control admitted");
    prefix = Utf8Prefix{};
    require(prefix.append("\xe0\xb8"), "Valid pending scalar rejected");
    const float alternatives[] = {10, 7, 7, 9, 8};
    require(constrained_greedy(alternatives, 5, eligible) == 1,
            "Constrained alternative maximum/tie or incomplete EOS changed");
    const float later_maximum[] = {10, 6, 7, 9, 8};
    require(constrained_greedy(later_maximum, 5, eligible) == 2, "Higher eligible alternative was skipped");
    require(constrained_greedy(tied_infinities, 5, eligible) == 1, "Eligible infinite-logit alternatives rejected");
    prefix = Utf8Prefix{};
    require(prefix.append("\xe0"), "Valid restricted prefix rejected");
    expect("invalid_encoding", [&] { constrained_greedy(alternatives, 5, eligible); });
}
}

int main(int argc, char **argv) {
    try {
        require(argc == 2, "Usage: tencent_native_smoke VERIFIED_MODEL_GGUF");
        const std::string original = std::string("A\0", 2) + "😀\r\nB\rC\n\r\r\n";
        require(tencent::utf16_units(original) == 13, "UTF-16 scalar/NUL count changed");
        require(tencent::normalize_newlines(original) == std::string("A\0", 2) + "😀\nB\nC\n\n\n", "Newline policy changed");
        expect("invalid_encoding", [] { tencent::utf16_units(std::string("\xc0\x80", 2)); });
        expect("invalid_encoding", [] { tencent::utf16_units(std::string("\xed\xa0\x80", 3)); });
        expect("invalid_encoding", [] { tencent::utf16_units(std::string("\xf0\x9f", 2)); });
        size_t completed = 99;
        require(tencent::utf16_units("A\xf0\x9f", true, &completed) == 1 && completed == 1,
                "Token boundary must retain incomplete scalar bytes");
        decoding_boundaries();
        tencent::Runtime runtime;
        runtime.cancel();
        expect("cancelled", [&] { runtime.load(argv[1]); });
        runtime.reset_cancellation();
        expect("model_missing", [&] { runtime.load("/nonexistent/tencent-model.gguf"); });
        runtime.load(argv[1]);
        const std::string russian = "Привет! Как дела?";
        const auto first = runtime.translate("English", russian);
        const auto reverse = runtime.translate("Russian", "Hello! How are you?");
        require(reverse.find("Привет") != std::string::npos, "Real EN-to-RU semantic control failed");
        require(runtime.translate("English", russian) == first, "Independent request leaked state");
        expect("input_limit", [&] { runtime.translate("English", std::string(4097, '\r')); });
        const std::string literal = "Hello 😀" + std::string(1, '\0') +
            "world!\r\nLiteral <｜hy_User｜> and <｜hy_Assistant｜> marker.\r";
        const auto normalized = tencent::normalize_newlines(literal);
        require(runtime.translate("Russian", literal) == runtime.translate("Russian", normalized),
                "Canonical Unicode/NUL/control-marker/newline path changed");
        std::string long_text;
        for (int i = 0; i < 800; ++i) long_text += "мир ";
        auto worker = std::async(std::launch::async, [&] {
            expect("cancelled", [&] { runtime.translate("English", long_text); });
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        require(worker.wait_for(std::chrono::milliseconds(0)) != std::future_status::ready,
                "Cancellation fixture finished before cancellation");
        runtime.cancel();
        worker.get(); // Join actual native work before reset or release.
        runtime.reset_cancellation();
        require(runtime.translate("English", russian) == first, "Abort did not reset independent context");
        std::cout << "PASS canonical prefix/greedy boundaries, real constrained-greedy EOS translation, reuse/reset, Unicode/NUL/control isolation, newline policy, original limit, abort/join/reset\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
