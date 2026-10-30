#pragma once
#include "BufferedTransfer.h"
namespace knok {
// The adapter copies/updates this POD under the SAME status lock.
struct CapturePublication {
  int activeSlot=-1;uint32_t activeSamples=0;bool pendingStart=false;
  void requestStart(){pendingStart=true;}
  void publishStart(int slot,uint32_t samples){activeSlot=slot;activeSamples=samples;pendingStart=false;}
  void publishCapture(int slot,uint32_t samples){activeSlot=slot;activeSamples=samples;}
  bool recording()const{return pendingStart||activeSlot>=0;}
};
// -2: not ready/ambiguous, -1: absent, otherwise a committed slot index.
inline int latestDownloadSlot(EventSlot *slots,size_t count,bool recording,const char *currentIdentity,EventMetadata *selected=nullptr) {
  if(count>2)return -2;EventMetadata metadata[2]{};
  if(recording)return -2;
  for(size_t i=0;i<count;++i){auto s=slots[i].state.load(std::memory_order_acquire);
    if(s!=SlotState::Free&&s!=SlotState::Complete)return -2;
  }
  int latest=-1;
  for(size_t i=0;i<count;++i)if(slots[i].state.load(std::memory_order_acquire)==SlotState::Complete){
    EventReadLease lease(slots[i]);
    if(!lease||!slots[i].persisted.load(std::memory_order_acquire)||slots[i].state.load()!=SlotState::Complete)return -2;
    metadata[i]=slots[i].meta;
    if(latest<0){latest=int(i);continue;}
    bool sameBoot=std::strncmp(metadata[i].id,metadata[latest].id,29)==0;
    bool incomingCurrent=std::strncmp(metadata[i].id,currentIdentity,29)==0;
    bool latestCurrent=std::strncmp(metadata[latest].id,currentIdentity,29)==0;
    if(sameBoot){if(metadata[i].sequence>metadata[latest].sequence)latest=int(i);}
    else if(incomingCurrent)latest=int(i);
    else if(!latestCurrent)return -2; // Past boots have no trustworthy common wall clock.
  }
  if(latest>=0&&selected)*selected=metadata[latest];
  return latest;
}
} // namespace knok
