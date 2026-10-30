#include <Arduino.h>
#include <Adafruit_NeoPixel.h>
#include <ESPmDNS.h>
#include <SPIFFS.h>
#include <WebServer.h>
#include <WiFi.h>
#include <driver/i2s.h>
#include <esp_heap_caps.h>
#include <esp_timer.h>
#include <esp_spiffs.h>
#include "RecorderCore.h"
#include "Persistence.h"
#include "StorageScheduler.h"
#include "StorageGc.h"
#include "BufferedTransfer.h"
#include "RecorderApiPolicy.h"
#include "RecorderSettings.h"
#include "PiezoSensor.h"
#include "secrets.h"

// Coach hardware contract retained: INMP441 left, digital GPIO8, twelve GPIO7 LEDs.
// Added 2026-10-28: piezo disc on GPIO2, measured at 1 kHz on its own task.
// Capture owns I2S and the engine. Storage owns all filesystem mutations.
using namespace knok;
constexpr uint32_t SAMPLE_RATE=16000, BLOCK_SAMPLES=512, MAX_RECORDING_MS=15000;
constexpr uint32_t MAX_SAMPLES=SAMPLE_RATE*MAX_RECORDING_MS/1000;
constexpr const char *FIRMWARE_VERSION="knok-v2-demo-20261029-piezo-gpio2-fast1";
constexpr const char *MDNS_HOSTNAME="knok-esp32-gpio2";
WebServer server(80);
Adafruit_NeoPixel pixels(LED_COUNT,LED_PIN,NEO_GRB+NEO_KHZ800);
EventSlot slots[2]; RecorderEngine *engine=nullptr;
QueueHandle_t controls=nullptr, i2sEvents=nullptr;
portMUX_TYPE statusMux=portMUX_INITIALIZER_UNLOCKED;
portMUX_TYPE edgeMux=portMUX_INITIALIZER_UNLOCKED;
char deviceId[13]{},bootId[17]{},bootIdentity[30]{};
bool capacityWarning[2]{};
bool storageReady=false,memoryReady=false,i2sReady=false,mdnsReady=false;
uint32_t legacyFileSize=0,lastWifiAttempt=0;
std::atomic<bool> downloadActive{false};
volatile uint32_t rawEdges=0,digitalEvents=0;
volatile uint64_t lastRawUs=0,lastDigitalUs=0;
volatile bool edgeSeen=false;
portMUX_TYPE piezoMux=portMUX_INITIALIZER_UNLOCKED;
struct PiezoShared {
  uint32_t hits=0,samples=0,thresholdMv=PIEZO_HIT_THRESHOLD_MV;
  uint16_t blockPeakMv=0,lastMv=0,recentPeakMv=0,baselineMv=0;
  bool ready=false;
} piezoShared;
// Per-slot piezo summary, matched to the event ID; RAM only, so events
// recovered from flash after a reboot report piezoAvailable=false.
struct PiezoEventStats { char id[48]{}; uint32_t hits=0; uint16_t peakMv=0; bool valid=false; } piezoEventStats[2];
bool piezoTaskRunning=false;
struct StatusSnapshot : CapturePublication {
  uint64_t sampleSequence=0;
  uint32_t blockCount=0,lastLevelMs=0,overflowCount=0,queueOverflows=0,startedEvents=0;
  uint32_t storageErrors=0,recoveryErrors=0,rawEdges=0,digitalEvents=0,stableChanges=0;
  uint32_t ramAcknowledgedEvents=0,flashCommittedEvents=0;
  uint32_t lastEventSamples=0,lastEventBytes=0,recordingRawEdges=0;
  uint32_t recordingPiezoHits=0;uint16_t recordingPiezoPeakMv=0;
  int tiltState=1,lastPeak=0;
  bool autoEnabled=false;
  float levelDb=0,recentPeakDb=0,recordMaxDb=0,recordAverageDb=0,thresholdDb=90;
  char audioError[96]{},storageError[96]{};
} currentStatus;
struct Control { uint8_t kind;bool enabled;float threshold;uint32_t duration; };
constexpr uint8_t AUTO=1,START=2,STOP=3;

StatusSnapshot snapshot() {
  StatusSnapshot copy;
  portENTER_CRITICAL(&statusMux);copy=currentStatus;portEXIT_CRITICAL(&statusMux);
  return copy;
}
void storageError(const char *message,bool recovery=false) {
  portENTER_CRITICAL(&statusMux);
  ++currentStatus.storageErrors;if(recovery)++currentStatus.recoveryErrors;
  snprintf(currentStatus.storageError,sizeof(currentStatus.storageError),"%s",message);
  portEXIT_CRITICAL(&statusMux);
}
void audioError(const char *message) {
  portENTER_CRITICAL(&statusMux);
  snprintf(currentStatus.audioError,sizeof(currentStatus.audioError),"%s",message);
  portEXIT_CRITICAL(&statusMux);
}
void ARDUINO_ISR_ATTR onDigitalEdge() {
  uint64_t now=uint64_t(esp_timer_get_time());
  portENTER_CRITICAL_ISR(&edgeMux);
  ++rawEdges;lastRawUs=now;
  if(!edgeSeen||now-lastDigitalUs>=30000) {++digitalEvents;lastDigitalUs=now;edgeSeen=true;}
  portEXIT_CRITICAL_ISR(&edgeMux);
}
String slotPath(int i,const char *ext) {return String("/q")+i+ext;}
String quote(const String &value) {
  String result="\"";
  for(size_t i=0;i<value.length();++i) {
    char c=value[i];if(c=='"'||c=='\\')result+='\\';
    if(uint8_t(c)>=32)result+=c;
  }
  return result+"\"";
}
String number64(uint64_t v) {char b[24];snprintf(b,sizeof(b),"%llu",(unsigned long long)v);return String(b);}
int completedSlotById(const String &id) {
  if(id.length()!=38||!validEventId(id.c_str()))return -1;
  for(int i=0;i<2;++i){EventReadLease lease(slots[i]);if(lease&&id==slots[i].meta.id)return i;}
  return -1;
}
String eventJson(const EventMetadata &m,bool persisted=true,int slotIndex=-1) {
  String j="{\"id\":"+quote(m.id)+",\"completed\":true,\"sampleRate\":"+String(m.sampleRate);
  j+=",\"sampleCount\":"+String(m.sampleCount)+",\"bytes\":"+String(44+m.sampleCount*2);
  char crc[9];snprintf(crc,sizeof(crc),"%08x",m.crc);
  j+=",\"crc32\":"+quote(crc)+",\"startSample\":"+number64(m.startSample)+",\"triggerSample\":"+number64(m.triggerSample);
  j+=",\"durationMs\":"+String(uint64_t(m.sampleCount)*1000/m.sampleRate);
  j+=",\"rawEdges\":"+String(m.rawEdges)+",\"digitalEvents\":"+String(m.digitalEvents);
  j+=",\"lastRawEdgeUs\":"+number64(m.lastRawEdgeUs)+",\"lastDigitalEventUs\":"+number64(m.lastDigitalEventUs);
  j+=",\"maxLevelDb\":"+String(m.maxDb,2)+",\"averageLevelDb\":"+String(m.averageDb,2);
  j+=",\"manual\":"+String(m.manual?"true":"false")+",\"truncatedAtLimit\":"+String(m.truncatedAtLimit?"true":"false");
  PiezoEventStats p{};
  if(slotIndex>=0&&slotIndex<2){portENTER_CRITICAL(&piezoMux);p=piezoEventStats[slotIndex];portEXIT_CRITICAL(&piezoMux);}
  bool piezo=PIEZO_ENABLED&&p.valid&&strcmp(p.id,m.id)==0;
  j+=",\"piezoAvailable\":"+String(piezo?"true":"false")+",\"piezoPeakMv\":"+String(unsigned(piezo?p.peakMv:0))+",\"piezoHits\":"+String(unsigned(piezo?p.hits:0));
  return j+",\"devicePersistence\":"+quote(persisted?"flash":"ram")+",\"vibrationIntensityAvailable\":false,\"levelCalibrated\":false}";
}
String piezoFields(const StatusSnapshot &s) {
  PiezoShared pz;portENTER_CRITICAL(&piezoMux);pz=piezoShared;portEXIT_CRITICAL(&piezoMux);
  String j=",\"piezoEnabled\":"+String(PIEZO_ENABLED?"true":"false")+",\"piezoPin\":"+String(PIEZO_PIN);
  j+=",\"piezoReady\":"+String(piezoTaskRunning&&pz.ready?"true":"false")+",\"piezoMv\":"+String(unsigned(pz.lastMv));
  j+=",\"piezoRecentPeakMv\":"+String(unsigned(pz.recentPeakMv))+",\"piezoBaselineMv\":"+String(unsigned(pz.baselineMv));
  j+=",\"piezoHitThresholdMv\":"+String(unsigned(pz.thresholdMv))+",\"piezoHits\":"+String(unsigned(pz.hits))+",\"piezoSamples\":"+String(unsigned(pz.samples));
  j+=",\"recordingPiezoPeakMv\":"+String(unsigned(s.recordingPiezoPeakMv))+",\"recordingPiezoHits\":"+String(unsigned(s.recordingPiezoHits));
  return j+",\"piezoCalibrated\":false";
}
String piezoJson() {
  auto s=snapshot();
  String j="{\"firmwareVersion\":"+quote(FIRMWARE_VERSION)+",\"uptimeMs\":"+String(millis())+",\"recording\":"+String(s.recording()?"true":"false");
  j+=",\"tiltState\":"+String(s.tiltState)+",\"digitalEvents\":"+String(s.digitalEvents);
  return j+piezoFields(s)+"}";
}
String statusJson() {
  auto s=snapshot();uint32_t pending=0,finalizing=0,quarantined=0,waiting=0;bool has=false;
  for(auto &slot:slots) {auto state=slot.state.load(std::memory_order_acquire);
    bool unacked=!slot.ackRequested.load(std::memory_order_acquire);
    if(slot.published.load(std::memory_order_acquire)&&unacked){++pending;has=true;}
    if(unacked&&(state==SlotState::Ready||state==SlotState::Publishing||state==SlotState::Writing||state==SlotState::WaitingForSpace))++finalizing;
    if(state==SlotState::WaitingForSpace)++waiting;
    if(state==SlotState::Quarantined)++quarantined;
  }
  String error=s.audioError[0]?s.audioError:s.storageError;
  String j="{\"protocolVersion\":2,\"firmwareVersion\":"+quote(FIRMWARE_VERSION);
  j+=",\"device\":\"ESP32-S3-SuperMini\",\"deviceId\":"+quote(deviceId)+",\"bootId\":"+quote(bootId);
  j+=",\"hostname\":"+quote(String(MDNS_HOSTNAME)+".local")+",\"ip\":"+quote(WiFi.localIP().toString());
  j+=",\"wifiConnected\":"+String(WiFi.status()==WL_CONNECTED?"true":"false");
  j+=",\"recording\":"+String(s.recording()?"true":"false");
  j+=",\"finalizing\":"+String(finalizing?"true":"false")+",\"completed\":"+String(has?"true":"false");
  j+=",\"durationMs\":"+String(uint64_t(s.activeSlot>=0?s.activeSamples:s.lastEventSamples)*1000/SAMPLE_RATE);
  j+=",\"bytesWritten\":"+String(s.lastEventBytes?s.lastEventBytes:legacyFileSize);
  j+=",\"sampleRate\":16000,\"channels\":1,\"bitsPerSample\":16,\"recordingLimitMs\":"+String(MAX_RECORDING_MS);
  j+=",\"peakPcm\":"+String(s.lastPeak)+",\"levelDb\":"+String(s.levelDb,2);
  j+=",\"recentPeakDb\":"+String(s.recentPeakDb,2)+",\"maxLevelDb\":"+String(s.recordMaxDb,2)+",\"averageLevelDb\":"+String(s.recordAverageDb,2);
  j+=",\"thresholdDb\":"+String(s.thresholdDb,2)+",\"levelAgeMs\":"+String(uint32_t(millis()-s.lastLevelMs));
  j+=",\"levelSampleBlockCount\":"+String(s.blockCount)+",\"lastLevelPcmPeak\":"+String(s.lastPeak);
  j+=",\"sampleSequence\":"+number64(s.sampleSequence)+",\"autoEnabled\":"+String(s.autoEnabled?"true":"false");
  j+=",\"ramTransferAvailable\":true,\"ramAcknowledgedEvents\":"+String(s.ramAcknowledgedEvents)+",\"flashCommittedEvents\":"+String(s.flashCommittedEvents);
  j+=",\"eventQueueAvailable\":true,\"pendingEvents\":"+String(pending)+",\"queueCapacity\":2";
  j+=",\"queueOverflows\":"+String(s.queueOverflows)+",\"audioOverflows\":"+String(s.overflowCount);
  j+=",\"startedEvents\":"+String(s.startedEvents)+",\"storageErrors\":"+String(s.storageErrors)+",\"recoveryErrors\":"+String(s.recoveryErrors);
  j+=",\"waitingForSpace\":"+String(waiting)+",\"quarantinedSlots\":"+String(quarantined)+",\"freeHeap\":"+String(ESP.getFreeHeap())+",\"freePsram\":"+String(ESP.getFreePsram());
  j+=",\"tiltState\":"+String(s.tiltState)+",\"tiltChangeCount\":"+String(s.stableChanges)+",\"tiltRawEdges\":"+String(s.rawEdges)+",\"digitalEvents\":"+String(s.digitalEvents);
  j+=",\"recordingTiltEdges\":"+String(s.recordingRawEdges)+",\"uptimeMs\":"+String(millis())+",\"ledCount\":"+String(LED_COUNT);
  j+=",\"vibrationIntensityAvailable\":false,\"levelCalibrated\":false"+piezoFields(s);
  j+=",\"hasRecording\":"+String(has||legacyFileSize>44?"true":"false")+",\"latestFileName\":\"latest.wav\"";
  j+=",\"ready\":"+String(i2sReady&&memoryReady&&storageReady?"true":"false")+",\"wiringConfirmed\":"+String(KNOK_WIRING_CONFIRMED?"true":"false");
  j+=",\"pins\":{\"sck\":"+String(I2S_BCLK_PIN)+",\"ws\":"+String(I2S_WS_PIN)+",\"sd\":"+String(I2S_DATA_PIN)+"}";
  return j+",\"message\":"+quote(error.length()?error:"Ready")+"}";
}
void sendStatus(int code=200) {server.sendHeader("Cache-Control","no-store");server.send(code,"application/json",statusJson());}
void reject(int code,const char *message) {server.send(code,"application/json","{\"message\":"+quote(message)+"}");}

bool verifyFile(int i,const EventMetadata &m) {
  File f=SPIFFS.open(slotPath(i,".wav"),FILE_READ);if(!f)return false;
  uint8_t h[44];if(f.read(h,44)!=44||!recoverableMetadata(m,sizeof(m),h,f.size())){f.close();return false;}
  uint32_t crc=crc32Update(0xffffffff,h,44);uint8_t block[2048];
  while(f.available()) {size_t n=f.read(block,sizeof(block));if(!n){f.close();return false;}crc=crc32Update(crc,block,n);}
  f.close();return (crc^0xffffffff)==m.crc;
}
void recoverFiles() {
  for(int i=0;i<2;++i) {
    if(SPIFFS.exists(slotPath(i,".ack"))) {
      // Durable acknowledgement: this event was committed to the app before deletion.
      bool clean=true;
      for(auto ext:{".wav",".meta",".part",".metapart",".ackpart"})if(SPIFFS.exists(slotPath(i,ext))&&!SPIFFS.remove(slotPath(i,ext)))clean=false;
      if(clean)clean=SPIFFS.remove(slotPath(i,".ack"));
      if(!clean){slots[i].state.store(SlotState::Quarantined);storageError("Acknowledged event recovery cleanup failed",true);}
      continue;
    }
    auto path=slotPath(i,".meta");bool valid=false;
    if(SPIFFS.exists(path)) {
      File meta=SPIFFS.open(path,FILE_READ);EventMetadata m{};
      if(meta&&meta.size()==sizeof(m)&&meta.read((uint8_t*)&m,sizeof(m))==sizeof(m))valid=verifyFile(i,m);
      if(meta)meta.close();
      if(valid){slots[i].meta=m;slots[i].persisted.store(true);slots[i].published.store(true,std::memory_order_release);slots[i].state.store(SlotState::Complete,std::memory_order_release);continue;}
    }
    if(SPIFFS.exists(path)||SPIFFS.exists(slotPath(i,".wav"))||SPIFFS.exists(slotPath(i,".part"))||SPIFFS.exists(slotPath(i,".metapart"))||SPIFFS.exists(slotPath(i,".ackpart"))) {
      slots[i].state.store(SlotState::Quarantined);storageError("Incomplete event preserved for recovery",true);
    }
  }
  if(SPIFFS.exists("/latest.wav")){File f=SPIFFS.open("/latest.wav",FILE_READ);if(f){legacyFileSize=f.size();f.close();}}
}
SaveOutcome saveSlot(int i) {
  auto &slot=slots[i];auto &m=slot.meta;
  uint32_t bytes=44+m.sampleCount*2;
  if(!prepareBufferedEvent(slot,[]{vTaskDelay(1);})){storageError("Invalid captured event buffer");return SaveOutcome::Damaged;}
  portENTER_CRITICAL(&statusMux);
  currentStatus.lastEventSamples=m.sampleCount;currentStatus.lastEventBytes=bytes;
  currentStatus.recordMaxDb=m.maxDb;currentStatus.recordAverageDb=m.averageDb;
  portEXIT_CRITICAL(&statusMux);
  if(slot.ackRequested.load(std::memory_order_acquire))return SaveOutcome::Acknowledged;
  if(!slot.receiverWaited) {
    uint32_t start=millis();
    while(uint32_t(millis()-start)<3000) {
      if(slot.ackRequested.load(std::memory_order_acquire))return SaveOutcome::Acknowledged;
      vTaskDelay(pdMS_TO_TICKS(10));
    }
    slot.receiverWaited=true;
  }
  if(slot.ackRequested.load(std::memory_order_acquire))return SaveOutcome::Acknowledged;
  if(!m.sampleCount||SPIFFS.usedBytes()+bytes+sizeof(m)>SPIFFS.totalBytes()*3/4) {
    if(!capacityWarning[i])storageError("Storage capacity reached; event held in RAM");
    capacityWarning[i]=true;return SaveOutcome::RetryWhenSpace;
  }
  // No pathname is reused while it contains an unacknowledged or partial event.
  if(SPIFFS.exists(slotPath(i,".wav"))||SPIFFS.exists(slotPath(i,".part"))||SPIFFS.exists(slotPath(i,".meta"))) {
    storageError("Existing event preserved; slot requires recovery");return SaveOutcome::Damaged;
  }
  auto gc=reclaimStorageWindow([bytes]{
    esp_err_t result=esp_spiffs_gc(nullptr,bytes+16384);
    return result==ESP_OK?ReclaimResult::Ready:result==ESP_ERR_NOT_FINISHED?ReclaimResult::Pending:ReclaimResult::Failed;
  },[]{vTaskDelay(1);});
  if(gc==ReclaimResult::Pending)return SaveOutcome::RetryWhenSpace;
  if(gc==ReclaimResult::Failed){storageError("Flash garbage collection failed; event held in RAM");return SaveOutcome::RetryWhenSpace;}
  if(slot.ackRequested.load(std::memory_order_acquire))return SaveOutcome::Acknowledged;
  slot.diskStarted=true;
  File f=SPIFFS.open(slotPath(i,".part"),FILE_WRITE);if(!f){storageError("Cannot create event file");return SaveOutcome::Damaged;}
  uint8_t h[44];writeWavHeader(h,SAMPLE_RATE,m.sampleCount);
  errno=0;size_t lastWritten=f.write(h,44);bool ok=lastWritten==44;uint32_t writeAt=0;
  auto *pcm=(const uint8_t*)slot.pcm;
  for(uint32_t at=0;ok&&at<m.sampleCount*2;) {
    uint32_t n=std::min<uint32_t>(4096,m.sampleCount*2-at);
    writeAt=at;lastWritten=f.write(pcm+at,n);ok=lastWritten==n;at+=n;vTaskDelay(1);
  }
  int writeErrno=errno;f.flush();uint32_t actual=f.size();f.close();
  if(!ok||actual!=bytes) {
    char reason[96];snprintf(reason,sizeof(reason),"WAV write failed at %lu n=%lu actual=%lu want=%lu err=%d",(unsigned long)writeAt,(unsigned long)lastWritten,(unsigned long)actual,(unsigned long)bytes,writeErrno);
    storageError(reason);return SaveOutcome::Damaged;
  }
  if(!SPIFFS.rename(slotPath(i,".part"),slotPath(i,".wav"))) {storageError("WAV rename failed; partial file preserved");return SaveOutcome::Damaged;}
  if(!verifyFile(i,m)){storageError("WAV verification failed");return SaveOutcome::Damaged;}
  File mf=SPIFFS.open(slotPath(i,".metapart"),FILE_WRITE);bool metaOk=mf&&mf.write((const uint8_t*)&m,sizeof(m))==sizeof(m);
  if(mf){mf.flush();mf.close();}
  if(!metaOk||!SPIFFS.rename(slotPath(i,".metapart"),slotPath(i,".meta"))) {storageError("Event metadata commit failed");return SaveOutcome::Damaged;}
  portENTER_CRITICAL(&statusMux);
  currentStatus.lastEventSamples=m.sampleCount;currentStatus.lastEventBytes=bytes;
  currentStatus.recordMaxDb=m.maxDb;currentStatus.recordAverageDb=m.averageDb;
  portEXIT_CRITICAL(&statusMux);
  slot.persisted.store(true,std::memory_order_release);
  portENTER_CRITICAL(&statusMux);++currentStatus.flashCommittedEvents;portEXIT_CRITICAL(&statusMux);
  capacityWarning[i]=false;
  if(!capacityWarning[0]&&!capacityWarning[1]) {
    portENTER_CRITICAL(&statusMux);
    if(strcmp(currentStatus.storageError,"Storage capacity reached; event held in RAM")==0)currentStatus.storageError[0]=0;
    portEXIT_CRITICAL(&statusMux);
  }
  return SaveOutcome::Saved;
}
bool eraseAcknowledgedSlot(int i) {
  auto &slot=slots[i];bool any=false;
  if(!slot.diskStarted&&!slot.persisted.load()) {
    portENTER_CRITICAL(&statusMux);++currentStatus.ramAcknowledgedEvents;portEXIT_CRITICAL(&statusMux);return true;
  }
  for(auto ext:{".meta",".wav",".part",".metapart",".ack",".ackpart"})if(SPIFFS.exists(slotPath(i,ext)))any=true;
  if(!any) {
    portENTER_CRITICAL(&statusMux);++currentStatus.ramAcknowledgedEvents;portEXIT_CRITICAL(&statusMux);
    return true;
  }
  // Only an ACK from the receiver permits retiring these files. Metadata rename
  // or a verified small marker makes cleanup repeatable after a power loss.
  bool marked=SPIFFS.exists(slotPath(i,".ack"));
  if(!marked&&SPIFFS.exists(slotPath(i,".meta")))marked=SPIFFS.rename(slotPath(i,".meta"),slotPath(i,".ack"));
  if(!marked) {
    File marker=SPIFFS.open(slotPath(i,".ackpart"),FILE_WRITE);
    bool ok=marker&&marker.write((const uint8_t*)&slot.meta,sizeof(slot.meta))==sizeof(slot.meta);
    if(marker){marker.flush();ok=ok&&marker.size()==sizeof(slot.meta);marker.close();}
    marked=ok&&SPIFFS.rename(slotPath(i,".ackpart"),slotPath(i,".ack"));
  }
  bool ok=marked;
  if(marked)for(auto ext:{".wav",".part",".meta",".metapart",".ackpart"})
    if(SPIFFS.exists(slotPath(i,ext))&&!SPIFFS.remove(slotPath(i,ext)))ok=false;
  if(ok)ok=SPIFFS.remove(slotPath(i,".ack"));
  if(!ok)storageError("Acknowledged event cleanup failed");
  return ok;
}
void publicationTask(void*) {
  for(;;) {
    for(auto &slot:slots)publishBufferedEvent(slot,[]{vTaskDelay(1);});
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}
void storageTask(void*) {
  StorageScheduler scheduler;
  for(;;) {
    scheduler.service(slots,2,millis(),[](size_t i){return saveSlot(int(i));},
      [](size_t i){return eraseAcknowledgedSlot(int(i));});
    bool problem=false;for(auto &slot:slots){auto state=slot.state.load();if(state==SlotState::Quarantined||state==SlotState::WaitingForSpace)problem=true;}
    if(!problem){portENTER_CRITICAL(&statusMux);currentStatus.storageError[0]=0;portEXIT_CRITICAL(&statusMux);}
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}
void captureTask(void*) {
  int32_t raw[BLOCK_SAMPLES];int16_t pcm[BLOCK_SAMPLES];
  uint32_t peakSince=0;float recentPeak=0;int rawTilt=digitalRead(TILT_PIN),stableTilt=rawTilt;uint32_t tiltSince=millis(),changes=0;
  PiezoEventTracker piezoTracker;uint32_t piezoHitsSeen=0;
  for(;;) {
    Control command{};
    while(xQueueReceive(controls,&command,0)==pdTRUE) {
      if(command.kind==AUTO){engine->setThreshold(command.threshold,5);engine->setAutoEnabled(command.enabled);}
      if(command.kind==START){
        if(!engine->startManual(command.duration*SAMPLE_RATE/1000))audioError("Manual start rejected: event queue busy");
        int active=engine->activeSlot();
        portENTER_CRITICAL(&statusMux);
        currentStatus.publishStart(active,active>=0?slots[active].meta.sampleCount:0);
        portEXIT_CRITICAL(&statusMux);
      }
      if(command.kind==STOP)engine->requestStop();
    }
    i2s_event_t event{};uint32_t overflow=0;
    while(xQueueReceive(i2sEvents,&event,0)==pdTRUE)if(event.type==I2S_EVENT_RX_Q_OVF)++overflow;
    size_t bytes=0;esp_err_t result=i2s_read(I2S_NUM_0,raw,sizeof(raw),&bytes,pdMS_TO_TICKS(50));
    if(result!=ESP_OK||!bytes){audioError("I2S capture stalled");vTaskDelay(1);continue;}
    uint32_t count=bytes/sizeof(int32_t);double squares=0;int peak=0;
    for(uint32_t i=0;i<count;++i){int32_t sample=raw[i]>>14;sample=std::max(-32768,std::min(32767,int(sample)));pcm[i]=int16_t(sample);squares+=double(sample)*sample;peak=std::max(peak,std::abs(int(sample)));}
    double rms=sqrt(squares/count);float db=rms<1?0:std::max(0.0,std::min(120.0,20*log10(rms)+30));
    uint32_t edges,events;uint64_t rawUs,digitalUs;
    portENTER_CRITICAL(&edgeMux);edges=rawEdges;events=digitalEvents;rawUs=lastRawUs;digitalUs=lastDigitalUs;portEXIT_CRITICAL(&edgeMux);
    uint16_t piezoPeak=0;uint32_t piezoHitsNow=piezoHitsSeen;
    if(PIEZO_ENABLED){portENTER_CRITICAL(&piezoMux);piezoPeak=piezoShared.blockPeakMv;piezoShared.blockPeakMv=0;piezoHitsNow=piezoShared.hits;portEXIT_CRITICAL(&piezoMux);}
    engine->process(pcm,count,db,edges,events,rawUs,digitalUs);
    int active=engine->activeSlot();
    auto piezoDone=piezoTracker.block(engine->sampleSequence(),piezoHitsSeen,piezoHitsNow,piezoPeak,active,active>=0?slots[active].meta.startSample:0);
    piezoHitsSeen=piezoHitsNow;
    if(piezoDone.slot>=0) {
      PiezoEventStats stats{};snprintf(stats.id,sizeof(stats.id),"%s",slots[piezoDone.slot].meta.id);
      stats.hits=piezoDone.hits;stats.peakMv=piezoDone.peakMv;stats.valid=PIEZO_ENABLED&&piezoTaskRunning;
      portENTER_CRITICAL(&piezoMux);piezoEventStats[piezoDone.slot]=stats;portEXIT_CRITICAL(&piezoMux);
    }
    uint32_t now=millis();if(uint32_t(now-peakSince)>=500){peakSince=now;recentPeak=db;}else recentPeak=std::max(recentPeak,db);
    int tilt=digitalRead(TILT_PIN);if(tilt!=rawTilt){rawTilt=tilt;tiltSince=now;}
    if(tilt!=stableTilt&&uint32_t(now-tiltSince)>=30){stableTilt=tilt;++changes;}
    uint32_t activeSamples=active>=0?slots[active].meta.sampleCount:0;
    portENTER_CRITICAL(&statusMux);
    currentStatus.sampleSequence=engine->sampleSequence();currentStatus.blockCount++;
    currentStatus.levelDb=db;currentStatus.lastPeak=peak;currentStatus.lastLevelMs=now;currentStatus.recentPeakDb=recentPeak;
    currentStatus.publishCapture(active,activeSamples);currentStatus.overflowCount+=overflow;
    if(active>=0)currentStatus.recordingRawEdges=slots[active].meta.rawEdges;
    currentStatus.recordingPiezoPeakMv=piezoTracker.activePeak();currentStatus.recordingPiezoHits=piezoTracker.activeHits(piezoHitsNow);
    currentStatus.queueOverflows=engine->queueOverflows();currentStatus.startedEvents=engine->startedEvents();
    currentStatus.autoEnabled=engine->autoEnabled();currentStatus.thresholdDb=engine->thresholdDb();
    currentStatus.tiltState=stableTilt;currentStatus.stableChanges=changes;currentStatus.rawEdges=edges;currentStatus.digitalEvents=events;
    portEXIT_CRITICAL(&statusMux);
  }
}
void piezoTask(void*) {
  analogReadResolution(12);analogSetPinAttenuation(PIEZO_PIN,ADC_11db);
  PiezoDetector detector(PIEZO_HIT_THRESHOLD_MV);
  uint32_t recentSince=millis();uint16_t recentPeak=0;
  for(;;) {
    uint32_t a=analogReadMilliVolts(PIEZO_PIN),b=analogReadMilliVolts(PIEZO_PIN);
    uint32_t threshold;portENTER_CRITICAL(&piezoMux);threshold=piezoShared.thresholdMv;portEXIT_CRITICAL(&piezoMux);
    if(threshold!=detector.threshold())detector.setThreshold(threshold);
    uint32_t now=millis();auto sample=detector.update(std::max(a,b),now);
    if(uint32_t(now-recentSince)>=500){recentSince=now;recentPeak=sample.amplitudeMv;}else recentPeak=std::max(recentPeak,sample.amplitudeMv);
    portENTER_CRITICAL(&piezoMux);
    piezoShared.hits=detector.hits();piezoShared.lastMv=sample.amplitudeMv;piezoShared.recentPeakMv=recentPeak;
    piezoShared.blockPeakMv=std::max(piezoShared.blockPeakMv,sample.amplitudeMv);piezoShared.baselineMv=detector.baselineMv();
    ++piezoShared.samples;piezoShared.ready=true;
    portEXIT_CRITICAL(&piezoMux);
    vTaskDelay(1);
  }
}
bool setupI2S() {
  i2s_config_t cfg{};cfg.mode=(i2s_mode_t)(I2S_MODE_MASTER|I2S_MODE_RX);cfg.sample_rate=SAMPLE_RATE;
  cfg.bits_per_sample=I2S_BITS_PER_SAMPLE_32BIT;cfg.channel_format=I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format=I2S_COMM_FORMAT_STAND_I2S;cfg.intr_alloc_flags=ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count=8;cfg.dma_buf_len=256;cfg.use_apll=false;cfg.tx_desc_auto_clear=false;
  i2s_pin_config_t pins{};pins.bck_io_num=I2S_BCLK_PIN;pins.ws_io_num=I2S_WS_PIN;pins.data_out_num=I2S_PIN_NO_CHANGE;pins.data_in_num=I2S_DATA_PIN;
  if(i2s_driver_install(I2S_NUM_0,&cfg,16,&i2sEvents)!=ESP_OK)return false;
  if(i2s_set_pin(I2S_NUM_0,&pins)!=ESP_OK){i2s_driver_uninstall(I2S_NUM_0);return false;}
  i2s_zero_dma_buffer(I2S_NUM_0);return true;
}
void maintainWiFi() {
  static bool wasConnected=false;bool connected=WiFi.status()==WL_CONNECTED;
  if(!connected&&wasConnected&&mdnsReady){MDNS.end();mdnsReady=false;}
  if(connected&&!mdnsReady){mdnsReady=MDNS.begin(MDNS_HOSTNAME);if(mdnsReady)MDNS.addService("http","tcp",80);}
  if(!connected&&uint32_t(millis()-lastWifiAttempt)>=10000){lastWifiAttempt=millis();WiFi.reconnect();}
  wasConnected=connected;
}
void updateLed() {
  auto s=snapshot();uint32_t color;
  if(!storageReady||!i2sReady||!memoryReady||s.audioError[0]||s.storageError[0])color=pixels.Color(255,0,0);
  else if(downloadActive.load())color=pixels.Color(0,180,255);
  else if(s.activeSlot>=0)color=pixels.Color(255,100,0);
  else if(WiFi.status()!=WL_CONNECTED)color=pixels.Color(0,0,255);
  else color=pixels.Color(0,255,0);
  static uint32_t previous=0xffffffff;if(color==previous)return;
  previous=color;pixels.fill(color);pixels.show();
}
void downloadSlot(int i,const String &expectedId=String()) {
  if(i<0){reject(404,"Completed event not found");return;}
  auto &slot=slots[i];EventReadLease lease(slot);
  if(!lease||(expectedId.length()&&expectedId!=slot.meta.id)){reject(404,"Event no longer available");return;}
  const auto &m=slot.meta;bool persisted=slot.persisted.load(std::memory_order_acquire);
  if(!expectedId.length()&&!persisted){reject(409,"Latest file is not persisted; use event IDs");return;}
  downloadActive.store(true);updateLed();
  server.sendHeader("X-KNOK-Event-ID",m.id);server.sendHeader("X-KNOK-Storage",persisted?"flash":"ram");server.sendHeader("Cache-Control","no-store");
  if(persisted) {
    File f=SPIFFS.open(slotPath(i,".wav"),FILE_READ);
    if(!f||f.size()!=44+m.sampleCount*2){if(f)f.close();downloadActive.store(false);reject(409,"Event file unavailable");return;}
    server.streamFile(f,"audio/wav");f.close();
  } else {
    uint8_t header[44];writeWavHeader(header,m.sampleRate,m.sampleCount);
    server.setContentLength(44+m.sampleCount*2);server.send(200,"audio/wav","");
    auto client=server.client();client.setNoDelay(true);
    bool ok=client.write(header,44)==44;const auto *pcm=reinterpret_cast<const uint8_t*>(slot.pcm);
    for(uint32_t at=0;ok&&at<m.sampleCount*2;) {
      uint32_t n=std::min<uint32_t>(16384,m.sampleCount*2-at);size_t sent=client.write(pcm+at,n);
      if(sent==0){ok=false;break;}at+=sent;
    }
    if(!ok)client.stop();
  }
  downloadActive.store(false);
}
void setupServer() {
  server.on("/",HTTP_GET,[]{server.send(200,"text/plain","KNOK recorder v2 - /api/status");});
  server.on("/api/status",HTTP_GET,[]{sendStatus();});
  server.on("/api/piezo",HTTP_GET,[]{server.sendHeader("Cache-Control","no-store");server.send(200,"application/json",piezoJson());});
  server.on("/api/piezo",HTTP_POST,[]{
    long mv=server.hasArg("thresholdMv")?server.arg("thresholdMv").toInt():-1;
    if(mv<long(PiezoDetector::MIN_THRESHOLD_MV)||mv>long(PiezoDetector::MAX_THRESHOLD_MV)){reject(400,"thresholdMv must be 20..3000");return;}
    portENTER_CRITICAL(&piezoMux);piezoShared.thresholdMv=uint32_t(mv);portEXIT_CRITICAL(&piezoMux);
    server.sendHeader("Cache-Control","no-store");server.send(200,"application/json",piezoJson());
  });
  server.on("/api/storage",HTTP_GET,[]{
    String j="{\"totalBytes\":"+String(SPIFFS.totalBytes())+",\"usedBytes\":"+String(SPIFFS.usedBytes())+",\"slots\":[";
    for(int i=0;i<2;++i){if(i)j+=",";j+=String(int(slots[i].state.load()));}
    j+="],\"files\":[";bool first=true;File root=SPIFFS.open("/");File f=root.openNextFile();
    while(f){if(!first)j+=",";first=false;j+="{\"name\":"+quote(f.name())+",\"bytes\":"+String(f.size())+"}";f.close();f=root.openNextFile();}
    root.close();server.send(200,"application/json",j+"]}");
  });
  server.on("/api/storage/file",HTTP_GET,[]{
    String name=server.arg("name");bool allowed=name=="/latest.wav";
    for(int i=0;i<2;++i)for(auto ext:{".wav",".part",".meta",".metapart",".ack",".ackpart"})if(name==slotPath(i,ext))allowed=true;
    if(!allowed){reject(400,"Unknown diagnostic file");return;}
    File f=SPIFFS.open(name,FILE_READ);if(!f){reject(404,"Diagnostic file absent");return;}
    server.streamFile(f,"application/octet-stream");f.close();
  });
  server.on("/api/auto",HTTP_POST,[]{
    if(!i2sReady||!memoryReady||!storageReady){reject(503,"Capture not ready; inspect status");return;}
    float threshold=server.hasArg("thresholdDb")?server.arg("thresholdDb").toFloat():90;
    if(!std::isfinite(threshold)||threshold<30||threshold>120){reject(400,"thresholdDb must be 30..120 relative dB");return;}
    Control c{AUTO,server.arg("enabled")=="1",threshold,0};
    if(xQueueSend(controls,&c,0)!=pdTRUE){reject(503,"Control queue busy");return;}sendStatus(202);
  });
  server.on("/api/record/start",HTTP_POST,[]{
    auto s=snapshot();if(!i2sReady||!storageReady||!memoryReady){reject(503,"Recorder not ready");return;}
    if(s.autoEnabled||s.recording()){reject(409,"Recorder busy; disable auto before manual start");return;}
    bool free=false;for(auto &slot:slots)if(slot.state.load(std::memory_order_acquire)==SlotState::Free)free=true;
    if(!free){reject(409,"Event queue full; import pending recordings first");return;}
    long duration=server.hasArg("durationMs")?server.arg("durationMs").toInt():MAX_RECORDING_MS;
    if(duration<1||duration>MAX_RECORDING_MS){reject(400,"durationMs must be 1..15000");return;}
    Control c{START,false,90,uint32_t(duration)};
    portENTER_CRITICAL(&statusMux);currentStatus.requestStart();portEXIT_CRITICAL(&statusMux);
    if(xQueueSend(controls,&c,0)!=pdTRUE){portENTER_CRITICAL(&statusMux);currentStatus.pendingStart=false;portEXIT_CRITICAL(&statusMux);reject(503,"Control queue busy");return;}sendStatus(200);
  });
  server.on("/api/record/stop",HTTP_POST,[]{Control c{STOP,false,90,0};if(!controls||xQueueSend(controls,&c,0)!=pdTRUE){reject(503,"Control queue busy");return;}sendStatus(202);});
  server.on("/api/events",HTTP_GET,[]{
    String j="{\"protocolVersion\":2,\"events\":[";bool first=true;
    for(auto &slot:slots){EventReadLease lease(slot);if(lease){if(!first)j+=",";j+=eventJson(slot.meta,slot.persisted.load(std::memory_order_acquire),int(&slot-slots));first=false;}}
    server.sendHeader("Cache-Control","no-store");server.send(200,"application/json",j+"]}");
  });
  server.on("/api/events/file",HTTP_GET,[]{downloadSlot(completedSlotById(server.arg("id")),server.arg("id"));});
  server.on("/api/events/ack",HTTP_POST,[]{
    String id=server.arg("id");if(id.length()!=38||!validEventId(id.c_str())){reject(400,"Invalid event ID");return;}
    int i=completedSlotById(id);if(i>=0){EventReadLease lease(slots[i]);if(lease&&id==slots[i].meta.id)slots[i].ackRequested.store(true,std::memory_order_release);}
    server.send(200,"application/json","{\"acknowledged\":true}");
  });
  server.on("/api/recording/latest",HTTP_GET,[]{
    auto s=snapshot();if(s.recording()){reject(409,"Recording is not complete");return;}
    EventMetadata selected;int latest=latestDownloadSlot(slots,2,s.recording(),bootIdentity,&selected);
    if(latest==-2){reject(409,"Latest recording is finalizing or ambiguous; use event IDs");return;}
    if(latest>=0){downloadSlot(latest,selected.id);return;}
    if(s.startedEvents==0&&legacyFileSize>44){File f=SPIFFS.open("/latest.wav",FILE_READ);if(f){downloadActive.store(true);updateLed();server.streamFile(f,"audio/wav");f.close();downloadActive.store(false);return;}}
    reject(404,"No completed recording");
  });
  server.begin();
}
void setup() {
  if(KNOK_SERIAL_LOGGING){Serial.begin(115200);Serial.setTxTimeoutMs(0);}
  pinMode(TILT_PIN,INPUT_PULLUP);attachInterrupt(digitalPinToInterrupt(TILT_PIN),onDigitalEdge,CHANGE);
  pixels.begin();pixels.setBrightness(12);pixels.clear();pixels.show();
  uint64_t mac=ESP.getEfuseMac();snprintf(deviceId,sizeof(deviceId),"%012llx",(unsigned long long)(mac&0xffffffffffffULL));
  snprintf(bootId,sizeof(bootId),"%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random());
  snprintf(bootIdentity,sizeof(bootIdentity),"%s-%s",deviceId,bootId);
  storageReady=SPIFFS.begin(false);if(!storageReady)storageError("SPIFFS mount failed; automatic format disabled");
  auto *ring=(int16_t*)heap_caps_malloc(16000*sizeof(int16_t),MALLOC_CAP_8BIT);
  memoryReady=ring!=nullptr;
  for(auto &slot:slots){slot.pcm=(int16_t*)heap_caps_malloc(MAX_SAMPLES*sizeof(int16_t),MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT);slot.capacity=MAX_SAMPLES;if(!slot.pcm)memoryReady=false;}
    if(storageReady)recoverFiles();
  controls=xQueueCreate(4,sizeof(Control));if(!controls)memoryReady=false;
  if(!memoryReady)audioError("Cannot reserve bounded event buffers");
  if(!KNOK_WIRING_CONFIRMED)audioError("Wiring unconfirmed: flashing/capture gate is closed");
  if(memoryReady&&storageReady&&KNOK_WIRING_CONFIRMED) {
    Config recorderConfig;recorderConfig.maxSamples=MAX_SAMPLES;recorderConfig.postSamples=3*SAMPLE_RATE;recorderConfig.autoMaxSamples=6*SAMPLE_RATE;
    engine=new RecorderEngine(slots,2,ring,16000,recorderConfig);engine->setIdentity(bootIdentity);
    i2sReady=setupI2S();if(!i2sReady)audioError("I2S initialization failed");
    if(i2sReady) {
      if(xTaskCreatePinnedToCore(publicationTask,"knok-publish",4096,nullptr,2,nullptr,1)!=pdPASS){i2sReady=false;audioError("Publication worker unavailable");}
      if(i2sReady&&xTaskCreatePinnedToCore(storageTask,"knok-store",8192,nullptr,1,nullptr,1)!=pdPASS){i2sReady=false;audioError("Storage worker unavailable");}
      if(i2sReady&&xTaskCreatePinnedToCore(captureTask,"knok-capture",8192,nullptr,3,nullptr,0)!=pdPASS){i2sReady=false;audioError("Capture worker unavailable");}
    }
  }
  if(PIEZO_ENABLED)piezoTaskRunning=xTaskCreatePinnedToCore(piezoTask,"knok-piezo",4096,nullptr,2,nullptr,1)==pdPASS;
  WiFi.mode(WIFI_STA);WiFi.setSleep(false);WiFi.begin(WIFI_SSID,WIFI_PASSWORD);lastWifiAttempt=millis();setupServer();
}
void loop() {
  server.handleClient();maintainWiFi();updateLed();
  if(KNOK_SERIAL_LOGGING) {
    static uint32_t last=0;if(uint32_t(millis()-last)>=3000){last=millis();constexpr char message[]="KNOK v2 alive\n";
      if(Serial.availableForWrite()>=int(sizeof(message)-1))Serial.write((const uint8_t*)message,sizeof(message)-1);
    }
  }
  delay(1);
}
