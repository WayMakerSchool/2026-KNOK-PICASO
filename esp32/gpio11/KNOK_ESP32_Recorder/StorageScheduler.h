#pragma once
#include "BufferedTransfer.h"
namespace knok {
enum class SaveOutcome { Saved, RetryWhenSpace, Damaged, Acknowledged };
class StorageScheduler {
  uint32_t lastAttempt_[2]{};
 public:
  template<class Save,class Erase>
  void service(EventSlot *slots,size_t count,uint32_t now,Save save,Erase erase) {
    count=std::min<size_t>(count,2);
    // Free acknowledged files first, so a waiting RAM event can use the space.
    for(size_t i=0;i<count;++i) {
      auto &slot=slots[i];auto state=slot.state.load(std::memory_order_acquire);
      if(slot.ackRequested.load(std::memory_order_acquire)&&state!=SlotState::Free&&state!=SlotState::Capturing&&state!=SlotState::Publishing&&state!=SlotState::Writing) {
        slot.state.store(SlotState::DeletePending,std::memory_order_release);state=SlotState::DeletePending;
      }
      if(state!=SlotState::DeletePending)continue;
      slot.published.store(false,std::memory_order_release);
      // Close admission in the same atomic word as the count: a late reader
      // cannot increment from zero after the deletion worker has observed zero.
      auto readers=slot.readers.fetch_or(ReaderGateClosed,std::memory_order_acq_rel);
      if((readers&ReaderCountMask)>0)continue;
      if(erase(i)) {
        slot.persisted.store(false);slot.ackRequested.store(false);slot.receiverWaited=false;slot.diskStarted=false;
        slot.state.store(SlotState::Free,std::memory_order_release);
      } else {slot.ackRequested.store(false);slot.state.store(SlotState::Quarantined,std::memory_order_release);}
    }
    for(size_t i=0;i<count;++i) {
      auto state=slots[i].state.load(std::memory_order_acquire);
      if(state!=SlotState::Ready&&state!=SlotState::WaitingForSpace)continue;
      if(state==SlotState::WaitingForSpace&&uint32_t(now-lastAttempt_[i])<1000)continue;
      if(!slots[i].state.compare_exchange_strong(state,SlotState::Writing,std::memory_order_acq_rel))continue;
      lastAttempt_[i]=now;auto outcome=save(i);
      slots[i].state.store(outcome==SaveOutcome::Acknowledged?SlotState::DeletePending:
        outcome==SaveOutcome::Saved?SlotState::Complete:
        outcome==SaveOutcome::RetryWhenSpace?SlotState::WaitingForSpace:SlotState::Quarantined,std::memory_order_release);
    }
  }
};
} // namespace knok
