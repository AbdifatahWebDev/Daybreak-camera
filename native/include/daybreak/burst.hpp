// Daybreak burst merging: several frames of one scene become one cleaner photo.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <memory>

#include "daybreak/edit.hpp"
#include "daybreak/engine.hpp"

namespace daybreak {

// How the merged frames are turned into the final photo. The result blends
// three virtual exposures of the merged image (a short one that keeps bright
// areas, a normal one and a long one that opens up shadows), picking the
// best-exposed one for each region.
struct BurstFinish {
  // Linear brightness gain of the "normal" exposure; 1 keeps the frames' brightness.
  float gain = 1.0f;
  // Stops below the normal exposure for the short exposure (keeps highlights).
  float under = 1.0f;
  // Stops above the normal exposure for the long exposure (lifts shadows).
  float over = 1.0f;
};

// Statistics of the merge, for tests and the camera's progress display.
struct BurstStats {
  std::uint32_t frames = 0;
  // Average fraction of each extra frame that was merged (1 = everything matched).
  float merged_fraction = 0.0f;
};

// Merges a burst frame by frame, so only one frame needs to be in memory at a
// time. The first frame is the reference: the photo shows the scene as it was
// in that frame, and parts of later frames that don't match it (something
// moved) are left out. Later frames are aligned to it first, which removes
// hand shake. Up to kMaxFrames frames are merged.
class BurstMerger {
 public:
  static constexpr std::uint32_t kMaxFrames = 8;

  BurstMerger();
  ~BurstMerger();
  BurstMerger(const BurstMerger&) = delete;
  BurstMerger& operator=(const BurstMerger&) = delete;

  // Adds a frame; every frame must be the reference's size.
  Result<void> add(const RgbaView& frame);

  std::uint32_t frames() const;
  BurstStats stats() const;

  // Writes the finished photo; [out] must be the frames' size.
  Result<void> finish(const BurstFinish& options, const MutableRgbaView& out) const;

 private:
  struct State;
  std::unique_ptr<State> state_;
};

}  // namespace daybreak
