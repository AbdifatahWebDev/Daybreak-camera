// Tests for burst merging (Night and HDR).
// SPDX-License-Identifier: GPL-3.0-or-later
#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <random>

#include "daybreak/burst.hpp"

namespace daybreak {
namespace {

// A scene with texture at several scales, so alignment has something to lock on to.
float scene(float x, float y) {
  const float v = 120.0f + 50.0f * std::sin(x * 0.07f) * std::cos(y * 0.05f) + 30.0f * std::sin((x + 2 * y) * 0.21f) +
                  20.0f * std::cos((3 * x - y) * 0.013f);
  return std::clamp(v, 0.0f, 255.0f);
}

// The scene seen with the camera moved by (dx, dy), plus noise.
Image frame(std::uint32_t w, std::uint32_t h, int dx, int dy, float noise, unsigned seed) {
  std::mt19937 rng(seed);
  std::normal_distribution<float> n(0.0f, noise);
  Image img;
  img.width = w;
  img.height = h;
  img.rgba.resize(std::size_t{w} * h * 4);
  for (std::uint32_t y = 0; y < h; ++y) {
    for (std::uint32_t x = 0; x < w; ++x) {
      const float v = scene(static_cast<float>(x) + dx, static_cast<float>(y) + dy);
      std::uint8_t* p = img.rgba.data() + (std::size_t{y} * w + x) * 4;
      p[0] = static_cast<std::uint8_t>(std::clamp(std::lround(v + n(rng)), 0L, 255L));
      p[1] = static_cast<std::uint8_t>(std::clamp(std::lround(v * 0.9f + n(rng)), 0L, 255L));
      p[2] = static_cast<std::uint8_t>(std::clamp(std::lround(v * 0.8f + n(rng)), 0L, 255L));
      p[3] = 255;
    }
  }
  return img;
}

RgbaView view(const Image& img) {
  return RgbaView{std::span<const std::uint8_t>(img.rgba), img.width, img.height, img.width * 4};
}

MutableRgbaView target(Image& img) {
  return MutableRgbaView{std::span<std::uint8_t>(img.rgba), img.width, img.height, img.width * 4};
}

Image blank(std::uint32_t w, std::uint32_t h) {
  Image img;
  img.width = w;
  img.height = h;
  img.rgba.assign(std::size_t{w} * h * 4, 0);
  return img;
}

// Root-mean-square error of the red channel against the clean scene, away from the edges.
double rmse(const Image& img, int margin) {
  double sum = 0.0;
  long count = 0;
  for (int y = margin; y < static_cast<int>(img.height) - margin; ++y) {
    for (int x = margin; x < static_cast<int>(img.width) - margin; ++x) {
      const double d = img.rgba[(static_cast<std::size_t>(y) * img.width + x) * 4] -
                       scene(static_cast<float>(x), static_cast<float>(y));
      sum += d * d;
      ++count;
    }
  }
  return std::sqrt(sum / static_cast<double>(count));
}

BurstFinish neutral() {
  BurstFinish finish;
  finish.gain = 1.0f;
  finish.under = 0.0f;
  finish.over = 0.0f;
  return finish;
}

TEST(Burst, OneFrameComesBackUnchanged) {
  const Image a = frame(96, 64, 0, 0, 0.0f, 1);
  BurstMerger merger;
  ASSERT_TRUE(merger.add(view(a)));
  Image out = blank(96, 64);
  ASSERT_TRUE(merger.finish(neutral(), target(out)));
  int worst = 0;
  for (std::size_t i = 0; i < a.rgba.size(); ++i) worst = std::max(worst, std::abs(a.rgba[i] - out.rgba[i]));
  EXPECT_LE(worst, 1);
}

TEST(Burst, MergingStillFramesRemovesNoise) {
  const std::uint32_t w = 320;
  const std::uint32_t h = 240;
  BurstMerger merger;
  const Image first = frame(w, h, 0, 0, 8.0f, 1);
  ASSERT_TRUE(merger.add(view(first)));
  for (unsigned i = 2; i <= 6; ++i) ASSERT_TRUE(merger.add(view(frame(w, h, 0, 0, 8.0f, i))));
  Image out = blank(w, h);
  ASSERT_TRUE(merger.finish(neutral(), target(out)));
  const double single = rmse(first, 4);
  const double merged = rmse(out, 4);
  std::printf("noise: single %.2f, merged %.2f, merged fraction %.2f\n", single, merged,
              merger.stats().merged_fraction);
  EXPECT_LT(merged, single * 0.6);
  EXPECT_GT(merger.stats().merged_fraction, 0.8f);
}

TEST(Burst, AlignsHandShake) {
  const std::uint32_t w = 640;
  const std::uint32_t h = 480;
  BurstMerger merger;
  const Image first = frame(w, h, 0, 0, 8.0f, 1);
  ASSERT_TRUE(merger.add(view(first)));
  const int shifts[][2] = {{37, -23}, {-12, 18}, {5, 41}, {-60, -9}};
  unsigned seed = 2;
  for (const auto& s : shifts) ASSERT_TRUE(merger.add(view(frame(w, h, s[0], s[1], 8.0f, seed++))));
  Image out = blank(w, h);
  ASSERT_TRUE(merger.finish(neutral(), target(out)));
  const double single = rmse(first, 70);
  const double merged = rmse(out, 70);
  std::printf("shake: single %.2f, merged %.2f, merged fraction %.2f\n", single, merged,
              merger.stats().merged_fraction);
  // Misaligned frames would blur the texture and raise the error well above a single frame's.
  EXPECT_LT(merged, single * 0.75);
}

TEST(Burst, SomethingThatMovesLeavesNoGhost) {
  const std::uint32_t w = 320;
  const std::uint32_t h = 240;
  BurstMerger merger;
  const Image first = frame(w, h, 0, 0, 4.0f, 1);
  ASSERT_TRUE(merger.add(view(first)));
  for (unsigned i = 2; i <= 5; ++i) {
    Image f = frame(w, h, 0, 0, 4.0f, i);
    // A white car drives through the later frames only.
    const std::uint32_t left = 40 + i * 30;
    for (std::uint32_t y = 100; y < 140; ++y) {
      for (std::uint32_t x = left; x < left + 40; ++x) {
        std::uint8_t* p = f.rgba.data() + (std::size_t{y} * w + x) * 4;
        p[0] = p[1] = p[2] = 255;
      }
    }
    ASSERT_TRUE(merger.add(view(f)));
  }
  Image out = blank(w, h);
  ASSERT_TRUE(merger.finish(neutral(), target(out)));
  double worst = 0.0;
  for (std::uint32_t y = 100; y < 140; ++y) {
    for (std::uint32_t x = 100; x < 240; ++x) {
      const double d = std::abs(out.rgba[(std::size_t{y} * w + x) * 4] - scene(static_cast<float>(x), static_cast<float>(y)));
      worst = std::max(worst, d);
    }
  }
  std::printf("ghost: worst difference %.1f\n", worst);
  EXPECT_LT(worst, 40.0);
}

TEST(Burst, FinishBrightensShadowsAndKeepsHighlights) {
  const std::uint32_t w = 256;
  const std::uint32_t h = 128;
  // Left half dark (a dim room), right half bright (a window).
  Image img = blank(w, h);
  for (std::uint32_t y = 0; y < h; ++y) {
    for (std::uint32_t x = 0; x < w; ++x) {
      std::uint8_t* p = img.rgba.data() + (std::size_t{y} * w + x) * 4;
      const std::uint8_t v = x < w / 2 ? 30 : 230;
      p[0] = p[1] = p[2] = v;
      p[3] = 255;
    }
  }
  BurstMerger merger;
  ASSERT_TRUE(merger.add(view(img)));
  BurstFinish finish;
  finish.gain = 2.0f;
  finish.under = 1.5f;
  finish.over = 1.0f;
  Image out = blank(w, h);
  ASSERT_TRUE(merger.finish(finish, target(out)));
  const int dark = out.rgba[(std::size_t{h / 2} * w + 20) * 4];
  const int bright = out.rgba[(std::size_t{h / 2} * w + w - 20) * 4];
  std::printf("finish: dark 30 -> %d, bright 230 -> %d\n", dark, bright);
  EXPECT_GT(dark, 50);
  EXPECT_LT(bright, 255);
  EXPECT_GT(bright, dark + 60);
}

TEST(Burst, RejectsMismatchedFrames) {
  BurstMerger merger;
  const Image a = frame(64, 48, 0, 0, 0.0f, 1);
  const Image b = frame(48, 64, 0, 0, 0.0f, 1);
  ASSERT_TRUE(merger.add(view(a)));
  EXPECT_FALSE(merger.add(view(b)));
  for (std::uint32_t i = 1; i < BurstMerger::kMaxFrames; ++i) ASSERT_TRUE(merger.add(view(a)));
  EXPECT_FALSE(merger.add(view(a)));
  Image wrong = blank(32, 32);
  EXPECT_FALSE(merger.finish(neutral(), target(wrong)));
  BurstMerger empty;
  Image out = blank(64, 48);
  EXPECT_FALSE(empty.finish(neutral(), target(out)));
}

TEST(Burst, TwelveMegapixelTiming) {
  const std::uint32_t w = 4000;
  const std::uint32_t h = 3000;
  const Image a = frame(w, h, 0, 0, 6.0f, 1);
  const Image b = frame(w, h, 9, -5, 6.0f, 2);
  BurstMerger merger;
  const auto start = std::chrono::steady_clock::now();
  ASSERT_TRUE(merger.add(view(a)));
  const auto after_first = std::chrono::steady_clock::now();
  ASSERT_TRUE(merger.add(view(b)));
  const auto after_second = std::chrono::steady_clock::now();
  Image out = blank(w, h);
  ASSERT_TRUE(merger.finish(BurstFinish{}, target(out)));
  const auto done = std::chrono::steady_clock::now();
  const auto ms = [](auto d) { return std::chrono::duration_cast<std::chrono::milliseconds>(d).count(); };
  std::printf("12 MP: reference %lld ms, aligned frame %lld ms, finish %lld ms\n",
              static_cast<long long>(ms(after_first - start)), static_cast<long long>(ms(after_second - after_first)),
              static_cast<long long>(ms(done - after_second)));
}

}  // namespace
}  // namespace daybreak
