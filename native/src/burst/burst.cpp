// Daybreak burst merging.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Each added frame is aligned to the first (the reference) and merged into
// running sums, so memory stays at about 8 bytes per pixel plus one frame.
//
// Alignment: luma pyramids at 1/2, 1/4, 1/8 and 1/16 resolution. Every
// 16 x 16 tile of a level searches around the best of its coarser tile's
// vectors, and a last search at full resolution makes the vectors exact to the
// pixel. Vectors are interpolated between tile centers, so the warp is smooth.
//
// Merging: a pixel of a later frame counts fully when its (lightly blurred)
// brightness matches the reference within the noise, less as it differs, and
// not at all when something moved there. This keeps ghosts out.
//
// Finishing: three virtual exposures of the merged image are blended by how
// well exposed each region is (exposure fusion, on a Laplacian pyramid at low
// resolution). The blend gives a smooth gain map, upsampled with the
// full-resolution brightness as a guide so edges don't get halos.

#include "daybreak/burst.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <functional>
#include <thread>
#include <vector>

namespace daybreak {
namespace {

constexpr int kLevels = 4;     // 1/2, 1/4, 1/8, 1/16 resolution
constexpr int kTile = 16;      // tile size in each pyramid level's pixels
constexpr int kFullTile = 32;  // full-resolution tile, matching a 1/2-resolution tile
constexpr int kWeightSteps = 32;

void parallel_rows(int rows, const std::function<void(int, int)>& work) {
  const unsigned hardware = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
  const int threads = std::min<int>(static_cast<int>(hardware), std::max(1, rows / 16));
  if (threads <= 1) {
    work(0, rows);
    return;
  }
  std::vector<std::thread> pool;
  pool.reserve(static_cast<std::size_t>(threads - 1));
  const int chunk = (rows + threads - 1) / threads;
  for (int t = 1; t < threads; ++t) {
    const int begin = t * chunk;
    const int end = std::min(rows, begin + chunk);
    if (begin < end) pool.emplace_back(work, begin, end);
  }
  work(0, std::min(rows, chunk));
  for (auto& thread : pool) thread.join();
}

// ------------------------------------------------------------------ planes

struct Plane8 {
  int w = 0;
  int h = 0;
  std::vector<std::uint8_t> v;

  Plane8() = default;
  Plane8(int width, int height) : w(width), h(height), v(static_cast<std::size_t>(width) * height) {}

  std::uint8_t at(int x, int y) const {
    x = std::clamp(x, 0, w - 1);
    y = std::clamp(y, 0, h - 1);
    return v[static_cast<std::size_t>(y) * w + x];
  }

  float sample(float x, float y) const {
    x = std::clamp(x, 0.0f, static_cast<float>(w - 1));
    y = std::clamp(y, 0.0f, static_cast<float>(h - 1));
    const int x0 = static_cast<int>(x);
    const int y0 = static_cast<int>(y);
    const int x1 = std::min(x0 + 1, w - 1);
    const int y1 = std::min(y0 + 1, h - 1);
    const float fx = x - static_cast<float>(x0);
    const float fy = y - static_cast<float>(y0);
    const auto row0 = static_cast<std::size_t>(y0) * w;
    const auto row1 = static_cast<std::size_t>(y1) * w;
    const float top = v[row0 + x0] + (v[row0 + x1] - v[row0 + x0]) * fx;
    const float bottom = v[row1 + x0] + (v[row1 + x1] - v[row1 + x0]) * fx;
    return top + (bottom - top) * fy;
  }
};

Plane8 luma_of(const RgbaView& image) {
  Plane8 out(static_cast<int>(image.width), static_cast<int>(image.height));
  parallel_rows(out.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const std::uint8_t* p = image.pixels.data() + static_cast<std::size_t>(y) * image.stride;
      std::uint8_t* o = out.v.data() + static_cast<std::size_t>(y) * out.w;
      for (int x = 0; x < out.w; ++x, p += 4) {
        o[x] = static_cast<std::uint8_t>((p[0] * 54 + p[1] * 183 + p[2] * 19 + 128) >> 8);
      }
    }
  });
  return out;
}

Plane8 half_of(const Plane8& in) {
  Plane8 out(std::max(1, in.w / 2), std::max(1, in.h / 2));
  parallel_rows(out.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      for (int x = 0; x < out.w; ++x) {
        const int sum = in.at(2 * x, 2 * y) + in.at(2 * x + 1, 2 * y) + in.at(2 * x, 2 * y + 1) +
                        in.at(2 * x + 1, 2 * y + 1);
        out.v[static_cast<std::size_t>(y) * out.w + x] = static_cast<std::uint8_t>((sum + 2) >> 2);
      }
    }
  });
  return out;
}

// 3 x 3 box blur, edges clamped.
Plane8 blur3(const Plane8& in) {
  Plane8 tmp(in.w, in.h);
  Plane8 out(in.w, in.h);
  parallel_rows(in.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      for (int x = 0; x < in.w; ++x) {
        const int sum = in.at(x - 1, y) + in.at(x, y) + in.at(x + 1, y);
        tmp.v[static_cast<std::size_t>(y) * in.w + x] = static_cast<std::uint8_t>((sum + 1) / 3);
      }
    }
  });
  parallel_rows(in.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      for (int x = 0; x < in.w; ++x) {
        const int sum = tmp.at(x, y - 1) + tmp.at(x, y) + tmp.at(x, y + 1);
        out.v[static_cast<std::size_t>(y) * in.w + x] = static_cast<std::uint8_t>((sum + 1) / 3);
      }
    }
  });
  return out;
}

std::array<Plane8, kLevels> pyramid_of(const Plane8& luma) {
  std::array<Plane8, kLevels> levels;
  levels[0] = half_of(luma);
  for (int l = 1; l < kLevels; ++l) levels[l] = half_of(levels[l - 1]);
  return levels;
}

// ------------------------------------------------------------------ alignment

struct Motion {
  int tile = kTile;
  int tiles_x = 0;
  int tiles_y = 0;
  std::vector<int> dx;
  std::vector<int> dy;

  Motion() = default;
  Motion(int width, int height, int tile_size)
      : tile(tile_size),
        tiles_x((width + tile_size - 1) / tile_size),
        tiles_y((height + tile_size - 1) / tile_size),
        dx(static_cast<std::size_t>(tiles_x) * tiles_y, 0),
        dy(static_cast<std::size_t>(tiles_x) * tiles_y, 0) {}

  std::size_t index(int tx, int ty) const { return static_cast<std::size_t>(ty) * tiles_x + tx; }
};

// Sum of absolute differences of a tile against the other frame moved by (dx, dy).
std::uint32_t tile_sad(const Plane8& ref, const Plane8& alt, int x0, int y0, int size, int dx, int dy, int step) {
  const int x1 = std::min(ref.w, x0 + size);
  const int y1 = std::min(ref.h, y0 + size);
  std::uint32_t sum = 0;
  for (int y = y0; y < y1; y += step) {
    const std::uint8_t* r = ref.v.data() + static_cast<std::size_t>(y) * ref.w;
    const int ay = y + dy;
    const bool row_inside = ay >= 0 && ay < alt.h;
    const std::uint8_t* a = row_inside ? alt.v.data() + static_cast<std::size_t>(ay) * alt.w : nullptr;
    for (int x = x0; x < x1; x += step) {
      const int ax = x + dx;
      const int value = (a != nullptr && ax >= 0 && ax < alt.w) ? a[ax] : alt.at(ax, ay);
      sum += static_cast<std::uint32_t>(std::abs(static_cast<int>(r[x]) - value));
    }
  }
  return sum;
}

// Best vector for one tile, searching +-radius around each candidate.
void search_tile(const Plane8& ref, const Plane8& alt, int x0, int y0, int size, int step,
                 const std::vector<std::pair<int, int>>& candidates, int radius, int& best_dx, int& best_dy) {
  // Start from the best candidate, then search around it.
  std::uint64_t best = UINT64_MAX;
  int cx = 0;
  int cy = 0;
  for (const auto& [dx, dy] : candidates) {
    const std::uint64_t cost = tile_sad(ref, alt, x0, y0, size, dx, dy, step);
    if (cost < best) {
      best = cost;
      cx = dx;
      cy = dy;
    }
  }
  best_dx = cx;
  best_dy = cy;
  // A small pull toward the start keeps flat areas (no texture) from wandering.
  const auto penalty = [](int ddx, int ddy) { return static_cast<std::uint64_t>(std::abs(ddx) + std::abs(ddy)); };
  std::uint64_t best_score = best * 4;
  for (int ddy = -radius; ddy <= radius; ++ddy) {
    for (int ddx = -radius; ddx <= radius; ++ddx) {
      if (ddx == 0 && ddy == 0) continue;
      const std::uint64_t cost = tile_sad(ref, alt, x0, y0, size, cx + ddx, cy + ddy, step);
      const std::uint64_t score = cost * 4 + penalty(ddx, ddy);
      if (score < best_score) {
        best_score = score;
        best_dx = cx + ddx;
        best_dy = cy + ddy;
      }
    }
  }
}

// Vectors for every tile of one level, seeded from the coarser level (if any).
Motion align_level(const Plane8& ref, const Plane8& alt, const Motion* coarse, int radius, int tile, int scale) {
  Motion motion(ref.w, ref.h, tile);
  const int step = tile >= 32 ? 2 : 1;
  parallel_rows(motion.tiles_y, [&](int ty0, int ty1) {
    std::vector<std::pair<int, int>> candidates;
    for (int ty = ty0; ty < ty1; ++ty) {
      for (int tx = 0; tx < motion.tiles_x; ++tx) {
        candidates.clear();
        if (coarse == nullptr) {
          candidates.emplace_back(0, 0);
        } else {
          // The coarse tile under this tile's center, and its four neighbors.
          const int cx = std::clamp((tx * tile + tile / 2) / scale / coarse->tile, 0, coarse->tiles_x - 1);
          const int cy = std::clamp((ty * tile + tile / 2) / scale / coarse->tile, 0, coarse->tiles_y - 1);
          const int offsets[5][2] = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
          for (const auto& o : offsets) {
            const int nx = cx + o[0];
            const int ny = cy + o[1];
            if (nx < 0 || ny < 0 || nx >= coarse->tiles_x || ny >= coarse->tiles_y) continue;
            const auto i = coarse->index(nx, ny);
            candidates.emplace_back(coarse->dx[i] * scale, coarse->dy[i] * scale);
          }
          candidates.emplace_back(0, 0);
        }
        int dx = 0;
        int dy = 0;
        search_tile(ref, alt, tx * tile, ty * tile, tile, step, candidates, radius, dx, dy);
        const auto i = motion.index(tx, ty);
        motion.dx[i] = dx;
        motion.dy[i] = dy;
      }
    }
  });
  return motion;
}

// Full-resolution vectors (one per 32 x 32 tile) that move [alt] onto [ref].
Motion align(const std::array<Plane8, kLevels>& ref_pyramid, const Plane8& ref_full,
             const std::array<Plane8, kLevels>& alt_pyramid, const Plane8& alt_full) {
  Motion coarse = align_level(ref_pyramid[kLevels - 1], alt_pyramid[kLevels - 1], nullptr, 8, kTile, 1);
  for (int l = kLevels - 2; l >= 0; --l) {
    coarse = align_level(ref_pyramid[l], alt_pyramid[l], &coarse, 2, kTile, 2);
  }
  return align_level(ref_full, alt_full, &coarse, 1, kFullTile, 2);
}

// ------------------------------------------------------------------ transfer

float srgb_decode(float g) { return g <= 0.04045f ? g / 12.92f : std::pow((g + 0.055f) / 1.055f, 2.4f); }

float srgb_encode(float l) {
  if (l <= 0.0f) return 0.0f;
  if (l >= 1.0f) return 1.0f;
  return l <= 0.0031308f ? l * 12.92f : 1.055f * std::pow(l, 1.0f / 2.4f) - 0.055f;
}

struct Tables {
  // Linear value of an 8-bit code, in quarter steps (0..255).
  std::array<float, 1021> decode{};
  // 8-bit code of a linear value, in 1/65536 steps (0..1).
  std::vector<std::uint8_t> encode;

  Tables() : encode(65537) {
    for (std::size_t i = 0; i < decode.size(); ++i) decode[i] = srgb_decode(static_cast<float>(i) / 4.0f / 255.0f);
    for (std::size_t i = 0; i < encode.size(); ++i) {
      const float g = srgb_encode(static_cast<float>(i) / 65536.0f);
      encode[i] = static_cast<std::uint8_t>(std::clamp(std::lround(g * 255.0f), 0L, 255L));
    }
  }

  float to_linear(float code) const {
    const float p = std::clamp(code, 0.0f, 255.0f) * 4.0f;
    const int i = std::min(static_cast<int>(p), static_cast<int>(decode.size()) - 2);
    const float f = p - static_cast<float>(i);
    return decode[i] + (decode[i + 1] - decode[i]) * f;
  }

  std::uint8_t to_code(float linear) const {
    if (!(linear > 0.0f)) return 0;
    if (linear >= 1.0f) return 255;
    return encode[static_cast<std::size_t>(linear * 65536.0f)];
  }
};

const Tables& tables() {
  static const Tables t;
  return t;
}

// ------------------------------------------------------------------ fusion

struct PlaneF {
  int w = 0;
  int h = 0;
  std::vector<float> v;

  PlaneF() = default;
  PlaneF(int width, int height, float fill = 0.0f)
      : w(width), h(height), v(static_cast<std::size_t>(width) * height, fill) {}

  float at(int x, int y) const {
    x = std::clamp(x, 0, w - 1);
    y = std::clamp(y, 0, h - 1);
    return v[static_cast<std::size_t>(y) * w + x];
  }
  float& ref(int x, int y) { return v[static_cast<std::size_t>(y) * w + x]; }

  float sample(float x, float y) const {
    x = std::clamp(x, 0.0f, static_cast<float>(w - 1));
    y = std::clamp(y, 0.0f, static_cast<float>(h - 1));
    const int x0 = static_cast<int>(x);
    const int y0 = static_cast<int>(y);
    const int x1 = std::min(x0 + 1, w - 1);
    const int y1 = std::min(y0 + 1, h - 1);
    const float fx = x - static_cast<float>(x0);
    const float fy = y - static_cast<float>(y0);
    const float top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx;
    const float bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx;
    return top + (bottom - top) * fy;
  }
};

// 5-tap binomial blur, then every second pixel.
PlaneF down(const PlaneF& in) {
  static constexpr float k[5] = {1.0f / 16, 4.0f / 16, 6.0f / 16, 4.0f / 16, 1.0f / 16};
  PlaneF tmp(in.w, in.h);
  for (int y = 0; y < in.h; ++y) {
    for (int x = 0; x < in.w; ++x) {
      float sum = 0.0f;
      for (int i = -2; i <= 2; ++i) sum += k[i + 2] * in.at(x + i, y);
      tmp.ref(x, y) = sum;
    }
  }
  PlaneF out(std::max(1, (in.w + 1) / 2), std::max(1, (in.h + 1) / 2));
  for (int y = 0; y < out.h; ++y) {
    for (int x = 0; x < out.w; ++x) {
      float sum = 0.0f;
      for (int i = -2; i <= 2; ++i) sum += k[i + 2] * tmp.at(2 * x, 2 * y + i);
      out.ref(x, y) = sum;
    }
  }
  return out;
}

// Back up to [w] x [h], bilinear.
PlaneF up(const PlaneF& in, int w, int h) {
  PlaneF out(w, h);
  const float sx = static_cast<float>(in.w) / static_cast<float>(w);
  const float sy = static_cast<float>(in.h) / static_cast<float>(h);
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      out.ref(x, y) = in.sample((static_cast<float>(x) + 0.5f) * sx - 0.5f, (static_cast<float>(y) + 0.5f) * sy - 0.5f);
    }
  }
  return out;
}

// Blends [images] by [weights] (which sum to 1 per pixel) on Laplacian pyramids.
PlaneF fuse(const std::vector<PlaneF>& images, const std::vector<PlaneF>& weights) {
  const int w = images[0].w;
  const int h = images[0].h;
  int levels = 1;
  for (int size = std::min(w, h); size >= 16 && levels < 8; size /= 2) ++levels;

  std::vector<PlaneF> result(static_cast<std::size_t>(levels));
  for (std::size_t k = 0; k < images.size(); ++k) {
    std::vector<PlaneF> gauss{images[k]};
    std::vector<PlaneF> weight{weights[k]};
    for (int l = 1; l < levels; ++l) {
      gauss.push_back(down(gauss.back()));
      weight.push_back(down(weight.back()));
    }
    for (int l = 0; l < levels; ++l) {
      PlaneF band = gauss[static_cast<std::size_t>(l)];
      if (l + 1 < levels) {
        const PlaneF next = up(gauss[static_cast<std::size_t>(l) + 1], band.w, band.h);
        for (std::size_t i = 0; i < band.v.size(); ++i) band.v[i] -= next.v[i];
      }
      auto& out = result[static_cast<std::size_t>(l)];
      if (out.v.empty()) out = PlaneF(band.w, band.h);
      const auto& wl = weight[static_cast<std::size_t>(l)];
      for (std::size_t i = 0; i < band.v.size(); ++i) out.v[i] += band.v[i] * wl.v[i];
    }
  }
  PlaneF image = result.back();
  for (int l = levels - 2; l >= 0; --l) {
    PlaneF bigger = up(image, result[static_cast<std::size_t>(l)].w, result[static_cast<std::size_t>(l)].h);
    for (std::size_t i = 0; i < bigger.v.size(); ++i) bigger.v[i] += result[static_cast<std::size_t>(l)].v[i];
    image = std::move(bigger);
  }
  return image;
}

float luma_linear(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }

// log2 to about 0.01, from the float's bits; plenty for comparing brightness.
float fast_log2(float x) {
  const auto bits = std::bit_cast<std::uint32_t>(x);
  const float exponent = static_cast<float>(static_cast<int>((bits >> 23) & 0xFFu) - 127);
  const float m = std::bit_cast<float>((bits & 0x007FFFFFu) | 0x3F800000u);
  return exponent + (-0.34484843f * m + 2.02466578f) * m - 0.67487759f;
}

// A smooth falloff close to exp(-t) for t >= 0, without calling exp.
float falloff(float t) { return 1.0f / (1.0f + t + 0.5f * t * t); }

// Highlights above 0.85 roll off smoothly instead of clipping.
float roll_off(float v) {
  constexpr float knee = 0.85f;
  if (v <= knee) return v;
  constexpr float room = 1.0f - knee;
  return knee + room * (1.0f - std::exp(-(v - knee) / room));
}

}  // namespace

// ------------------------------------------------------------------ merger

struct BurstMerger::State {
  int w = 0;
  int h = 0;
  std::uint32_t frames = 0;
  // Per pixel: R, G, B sums and the weight sum, all in 1/32 steps.
  std::vector<std::uint16_t> sums;
  Plane8 ref_luma;
  Plane8 ref_blur;
  std::array<Plane8, kLevels> ref_pyramid;
  float threshold = 4.0f;
  double merged = 0.0;
};

BurstMerger::BurstMerger() : state_(std::make_unique<State>()) {}
BurstMerger::~BurstMerger() = default;

std::uint32_t BurstMerger::frames() const { return state_->frames; }

BurstStats BurstMerger::stats() const {
  BurstStats stats;
  stats.frames = state_->frames;
  stats.merged_fraction =
      state_->frames > 1 ? static_cast<float>(state_->merged / static_cast<double>(state_->frames - 1)) : 1.0f;
  return stats;
}

Result<void> BurstMerger::add(const RgbaView& frame) {
  auto& s = *state_;
  if (frame.width == 0 || frame.height == 0 || frame.stride < frame.width * 4 ||
      frame.pixels.size() < static_cast<std::size_t>(frame.stride) * (frame.height - 1) + frame.width * 4) {
    return std::unexpected(Error{"The frame is empty or too small for its size"});
  }
  if (s.frames >= kMaxFrames) return std::unexpected(Error{"The burst already has the most frames it can merge"});
  const int w = static_cast<int>(frame.width);
  const int h = static_cast<int>(frame.height);

  if (s.frames == 0) {
    s.w = w;
    s.h = h;
    s.sums.assign(static_cast<std::size_t>(w) * h * 4, 0);
    s.ref_luma = luma_of(frame);
    s.ref_blur = blur3(s.ref_luma);
    s.ref_pyramid = pyramid_of(s.ref_luma);
    // Noise level from the reference: the typical difference between a pixel
    // and its neighborhood, measured on a sparse grid.
    std::vector<std::uint8_t> diffs;
    diffs.reserve(static_cast<std::size_t>(w / 4 + 1) * (h / 4 + 1));
    for (int y = 1; y < h - 1; y += 4) {
      for (int x = 1; x < w - 1; x += 4) {
        diffs.push_back(static_cast<std::uint8_t>(
            std::abs(static_cast<int>(s.ref_luma.at(x, y)) - static_cast<int>(s.ref_blur.at(x, y)))));
      }
    }
    float sigma = 2.0f;
    if (!diffs.empty()) {
      auto middle = diffs.begin() + static_cast<std::ptrdiff_t>(diffs.size() / 2);
      std::nth_element(diffs.begin(), middle, diffs.end());
      sigma = 1.4826f * static_cast<float>(*middle);
    }
    s.threshold = std::max(4.0f, 1.5f * sigma);
    parallel_rows(h, [&](int y0, int y1) {
      for (int y = y0; y < y1; ++y) {
        const std::uint8_t* p = frame.pixels.data() + static_cast<std::size_t>(y) * frame.stride;
        std::uint16_t* o = s.sums.data() + static_cast<std::size_t>(y) * w * 4;
        for (int x = 0; x < w; ++x, p += 4, o += 4) {
          o[0] = static_cast<std::uint16_t>(p[0] * kWeightSteps);
          o[1] = static_cast<std::uint16_t>(p[1] * kWeightSteps);
          o[2] = static_cast<std::uint16_t>(p[2] * kWeightSteps);
          o[3] = kWeightSteps;
        }
      }
    });
    s.frames = 1;
    return {};
  }

  if (w != s.w || h != s.h) return std::unexpected(Error{"Every frame of a burst must be the same size"});

  const Plane8 alt_luma = luma_of(frame);
  const Plane8 alt_blur = blur3(alt_luma);
  const auto alt_pyramid = pyramid_of(alt_luma);
  const Motion motion = align(s.ref_pyramid, s.ref_luma, alt_pyramid, alt_luma);

  // Per column and row: the two nearest tile centers and the blend between them.
  struct Span {
    int a = 0;
    int b = 0;
    float t = 0.0f;
  };
  const auto spans = [](int size, int tiles) {
    std::vector<Span> out(static_cast<std::size_t>(size));
    for (int i = 0; i < size; ++i) {
      const float f = (static_cast<float>(i) - kFullTile / 2.0f) / kFullTile;
      const int a = std::clamp(static_cast<int>(std::floor(f)), 0, tiles - 1);
      const int b = std::min(a + 1, tiles - 1);
      out[static_cast<std::size_t>(i)] = {a, b, std::clamp(f - static_cast<float>(a), 0.0f, 1.0f)};
    }
    return out;
  };
  const auto columns = spans(w, motion.tiles_x);
  const auto rows = spans(h, motion.tiles_y);
  const float threshold = s.threshold;

  std::vector<double> merged_rows(static_cast<std::size_t>(h), 0.0);
  parallel_rows(h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const Span& row = rows[static_cast<std::size_t>(y)];
      std::uint16_t* o = s.sums.data() + static_cast<std::size_t>(y) * w * 4;
      const std::uint8_t* ref_blur = s.ref_blur.v.data() + static_cast<std::size_t>(y) * w;
      double merged_sum = 0.0;
      for (int x = 0; x < w; ++x, o += 4) {
        const Span& column = columns[static_cast<std::size_t>(x)];
        const auto v00 = motion.index(column.a, row.a);
        const auto v10 = motion.index(column.b, row.a);
        const auto v01 = motion.index(column.a, row.b);
        const auto v11 = motion.index(column.b, row.b);
        const float top_x = static_cast<float>(motion.dx[v00]) +
                            static_cast<float>(motion.dx[v10] - motion.dx[v00]) * column.t;
        const float bottom_x = static_cast<float>(motion.dx[v01]) +
                               static_cast<float>(motion.dx[v11] - motion.dx[v01]) * column.t;
        const float top_y = static_cast<float>(motion.dy[v00]) +
                            static_cast<float>(motion.dy[v10] - motion.dy[v00]) * column.t;
        const float bottom_y = static_cast<float>(motion.dy[v01]) +
                               static_cast<float>(motion.dy[v11] - motion.dy[v01]) * column.t;
        const float sx = static_cast<float>(x) + top_x + (bottom_x - top_x) * row.t;
        const float sy = static_cast<float>(y) + top_y + (bottom_y - top_y) * row.t;
        if (sx < 0.0f || sy < 0.0f || sx > static_cast<float>(w - 1) || sy > static_cast<float>(h - 1)) continue;

        // How well this frame matches the reference here.
        const float diff = std::fabs(static_cast<float>(ref_blur[x]) - alt_blur.sample(sx, sy));
        const float weight = std::clamp(1.0f - (diff - threshold) / (2.0f * threshold), 0.0f, 1.0f);
        const int q = static_cast<int>(std::lround(weight * kWeightSteps));
        merged_sum += weight;
        if (q == 0) continue;

        const int x0 = static_cast<int>(sx);
        const int y0i = static_cast<int>(sy);
        const int x1 = std::min(x0 + 1, w - 1);
        const int y1i = std::min(y0i + 1, h - 1);
        const float fx = sx - static_cast<float>(x0);
        const float fy = sy - static_cast<float>(y0i);
        const std::uint8_t* p00 = frame.pixels.data() + static_cast<std::size_t>(y0i) * frame.stride + x0 * 4;
        const std::uint8_t* p10 = frame.pixels.data() + static_cast<std::size_t>(y0i) * frame.stride + x1 * 4;
        const std::uint8_t* p01 = frame.pixels.data() + static_cast<std::size_t>(y1i) * frame.stride + x0 * 4;
        const std::uint8_t* p11 = frame.pixels.data() + static_cast<std::size_t>(y1i) * frame.stride + x1 * 4;
        for (int c = 0; c < 3; ++c) {
          const float top = p00[c] + (p10[c] - p00[c]) * fx;
          const float bottom = p01[c] + (p11[c] - p01[c]) * fx;
          const float value = top + (bottom - top) * fy;
          o[c] = static_cast<std::uint16_t>(o[c] + std::lround(value * static_cast<float>(q)));
        }
        o[3] = static_cast<std::uint16_t>(o[3] + q);
      }
      merged_rows[static_cast<std::size_t>(y)] = merged_sum;
    }
  });
  double total = 0.0;
  for (double r : merged_rows) total += r;
  s.merged += total / (static_cast<double>(w) * h);
  ++s.frames;
  return {};
}

Result<void> BurstMerger::finish(const BurstFinish& options, const MutableRgbaView& out) const {
  const auto& s = *state_;
  if (s.frames == 0) return std::unexpected(Error{"The burst has no frames"});
  if (out.width != static_cast<std::uint32_t>(s.w) || out.height != static_cast<std::uint32_t>(s.h) ||
      out.stride < out.width * 4 ||
      out.pixels.size() < static_cast<std::size_t>(out.stride) * (out.height - 1) + out.width * 4) {
    return std::unexpected(Error{"The output must be the size of the frames"});
  }
  const auto& t = tables();
  const int w = s.w;
  const int h = s.h;

  // Linear brightness of the merged image at low resolution (long side about 1000).
  const int factor = std::max(1, static_cast<int>(std::lround(std::max(w, h) / 1000.0)));
  const int lw = std::max(1, w / factor);
  const int lh = std::max(1, h / factor);
  PlaneF low(lw, lh);
  parallel_rows(lh, [&](int y0, int y1) {
    for (int ly = y0; ly < y1; ++ly) {
      for (int lx = 0; lx < lw; ++lx) {
        float sum = 0.0f;
        int count = 0;
        for (int y = ly * factor; y < std::min(h, (ly + 1) * factor); ++y) {
          const std::uint16_t* p = s.sums.data() + (static_cast<std::size_t>(y) * w + lx * factor) * 4;
          for (int x = lx * factor; x < std::min(w, (lx + 1) * factor); ++x, p += 4) {
            const float inv = 1.0f / static_cast<float>(p[3]);
            sum += luma_linear(t.to_linear(p[0] * inv), t.to_linear(p[1] * inv), t.to_linear(p[2] * inv));
            ++count;
          }
        }
        low.ref(lx, ly) = count > 0 ? sum / static_cast<float>(count) : 0.0f;
      }
    }
  });

  // Three virtual exposures, weighted by how close each is to mid-tone.
  const float gain = std::clamp(options.gain, 1.0f / 64.0f, 64.0f);
  const std::array<float, 3> exposures = {gain * std::exp2(-std::clamp(options.under, 0.0f, 6.0f)), gain,
                                          gain * std::exp2(std::clamp(options.over, 0.0f, 6.0f))};
  std::vector<PlaneF> images;
  std::vector<PlaneF> weights;
  for (float m : exposures) {
    PlaneF image(lw, lh);
    PlaneF weight(lw, lh);
    for (std::size_t i = 0; i < low.v.size(); ++i) {
      const float e = srgb_encode(low.v[i] * m);
      image.v[i] = e;
      const float d = e - 0.5f;
      weight.v[i] = std::exp(-d * d / (2.0f * 0.2f * 0.2f)) + 1e-6f;
    }
    images.push_back(std::move(image));
    weights.push_back(std::move(weight));
  }
  for (std::size_t i = 0; i < low.v.size(); ++i) {
    const float sum = weights[0].v[i] + weights[1].v[i] + weights[2].v[i];
    for (auto& weight : weights) weight.v[i] /= sum;
  }
  const PlaneF fused = fuse(images, weights);

  // Gain per low-resolution pixel, and its log brightness for edge-aware upsampling.
  PlaneF gains(lw, lh);
  PlaneF log_low(lw, lh);
  for (std::size_t i = 0; i < low.v.size(); ++i) {
    const float target = srgb_decode(std::clamp(fused.v[i], 0.0f, 1.0f));
    const float y = std::max(low.v[i], 1e-5f);
    gains.v[i] = std::clamp(target / y, exposures[0], exposures[2]);
    log_low.v[i] = fast_log2(y);
  }

  parallel_rows(h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const float ly = (static_cast<float>(y) + 0.5f) / static_cast<float>(factor) - 0.5f;
      const int ly0 = std::clamp(static_cast<int>(std::floor(ly)), 0, lh - 1);
      const int ly1 = std::min(ly0 + 1, lh - 1);
      const float fy = std::clamp(ly - static_cast<float>(ly0), 0.0f, 1.0f);
      const std::uint16_t* p = s.sums.data() + static_cast<std::size_t>(y) * w * 4;
      std::uint8_t* o = out.pixels.data() + static_cast<std::size_t>(y) * out.stride;
      for (int x = 0; x < w; ++x, p += 4, o += 4) {
        const float inv = 1.0f / static_cast<float>(p[3]);
        const float r = t.to_linear(p[0] * inv);
        const float g = t.to_linear(p[1] * inv);
        const float b = t.to_linear(p[2] * inv);
        const float log_y = fast_log2(std::max(luma_linear(r, g, b), 1e-5f));

        const float lx = (static_cast<float>(x) + 0.5f) / static_cast<float>(factor) - 0.5f;
        const int lx0 = std::clamp(static_cast<int>(std::floor(lx)), 0, lw - 1);
        const int lx1 = std::min(lx0 + 1, lw - 1);
        const float fx = std::clamp(lx - static_cast<float>(lx0), 0.0f, 1.0f);
        // Joint bilateral upsampling: neighbors of similar brightness count more.
        const int xs[2] = {lx0, lx1};
        const int ys[2] = {ly0, ly1};
        const float wx[2] = {1.0f - fx, fx};
        const float wy[2] = {1.0f - fy, fy};
        float sum = 0.0f;
        float total = 0.0f;
        float plain = 0.0f;
        for (int j = 0; j < 2; ++j) {
          for (int i = 0; i < 2; ++i) {
            const auto index = static_cast<std::size_t>(ys[j]) * lw + xs[i];
            const float spatial = wx[i] * wy[j];
            const float d = log_y - log_low.v[index];
            const float range = falloff(d * d / (2.0f * 0.6f * 0.6f));
            sum += gains.v[index] * spatial * range;
            total += spatial * range;
            plain += gains.v[index] * spatial;
          }
        }
        const float k = total > 1e-4f ? sum / total : plain;
        o[0] = t.to_code(roll_off(r * k));
        o[1] = t.to_code(roll_off(g * k));
        o[2] = t.to_code(roll_off(b * k));
        o[3] = 255;
      }
    }
  });
  return {};
}

}  // namespace daybreak
