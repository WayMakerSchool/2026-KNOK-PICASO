#pragma once
#include "RecorderCore.h"
namespace knok {
inline bool validEventId(const char *id) {
  for(int i=0;i<38;++i) {
    char c=id[i];
    if(i==12||i==29) {if(c!='-')return false;}
    else if(!((c>='0'&&c<='9')||(c>='a'&&c<='f'))) return false;
  }
  return id[38]=='\0';
}
inline bool recoverableMetadata(const EventMetadata &m,size_t metadataBytes,const uint8_t *header,uint32_t bytes) {
  return metadataBytes==sizeof(EventMetadata) && validEventId(m.id) && m.sampleRate==16000 &&
         m.sampleCount<=480000 && isCompleteWav(m,header,bytes);
}
} // namespace knok
