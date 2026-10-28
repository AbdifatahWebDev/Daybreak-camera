// Area-average downscaling for thumbnails and previews.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <cmath>

#include "daybreak/engine.hpp"

namespace daybreak {

Image downscale(const Image& source, std::uint32_t max_dimension) {
  const std::uint32_t longer = std::max(source.width, source.height);
  if (max_dimension == 0 || longer <= max_dimension || source.rgba.empty()) return source;

  const double scale = static_cast<double>(max_dimension) / longer;
  Image out;
  out.width = std::max<std::uint32_t>(1, static_cast<std::uint32_t>(std::lround(source.width * scale)));
  out.height = std::max<std::uint32_t>(1, static_cast<std::uint32_t>(std::lround(source.height * scale)));
  out.rgba.resize(std::size_t{out.width} * out.height * 4);

  const double step_x = static_cast<double>(source.width) / out.width;
  const double step_y = static_cast<double>(source.height) / out.height;

  for (std::uint32_t oy = 0; oy < out.height; ++oy) {
    const auto y0 = static_cast<std::uint32_t>(oy * step_y);
    const auto y1 = std::min(source.height, std::max(y0 + 1, static_cast<std::uint32_t>((oy + 1) * step_y)));
    for (std::uint32_t ox = 0; ox < out.width; ++ox) {
      const auto x0 = static_cast<std::uint32_t>(ox * step_x);
      const auto x1 = std::min(source.width, std::max(x0 + 1, static_cast<std::uint32_t>((ox + 1) * step_x)));
      std::uint64_t sum[4] = {0, 0, 0, 0};
      for (std::uint32_t y = y0; y < y1; ++y) {
        const std::uint8_t* row = source.rgba.data() + (std::size_t{y} * source.width + x0) * 4;
        for (std::uint32_t x = x0; x < x1; ++x) {
          sum[0] += row[0];
          sum[1] += row[1];
          sum[2] += row[2];
          sum[3] += row[3];
          row += 4;
        }
      }
      const std::uint64_t count = std::uint64_t{y1 - y0} * (x1 - x0);
      std::uint8_t* dst = out.rgba.data() + (std::size_t{oy} * out.width + ox) * 4;
      for (int c = 0; c < 4; ++c) {
        dst[c] = static_cast<std::uint8_t>((sum[c] + count / 2) / count);
      }
    }
  }
  return out;
}

}  // namespace daybreak
