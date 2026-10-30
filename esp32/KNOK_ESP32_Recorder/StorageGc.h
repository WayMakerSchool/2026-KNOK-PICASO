#pragma once
#include <cstddef>
namespace knok {
enum class ReclaimResult { Ready, Pending, Failed };
// SPIFFS has a per-call GC budget. Wear levelling can move live pages before
// reclaiming deleted blocks, so one exhausted budget is not data corruption.
// Bound the work and yield; the caller retains the event in RAM if still pending.
template<class Attempt,class Yield>
ReclaimResult reclaimStorageWindow(Attempt attempt,Yield yield,std::size_t budget=8) {
  for(std::size_t i=0;i<budget;++i) {
    auto result=attempt();if(result!=ReclaimResult::Pending)return result;
    if(i+1<budget)yield();
  }
  return ReclaimResult::Pending;
}
} // namespace knok
