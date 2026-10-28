// EXIF extraction from camera JPEG data and orientation normalisation.
// Every read is bounds-checked: this code parses untrusted bytes.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <cstring>

#include "daybreak/engine.hpp"

namespace daybreak {
namespace {

constexpr std::uint16_t kOrientationTag = 0x0112;
constexpr std::uint16_t kTypeShort = 3;

struct Tiff {
  std::span<std::uint8_t> bytes;
  bool little_endian = true;

  bool in_range(std::size_t offset, std::size_t length) const {
    return offset <= bytes.size() && length <= bytes.size() - offset;
  }
  std::uint16_t u16(std::size_t offset) const {
    const std::uint8_t a = bytes[offset];
    const std::uint8_t b = bytes[offset + 1];
    return little_endian ? static_cast<std::uint16_t>(a | (b << 8))
                         : static_cast<std::uint16_t>((a << 8) | b);
  }
  std::uint32_t u32(std::size_t offset) const {
    const std::uint32_t a = bytes[offset], b = bytes[offset + 1], c = bytes[offset + 2],
                        d = bytes[offset + 3];
    return little_endian ? (a | (b << 8) | (c << 16) | (d << 24))
                         : ((a << 24) | (b << 16) | (c << 8) | d);
  }
  void put_u16(std::size_t offset, std::uint16_t value) {
    if (little_endian) {
      bytes[offset] = static_cast<std::uint8_t>(value & 0xff);
      bytes[offset + 1] = static_cast<std::uint8_t>(value >> 8);
    } else {
      bytes[offset] = static_cast<std::uint8_t>(value >> 8);
      bytes[offset + 1] = static_cast<std::uint8_t>(value & 0xff);
    }
  }
  void put_u32(std::size_t offset, std::uint32_t value) {
    for (int i = 0; i < 4; ++i) {
      const int shift = little_endian ? 8 * i : 8 * (3 - i);
      bytes[offset + i] = static_cast<std::uint8_t>((value >> shift) & 0xff);
    }
  }
};

}  // namespace

std::vector<std::uint8_t> exif_tiff_from_jpeg(std::span<const std::uint8_t> jpeg) {
  if (jpeg.size() < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8) return {};
  std::size_t pos = 2;
  while (pos + 4 <= jpeg.size()) {
    if (jpeg[pos] != 0xFF) return {};
    const std::uint8_t marker = jpeg[pos + 1];
    if (marker == 0xFF) {  // fill byte
      ++pos;
      continue;
    }
    if (marker == 0xD9 || marker == 0xDA) return {};  // end of image / start of scan
    const std::size_t length = (std::size_t{jpeg[pos + 2]} << 8) | jpeg[pos + 3];
    if (length < 2 || pos + 2 + length > jpeg.size()) return {};
    const std::size_t payload = pos + 4;
    const std::size_t payload_size = length - 2;
    static constexpr std::uint8_t kExifHeader[6] = {'E', 'x', 'i', 'f', 0, 0};
    if (marker == 0xE1 && payload_size > sizeof(kExifHeader) &&
        std::memcmp(jpeg.data() + payload, kExifHeader, sizeof(kExifHeader)) == 0) {
      const auto begin = jpeg.begin() + static_cast<std::ptrdiff_t>(payload + sizeof(kExifHeader));
      const auto end = jpeg.begin() + static_cast<std::ptrdiff_t>(payload + payload_size);
      return std::vector<std::uint8_t>(begin, end);
    }
    pos += 2 + length;
  }
  return {};
}

bool normalize_exif_orientation(std::span<std::uint8_t> bytes) {
  Tiff tiff{bytes};
  if (!tiff.in_range(0, 8)) return false;
  if (bytes[0] == 'I' && bytes[1] == 'I') {
    tiff.little_endian = true;
  } else if (bytes[0] == 'M' && bytes[1] == 'M') {
    tiff.little_endian = false;
  } else {
    return false;
  }
  if (tiff.u16(2) != 42) return false;

  const std::size_t ifd0 = tiff.u32(4);
  if (!tiff.in_range(ifd0, 2)) return false;
  const std::size_t count = tiff.u16(ifd0);
  const std::size_t entries = ifd0 + 2;
  if (!tiff.in_range(entries, count * 12 + 4)) return false;

  for (std::size_t i = 0; i < count; ++i) {
    const std::size_t entry = entries + i * 12;
    if (tiff.u16(entry) == kOrientationTag && tiff.u16(entry + 2) == kTypeShort &&
        tiff.u32(entry + 4) == 1) {
      tiff.put_u16(entry + 8, 1);
    }
  }
  // Unlink IFD1, which only holds the embedded JPEG thumbnail.
  tiff.put_u32(entries + count * 12, 0);
  return true;
}

}  // namespace daybreak
