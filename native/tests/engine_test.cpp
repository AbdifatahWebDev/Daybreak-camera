// SPDX-License-Identifier: GPL-3.0-or-later
#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "daybreak/engine.hpp"

namespace {

daybreak::Image gradient(std::uint32_t w, std::uint32_t h) {
  daybreak::Image img;
  img.width = w;
  img.height = h;
  img.rgba.resize(std::size_t{w} * h * 4);
  for (std::uint32_t y = 0; y < h; ++y) {
    for (std::uint32_t x = 0; x < w; ++x) {
      std::uint8_t* p = img.rgba.data() + (std::size_t{y} * w + x) * 4;
      p[0] = static_cast<std::uint8_t>((x * 255) / (w - 1));
      p[1] = static_cast<std::uint8_t>((y * 255) / (h - 1));
      p[2] = static_cast<std::uint8_t>(((x + y) * 255) / (w + h - 2));
      p[3] = 255;
    }
  }
  return img;
}

daybreak::RgbaView view_of(const daybreak::Image& img) {
  return {img.rgba, img.width, img.height, img.width * 4};
}

double psnr(const daybreak::Image& a, const daybreak::Image& b) {
  double sum = 0;
  std::size_t n = 0;
  for (std::size_t i = 0; i < a.rgba.size(); i += 4) {
    for (int c = 0; c < 3; ++c) {
      const double d = double(a.rgba[i + c]) - double(b.rgba[i + c]);
      sum += d * d;
      ++n;
    }
  }
  const double mse = sum / double(n);
  return mse == 0 ? 99.0 : 10.0 * std::log10(255.0 * 255.0 / mse);
}

// A minimal big-endian TIFF with Orientation=6 and an IFD1 pointer.
std::vector<std::uint8_t> tiff_with_orientation6() {
  return {'M', 'M', 0, 42, 0, 0, 0, 8,
          0, 1,                                   // 1 entry
          0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0,  // Orientation SHORT 1 = 6
          0, 0, 0, 26,                            // next IFD offset (IFD1)
          0, 0};
}

}  // namespace

TEST(Jxl, LosslessRoundTripIsExact) {
  const auto img = gradient(257, 131);
  daybreak::EncodeOptions options;
  options.lossless = true;
  options.effort = 3;
  auto encoded = daybreak::encode_jxl(view_of(img), daybreak::Orientation::Identity, options, {});
  ASSERT_TRUE(encoded.has_value()) << encoded.error().message;
  auto decoded = daybreak::decode_jxl(*encoded, 0);
  ASSERT_TRUE(decoded.has_value()) << decoded.error().message;
  EXPECT_EQ(decoded->width, img.width);
  EXPECT_EQ(decoded->height, img.height);
  EXPECT_EQ(decoded->rgba, img.rgba);
}

TEST(Jxl, LossyQuality90IsCloseAndSmaller) {
  const auto img = gradient(640, 480);
  daybreak::EncodeOptions options;
  options.quality = 90;
  options.effort = 3;
  auto encoded = daybreak::encode_jxl(view_of(img), daybreak::Orientation::Identity, options, {});
  ASSERT_TRUE(encoded.has_value()) << encoded.error().message;
  EXPECT_LT(encoded->size(), img.rgba.size() / 4);
  auto decoded = daybreak::decode_jxl(*encoded, 0);
  ASSERT_TRUE(decoded.has_value()) << decoded.error().message;
  EXPECT_GT(psnr(img, *decoded), 35.0);
}

TEST(Jxl, OrientationSwapsDisplaySize) {
  const auto img = gradient(200, 100);
  daybreak::EncodeOptions options;
  options.effort = 1;
  auto encoded =
      daybreak::encode_jxl(view_of(img), daybreak::orientation_from_degrees(90), options, {});
  ASSERT_TRUE(encoded.has_value()) << encoded.error().message;
  auto info = daybreak::read_jxl_info(*encoded);
  ASSERT_TRUE(info.has_value());
  EXPECT_EQ(info->width, 100u);
  EXPECT_EQ(info->height, 200u);
  auto decoded = daybreak::decode_jxl(*encoded, 0);
  ASSERT_TRUE(decoded.has_value());
  EXPECT_EQ(decoded->width, 100u);
  EXPECT_EQ(decoded->height, 200u);
}

TEST(Jxl, ExifBoxIsAccepted) {
  const auto img = gradient(64, 48);
  auto tiff = tiff_with_orientation6();
  ASSERT_TRUE(daybreak::normalize_exif_orientation(tiff));
  daybreak::EncodeOptions options;
  options.effort = 1;
  auto encoded = daybreak::encode_jxl(view_of(img), daybreak::Orientation::Identity, options, tiff);
  ASSERT_TRUE(encoded.has_value()) << encoded.error().message;
  EXPECT_TRUE(daybreak::decode_jxl(*encoded, 0).has_value());
}

TEST(Jxl, DecodeDownscalesToMaxDimension) {
  const auto img = gradient(800, 600);
  daybreak::EncodeOptions options;
  options.effort = 1;
  auto encoded = daybreak::encode_jxl(view_of(img), daybreak::Orientation::Identity, options, {});
  ASSERT_TRUE(encoded.has_value());
  auto decoded = daybreak::decode_jxl(*encoded, 200);
  ASSERT_TRUE(decoded.has_value());
  EXPECT_EQ(decoded->width, 200u);
  EXPECT_EQ(decoded->height, 150u);
}

TEST(Jxl, RejectsGarbage) {
  const std::vector<std::uint8_t> junk = {1, 2, 3, 4, 5, 6, 7, 8};
  EXPECT_FALSE(daybreak::decode_jxl(junk, 0).has_value());
  EXPECT_FALSE(daybreak::decode_jxl({}, 0).has_value());
  EXPECT_FALSE(daybreak::read_jxl_info(junk).has_value());
}

TEST(Jxl, RejectsBadBuffers) {
  const std::vector<std::uint8_t> tiny(16, 0);
  daybreak::RgbaView view{tiny, 100, 100, 400};
  EXPECT_FALSE(daybreak::encode_jxl(view, daybreak::Orientation::Identity, {}, {}).has_value());
  daybreak::RgbaView empty{tiny, 0, 0, 0};
  EXPECT_FALSE(daybreak::encode_jxl(empty, daybreak::Orientation::Identity, {}, {}).has_value());
}

TEST(Exif, NormalizesOrientationAndUnlinksThumbnail) {
  auto tiff = tiff_with_orientation6();
  ASSERT_TRUE(daybreak::normalize_exif_orientation(tiff));
  EXPECT_EQ(tiff[18], 0);
  EXPECT_EQ(tiff[19], 1);  // big-endian value 1
  EXPECT_EQ(tiff[22], 0);
  EXPECT_EQ(tiff[25], 0);  // next IFD offset cleared
}

TEST(Exif, RejectsMalformedTiff) {
  std::vector<std::uint8_t> bad = {'I', 'I', 42, 0, 0xff, 0xff, 0xff, 0x7f};
  EXPECT_FALSE(daybreak::normalize_exif_orientation(bad));
  std::vector<std::uint8_t> short_data = {'I', 'I'};
  EXPECT_FALSE(daybreak::normalize_exif_orientation(short_data));
}

TEST(Exif, ExtractsApp1FromJpeg) {
  const auto tiff = tiff_with_orientation6();
  std::vector<std::uint8_t> jpeg = {0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 'J', 'F', 0xFF, 0xE1};
  const std::size_t len = 2 + 6 + tiff.size();
  jpeg.push_back(static_cast<std::uint8_t>(len >> 8));
  jpeg.push_back(static_cast<std::uint8_t>(len & 0xff));
  for (char c : {'E', 'x', 'i', 'f'}) jpeg.push_back(static_cast<std::uint8_t>(c));
  jpeg.push_back(0);
  jpeg.push_back(0);
  jpeg.insert(jpeg.end(), tiff.begin(), tiff.end());
  jpeg.insert(jpeg.end(), {0xFF, 0xDA, 0, 2, 0xFF, 0xD9});
  EXPECT_EQ(daybreak::exif_tiff_from_jpeg(jpeg), tiff);
}

TEST(Exif, IgnoresTruncatedJpeg) {
  const std::vector<std::uint8_t> jpeg = {0xFF, 0xD8, 0xFF, 0xE1, 0x40, 0x00, 'E', 'x'};
  EXPECT_TRUE(daybreak::exif_tiff_from_jpeg(jpeg).empty());
  EXPECT_TRUE(daybreak::exif_tiff_from_jpeg({}).empty());
}

TEST(Orientation, MapsCameraDegrees) {
  EXPECT_EQ(daybreak::orientation_from_degrees(0), daybreak::Orientation::Identity);
  EXPECT_EQ(daybreak::orientation_from_degrees(90), daybreak::Orientation::Rotate90Cw);
  EXPECT_EQ(daybreak::orientation_from_degrees(180), daybreak::Orientation::Rotate180);
  EXPECT_EQ(daybreak::orientation_from_degrees(270), daybreak::Orientation::Rotate90Ccw);
  EXPECT_EQ(daybreak::orientation_from_degrees(-90), daybreak::Orientation::Rotate90Ccw);
}

TEST(Version, ReportsLibjxl) {
  EXPECT_EQ(daybreak::libjxl_version().rfind("0.12", 0), 0u);
}
