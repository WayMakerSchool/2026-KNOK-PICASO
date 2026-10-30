#pragma once
#include "RecorderCore.h"
namespace knok {
constexpr uint32_t ReaderGateClosed=0x80000000u;
constexpr uint32_t ReaderCountMask=0x7fffffffu;
// A slot cannot be reused while any response still references its metadata/PCM.
class EventReadLease {
  EventSlot *slot_=nullptr;
 public:
  explicit EventReadLease(EventSlot &slot) {
    if(!slot.published.load(std::memory_order_acquire)||slot.ackRequested.load(std::memory_order_acquire))return;
    auto count=slot.readers.load(std::memory_order_acquire);
    do {
      if((count&ReaderGateClosed)||(count&ReaderCountMask)==ReaderCountMask)return;
    } while(!slot.readers.compare_exchange_weak(count,count+1,std::memory_order_acq_rel));
    if(slot.published.load(std::memory_order_acquire)&&!slot.ackRequested.load(std::memory_order_acquire))slot_=&slot;
    else slot.readers.fetch_sub(1,std::memory_order_release);
  }
  EventReadLease(const EventReadLease&)=delete;
  EventReadLease& operator=(const EventReadLease&)=delete;
  ~EventReadLease(){if(slot_)slot_->readers.fetch_sub(1,std::memory_order_release);}
  explicit operator bool()const{return slot_!=nullptr;}
};
// Only a thread holding Publishing or Writing may call this preparation function.
template<class Yield> bool prepareBufferedEvent(EventSlot &slot,Yield yield) {
  if(slot.published.load(std::memory_order_acquire))return true;
  if(!slot.pcm||!slot.meta.sampleCount||slot.meta.sampleCount>slot.capacity)return false;
  uint8_t header[44];writeWavHeader(header,slot.meta.sampleRate,slot.meta.sampleCount);
  uint32_t crc=crc32Update(0xffffffff,header,44);auto pcm=reinterpret_cast<const uint8_t*>(slot.pcm);
  uint32_t bytes=slot.meta.sampleCount*2;
  for(uint32_t at=0;at<bytes;){auto n=std::min<uint32_t>(16384,bytes-at);crc=crc32Update(crc,pcm+at,n);at+=n;yield();}
  slot.meta.crc=crc^0xffffffff;
  slot.readers.store(0,std::memory_order_release);
  slot.published.store(true,std::memory_order_release);return true;
}
template<class Yield> bool publishBufferedEvent(EventSlot &slot,Yield yield) {
  if(slot.published.load(std::memory_order_acquire))return false;
  auto expected=SlotState::Ready;
  if(!slot.state.compare_exchange_strong(expected,SlotState::Publishing,std::memory_order_acq_rel))return false;
  bool ok=prepareBufferedEvent(slot,yield);
  slot.state.store(ok?SlotState::Ready:SlotState::Quarantined,std::memory_order_release);
  return ok;
}
} // namespace knok
