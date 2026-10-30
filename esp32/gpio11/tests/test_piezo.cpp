#include <algorithm>
#include <cassert>
#include <cstdio>
#include "../KNOK_ESP32_Recorder/PiezoSensor.h"
using namespace knok;

static void quietBaselineAndSingleHit() {
  PiezoDetector d(100, 150, 40);
  uint32_t t = 0;
  for (; t < 500; ++t) assert(!d.update(12, t).hit);
  assert(d.baselineMv() == 12);
  // A tap lasting several milliseconds counts once, on its second sample.
  assert(!d.update(612, t++).hit);
  auto s = d.update(612, t++);
  assert(s.hit && s.amplitudeMv == 600 && d.hits() == 1);
  // Ringing above half threshold stays one impact.
  for (uint32_t end = t + 30; t < end; ++t) assert(!d.update(300, t).hit);
  assert(d.hits() == 1);
  // Impacts must not raise the resting level.
  assert(d.baselineMv() == 12);
}

static void isolatedGlitchesAreIgnored() {
  PiezoDetector d(100, 150, 40);
  uint32_t t = 0, peak = 0;
  for (; t < 300; ++t) d.update(3, t);
  // Bench-like glitches: one bad sample every 37 ms, including an out-of-range value.
  for (uint32_t end = t + 3000; t < end; ++t) {
    uint32_t mv = (t % 37 == 0) ? (t % 74 == 0 ? 4968 : 180) : 3;
    auto s = d.update(mv, t);
    peak = std::max<uint32_t>(peak, s.amplitudeMv);
    assert(!s.hit);
  }
  assert(d.hits() == 0 && peak < 50 && d.baselineMv() <= 4);
}

static void medianOfThree() {
  assert(median3(1, 2, 3) == 2 && median3(3, 1, 2) == 2 && median3(2, 3, 1) == 2);
  assert(median3(4968, 3, 4) == 4 && median3(5, 5, 900) == 5);
}

static void refractoryAndRearm() {
  PiezoDetector d(100, 150, 40);
  uint32_t t = 0;
  auto pulse = [&](uint32_t ms) { bool hit = false; for (uint32_t i = 0; i < ms; ++i) hit |= d.update(500, t++).hit; return hit; };
  for (; t < 100; ++t) d.update(0, t);
  assert(pulse(5));
  for (uint32_t end = t + 30; t < end; ++t) d.update(0, t);  // 30 ms quiet: not yet re-armed
  assert(!pulse(5));
  for (; t < 500; ++t) d.update(0, t);                        // long quiet: re-armed, refractory over
  assert(pulse(5));
  assert(d.hits() == 2);
}

// Bench case: a fingertip tap rings for ~300 ms; the 1 kHz samples alternate
// between the ringing envelope and clipped half-waves. One tap must count once.
static void ringingTapCountsOnce() {
  PiezoDetector d(100, 150, 40);
  uint32_t t = 0;
  for (; t < 200; ++t) d.update(3, t);
  for (int tap = 0; tap < 5; ++tap) {
    uint32_t start = t;
    for (; t < start + 300; ++t) {
      uint32_t age = t - start;
      uint32_t envelope = 850 * (300 - age) / 300;   // decays from 850 mV
      d.update((age % 3 == 0) ? 3 : 3 + envelope, t);  // every third sample near zero
    }
    for (; t < start + 1000; ++t) d.update(3, t);    // one tap per second
  }
  assert(d.hits() == 5);
}

static void fastHammerBlowsCountSeparately() {
  PiezoDetector d(100, 150, 40);
  uint32_t t = 0;
  for (; t < 200; ++t) d.update(0, t);
  for (int blow = 0; blow < 6; ++blow) {            // ~4 blows per second
    uint32_t start = t;
    for (; t < start + 120; ++t) d.update((t - start) % 3 == 2 ? 0 : 900 - 7 * (t - start), t);
    for (; t < start + 250; ++t) d.update(0, t);
  }
  assert(d.hits() == 6);
}

static void thresholdBounds() {
  PiezoDetector d;
  assert(!d.setThreshold(5) && !d.setThreshold(5000) && d.threshold() == 100);
  assert(d.setThreshold(40) && d.threshold() == 40);
  d.update(0, 0);
  d.update(45, 1);
  assert(d.update(45, 2).hit);
}

static void eventTrackerIncludesPreRollAndCurrentBlock() {
  PiezoEventTracker tr;
  uint32_t hits = 0; uint64_t seq = 0;
  // 40 idle blocks of 512 samples, one impact (peak 800 mV) in block 35.
  for (int b = 0; b < 40; ++b) {
    uint32_t before = hits; uint16_t peak = 3;
    if (b == 35) { ++hits; peak = 800; }
    seq += 512;
    auto r = tr.block(seq, before, hits, peak, -1, 0);
    assert(r.slot < 0);
  }
  // Auto event triggers now with a 16000-sample pre-roll covering block 35.
  uint64_t start = seq - 16000;
  seq += 512;
  auto r = tr.block(seq, hits, hits, 20, 1, start);
  assert(r.slot < 0 && tr.active() && tr.activePeak() == 800 && tr.activeHits(hits) == 1);
  // Two more impacts while recording, then the event finishes.
  for (int b = 0; b < 10; ++b) {
    uint32_t before = hits; uint16_t peak = 5;
    if (b == 2 || b == 7) { ++hits; peak = (b == 2 ? 1200 : 400); }
    seq += 512;
    r = tr.block(seq, before, hits, peak, 1, start);
    assert(r.slot < 0);
  }
  seq += 512;
  r = tr.block(seq, hits, hits, 9, -1, 0);
  assert(r.slot == 1 && r.hits == 3 && r.peakMv == 1200 && !tr.active());
}

static void eventTrackerExcludesImpactsBeforeEvent() {
  PiezoEventTracker tr;
  uint32_t hits = 1; uint64_t seq = 0;
  tr.block(seq += 512, 0, 1, 900, -1, 0);  // impact long before a manual start
  for (int b = 0; b < 60; ++b) tr.block(seq += 512, hits, hits, 2, -1, 0);
  uint64_t start = seq;                    // manual start: no pre-roll
  tr.block(seq += 512, hits, hits, 4, 0, start);
  assert(tr.activePeak() == 4 && tr.activeHits(hits) == 0);
  auto r = tr.block(seq += 512, hits, hits, 6, -1, 0);
  assert(r.slot == 0 && r.hits == 0 && r.peakMv == 6);
}

int main() {
  medianOfThree();
  quietBaselineAndSingleHit();
  isolatedGlitchesAreIgnored();
  refractoryAndRearm();
  ringingTapCountsOnce();
  fastHammerBlowsCountSeparately();
  thresholdBounds();
  eventTrackerIncludesPreRollAndCurrentBlock();
  eventTrackerExcludesImpactsBeforeEvent();
  std::puts("piezo tests: 9 passed");
  return 0;
}
