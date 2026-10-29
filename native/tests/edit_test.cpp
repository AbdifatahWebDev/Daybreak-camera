// Tests for the photo editor pipeline.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <functional>

#include "daybreak/edit.hpp"

namespace daybreak {
namespace {

struct Rgb {
  int r, g, b;
};

Image make(std::uint32_t w, std::uint32_t h, const std::function<Rgb(std::uint32_t, std::uint32_t)>& fn) {
  Image img;
  img.width = w;
  img.height = h;
  img.rgba.resize(std::size_t{w} * h * 4);
  for (std::uint32_t y = 0; y < h; ++y) {
    for (std::uint32_t x = 0; x < w; ++x) {
      const Rgb c = fn(x, y);
      std::uint8_t* p = img.rgba.data() + (std::size_t{y} * w + x) * 4;
      p[0] = static_cast<std::uint8_t>(c.r);
      p[1] = static_cast<std::uint8_t>(c.g);
      p[2] = static_cast<std::uint8_t>(c.b);
      p[3] = 255;
    }
  }
  return img;
}

Image flat(std::uint32_t w, std::uint32_t h, Rgb c) {
  return make(w, h, [c](std::uint32_t, std::uint32_t) { return c; });
}

RgbaView view(const Image& img) { return {img.rgba, img.width, img.height, img.width * 4}; }

Image render(const Image& src, const EditRecipe& recipe, bool apply_crop = true, std::uint32_t out_w = 0,
             std::uint32_t out_h = 0) {
  const EditSize size = edit_output_size(src.width, src.height, recipe, apply_crop);
  Image out;
  out.width = out_w != 0 ? out_w : size.width;
  out.height = out_h != 0 ? out_h : size.height;
  out.rgba.assign(std::size_t{out.width} * out.height * 4, 0);
  RenderOptions options;
  options.apply_crop = apply_crop;
  const auto result =
      render_edit(view(src), recipe, MutableRgbaView{out.rgba, out.width, out.height, out.width * 4}, options);
  EXPECT_TRUE(result.has_value()) << (result ? "" : result.error().message);
  return out;
}

Rgb at(const Image& img, std::uint32_t x, std::uint32_t y) {
  const std::uint8_t* p = img.rgba.data() + (std::size_t{y} * img.width + x) * 4;
  return {p[0], p[1], p[2]};
}

Image gradient(std::uint32_t w, std::uint32_t h) {
  return make(w, h, [w, h](std::uint32_t x, std::uint32_t y) {
    return Rgb{static_cast<int>(x * 255 / (w - 1)), static_cast<int>(y * 255 / (h - 1)), 128};
  });
}

TEST(Edit, DefaultRecipeIsIdentityAndCopiesExactly) {
  const EditRecipe recipe;
  EXPECT_TRUE(is_identity(recipe));
  const Image src = gradient(37, 23);
  const Image out = render(src, recipe);
  EXPECT_EQ(out.rgba, src.rgba);
}

TEST(Edit, PixelOpsWithNoEffectRoundTripExactly) {
  // A vignette of zero strength at the center region still goes through the
  // linear pipeline; unaffected pixels must come back bit for bit.
  EditRecipe recipe;
  recipe.vignette = -0.0001f;
  const Image src = make(9, 9, [](std::uint32_t x, std::uint32_t y) {
    return Rgb{static_cast<int>(x * 28), static_cast<int>(y * 28), static_cast<int>((x + y) * 14)};
  });
  const Image out = render(src, recipe);
  EXPECT_EQ(at(out, 4, 4).r, at(src, 4, 4).r);
  for (int v = 0; v < 256; ++v) {
    EditRecipe tiny;
    tiny.temperature = 1e-9f;  // gains of exactly 1.0f after rounding
    const Image grey = flat(1, 1, {v, v, v});
    EXPECT_EQ(render(grey, tiny).rgba, grey.rgba) << v;
  }
}

TEST(Edit, OutputSizeFollowsRotationAndCrop) {
  EditRecipe recipe;
  recipe.quarter_turns = 1;
  EXPECT_EQ(edit_output_size(400, 300, recipe, true).width, 300u);
  EXPECT_EQ(edit_output_size(400, 300, recipe, true).height, 400u);
  recipe.crop_left = 0.25f;
  recipe.crop_right = 0.75f;
  EXPECT_EQ(edit_output_size(400, 300, recipe, true).width, 150u);
  EXPECT_EQ(edit_output_size(400, 300, recipe, true).height, 400u);
  EXPECT_EQ(edit_output_size(400, 300, recipe, false).width, 300u);
}

TEST(Edit, QuarterTurnClockwiseMovesTopLeftToTopRight) {
  const Image src = make(3, 2, [](std::uint32_t x, std::uint32_t y) {
    return Rgb{static_cast<int>(10 + x * 40 + y * 120), 0, 0};
  });
  EditRecipe recipe;
  recipe.quarter_turns = 1;
  const Image out = render(src, recipe);
  ASSERT_EQ(out.width, 2u);
  ASSERT_EQ(out.height, 3u);
  EXPECT_EQ(at(out, 1, 0).r, at(src, 0, 0).r);  // top-left -> top-right
  EXPECT_EQ(at(out, 0, 0).r, at(src, 0, 1).r);  // bottom-left -> top-left
  EXPECT_EQ(at(out, 1, 2).r, at(src, 2, 0).r);  // top-right -> bottom-right
  recipe.quarter_turns = 3;
  const Image back = render(src, recipe);
  EXPECT_EQ(at(back, 0, 2).r, at(src, 0, 0).r);  // top-left -> bottom-left
}

TEST(Edit, FlipMirrors) {
  const Image src = make(2, 1, [](std::uint32_t x, std::uint32_t) { return Rgb{x == 0 ? 10 : 200, 0, 0}; });
  EditRecipe recipe;
  recipe.flip = true;
  const Image out = render(src, recipe);
  EXPECT_EQ(at(out, 0, 0).r, 200);
  EXPECT_EQ(at(out, 1, 0).r, 10);
}

TEST(Edit, CropKeepsTheChosenRegion) {
  const Image src = gradient(8, 8);
  EditRecipe recipe;
  recipe.crop_left = 0.5f;
  recipe.crop_top = 0.25f;
  recipe.crop_bottom = 0.75f;
  const Image out = render(src, recipe);
  ASSERT_EQ(out.width, 4u);
  ASSERT_EQ(out.height, 4u);
  for (std::uint32_t y = 0; y < 4; ++y) {
    for (std::uint32_t x = 0; x < 4; ++x) {
      EXPECT_EQ(at(out, x, y).r, at(src, x + 4, y + 2).r);
      EXPECT_EQ(at(out, x, y).g, at(src, x + 4, y + 2).g);
    }
  }
}

TEST(Edit, StraightenKeepsSizeAndCenter) {
  const Image src = make(101, 101, [](std::uint32_t x, std::uint32_t y) {
    return (x == 50 && y == 50) ? Rgb{250, 10, 10} : Rgb{20, 20, 20};
  });
  EditRecipe recipe;
  recipe.straighten = 10.0f;
  const Image out = render(src, recipe);
  EXPECT_EQ(out.width, 101u);
  EXPECT_EQ(out.height, 101u);
  EXPECT_GT(at(out, 50, 50).r, 150);
}

TEST(Edit, ExposureDoublesLinearLight) {
  EditRecipe recipe;
  recipe.exposure = 1.0f;
  const Image out = render(flat(4, 4, {118, 118, 118}), recipe);
  const Rgb c = at(out, 1, 1);
  EXPECT_NEAR(c.r, 161, 1);
  EXPECT_EQ(c.r, c.g);
  EXPECT_EQ(c.g, c.b);
}

TEST(Edit, SaturationMinusOneMakesGrey) {
  EditRecipe recipe;
  recipe.saturation = -1.0f;
  const Image out = render(flat(2, 2, {200, 60, 30}), recipe);
  const Rgb c = at(out, 0, 0);
  EXPECT_NEAR(c.r, c.g, 1);
  EXPECT_NEAR(c.g, c.b, 1);
}

TEST(Edit, TemperatureWarmsAndCools) {
  EditRecipe warm;
  warm.temperature = 1.0f;
  const Rgb w = at(render(flat(2, 2, {128, 128, 128}), warm), 0, 0);
  EXPECT_GT(w.r, 128);
  EXPECT_LT(w.b, 128);
  EditRecipe cool;
  cool.temperature = -1.0f;
  const Rgb c = at(render(flat(2, 2, {128, 128, 128}), cool), 0, 0);
  EXPECT_LT(c.r, 128);
  EXPECT_GT(c.b, 128);
}

TEST(Edit, ContrastSpreadsTones) {
  EditRecipe recipe;
  recipe.contrast = 1.0f;
  const Image src = make(2, 1, [](std::uint32_t x, std::uint32_t) { return x == 0 ? Rgb{60, 60, 60} : Rgb{190, 190, 190}; });
  const Image out = render(src, recipe);
  EXPECT_LT(at(out, 0, 0).r, 60);
  EXPECT_GT(at(out, 1, 0).r, 190);
}

TEST(Edit, ShadowsLiftDarkAndKeepWhite) {
  EditRecipe recipe;
  recipe.shadows = 1.0f;
  const Image src = make(2, 1, [](std::uint32_t x, std::uint32_t) { return x == 0 ? Rgb{40, 40, 40} : Rgb{255, 255, 255}; });
  const Image out = render(src, recipe);
  EXPECT_GT(at(out, 0, 0).r, 55);
  EXPECT_EQ(at(out, 1, 0).r, 255);
}

TEST(Edit, HighlightsPullDownBrightTones) {
  EditRecipe recipe;
  recipe.highlights = -1.0f;
  const Rgb c = at(render(flat(1, 1, {200, 200, 200}), recipe), 0, 0);
  EXPECT_LT(c.r, 190);
}

TEST(Edit, InvertedCurveNegates) {
  EditRecipe recipe;
  recipe.curves[0] = {{0, 1}, {1, 0}};
  const Image src = make(2, 1, [](std::uint32_t x, std::uint32_t) { return x == 0 ? Rgb{0, 0, 0} : Rgb{255, 255, 255}; });
  const Image out = render(src, recipe);
  EXPECT_EQ(at(out, 0, 0).r, 255);
  EXPECT_EQ(at(out, 1, 0).r, 0);
}

TEST(Edit, DiagonalCurveIsIdentity) {
  EditRecipe recipe;
  recipe.curves[0] = {{0, 0}, {0.5f, 0.5f}, {1, 1}};
  EXPECT_TRUE(is_identity(recipe));
  recipe.curves[0] = {{0.2f, 0.2f}, {0.8f, 0.8f}};  // flat outside the points
  EXPECT_FALSE(is_identity(recipe));
}

TEST(Edit, CurvePassesThroughPointsAndIsMonotone) {
  const std::vector<CurvePoint> points = {{0, 0}, {0.5f, 0.7f}, {1, 1}};
  EXPECT_NEAR(evaluate_curve(points, 0.5f), 0.7f, 1e-4f);
  float previous = -1;
  for (int i = 0; i <= 100; ++i) {
    const float v = evaluate_curve(points, i / 100.0f);
    EXPECT_GE(v, previous - 1e-5f);
    previous = v;
  }
}

TEST(Edit, RedChannelCurveOnlyTouchesRed) {
  EditRecipe recipe;
  recipe.curves[1] = {{0, 0}, {0.5f, 0.8f}, {1, 1}};
  const Rgb c = at(render(flat(1, 1, {128, 128, 128}), recipe), 0, 0);
  EXPECT_GT(c.r, 180);
  EXPECT_EQ(c.g, 128);
  EXPECT_EQ(c.b, 128);
}

TEST(Edit, VignetteDarkensCornersNotCenter) {
  EditRecipe recipe;
  recipe.vignette = -1.0f;
  const Image out = render(flat(41, 41, {180, 180, 180}), recipe);
  EXPECT_EQ(at(out, 20, 20).r, 180);
  EXPECT_LT(at(out, 0, 0).r, 120);
}

TEST(Edit, SharpenRaisesEdgeContrast) {
  const Image src = make(20, 3, [](std::uint32_t x, std::uint32_t) { return x < 10 ? Rgb{80, 80, 80} : Rgb{170, 170, 170}; });
  EditRecipe recipe;
  recipe.sharpen = 1.0f;
  const Image out = render(src, recipe);
  EXPECT_LT(at(out, 9, 1).r, 80);
  EXPECT_GT(at(out, 10, 1).r, 170);
  EXPECT_EQ(at(out, 2, 1).r, 80);  // flat areas untouched
}

TEST(Edit, NoiseReductionSmoothsNoise) {
  const Image src = make(40, 40, [](std::uint32_t x, std::uint32_t y) {
    const int v = ((x * 7 + y * 13) % 5) * 12 + 100;
    return Rgb{v, v, v};
  });
  auto spread = [](const Image& img) {
    int lo = 255, hi = 0;
    for (std::uint32_t y = 5; y < 35; ++y) {
      for (std::uint32_t x = 5; x < 35; ++x) {
        lo = std::min(lo, at(img, x, y).r);
        hi = std::max(hi, at(img, x, y).r);
      }
    }
    return hi - lo;
  };
  EditRecipe recipe;
  recipe.noise_luma = 1.0f;
  EXPECT_LT(spread(render(src, recipe)), spread(src));
}

TEST(Edit, MixerDesaturatesOnlyItsBand) {
  EditRecipe recipe;
  recipe.mixer[static_cast<int>(MixerBand::Red)].saturation = -1.0f;
  const Image src = make(2, 1, [](std::uint32_t x, std::uint32_t) { return x == 0 ? Rgb{220, 30, 40} : Rgb{30, 60, 220}; });
  const Image out = render(src, recipe);
  const Rgb red = at(out, 0, 0);
  const Rgb blue = at(out, 1, 0);
  EXPECT_LT(red.r - red.g, 100);  // much less red than 190 apart
  EXPECT_NEAR(blue.b, 220, 4);
  EXPECT_NEAR(blue.r, 30, 4);
}

TEST(Edit, ClarityAndGrainRunAndChangePixels) {
  const Image src = gradient(64, 48);
  EditRecipe recipe;
  recipe.clarity = 1.0f;
  recipe.grain = 1.0f;
  recipe.noise_color = 0.5f;
  recipe.vibrance = 0.5f;
  recipe.shadow_amount = 0.5f;
  recipe.highlight_amount = 0.5f;
  const Image out = render(src, recipe);
  EXPECT_NE(out.rgba, src.rgba);
}

TEST(Edit, RendersToASmallerPreview) {
  const Image src = gradient(200, 100);
  EditRecipe recipe;
  recipe.exposure = 0.5f;
  const Image out = render(src, recipe, true, 50, 25);
  EXPECT_EQ(out.width, 50u);
  EXPECT_GT(at(out, 49, 0).r, at(out, 0, 0).r);
}

TEST(Edit, RejectsBadBuffers) {
  const Image src = gradient(4, 4);
  std::vector<std::uint8_t> small(8);
  EXPECT_FALSE(render_edit(view(src), EditRecipe{}, MutableRgbaView{small, 4, 4, 16}, {}).has_value());
  EXPECT_FALSE(render_edit(RgbaView{src.rgba, 0, 4, 16}, EditRecipe{}, MutableRgbaView{small, 1, 1, 4}, {}).has_value());
}

TEST(Edit, SpeedOfAFullEdit) {
  const Image src = gradient(1600, 1200);
  EditRecipe recipe;
  recipe.exposure = 0.3f;
  recipe.contrast = 0.4f;
  recipe.shadows = 0.3f;
  recipe.highlights = -0.4f;
  recipe.clarity = 0.3f;
  recipe.vibrance = 0.3f;
  recipe.sharpen = 0.4f;
  recipe.mixer[1].saturation = 0.3f;
  recipe.curves[0] = {{0, 0.05f}, {0.5f, 0.5f}, {1, 0.95f}};
  recipe.vignette = -0.3f;
  recipe.straighten = 3.0f;
  const auto start = std::chrono::steady_clock::now();
  const Image out = render(src, recipe);
  const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start);
  std::printf("2 MP full edit: %lld ms\n", static_cast<long long>(ms.count()));
  EXPECT_EQ(out.width, 1600u);
}

}  // namespace
}  // namespace daybreak
