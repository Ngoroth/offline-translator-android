#pragma once

#include <cstddef>
#include <cstdint>
#include <exception>
#include <string>
#include <string_view>

namespace mozilla {
class Failure final : public std::exception {
 public:
  explicit Failure(const char* code) noexcept : code_(code) {}
  const char* what() const noexcept override { return code_; }
 private:
  const char* code_;
};

inline bool whitespace(uint32_t c) {
  return (c >= 9 && c <= 13) || (c >= 0x1c && c <= 0x20) || c == 0xa0 ||
         c == 0x1680 || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 ||
         c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000;
}

// RFC 3629, including scalar validity, NUL exclusion and UTF-16 code-unit bounds.
inline size_t validateUtf8(std::string_view text, const char* limitCode,
                           bool requireNonblank = true) {
  size_t units = 0;
  bool nonblank = false;
  for (size_t i = 0; i < text.size();) {
    uint32_t c = static_cast<unsigned char>(text[i++]);
    unsigned trailing = 0;
    uint32_t minimum = 0;
    if (c >= 0xc2 && c <= 0xdf) { c &= 0x1f; trailing = 1; minimum = 0x80; }
    else if (c >= 0xe0 && c <= 0xef) { c &= 0xf; trailing = 2; minimum = 0x800; }
    else if (c >= 0xf0 && c <= 0xf4) { c &= 7; trailing = 3; minimum = 0x10000; }
    else if (c >= 0x80) throw Failure("invalid_encoding");
    if (trailing > text.size() - i) throw Failure("invalid_encoding");
    for (unsigned j = 0; j < trailing; ++j) {
      unsigned char next = text[i++];
      if ((next & 0xc0) != 0x80) throw Failure("invalid_encoding");
      c = (c << 6) | (next & 0x3f);
    }
    if (c < minimum || c == 0 || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff))
      throw Failure("invalid_encoding");
    units += c > 0xffff ? 2 : 1;
    if (units > 4096) throw Failure(limitCode);
    nonblank |= !whitespace(c);
  }
  if (requireNonblank && !nonblank) throw Failure("invalid_encoding");
  return units;
}

inline void requireEos(bool observed) {
  if (!observed) throw Failure("token_limit");
}
}  // namespace mozilla
