// Daybreak image engine - public C++ API.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <expected>
#include <span>
#include <string>
#include <vector>

namespace daybreak {

struct Error {
  std::string message;
};

template <typename T>
using Result = std::expected<T, Error>;

// How the stored pixels must be turned to display upright (EXIF/JXL values).
enum class Orientation : std::uint8_t {
  Identity = 1,
  Rotate180 = 3,
  Rotate90Cw = 6,
  Rotate90Ccw = 8,
};

// Maps CameraX rotationDegrees (clockwise rotation needed to be upright).
Orientation orientation_from_degrees(int degrees);

struct EncodeOptions {
  float quality = 90.0f;  // 0..100, ignored when lossless
  int effort = 7;         // 1 (fastest) .. 10 (smallest)
  bool lossless = false;
};

// A borrowed, read-only view of 8-bit RGBA pixels.
struct RgbaView {
  std::span<const std::uint8_t> pixels;
  std::uint32_t width = 0;
  std::uint32_t height = 0;
  std::uint32_t stride = 0;  // bytes per row
};

// An owned, tightly packed 8-bit RGBA image.
struct Image {
  std::vector<std::uint8_t> rgba;
  std::uint32_t width = 0;
  std::uint32_t height = 0;
};

struct ImageInfo {
  std::uint32_t width = 0;   // display width, orientation applied
  std::uint32_t height = 0;  // display height, orientation applied
  std::uint32_t bits_per_sample = 0;
  bool has_alpha = false;
};

// Encodes RGBA pixels (alpha ignored) as JPEG XL in sRGB.
// exif_tiff: raw TIFF EXIF data (may be empty); stored in an "Exif" box.
Result<std::vector<std::uint8_t>> encode_jxl(const RgbaView& image,
                                             Orientation orientation,
                                             const EncodeOptions& options,
                                             std::span<const std::uint8_t> exif_tiff);

// Decodes the first frame to upright 8-bit RGBA.
// max_dimension > 0 downscales so the longer side fits.
Result<Image> decode_jxl(std::span<const std::uint8_t> data, std::uint32_t max_dimension);

Result<ImageInfo> read_jxl_info(std::span<const std::uint8_t> data);

// Area-average downscale so max(width, height) <= max_dimension.
Image downscale(const Image& source, std::uint32_t max_dimension);

// Returns the TIFF payload of a JPEG's EXIF APP1 segment, or empty.
std::vector<std::uint8_t> exif_tiff_from_jpeg(std::span<const std::uint8_t> jpeg);

// Sets IFD0 Orientation to 1 and unlinks IFD1 (the embedded JPEG thumbnail).
// Returns false when the TIFF data is malformed.
bool normalize_exif_orientation(std::span<std::uint8_t> tiff);

std::string libjxl_version();

}  // namespace daybreak
