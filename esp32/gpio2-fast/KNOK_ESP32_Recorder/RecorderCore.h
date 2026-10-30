#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <cstdio>

namespace knok {
enum class SlotState : uint8_t { Free, Capturing, Ready, Writing, Complete, DeletePending, Quarantined, WaitingForSpace, Publishing };
struct EventMetadata {
  uint32_t magic = 0x4b4e4f32;
  uint32_t version = 2;
  char id[48]{};
  uint64_t startSample = 0, triggerSample = 0;
  uint64_t lastRawEdgeUs = 0, lastDigitalEventUs = 0;
  uint32_t sequence = 0, sampleCount = 0, sampleRate = 16000;
  uint32_t rawEdges = 0, digitalEvents = 0, crc = 0;
  float maxDb = 0, averageDb = 0;
  bool manual = false, truncatedAtLimit = false;
};
struct EventSlot {
  std::atomic<SlotState> state{SlotState::Free};
  std::atomic<bool> published{false},ackRequested{false},persisted{false};
  std::atomic<uint32_t> readers{0};
  bool receiverWaited=false,diskStarted=false;
  int16_t *pcm = nullptr;
  uint32_t capacity = 0;
  EventMetadata meta{};
};
struct Config {
  uint32_t sampleRate = 16000, preSamples = 16000, postSamples = 64000, maxSamples = 480000;
  float thresholdDb = 90, hysteresisDb = 5;
  uint32_t autoMaxSamples = 0; // 0 retains the original unrestricted automatic cap
};
class EdgeDebouncer {
  uint32_t refractory_, last_ = 0;
  bool seen_ = false;
 public:
  explicit EdgeDebouncer(uint32_t us) : refractory_(us) {}
  bool edge(uint32_t now) {
    if (seen_ && uint32_t(now-last_) < refractory_) return false;
    seen_=true; last_=now; return true;
  }
};
inline uint32_t readLe32(const uint8_t *p) {
  return uint32_t(p[0]) | uint32_t(p[1])<<8 | uint32_t(p[2])<<16 | uint32_t(p[3])<<24;
}
inline void putLe32(uint8_t *p, uint32_t v) { for(int i=0;i<4;++i) p[i]=uint8_t(v>>(i*8)); }
inline void writeWavHeader(uint8_t *h, uint32_t rate, uint32_t samples) {
  std::memset(h,0,44); std::memcpy(h,"RIFF",4); putLe32(h+4,36+samples*2);
  std::memcpy(h+8,"WAVEfmt ",8); putLe32(h+16,16); h[20]=1; h[22]=1;
  putLe32(h+24,rate); putLe32(h+28,rate*2); h[32]=2; h[34]=16;
  std::memcpy(h+36,"data",4); putLe32(h+40,samples*2);
}
inline uint32_t crc32Update(uint32_t crc, const uint8_t *data, size_t size) {
  while(size--) { crc^=*data++; for(int b=0;b<8;++b) crc=(crc>>1)^((crc&1)?0xedb88320:0); }
  return crc;
}
inline uint32_t crc32(const uint8_t *data, size_t size) { return crc32Update(0xffffffff,data,size)^0xffffffff; }
inline bool isCompleteWav(const EventMetadata &m, const uint8_t *h, uint32_t bytes) {
  uint8_t expected[44]; writeWavHeader(expected,m.sampleRate,m.sampleCount);
  return m.magic==0x4b4e4f32 && m.version==2 && m.sampleCount>0 &&
         bytes==44+m.sampleCount*2 && std::memcmp(expected,h,44)==0;
}

// Capture owns PCM until Ready; one publisher computes CRC before publication.
// Published metadata/PCM remain immutable until ACK cleanup and all read leases end.
class RecorderEngine {
  EventSlot *slots_; size_t slotCount_; int16_t *ring_; uint32_t ringSize_, ringUsed_=0, ringPos_=0;
  Config cfg_; uint64_t sequence_=0, stopSample_=0; int active_=-1;
  uint32_t events_=0, overflows_=0;
  bool enabled_=false, armed_=true, blocked_=false, stopping_=false;
  char identity_[30]{};
  double levelSum_=0; uint64_t levelWeight_=0;
  uint32_t rawStart_=0, digitalStart_=0;
  struct Mark { uint64_t sample=0; uint32_t raw=0, digital=0; };
  Mark history_[128]{}; uint32_t historyPos_=0, historyUsed_=0;

  void begin(bool manual, uint32_t duration, uint32_t raw, uint32_t digital) {
    for(size_t i=0;i<slotCount_;++i) {
      SlotState free=SlotState::Free;
      if(!slots_[i].state.compare_exchange_strong(free,SlotState::Capturing,std::memory_order_acq_rel)) continue;
      auto &s=slots_[i];
      if(!s.pcm || s.capacity<cfg_.maxSamples) { s.state.store(SlotState::Free,std::memory_order_release); continue; }
      s.published.store(false);s.ackRequested.store(false);s.persisted.store(false);s.receiverWaited=false;s.diskStarted=false;
      active_=int(i); s.meta=EventMetadata{}; s.meta.sequence=++events_;
      if(identity_[0]) std::snprintf(s.meta.id,sizeof(s.meta.id),"%s-%08x",identity_,s.meta.sequence);
      stopping_=false;
      s.meta.sampleRate=cfg_.sampleRate; s.meta.manual=manual; s.meta.triggerSample=sequence_;
      uint32_t pre=manual?0:std::min(cfg_.preSamples,ringUsed_);
      s.meta.startSample=sequence_-pre; s.meta.sampleCount=pre;
      for(uint32_t j=0;j<pre;++j) s.pcm[j]=ring_[(ringPos_+ringSize_-pre+j)%ringSize_];
      stopSample_=manual?sequence_+std::min(duration,cfg_.maxSamples):sequence_+cfg_.postSamples;
      rawStart_=raw; digitalStart_=digital;
      // Counters are aligned to the nearest capture block at/before pre-roll start.
      for(uint32_t j=0;j<historyUsed_;++j) {
        const auto &m=history_[(historyPos_+128-1-j)%128];
        if(m.sample<=s.meta.startSample) { rawStart_=m.raw; digitalStart_=m.digital; break; }
      }
      levelSum_=0; levelWeight_=0; return;
    }
    if(!blocked_) ++overflows_;
    blocked_=true;
  }
  void finish(uint32_t raw,uint32_t digital,uint64_t rawUs,uint64_t digitalUs) {
    auto &s=slots_[active_];
    s.meta.rawEdges=raw-rawStart_; s.meta.digitalEvents=digital-digitalStart_;
    s.meta.lastRawEdgeUs=s.meta.rawEdges?rawUs:0; s.meta.lastDigitalEventUs=s.meta.digitalEvents?digitalUs:0;
    s.meta.averageDb=levelWeight_?float(levelSum_/levelWeight_):0;
    s.state.store(SlotState::Ready,std::memory_order_release); active_=-1;
  }
 public:
  RecorderEngine(EventSlot *slots,size_t n,int16_t *ring,uint32_t ringSize,Config cfg={})
      : slots_(slots),slotCount_(n),ring_(ring),ringSize_(ringSize),cfg_(cfg) {}
  void setAutoEnabled(bool enabled) { enabled_=enabled; if(!enabled) {armed_=true;blocked_=false;} }
  void setIdentity(const char *deviceAndBoot) {std::snprintf(identity_,sizeof(identity_),"%s",deviceAndBoot);}
  bool setThreshold(float threshold,float hysteresis) {
    if(!std::isfinite(threshold)||threshold<30||threshold>120||!std::isfinite(hysteresis)||hysteresis<1||hysteresis>20) return false;
    cfg_.thresholdDb=threshold;cfg_.hysteresisDb=hysteresis;return true;
  }
  bool startManual(uint32_t samples) {
    if(active_>=0 || !samples) return false;
    begin(true,samples,0,0); return active_>=0;
  }
  void requestStop() { if(active_>=0) {stopSample_=sequence_;stopping_=true;} }
  void process(const int16_t *pcm,uint32_t count,float db,uint32_t raw=0,uint32_t digital=0,uint64_t rawUs=0,uint64_t digitalUs=0) {
    if(!count || !ringSize_) return;
    history_[historyPos_]={sequence_,raw,digital}; historyPos_=(historyPos_+1)%128;
    historyUsed_=std::min<uint32_t>(historyUsed_+1,128);
    const bool loud=std::isfinite(db)&&db>=cfg_.thresholdDb;
    if(db<=cfg_.thresholdDb-cfg_.hysteresisDb) {armed_=true;blocked_=false;}
    if(active_<0 && enabled_ && loud && armed_) {begin(false,0,raw,digital);armed_=false;}
    if(active_>=0) {
      auto &s=slots_[active_];
      if(!s.meta.manual && loud && !stopping_) stopSample_=std::max(stopSample_,sequence_+count-1+cfg_.postSamples);
      uint32_t eventCap=(s.meta.manual || !cfg_.autoMaxSamples) ? cfg_.maxSamples : std::min(cfg_.maxSamples,cfg_.autoMaxSamples);
      uint64_t cap=s.meta.startSample+eventCap;
      uint64_t end=std::min(stopSample_,cap);
      uint32_t append=end>sequence_?uint32_t(std::min<uint64_t>(count,end-sequence_)):0;
      if(append) {
        std::memcpy(s.pcm+s.meta.sampleCount,pcm,append*sizeof(int16_t));
        s.meta.sampleCount+=append;s.meta.maxDb=std::max(s.meta.maxDb,db);
        levelSum_+=double(db)*append;levelWeight_+=append;
      }
      if(sequence_+count>=end) {s.meta.truncatedAtLimit=(end==cap);finish(raw,digital,rawUs,digitalUs);}
    }
    for(uint32_t i=0;i<count;++i) {ring_[ringPos_]=pcm[i];ringPos_=(ringPos_+1)%ringSize_;}
    ringUsed_=std::min(ringUsed_+count,ringSize_);sequence_+=count;
  }
  uint64_t sampleSequence() const { return sequence_; }
  uint32_t startedEvents() const { return events_; }
  uint32_t queueOverflows() const { return overflows_; }
  int activeSlot() const { return active_; }
  bool autoEnabled() const { return enabled_; }
  float thresholdDb() const { return cfg_.thresholdDb; }
};
} // namespace knok
