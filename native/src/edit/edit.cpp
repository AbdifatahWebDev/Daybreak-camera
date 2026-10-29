// Daybreak photo editor: renders an EditRecipe.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Pipeline, per output pixel:
//   geometry (quarter turns, flip, straighten, crop; bilinear)
//   -> linear light -> white balance -> exposure
//   -> tone on perceptual luminance (brightness, contrast, highlights,
//      shadows, whites, blacks, clarity, sharpen, luminance noise), applied
//      as a ratio so hues stay put
//   -> curves (RGB, R, G, B, luma) in gamma space
//   -> Oklab color (color noise, saturation, vibrance, mixer, grading)
//   -> vignette -> grain -> sRGB.
// Neighborhood effects read precomputed planes: a full-resolution blurred
// luminance (sharpen, luminance noise) and small blurred planes (clarity,
// color noise), so memory stays a few bytes per pixel.

#include "daybreak/edit.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <numbers>
#include <thread>

namespace daybreak {
namespace {

constexpr float kPi = std::numbers::pi_v<float>;

float clamp01(float v) { return std::clamp(v, 0.0f, 1.0f); }

float smoothstep(float e0, float e1, float x) {
  const float t = clamp01((x - e0) / (e1 - e0));
  return t * t * (3.0f - 2.0f * t);
}

// Cube root to about 1e-6 relative error: a bit-level first guess and two
// Newton steps. Several times faster than std::cbrt on phones.
float fast_cbrt(float x) {
  if (x == 0.0f) return 0.0f;
  const float ax = std::fabs(x);
  float y = std::bit_cast<float>(std::bit_cast<std::uint32_t>(ax) / 3u + 709921077u);
  y = (2.0f * y + ax / (y * y)) * (1.0f / 3.0f);
  y = (2.0f * y + ax / (y * y)) * (1.0f / 3.0f);
  return x < 0.0f ? -y : y;
}

// atan2 to about 1e-5 radians.
float fast_atan2(float y, float x) {
  const float ax = std::fabs(x);
  const float ay = std::fabs(y);
  const float mx = std::max(ax, ay);
  if (mx == 0.0f) return 0.0f;
  const float a = std::min(ax, ay) / mx;
  const float s = a * a;
  float r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a;
  if (ay > ax) r = 1.57079637f - r;
  if (x < 0.0f) r = 3.14159274f - r;
  return y < 0.0f ? -r : r;
}

// ---------------------------------------------------------------- transfer

float srgb_decode_exact(float g) {
  return g <= 0.04045f ? g / 12.92f : std::pow((g + 0.055f) / 1.055f, 2.4f);
}

float srgb_encode_exact(float l) {
  return l <= 0.0031308f ? l * 12.92f : 1.055f * std::pow(l, 1.0f / 2.4f) - 0.055f;
}

struct Transfer {
  static constexpr int kSteps = 4096;
  float decode8[256];
  float encode_table[kSteps + 1];
  float decode_table[kSteps + 1];
  std::uint8_t to8[65536];

  Transfer() {
    for (int i = 0; i < 256; ++i) decode8[i] = srgb_decode_exact(i / 255.0f);
    for (int i = 0; i <= kSteps; ++i) {
      const float x = static_cast<float>(i) / kSteps;
      encode_table[i] = srgb_encode_exact(x);
      decode_table[i] = srgb_decode_exact(x);
    }
    for (int i = 0; i < 65536; ++i) {
      const float g = srgb_encode_exact((i + 0.5f) / 65536.0f);
      to8[i] = static_cast<std::uint8_t>(std::clamp(std::lround(g * 255.0f), 0L, 255L));
    }
  }

  static float lookup(const float* table, float x) {
    const float p = x * kSteps;
    const int i = static_cast<int>(p);
    const float f = p - static_cast<float>(i);
    return table[i] + (table[i + 1] - table[i]) * f;
  }

  // Linear light to perceptual (sRGB-encoded) value.
  float encode(float l) const {
    if (l <= 0.0f) return 0.0f;
    if (l >= 1.0f) return srgb_encode_exact(l);
    return lookup(encode_table, l);
  }

  float decode(float g) const {
    if (g <= 0.0f) return 0.0f;
    if (g >= 1.0f) return srgb_decode_exact(g);
    return lookup(decode_table, g);
  }

  std::uint8_t to_byte(float l) const {
    if (!(l > 0.0f)) return 0;  // also catches NaN
    if (l >= 1.0f) return 255;
    return to8[static_cast<int>(l * 65536.0f)];
  }
};

const Transfer& transfer() {
  static const Transfer table;
  return table;
}

// ------------------------------------------------------------------- oklab

struct Lab {
  float l, a, b;
};

Lab linear_to_oklab(float r, float g, float b) {
  const float l = fast_cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b);
  const float m = fast_cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b);
  const float s = fast_cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b);
  return {0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
          1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
          0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s};
}

void oklab_to_linear(const Lab& c, float& r, float& g, float& b) {
  float l = c.l + 0.3963377774f * c.a + 0.2158037573f * c.b;
  float m = c.l - 0.1055613458f * c.a - 0.0638541728f * c.b;
  float s = c.l - 0.0894841775f * c.a - 1.2914855480f * c.b;
  l = l * l * l;
  m = m * m * m;
  s = s * s * s;
  r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
  g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
  b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
}

// ------------------------------------------------------------------ curves

std::vector<CurvePoint> clean_points(std::span<const CurvePoint> points) {
  std::vector<CurvePoint> p;
  p.reserve(points.size());
  for (const auto& q : points) {
    if (std::isfinite(q.x) && std::isfinite(q.y)) p.push_back({clamp01(q.x), clamp01(q.y)});
  }
  std::stable_sort(p.begin(), p.end(), [](const CurvePoint& a, const CurvePoint& b) { return a.x < b.x; });
  std::vector<CurvePoint> unique;
  for (const auto& q : p) {
    if (!unique.empty() && q.x - unique.back().x < 1e-4f) {
      unique.back() = q;
    } else {
      unique.push_back(q);
    }
  }
  return unique;
}

bool curve_is_identity(std::span<const CurvePoint> points) {
  for (const auto& p : points) {
    if (std::fabs(p.x - p.y) > 1e-4f) return false;
  }
  // Zero or one point, or all on the diagonal (with ends at 0 and 1 or not:
  // points on the diagonal extend flat beyond the ends, so require the ends).
  if (points.size() < 2) return true;
  const auto p = clean_points(points);
  return p.front().x <= 1e-4f && p.back().x >= 1.0f - 1e-4f;
}

// Fritsch-Carlson monotone cubic through the points; flat beyond the ends.
struct Spline {
  std::vector<CurvePoint> p;
  std::vector<float> m;  // tangents

  explicit Spline(std::span<const CurvePoint> points) : p(clean_points(points)) {
    const std::size_t n = p.size();
    if (n < 2) return;
    std::vector<float> d(n - 1);
    for (std::size_t i = 0; i + 1 < n; ++i) d[i] = (p[i + 1].y - p[i].y) / (p[i + 1].x - p[i].x);
    m.assign(n, 0.0f);
    m[0] = d[0];
    m[n - 1] = d[n - 2];
    for (std::size_t i = 1; i + 1 < n; ++i) m[i] = (d[i - 1] * d[i] <= 0.0f) ? 0.0f : 0.5f * (d[i - 1] + d[i]);
    for (std::size_t i = 0; i + 1 < n; ++i) {
      if (d[i] == 0.0f) {
        m[i] = 0.0f;
        m[i + 1] = 0.0f;
        continue;
      }
      const float a = m[i] / d[i];
      const float b = m[i + 1] / d[i];
      const float h = a * a + b * b;
      if (h > 9.0f) {
        const float t = 3.0f / std::sqrt(h);
        m[i] = t * a * d[i];
        m[i + 1] = t * b * d[i];
      }
    }
  }

  float operator()(float x) const {
    const std::size_t n = p.size();
    if (n == 0) return x;
    if (n == 1) return p[0].y;
    if (x <= p.front().x) return p.front().y;
    if (x >= p.back().x) return p.back().y;
    std::size_t i = 0;
    while (i + 2 < n && x > p[i + 1].x) ++i;
    const float h = p[i + 1].x - p[i].x;
    const float t = (x - p[i].x) / h;
    const float t2 = t * t;
    const float t3 = t2 * t;
    return (2 * t3 - 3 * t2 + 1) * p[i].y + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * p[i + 1].y +
           (t3 - t2) * h * m[i + 1];
  }
};

struct CurveLut {
  static constexpr int kSize = 1024;
  bool active = false;
  float v[kSize + 1];

  void build(std::span<const CurvePoint> points) {
    active = !curve_is_identity(points);
    if (!active) return;
    const Spline spline(points);
    for (int i = 0; i <= kSize; ++i) v[i] = clamp01(spline(static_cast<float>(i) / kSize));
  }

  float operator()(float x) const {
    const float p = clamp01(x) * kSize;
    const int i = std::min(static_cast<int>(p), kSize - 1);
    return v[i] + (v[i + 1] - v[i]) * (p - static_cast<float>(i));
  }
};

// ---------------------------------------------------------------- threads

template <typename F>
void parallel_rows(std::uint32_t rows, unsigned threads, F&& work) {
  unsigned n = threads != 0 ? threads : std::min(8u, std::max(1u, std::thread::hardware_concurrency()));
  n = std::min<unsigned>(n, std::max<std::uint32_t>(1, rows / 8));
  if (n <= 1) {
    work(0u, rows);
    return;
  }
  const std::uint32_t chunk = (rows + n - 1) / n;
  std::vector<std::thread> pool;
  pool.reserve(n);
  for (unsigned i = 0; i < n; ++i) {
    const std::uint32_t begin = i * chunk;
    const std::uint32_t end = std::min(rows, begin + chunk);
    if (begin >= end) break;
    pool.emplace_back([&work, begin, end] { work(begin, end); });
  }
  for (auto& t : pool) t.join();
}

// ------------------------------------------------------------------ planes

struct Plane {
  std::uint32_t w = 0, h = 0;
  std::vector<float> v;

  Plane() = default;
  Plane(std::uint32_t width, std::uint32_t height) : w(width), h(height), v(std::size_t{width} * height, 0.0f) {}
  float& at(std::uint32_t x, std::uint32_t y) { return v[std::size_t{y} * w + x]; }
  float at(std::uint32_t x, std::uint32_t y) const { return v[std::size_t{y} * w + x]; }

  // Bilinear sample at plane coordinates (pixel centers on integers).
  float sample(float x, float y) const {
    x = std::clamp(x, 0.0f, static_cast<float>(w - 1));
    y = std::clamp(y, 0.0f, static_cast<float>(h - 1));
    const std::uint32_t x0 = static_cast<std::uint32_t>(x);
    const std::uint32_t y0 = static_cast<std::uint32_t>(y);
    const std::uint32_t x1 = std::min(x0 + 1, w - 1);
    const std::uint32_t y1 = std::min(y0 + 1, h - 1);
    const float fx = x - static_cast<float>(x0);
    const float fy = y - static_cast<float>(y0);
    const float top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx;
    const float bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx;
    return top + (bottom - top) * fy;
  }
};

// Running-sum box blur, horizontal then vertical, edges clamped.
void box_blur(Plane& plane, int radius, unsigned threads) {
  if (radius < 1 || plane.w == 0 || plane.h == 0) return;
  Plane tmp(plane.w, plane.h);
  const float scale = 1.0f / static_cast<float>(2 * radius + 1);
  const int w = static_cast<int>(plane.w);
  const int h = static_cast<int>(plane.h);
  parallel_rows(plane.h, threads, [&](std::uint32_t y0, std::uint32_t y1) {
    for (std::uint32_t y = y0; y < y1; ++y) {
      float sum = 0.0f;
      for (int i = -radius; i <= radius; ++i) sum += plane.at(std::clamp(i, 0, w - 1), y);
      for (int x = 0; x < w; ++x) {
        tmp.at(x, y) = sum * scale;
        sum += plane.at(std::min(x + radius + 1, w - 1), y) - plane.at(std::max(x - radius, 0), y);
      }
    }
  });
  parallel_rows(plane.w, threads, [&](std::uint32_t x0, std::uint32_t x1) {
    for (std::uint32_t x = x0; x < x1; ++x) {
      float sum = 0.0f;
      for (int i = -radius; i <= radius; ++i) sum += tmp.at(x, std::clamp(i, 0, h - 1));
      for (int y = 0; y < h; ++y) {
        plane.at(x, y) = sum * scale;
        sum += tmp.at(x, std::min(y + radius + 1, h - 1)) - tmp.at(x, std::max(y - radius, 0));
      }
    }
  });
}

// ---------------------------------------------------------------- geometry

struct Geometry {
  std::uint32_t src_w = 0, src_h = 0;  // source
  float ow = 0, oh = 0;                // oriented frame
  int turns = 0;
  bool flip = false;
  float cos_t = 1, sin_t = 0, zoom = 1;  // straighten
  float crop_x = 0, crop_y = 0, crop_w = 0, crop_h = 0;  // in frame pixels
  bool identity = true;

  Geometry(std::uint32_t w, std::uint32_t h, const EditRecipe& r, bool apply_crop) : src_w(w), src_h(h) {
    turns = ((r.quarter_turns % 4) + 4) % 4;
    flip = r.flip;
    ow = static_cast<float>((turns & 1) ? h : w);
    oh = static_cast<float>((turns & 1) ? w : h);
    const float angle = std::clamp(std::isfinite(r.straighten) ? r.straighten : 0.0f, -45.0f, 45.0f);
    const float rad = angle * kPi / 180.0f;
    cos_t = std::cos(rad);
    sin_t = std::sin(rad);
    const float as = std::fabs(sin_t);
    zoom = std::max(cos_t + (oh / ow) * as, cos_t + (ow / oh) * as);
    float left = 0, top = 0, right = 1, bottom = 1;
    if (apply_crop) {
      left = clamp01(r.crop_left);
      top = clamp01(r.crop_top);
      right = clamp01(r.crop_right);
      bottom = clamp01(r.crop_bottom);
      if (right - left < 1e-3f) right = std::min(1.0f, left + 1e-3f), left = right - 1e-3f;
      if (bottom - top < 1e-3f) bottom = std::min(1.0f, top + 1e-3f), top = bottom - 1e-3f;
    }
    crop_x = left * ow;
    crop_y = top * oh;
    crop_w = (right - left) * ow;
    crop_h = (bottom - top) * oh;
    identity = turns == 0 && !flip && angle == 0.0f && left == 0 && top == 0 && right == 1 && bottom == 1;
  }

  EditSize output_size() const {
    return {static_cast<std::uint32_t>(std::max(1L, std::lround(crop_w))),
            static_cast<std::uint32_t>(std::max(1L, std::lround(crop_h)))};
  }

  // Source position (pixel centers at .5) for frame position (fx, fy).
  void to_source(float fx, float fy, float& sx, float& sy) const {
    const float ux = fx - ow * 0.5f;
    const float uy = fy - oh * 0.5f;
    float ox = (cos_t * ux + sin_t * uy) / zoom + ow * 0.5f;
    const float oy = (-sin_t * ux + cos_t * uy) / zoom + oh * 0.5f;
    if (flip) ox = ow - ox;
    const float w = static_cast<float>(src_w);
    const float h = static_cast<float>(src_h);
    switch (turns) {
      case 1: sx = oy; sy = h - ox; break;
      case 2: sx = w - ox; sy = h - oy; break;
      case 3: sx = w - oy; sy = ox; break;
      default: sx = ox; sy = oy; break;
    }
  }
};

// Resamples the source through the geometry into a packed RGBA image.
void apply_geometry(const RgbaView& src, const Geometry& g, std::uint32_t out_w, std::uint32_t out_h,
                    std::vector<std::uint8_t>& out, unsigned threads) {
  out.resize(std::size_t{out_w} * out_h * 4);
  const float step_x = g.crop_w / static_cast<float>(out_w);
  const float step_y = g.crop_h / static_cast<float>(out_h);
  const int max_x = static_cast<int>(src.width) - 1;
  const int max_y = static_cast<int>(src.height) - 1;
  parallel_rows(out_h, threads, [&](std::uint32_t y0, std::uint32_t y1) {
    for (std::uint32_t y = y0; y < y1; ++y) {
      std::uint8_t* row = out.data() + std::size_t{y} * out_w * 4;
      const float fy = g.crop_y + (static_cast<float>(y) + 0.5f) * step_y;
      for (std::uint32_t x = 0; x < out_w; ++x) {
        const float fx = g.crop_x + (static_cast<float>(x) + 0.5f) * step_x;
        float sx = 0, sy = 0;
        g.to_source(fx, fy, sx, sy);
        sx -= 0.5f;
        sy -= 0.5f;
        const float flx = std::floor(sx);
        const float fly = std::floor(sy);
        const float ax = sx - flx;
        const float ay = sy - fly;
        const int x0 = std::clamp(static_cast<int>(flx), 0, max_x);
        const int y0i = std::clamp(static_cast<int>(fly), 0, max_y);
        const int x1 = std::clamp(static_cast<int>(flx) + 1, 0, max_x);
        const int y1i = std::clamp(static_cast<int>(fly) + 1, 0, max_y);
        const std::uint8_t* p00 = src.pixels.data() + std::size_t(y0i) * src.stride + std::size_t(x0) * 4;
        const std::uint8_t* p10 = src.pixels.data() + std::size_t(y0i) * src.stride + std::size_t(x1) * 4;
        const std::uint8_t* p01 = src.pixels.data() + std::size_t(y1i) * src.stride + std::size_t(x0) * 4;
        const std::uint8_t* p11 = src.pixels.data() + std::size_t(y1i) * src.stride + std::size_t(x1) * 4;
        for (int c = 0; c < 4; ++c) {
          const float top = p00[c] + (p10[c] - p00[c]) * ax;
          const float bottom = p01[c] + (p11[c] - p01[c]) * ax;
          row[x * 4 + c] = static_cast<std::uint8_t>(std::lround(top + (bottom - top) * ay));
        }
      }
    }
  });
}

// --------------------------------------------------------------- the kernel

constexpr std::array<float, kMixerBands> kBandCenters = {25, 60, 105, 145, 195, 260, 295, 335};

float luma8(const std::uint8_t* p) {
  return (0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]) * (1.0f / 255.0f);
}

std::uint32_t hash2(std::int32_t x, std::int32_t y) {
  std::uint32_t h = static_cast<std::uint32_t>(x) * 0x8da6b343u ^ static_cast<std::uint32_t>(y) * 0xd8163841u;
  h ^= h >> 13;
  h *= 0x2545f491u;
  h ^= h >> 16;
  return h;
}

float lattice(std::int32_t x, std::int32_t y) {
  return static_cast<float>(hash2(x, y) & 0xffffu) / 32767.5f - 1.0f;
}

// Smooth value noise in -1..1.
float value_noise(float x, float y) {
  const float fx = std::floor(x);
  const float fy = std::floor(y);
  const std::int32_t ix = static_cast<std::int32_t>(fx);
  const std::int32_t iy = static_cast<std::int32_t>(fy);
  float tx = x - fx;
  float ty = y - fy;
  tx = tx * tx * (3 - 2 * tx);
  ty = ty * ty * (3 - 2 * ty);
  const float a = lattice(ix, iy) + (lattice(ix + 1, iy) - lattice(ix, iy)) * tx;
  const float b = lattice(ix, iy + 1) + (lattice(ix + 1, iy + 1) - lattice(ix, iy + 1)) * tx;
  return a + (b - a) * ty;
}

struct Kernel {
  const Transfer& tf = transfer();
  EditRecipe r;

  // White balance and exposure as linear gains.
  float gain_r = 1, gain_g = 1, gain_b = 1;
  bool tone_active = false, detail_active = false, color_active = false, mixer_active = false;
  bool grading_active = false, vignette_active = false, grain_active = false, curves_active = false;
  std::array<CurveLut, kCurveChannels> curves;
  float brightness_gamma = 1.0f;
  static constexpr int kToneSteps = 4096;
  std::vector<float> tone_table;  // tone() on 0..1, built when tone is active
  float shadow_a = 0, shadow_b = 0, highlight_a = 0, highlight_b = 0;


  explicit Kernel(const EditRecipe& recipe) : r(recipe) {
    const float t = std::clamp(r.temperature, -1.0f, 1.0f);
    const float tint = std::clamp(r.tint, -1.0f, 1.0f);
    float gr = std::exp2(0.30f * t + 0.05f * tint);
    float gg = std::exp2(-0.20f * tint);
    float gb = std::exp2(-0.30f * t + 0.05f * tint);
    const float lum = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb;
    const float exposure = std::exp2(std::clamp(r.exposure, -4.0f, 4.0f));
    gain_r = gr / lum * exposure;
    gain_g = gg / lum * exposure;
    gain_b = gb / lum * exposure;

    tone_active = r.brightness != 0 || r.contrast != 0 || r.highlights != 0 || r.shadows != 0 || r.whites != 0 ||
                  r.blacks != 0;
    detail_active = r.clarity != 0 || r.sharpen > 0 || r.noise_luma > 0;
    brightness_gamma = std::exp2(-std::clamp(r.brightness, -1.0f, 1.0f) * 0.9f);

    for (const auto& band : r.mixer) {
      if (band.hue != 0 || band.saturation != 0 || band.luminance != 0) mixer_active = true;
    }
    grading_active = r.shadow_amount > 0 || r.highlight_amount > 0;
    const float sh = r.shadow_hue * kPi / 180.0f;
    const float hh = r.highlight_hue * kPi / 180.0f;
    const float s_amount = clamp01(r.shadow_amount) * 0.08f;
    const float h_amount = clamp01(r.highlight_amount) * 0.08f;
    shadow_a = std::cos(sh) * s_amount;
    shadow_b = std::sin(sh) * s_amount;
    highlight_a = std::cos(hh) * h_amount;
    highlight_b = std::sin(hh) * h_amount;
    color_active = r.vibrance != 0 || r.saturation != 0 || mixer_active || grading_active || r.noise_color > 0;
    vignette_active = r.vignette != 0;
    grain_active = r.grain > 0;

    for (int c = 0; c < kCurveChannels; ++c) {
      curves[c].build(r.curves[c]);
      curves_active = curves_active || curves[c].active;
    }
    if (tone_active) {
      tone_table.resize(kToneSteps + 1);
      for (int i = 0; i <= kToneSteps; ++i) tone_table[i] = tone_exact(static_cast<float>(i) / kToneSteps);
    }
  }

  // Tone through the table, with the part above 1.0 (after exposure) handled exactly.
  float tone(float l) const {
    if (l >= 1.0f) {
      return tone_table[kToneSteps] + (l - 1.0f) * std::max(0.0f, 1.0f + r.highlights) * (1.0f + 0.5f * r.whites);
    }
    if (l <= 0.0f) return tone_table[0];
    const float p = l * kToneSteps;
    const int i = static_cast<int>(p);
    return tone_table[i] + (tone_table[i + 1] - tone_table[i]) * (p - static_cast<float>(i));
  }

  // Tone curve on perceptual luminance (0..1), before detail.
  float tone_exact(float l) const {
    l = clamp01(l);
    if (r.brightness != 0) l = std::pow(l, brightness_gamma);
    if (r.contrast > 0) {
      const float s = l * l * (3.0f - 2.0f * l);
      l += std::min(r.contrast, 1.0f) * (s - l);
    } else if (r.contrast < 0) {
      l += std::max(r.contrast, -1.0f) * -0.5f * (0.5f - l);
    }
    if (r.highlights != 0) {
      const float c = clamp01(l);
      l += r.highlights * 0.30f * 6.75f * c * c * (1.0f - c);
    }
    if (r.shadows != 0) {
      const float c = clamp01(l);
      l += r.shadows * 0.30f * 6.75f * c * (1.0f - c) * (1.0f - c);
    }
    if (r.whites != 0) {
      const float c = clamp01(l);
      l += r.whites * 0.25f * c * c * c;
    }
    if (r.blacks != 0) {
      const float c = 1.0f - clamp01(l);
      l += r.blacks * 0.12f * c * c * c;
    }
    return l;
  }

  // Applies hue-band adjustments to an Oklab color.
  void mix(Lab& c) const {
    const float chroma = std::sqrt(c.a * c.a + c.b * c.b);
    const float gate = smoothstep(0.015f, 0.06f, chroma);
    if (gate <= 0.0f) return;
    float hue = fast_atan2(c.b, c.a) * 180.0f / kPi;
    if (hue < 0) hue += 360.0f;
    int i = kMixerBands - 1;
    for (int k = 0; k < kMixerBands; ++k) {
      if (hue >= kBandCenters[k]) i = k;
    }
    if (hue < kBandCenters[0]) i = kMixerBands - 1;
    const int j = (i + 1) % kMixerBands;
    float span = kBandCenters[j] - kBandCenters[i];
    float offset = hue - kBandCenters[i];
    if (span <= 0) span += 360.0f;
    if (offset < 0) offset += 360.0f;
    const float wi = 1.0f - smoothstep(0.0f, 1.0f, offset / span);
    const float wj = 1.0f - wi;
    const auto& bi = r.mixer[i];
    const auto& bj = r.mixer[j];
    const float dh = (wi * bi.hue + wj * bj.hue) * 30.0f * gate;
    const float ds = (wi * bi.saturation + wj * bj.saturation) * gate;
    const float dl = (wi * bi.luminance + wj * bj.luminance) * gate;
    // Rotate by dh (at most 30 degrees) with short series, and scale the chroma.
    const float x = dh * kPi / 180.0f;
    const float x2 = x * x;
    const float cos_d = 1.0f - x2 * (0.5f - x2 * (1.0f / 24.0f));
    const float sin_d = x * (1.0f - x2 * (1.0f / 6.0f - x2 * (1.0f / 120.0f)));
    const float scale = std::max(0.0f, 1.0f + ds);
    const float a = (c.a * cos_d - c.b * sin_d) * scale;
    const float b = (c.a * sin_d + c.b * cos_d) * scale;
    c.a = a;
    c.b = b;
    c.l += dl * 0.12f;
  }
};

}  // namespace

// ---------------------------------------------------------------- public API

float evaluate_curve(std::span<const CurvePoint> points, float x) {
  if (curve_is_identity(points)) return x;
  return clamp01(Spline(points)(clamp01(x)));
}

bool is_identity(const EditRecipe& r) {
  if (r.exposure != 0 || r.brightness != 0 || r.contrast != 0 || r.highlights != 0 || r.shadows != 0 ||
      r.whites != 0 || r.blacks != 0 || r.clarity != 0 || r.vignette != 0 || r.temperature != 0 || r.tint != 0 ||
      r.vibrance != 0 || r.saturation != 0 || r.shadow_amount > 0 || r.highlight_amount > 0 || r.sharpen > 0 ||
      r.noise_luma > 0 || r.noise_color > 0 || r.grain > 0) {
    return false;
  }
  for (const auto& band : r.mixer) {
    if (band.hue != 0 || band.saturation != 0 || band.luminance != 0) return false;
  }
  for (const auto& curve : r.curves) {
    if (!curve_is_identity(curve)) return false;
  }
  return Geometry(2, 2, r, true).identity;
}

EditSize edit_output_size(std::uint32_t src_width, std::uint32_t src_height, const EditRecipe& recipe,
                          bool apply_crop) {
  if (src_width == 0 || src_height == 0) return {0, 0};
  return Geometry(src_width, src_height, recipe, apply_crop).output_size();
}

Result<void> render_edit(const RgbaView& source, const EditRecipe& recipe, const MutableRgbaView& destination,
                         const RenderOptions& options) {
  if (source.width == 0 || source.height == 0 || source.stride < source.width * 4 ||
      source.pixels.size() < std::size_t{source.stride} * (source.height - 1) + std::size_t{source.width} * 4) {
    return std::unexpected(Error{"Bad source image"});
  }
  if (destination.width == 0 || destination.height == 0 || destination.stride < destination.width * 4 ||
      destination.pixels.size() <
          std::size_t{destination.stride} * (destination.height - 1) + std::size_t{destination.width} * 4) {
    return std::unexpected(Error{"Bad destination image"});
  }
  const unsigned threads = options.threads;
  const std::uint32_t out_w = destination.width;
  const std::uint32_t out_h = destination.height;
  const Geometry geometry(source.width, source.height, recipe, options.apply_crop);

  // 1. Geometry. Skipped when the output is the source pixel for pixel.
  std::vector<std::uint8_t> resampled;
  const std::uint8_t* geo = source.pixels.data();
  std::size_t geo_stride = source.stride;
  if (!(geometry.identity && out_w == source.width && out_h == source.height)) {
    apply_geometry(source, geometry, out_w, out_h, resampled, threads);
    geo = resampled.data();
    geo_stride = std::size_t{out_w} * 4;
  }

  const Kernel k(recipe);
  const Transfer& tf = k.tf;

  // Output pixels per full-resolution pixel, for detail sizes.
  const EditSize at_source = geometry.output_size();
  const float src_long = static_cast<float>(std::max(source.width, source.height));
  const float full_long = options.full_resolution_long_side > 0
                              ? static_cast<float>(options.full_resolution_long_side)
                              : src_long;
  const float pixel_scale = (src_long / full_long) *
                            (static_cast<float>(std::max(out_w, out_h)) /
                             static_cast<float>(std::max(at_source.width, at_source.height)));

  // 2. Neighborhood planes.
  Plane fine;  // blurred luminance for sharpen and luminance noise
  if (recipe.sharpen > 0 || recipe.noise_luma > 0) {
    fine = Plane(out_w, out_h);
    parallel_rows(out_h, threads, [&](std::uint32_t y0, std::uint32_t y1) {
      for (std::uint32_t y = y0; y < y1; ++y) {
        const std::uint8_t* row = geo + y * geo_stride;
        for (std::uint32_t x = 0; x < out_w; ++x) fine.at(x, y) = luma8(row + x * 4);
      }
    });
    const float radius = recipe.sharpen > 0 ? std::clamp(recipe.sharpen_radius, 0.5f, 3.0f) : 1.0f;
    const int box = std::max(1, static_cast<int>(std::lround(radius * pixel_scale)));
    box_blur(fine, box, threads);
    box_blur(fine, box, threads);
  }

  const std::uint32_t out_long = std::max(out_w, out_h);
  Plane clarity;  // heavily blurred luminance, small
  std::uint32_t clarity_factor = 1;
  if (recipe.clarity != 0) {
    clarity_factor = std::max<std::uint32_t>(1, (out_long + 319) / 320);
    clarity = Plane((out_w + clarity_factor - 1) / clarity_factor, (out_h + clarity_factor - 1) / clarity_factor);
    for (std::uint32_t y = 0; y < clarity.h; ++y) {
      for (std::uint32_t x = 0; x < clarity.w; ++x) {
        float sum = 0;
        int count = 0;
        for (std::uint32_t yy = y * clarity_factor; yy < std::min(out_h, (y + 1) * clarity_factor); ++yy) {
          for (std::uint32_t xx = x * clarity_factor; xx < std::min(out_w, (x + 1) * clarity_factor); ++xx) {
            sum += luma8(geo + yy * geo_stride + xx * 4);
            ++count;
          }
        }
        clarity.at(x, y) = count > 0 ? sum / static_cast<float>(count) : 0.0f;
      }
    }
    const int radius = std::max(1, static_cast<int>(std::lround(std::max(clarity.w, clarity.h) * 0.035f)));
    for (int i = 0; i < 3; ++i) box_blur(clarity, radius, threads);
  }

  Plane chroma_a, chroma_b;  // blurred input chroma, small
  std::uint32_t chroma_factor = 1;
  if (recipe.noise_color > 0) {
    chroma_factor = std::max<std::uint32_t>(1, (out_long + 399) / 400);
    const std::uint32_t cw = (out_w + chroma_factor - 1) / chroma_factor;
    const std::uint32_t ch = (out_h + chroma_factor - 1) / chroma_factor;
    chroma_a = Plane(cw, ch);
    chroma_b = Plane(cw, ch);
    parallel_rows(ch, threads, [&](std::uint32_t y0, std::uint32_t y1) {
      for (std::uint32_t y = y0; y < y1; ++y) {
        for (std::uint32_t x = 0; x < cw; ++x) {
          float sa = 0, sb = 0;
          int count = 0;
          for (std::uint32_t yy = y * chroma_factor; yy < std::min(out_h, (y + 1) * chroma_factor); ++yy) {
            for (std::uint32_t xx = x * chroma_factor; xx < std::min(out_w, (x + 1) * chroma_factor); ++xx) {
              const std::uint8_t* p = geo + yy * geo_stride + xx * 4;
              const Lab c = linear_to_oklab(tf.decode8[p[0]], tf.decode8[p[1]], tf.decode8[p[2]]);
              sa += c.a;
              sb += c.b;
              ++count;
            }
          }
          chroma_a.at(x, y) = sa / static_cast<float>(count);
          chroma_b.at(x, y) = sb / static_cast<float>(count);
        }
      }
    });
    const int radius = 1 + static_cast<int>(std::lround(std::clamp(recipe.noise_color, 0.0f, 1.0f) * 4.0f));
    box_blur(chroma_a, radius, threads);
    box_blur(chroma_a, radius, threads);
    box_blur(chroma_b, radius, threads);
    box_blur(chroma_b, radius, threads);
  }

  const float grain_cell = std::max(1.0f, (1.0f + std::clamp(recipe.grain_size, 0.0f, 1.0f) * 3.0f) * pixel_scale);
  const float vignette_norm = std::hypot(static_cast<float>(out_w), static_cast<float>(out_h)) * 0.5f;
  const bool any_pixel_op = k.gain_r != 1.0f || k.gain_g != 1.0f || k.gain_b != 1.0f || k.tone_active ||
                            k.detail_active || k.curves_active || k.color_active || k.vignette_active ||
                            k.grain_active;

  // 3. Per-pixel pass.
  parallel_rows(out_h, threads, [&](std::uint32_t y0, std::uint32_t y1) {
    for (std::uint32_t y = y0; y < y1; ++y) {
      const std::uint8_t* in_row = geo + y * geo_stride;
      std::uint8_t* out_row = destination.pixels.data() + std::size_t{y} * destination.stride;
      if (!any_pixel_op) {
        std::memcpy(out_row, in_row, std::size_t{out_w} * 4);
        continue;
      }
      for (std::uint32_t x = 0; x < out_w; ++x) {
        const std::uint8_t* p = in_row + x * 4;
        float r = tf.decode8[p[0]] * k.gain_r;
        float g = tf.decode8[p[1]] * k.gain_g;
        float b = tf.decode8[p[2]] * k.gain_b;

        // Tone and detail on perceptual luminance, applied as a ratio.
        if (k.tone_active || k.detail_active) {
          const float y_lin = 0.2126f * r + 0.7152f * g + 0.0722f * b;
          const float lp = tf.encode(y_lin);
          float lt = k.tone_active ? k.tone(lp) : lp;
          if (k.detail_active) {
            const float in_luma = luma8(p);
            if (recipe.sharpen > 0 || recipe.noise_luma > 0) {
              const float d = in_luma - fine.at(x, y);
              lt += d * (std::clamp(recipe.sharpen, 0.0f, 1.0f) * 1.6f -
                         std::clamp(recipe.noise_luma, 0.0f, 1.0f) * 0.9f);
            }
            if (recipe.clarity != 0) {
              const float blurred = clarity.sample((static_cast<float>(x) + 0.5f) / clarity_factor - 0.5f,
                                                   (static_cast<float>(y) + 0.5f) / clarity_factor - 0.5f);
              const float c = clamp01(lt);
              lt += std::clamp(recipe.clarity, -1.0f, 1.0f) * 0.9f * (in_luma - blurred) * 4.0f * c * (1.0f - c);
            }
          }
          lt = std::max(lt, 0.0f);
          const float y_new = tf.decode(lt);
          if (y_lin > 1e-5f) {
            const float ratio = y_new / y_lin;
            r *= ratio;
            g *= ratio;
            b *= ratio;
          } else {
            r = g = b = y_new;
          }
        }

        // Curves in gamma space.
        if (k.curves_active) {
          float rg = tf.encode(r), gg = tf.encode(g), bg = tf.encode(b);
          const CurveLut& all = k.curves[static_cast<int>(CurveChannel::Rgb)];
          if (all.active) {
            rg = all(rg);
            gg = all(gg);
            bg = all(bg);
          }
          if (k.curves[1].active) rg = k.curves[1](rg);
          if (k.curves[2].active) gg = k.curves[2](gg);
          if (k.curves[3].active) bg = k.curves[3](bg);
          const CurveLut& luma = k.curves[static_cast<int>(CurveChannel::Luma)];
          if (luma.active) {
            const float l = 0.2126f * rg + 0.7152f * gg + 0.0722f * bg;
            const float d = luma(l) - l;
            rg += d;
            gg += d;
            bg += d;
          }
          r = tf.decode(rg);
          g = tf.decode(gg);
          b = tf.decode(bg);
        }

        // Color in Oklab.
        if (k.color_active) {
          Lab c = linear_to_oklab(std::max(r, 0.0f), std::max(g, 0.0f), std::max(b, 0.0f));
          if (recipe.noise_color > 0) {
            const Lab in = linear_to_oklab(tf.decode8[p[0]], tf.decode8[p[1]], tf.decode8[p[2]]);
            const float sx = (static_cast<float>(x) + 0.5f) / chroma_factor - 0.5f;
            const float sy = (static_cast<float>(y) + 0.5f) / chroma_factor - 0.5f;
            const float amount = std::clamp(recipe.noise_color, 0.0f, 1.0f);
            c.a += amount * (chroma_a.sample(sx, sy) - in.a);
            c.b += amount * (chroma_b.sample(sx, sy) - in.b);
          }
          if (recipe.saturation != 0 || recipe.vibrance != 0) {
            const float chroma = std::sqrt(c.a * c.a + c.b * c.b);
            float scale = 1.0f + std::clamp(recipe.saturation, -1.0f, 1.0f);
            if (recipe.vibrance != 0) {
              scale *= 1.0f + std::clamp(recipe.vibrance, -1.0f, 1.0f) * (1.0f - smoothstep(0.0f, 0.25f, chroma));
            }
            scale = std::max(scale, 0.0f);
            c.a *= scale;
            c.b *= scale;
          }
          if (k.mixer_active) k.mix(c);
          if (k.grading_active) {
            const float l = clamp01(c.l);
            const float bal = std::clamp(recipe.grading_balance, -1.0f, 1.0f);
            const float ws = (1.0f - l) * (1.0f - l) * (1.0f - bal);
            const float wh = l * l * (1.0f + bal);
            c.a += k.shadow_a * ws + k.highlight_a * wh;
            c.b += k.shadow_b * ws + k.highlight_b * wh;
          }
          oklab_to_linear(c, r, g, b);
        }

        if (k.vignette_active) {
          const float dx = static_cast<float>(x) + 0.5f - static_cast<float>(out_w) * 0.5f;
          const float dy = static_cast<float>(y) + 0.5f - static_cast<float>(out_h) * 0.5f;
          const float falloff = smoothstep(0.35f, 1.0f, std::sqrt(dx * dx + dy * dy) / vignette_norm);
          const float v = std::clamp(recipe.vignette, -1.0f, 1.0f);
          const float factor = 1.0f + v * falloff * (v < 0 ? 0.85f : 0.6f);
          r *= factor;
          g *= factor;
          b *= factor;
        }

        if (k.grain_active) {
          const float n = value_noise(static_cast<float>(x) / grain_cell, static_cast<float>(y) / grain_cell);
          const float lum = clamp01(0.2126f * r + 0.7152f * g + 0.0722f * b);
          const float factor = 1.0f + std::clamp(recipe.grain, 0.0f, 1.0f) * 0.35f * n * (1.0f - 0.6f * lum);
          r *= factor;
          g *= factor;
          b *= factor;
        }

        std::uint8_t* q = out_row + x * 4;
        q[0] = tf.to_byte(r);
        q[1] = tf.to_byte(g);
        q[2] = tf.to_byte(b);
        q[3] = p[3];
      }
    }
  });
  return {};
}

}  // namespace daybreak
