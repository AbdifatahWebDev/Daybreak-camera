// JPEG XL encode and decode through libjxl.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <jxl/decode.h>
#include <jxl/decode_cxx.h>
#include <jxl/encode.h>
#include <jxl/encode_cxx.h>
#include <jxl/thread_parallel_runner.h>
#include <jxl/thread_parallel_runner_cxx.h>

#include <algorithm>
#include <cstring>
#include <limits>

#include "daybreak/engine.hpp"

namespace daybreak {
namespace {

// Refuse images that cannot fit in memory on a phone (~ 400 MP).
constexpr std::uint64_t kMaxPixels = 400ull * 1000 * 1000;

std::unexpected<Error> fail(std::string message) {
  return std::unexpected(Error{std::move(message)});
}

bool swaps_axes(JxlOrientation orientation) {
  return orientation == JXL_ORIENT_TRANSPOSE || orientation == JXL_ORIENT_ROTATE_90_CW ||
         orientation == JXL_ORIENT_ANTI_TRANSPOSE || orientation == JXL_ORIENT_ROTATE_90_CCW;
}

std::size_t worker_threads() {
  return JxlThreadParallelRunnerDefaultNumWorkerThreads();
}

}  // namespace

Orientation orientation_from_degrees(int degrees) {
  int normalized = ((degrees % 360) + 360) % 360;
  switch (normalized) {
    case 90:
      return Orientation::Rotate90Cw;
    case 180:
      return Orientation::Rotate180;
    case 270:
      return Orientation::Rotate90Ccw;
    default:
      return Orientation::Identity;
  }
}

Result<std::vector<std::uint8_t>> encode_jxl(const RgbaView& image,
                                             Orientation orientation,
                                             const EncodeOptions& options,
                                             std::span<const std::uint8_t> exif_tiff) {
  if (image.width == 0 || image.height == 0) return fail("Image is empty");
  if (std::uint64_t{image.width} * image.height > kMaxPixels) return fail("Image is too large");
  if (image.stride < std::uint64_t{image.width} * 4) return fail("Row stride is smaller than the width");
  const std::uint64_t needed =
      std::uint64_t{image.stride} * (image.height - 1) + std::uint64_t{image.width} * 4;
  if (image.pixels.size() < needed) return fail("Pixel buffer is smaller than the image");

  // Pack to tightly packed RGB; photos have no alpha.
  std::vector<std::uint8_t> rgb(std::size_t{image.width} * image.height * 3);
  for (std::uint32_t y = 0; y < image.height; ++y) {
    const std::uint8_t* src = image.pixels.data() + std::size_t{y} * image.stride;
    std::uint8_t* dst = rgb.data() + std::size_t{y} * image.width * 3;
    for (std::uint32_t x = 0; x < image.width; ++x) {
      dst[0] = src[0];
      dst[1] = src[1];
      dst[2] = src[2];
      src += 4;
      dst += 3;
    }
  }

  auto encoder = JxlEncoderMake(nullptr);
  auto runner = JxlThreadParallelRunnerMake(nullptr, worker_threads());
  if (!encoder || !runner) return fail("Could not create the JPEG XL encoder");
  if (JxlEncoderSetParallelRunner(encoder.get(), JxlThreadParallelRunner, runner.get()) !=
      JXL_ENC_SUCCESS) {
    return fail("Could not start encoder threads");
  }

  const bool with_exif = !exif_tiff.empty();
  if (with_exif && JxlEncoderUseBoxes(encoder.get()) != JXL_ENC_SUCCESS) {
    return fail("Could not enable metadata boxes");
  }

  JxlBasicInfo info;
  JxlEncoderInitBasicInfo(&info);
  info.xsize = image.width;
  info.ysize = image.height;
  info.bits_per_sample = 8;
  info.exponent_bits_per_sample = 0;
  info.num_color_channels = 3;
  info.num_extra_channels = 0;
  info.alpha_bits = 0;
  info.orientation = static_cast<JxlOrientation>(orientation);
  info.uses_original_profile = options.lossless ? JXL_TRUE : JXL_FALSE;
  if (JxlEncoderSetBasicInfo(encoder.get(), &info) != JXL_ENC_SUCCESS) {
    return fail("Encoder rejected the image header");
  }

  JxlColorEncoding color;
  JxlColorEncodingSetToSRGB(&color, JXL_FALSE);
  if (JxlEncoderSetColorEncoding(encoder.get(), &color) != JXL_ENC_SUCCESS) {
    return fail("Encoder rejected the color encoding");
  }

  if (with_exif) {
    // JPEG XL "Exif" box: 4-byte big-endian offset to the TIFF header, then TIFF.
    std::vector<std::uint8_t> box(4 + exif_tiff.size(), 0);
    std::memcpy(box.data() + 4, exif_tiff.data(), exif_tiff.size());
    if (JxlEncoderAddBox(encoder.get(), "Exif", box.data(), box.size(), JXL_FALSE) !=
        JXL_ENC_SUCCESS) {
      return fail("Could not add EXIF metadata");
    }
    JxlEncoderCloseBoxes(encoder.get());
  }

  JxlEncoderFrameSettings* settings = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
  if (settings == nullptr) return fail("Could not create frame settings");
  const int effort = std::clamp(options.effort, 1, 10);
  if (JxlEncoderFrameSettingsSetOption(settings, JXL_ENC_FRAME_SETTING_EFFORT, effort) !=
      JXL_ENC_SUCCESS) {
    return fail("Encoder rejected the effort setting");
  }
  if (options.lossless) {
    if (JxlEncoderSetFrameLossless(settings, JXL_TRUE) != JXL_ENC_SUCCESS) {
      return fail("Encoder rejected lossless mode");
    }
  } else {
    const float quality = std::clamp(options.quality, 0.0f, 100.0f);
    if (JxlEncoderSetFrameDistance(settings, JxlEncoderDistanceFromQuality(quality)) !=
        JXL_ENC_SUCCESS) {
      return fail("Encoder rejected the quality setting");
    }
  }

  const JxlPixelFormat format{3, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
  if (JxlEncoderAddImageFrame(settings, &format, rgb.data(), rgb.size()) != JXL_ENC_SUCCESS) {
    return fail("Encoder rejected the pixels");
  }
  JxlEncoderCloseInput(encoder.get());

  std::vector<std::uint8_t> out(256 * 1024);
  std::uint8_t* next = out.data();
  std::size_t available = out.size();
  JxlEncoderStatus status = JXL_ENC_NEED_MORE_OUTPUT;
  while (status == JXL_ENC_NEED_MORE_OUTPUT) {
    status = JxlEncoderProcessOutput(encoder.get(), &next, &available);
    if (status == JXL_ENC_NEED_MORE_OUTPUT) {
      const std::size_t written = static_cast<std::size_t>(next - out.data());
      out.resize(out.size() * 2);
      next = out.data() + written;
      available = out.size() - written;
    }
  }
  if (status != JXL_ENC_SUCCESS) {
    return fail("JPEG XL encoding failed (code " +
                std::to_string(static_cast<int>(JxlEncoderGetError(encoder.get()))) + ")");
  }
  out.resize(static_cast<std::size_t>(next - out.data()));
  return out;
}

Result<Image> decode_jxl(std::span<const std::uint8_t> data, std::uint32_t max_dimension) {
  if (data.empty()) return fail("File is empty");
  if (JxlSignatureCheck(data.data(), data.size()) == JXL_SIG_INVALID) {
    return fail("Not a JPEG XL file");
  }

  auto decoder = JxlDecoderMake(nullptr);
  auto runner = JxlThreadParallelRunnerMake(nullptr, worker_threads());
  if (!decoder || !runner) return fail("Could not create the JPEG XL decoder");
  if (JxlDecoderSubscribeEvents(decoder.get(), JXL_DEC_BASIC_INFO | JXL_DEC_FULL_IMAGE) !=
      JXL_DEC_SUCCESS) {
    return fail("Decoder rejected the event list");
  }
  if (JxlDecoderSetParallelRunner(decoder.get(), JxlThreadParallelRunner, runner.get()) !=
      JXL_DEC_SUCCESS) {
    return fail("Could not start decoder threads");
  }
  if (JxlDecoderSetInput(decoder.get(), data.data(), data.size()) != JXL_DEC_SUCCESS) {
    return fail("Decoder rejected the input");
  }
  JxlDecoderCloseInput(decoder.get());

  const JxlPixelFormat format{4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
  JxlBasicInfo info{};
  Image image;
  for (;;) {
    const JxlDecoderStatus status = JxlDecoderProcessInput(decoder.get());
    switch (status) {
      case JXL_DEC_ERROR:
        return fail("The JPEG XL file is damaged");
      case JXL_DEC_NEED_MORE_INPUT:
        return fail("The JPEG XL file is incomplete");
      case JXL_DEC_BASIC_INFO: {
        if (JxlDecoderGetBasicInfo(decoder.get(), &info) != JXL_DEC_SUCCESS) {
          return fail("Could not read the image header");
        }
        if (info.xsize == 0 || info.ysize == 0 ||
            std::uint64_t{info.xsize} * info.ysize > kMaxPixels) {
          return fail("Image size is not supported");
        }
        const bool swap = swaps_axes(info.orientation);
        image.width = swap ? info.ysize : info.xsize;
        image.height = swap ? info.xsize : info.ysize;
        break;
      }
      case JXL_DEC_NEED_IMAGE_OUT_BUFFER: {
        std::size_t size = 0;
        if (JxlDecoderImageOutBufferSize(decoder.get(), &format, &size) != JXL_DEC_SUCCESS) {
          return fail("Could not size the output buffer");
        }
        if (size != std::size_t{image.width} * image.height * 4) {
          return fail("Unexpected output buffer size");
        }
        image.rgba.resize(size);
        if (JxlDecoderSetImageOutBuffer(decoder.get(), &format, image.rgba.data(), size) !=
            JXL_DEC_SUCCESS) {
          return fail("Decoder rejected the output buffer");
        }
        break;
      }
      case JXL_DEC_FULL_IMAGE:
        // First frame is enough for a still photo.
        if (max_dimension > 0) return downscale(image, max_dimension);
        return image;
      case JXL_DEC_SUCCESS:
        if (image.rgba.empty()) return fail("The file has no image");
        if (max_dimension > 0) return downscale(image, max_dimension);
        return image;
      default:
        break;
    }
  }
}

Result<ImageInfo> read_jxl_info(std::span<const std::uint8_t> data) {
  if (data.empty()) return fail("File is empty");
  auto decoder = JxlDecoderMake(nullptr);
  if (!decoder) return fail("Could not create the JPEG XL decoder");
  if (JxlDecoderSubscribeEvents(decoder.get(), JXL_DEC_BASIC_INFO) != JXL_DEC_SUCCESS) {
    return fail("Decoder rejected the event list");
  }
  if (JxlDecoderSetInput(decoder.get(), data.data(), data.size()) != JXL_DEC_SUCCESS) {
    return fail("Decoder rejected the input");
  }
  JxlDecoderCloseInput(decoder.get());
  for (;;) {
    const JxlDecoderStatus status = JxlDecoderProcessInput(decoder.get());
    if (status == JXL_DEC_BASIC_INFO) {
      JxlBasicInfo info{};
      if (JxlDecoderGetBasicInfo(decoder.get(), &info) != JXL_DEC_SUCCESS) {
        return fail("Could not read the image header");
      }
      const bool swap = swaps_axes(info.orientation);
      return ImageInfo{swap ? info.ysize : info.xsize, swap ? info.xsize : info.ysize,
                       info.bits_per_sample, info.alpha_bits > 0};
    }
    if (status == JXL_DEC_ERROR || status == JXL_DEC_NEED_MORE_INPUT ||
        status == JXL_DEC_SUCCESS) {
      return fail("Could not read the image header");
    }
  }
}

std::string libjxl_version() {
  const std::uint32_t v = JxlEncoderVersion();
  return std::to_string(v / 1000000) + "." + std::to_string((v / 1000) % 1000) + "." +
         std::to_string(v % 1000);
}

}  // namespace daybreak
