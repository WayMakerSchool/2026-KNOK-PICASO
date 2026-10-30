#pragma once
#include <algorithm>
#include <cstdint>

namespace knok {
// Piezo disc on an ADC pin: converts millivolt samples into an amplitude above
// a slowly tracked resting level and counts separated impacts. Values are raw
// sensor millivolts, not calibrated floor acceleration.
struct PiezoSample { uint16_t amplitudeMv = 0; bool hit = false; };

inline uint32_t median3(uint32_t a, uint32_t b, uint32_t c) {
  return std::max(std::min(a, b), std::min(std::max(a, b), c));
}

// GPIO11 is on ADC2, which the ESP32-S3 shares with Wi-Fi through an arbiter.
// The 2026-10-28 bench run on that pin showed isolated out-of-range readings
// (one reported 4968 mV, above the 3.1 V input range) between real taps.
// The firmware takes the median of three conversions per millisecond and drops
// physically impossible values; the detector then uses the median of the last
// three milliseconds, so a single bad sample can neither count as an impact nor
// appear as a peak, while a tap that lasts several milliseconds still does.
constexpr uint32_t PIEZO_MAX_VALID_MV = 3300;

class PiezoDetector {
  uint32_t thresholdMv_, refractoryMs_, rearmQuietMs_;
  float baseline_ = 0;
  bool primed_ = false, armed_ = true, hitSeen_ = false, quiet_ = true;
  uint32_t hits_ = 0, lastHitMs_ = 0, quietSinceMs_ = 0;
  uint32_t recent_[3]{};
 public:
  static constexpr uint32_t MIN_THRESHOLD_MV = 20, MAX_THRESHOLD_MV = 3000;
  // A struck disc rings for a few hundred ms. A new impact is counted only after
  // the signal has stayed below half the threshold for rearmQuietMs, and never
  // sooner than refractoryMs after the previous one (2026-10-28 bench: one tap
  // per second produced 3-4 counts with an instantaneous re-arm).
  explicit PiezoDetector(uint32_t thresholdMv = 100, uint32_t refractoryMs = 150, uint32_t rearmQuietMs = 40)
      : thresholdMv_(std::min(std::max(thresholdMv, MIN_THRESHOLD_MV), MAX_THRESHOLD_MV)),
        refractoryMs_(refractoryMs), rearmQuietMs_(rearmQuietMs) {}
  bool setThreshold(uint32_t mv) {
    if (mv < MIN_THRESHOLD_MV || mv > MAX_THRESHOLD_MV) return false;
    thresholdMv_ = mv; return true;
  }
  uint32_t threshold() const { return thresholdMv_; }
  uint32_t hits() const { return hits_; }
  uint16_t baselineMv() const { return uint16_t(std::min(baseline_ < 0 ? 0.f : baseline_ + 0.5f, 65535.f)); }
  // mv: one validated millivolt value per millisecond.
  PiezoSample update(uint32_t mv, uint32_t nowMs) {
    if (!primed_) { baseline_ = float(mv); primed_ = true; }
    const float instant = float(mv) > baseline_ ? float(mv) - baseline_ : 0.f;
    recent_[2] = recent_[1]; recent_[1] = recent_[0]; recent_[0] = uint32_t(instant + 0.5f);
    const float amplitude = float(median3(recent_[0], recent_[1], recent_[2]));
    const float quiet = float(thresholdMv_) / 2;
    // Only quiet input moves the resting level, so impacts and glitches cannot raise it.
    if (instant < quiet) baseline_ += (float(mv) - baseline_) / 256.f;
    if (amplitude < quiet) {
      if (!quiet_) { quiet_ = true; quietSinceMs_ = nowMs; }
      if (uint32_t(nowMs - quietSinceMs_) >= rearmQuietMs_) armed_ = true;
    } else {
      quiet_ = false;
    }
    PiezoSample out;
    out.amplitudeMv = uint16_t(std::min(amplitude, 65535.f));
    if (amplitude >= float(thresholdMv_) && armed_ &&
        (!hitSeen_ || uint32_t(nowMs - lastHitMs_) >= refractoryMs_)) {
      out.hit = true; ++hits_; lastHitMs_ = nowMs; hitSeen_ = true; armed_ = false;
    }
    return out;
  }
};

// Aligns piezo blocks with recorder events using audio sample positions. One
// block is reported per capture iteration after RecorderEngine::process.
class PiezoEventTracker {
 public:
  struct Result { int slot = -1; uint32_t hits = 0; uint16_t peakMv = 0; };
 private:
  struct Entry { uint64_t sampleEnd = 0; uint32_t hitsBefore = 0; uint16_t peak = 0; };
  static constexpr uint32_t HISTORY = 64;  // 64 x 32 ms blocks covers the 1 s pre-roll.
  Entry history_[HISTORY]{};
  uint32_t pos_ = 0, used_ = 0, hitsStart_ = 0;
  int slot_ = -1;
  uint16_t peak_ = 0;
  void begin(int slot, uint64_t startSample, uint32_t cumulativeHits) {
    slot_ = slot; peak_ = 0; hitsStart_ = cumulativeHits;
    for (uint32_t j = 0; j < used_; ++j) {
      const Entry &e = history_[(pos_ + HISTORY - 1 - j) % HISTORY];
      if (e.sampleEnd <= startSample) break;
      peak_ = std::max(peak_, e.peak); hitsStart_ = e.hitsBefore;
    }
  }
 public:
  Result block(uint64_t sampleEnd, uint32_t hitsBefore, uint32_t hitsAfter, uint16_t blockPeak,
               int activeSlot, uint64_t activeStartSample) {
    history_[pos_] = {sampleEnd, hitsBefore, blockPeak};
    pos_ = (pos_ + 1) % HISTORY; used_ = std::min(used_ + 1, HISTORY);
    Result done;
    if (slot_ >= 0 && activeSlot != slot_) {
      peak_ = std::max(peak_, blockPeak);
      done = {slot_, hitsAfter - hitsStart_, peak_}; slot_ = -1;
    }
    if (activeSlot >= 0 && slot_ < 0) begin(activeSlot, activeStartSample, hitsAfter);
    else if (activeSlot >= 0) peak_ = std::max(peak_, blockPeak);
    return done;
  }
  bool active() const { return slot_ >= 0; }
  uint16_t activePeak() const { return slot_ >= 0 ? peak_ : 0; }
  uint32_t activeHits(uint32_t cumulativeHits) const { return slot_ >= 0 ? cumulativeHits - hitsStart_ : 0; }
};
}  // namespace knok
