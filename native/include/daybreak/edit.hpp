// Daybreak photo editor: non-destructive edits rendered from a recipe.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <vector>

#include "daybreak/engine.hpp"

namespace daybreak {

// A writable view of 8-bit RGBA pixels.
struct MutableRgbaView {
  std::span<std::uint8_t> pixels;
  std::uint32_t width = 0;
  std::uint32_t height = 0;
  std::uint32_t stride = 0;  // bytes per row
};

struct CurvePoint {
  float x = 0.0f;  // input, 0..1
  float y = 0.0f;  // output, 0..1
};

enum class CurveChannel : int { Rgb = 0, Red = 1, Green = 2, Blue = 3, Luma = 4 };
inline constexpr int kCurveChannels = 5;

// Hue bands of the color mixer.
enum class MixerBand : int { Red, Orange, Yellow, Green, Aqua, Blue, Purple, Magenta };
inline constexpr int kMixerBands = 8;

// Everything an edit does. Sliders run -1..1 (or 0..1) unless noted; the
// default value of every field leaves the photo unchanged.
struct EditRecipe {
  // Light
  float exposure = 0.0f;    // stops, -4..4
  float brightness = 0.0f;  // midtones
  float contrast = 0.0f;
  float highlights = 0.0f;
  float shadows = 0.0f;
  float whites = 0.0f;
  float blacks = 0.0f;
  float clarity = 0.0f;   // local contrast
  float vignette = 0.0f;  // < 0 darkens the edges

  // Color
  float temperature = 0.0f;  // > 0 warmer
  float tint = 0.0f;         // > 0 magenta
  float vibrance = 0.0f;
  float saturation = 0.0f;
  struct MixerAdjust {
    float hue = 0.0f;         // -1..1 = -30..30 degrees
    float saturation = 0.0f;  // -1..1
    float luminance = 0.0f;   // -1..1
  };
  std::array<MixerAdjust, kMixerBands> mixer{};
  float shadow_hue = 220.0f;  // degrees, color grading
  float shadow_amount = 0.0f;  // 0..1
  float highlight_hue = 40.0f;
  float highlight_amount = 0.0f;
  float grading_balance = 0.0f;  // -1 favors shadows, 1 highlights

  // Detail
  float sharpen = 0.0f;         // 0..1
  float sharpen_radius = 1.0f;  // pixels at full resolution, 0.5..3
  float noise_luma = 0.0f;      // 0..1
  float noise_color = 0.0f;     // 0..1
  float grain = 0.0f;           // 0..1
  float grain_size = 0.25f;     // 0..1

  // Curves; an empty list (or just (0,0) and (1,1)) is the identity.
  std::array<std::vector<CurvePoint>, kCurveChannels> curves{};

  // Geometry, applied in this order: quarter turns, flip, straighten, crop.
  int quarter_turns = 0;   // clockwise, 0..3
  bool flip = false;       // mirror left-right
  float straighten = 0.0f;  // degrees, -45..45; zooms in so no corners show
  // Crop rectangle as fractions of the straightened frame.
  float crop_left = 0.0f;
  float crop_top = 0.0f;
  float crop_right = 1.0f;
  float crop_bottom = 1.0f;
};

struct EditSize {
  std::uint32_t width = 0;
  std::uint32_t height = 0;
};

struct RenderOptions {
  // false shows the whole straightened frame (while the crop is being drawn).
  bool apply_crop = true;
  // Long side, in pixels, of the full-resolution source. Detail sizes (sharpen
  // radius, grain) are in full-resolution pixels, so a small preview gets the
  // same look as the export. 0 means the source given is full resolution.
  std::uint32_t full_resolution_long_side = 0;
  // Worker threads; 0 uses the hardware's count (up to 8).
  unsigned threads = 0;
};

// Size of the edited image for a source of src_width x src_height.
EditSize edit_output_size(std::uint32_t src_width, std::uint32_t src_height,
                          const EditRecipe& recipe, bool apply_crop);

// Renders source through the recipe into destination. The destination may be
// any size; normally it has edit_output_size's aspect ratio. Source and
// destination must not overlap.
Result<void> render_edit(const RgbaView& source, const EditRecipe& recipe,
                         const MutableRgbaView& destination, const RenderOptions& options);

// True when the recipe changes nothing at all.
bool is_identity(const EditRecipe& recipe);

// Samples a curve through the points (monotone cubic), at x in 0..1.
float evaluate_curve(std::span<const CurvePoint> points, float x);

}  // namespace daybreak
