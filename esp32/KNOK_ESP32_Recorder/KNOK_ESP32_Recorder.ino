#include <Arduino.h>
#include <ESPmDNS.h>
#include <SPIFFS.h>
#include <WebServer.h>
#include <WiFi.h>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "driver/i2s.h"

// Change these two values before uploading the sketch.
static const char *WIFI_SSID = "YOUR_WIFI_SSID";
static const char *WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

static const char *DEVICE_NAME = "ESP32-S3-SuperMini";
static const char *MDNS_HOSTNAME = "knok-esp32";
static const char *LATEST_WAV_PATH = "/latest.wav";

static const uint32_t SAMPLE_RATE = 16000;
static const uint16_t WAV_BITS_PER_SAMPLE = 16;
static const uint16_t WAV_CHANNELS = 1;
static const uint32_t MAX_RECORDING_SECONDS = 30;
static const size_t RAW_SAMPLES_PER_READ = 512;
static const float AUTO_TRIGGER_DB = 90.0f;
// INMP441 amplitude is not a calibrated SPL value. Adjust this offset with a
// reference sound-level meter if the trigger must represent physical dB SPL.
static const float DB_CALIBRATION_OFFSET = 30.0f;

// INMP441: SCK/BCLK -> GPIO12, WS/LRCLK -> GPIO11, SD/DOUT -> GPIO10,
// L/R -> GND for the left channel, VDD -> 3V3, GND -> GND.
static const i2s_port_t I2S_PORT = I2S_NUM_0;
static const gpio_num_t I2S_SCK_PIN = GPIO_NUM_12;
static const gpio_num_t I2S_WS_PIN = GPIO_NUM_11;
static const gpio_num_t I2S_SD_PIN = GPIO_NUM_10;

WebServer server(80);
TaskHandle_t recordingTaskHandle = nullptr;
TaskHandle_t levelMonitorTaskHandle = nullptr;
SemaphoreHandle_t i2sReadMutex = nullptr;

volatile bool recordingRequested = false;
volatile bool recordingActive = false;
volatile uint32_t recordingStartMs = 0;
volatile uint32_t recordingLimitMs = MAX_RECORDING_SECONDS * 1000UL;
volatile uint32_t lastRecordingDurationMs = 0;
volatile size_t wavDataBytes = 0;
volatile int32_t peakPcm = 0;
volatile float currentLevelDb = 0.0f;
volatile float maxLevelDb = 0.0f;
volatile float averageLevelDb = 0.0f;
volatile double recordingLevelSumDb = 0.0;
volatile uint32_t recordingLevelBlockCount = 0;
volatile uint32_t levelSampleBlockCount = 0;
volatile int32_t lastLevelPcmPeak = 0;

String statusMessage = "ready";
String lastError = "";
wl_status_t lastWiFiStatus = WL_IDLE_STATUS;
unsigned long lastWiFiRetryMs = 0;
bool spiffsReady = false;
bool i2sReady = false;
bool mdnsReady = false;

bool readI2sSamples(
    int32_t *rawSamples,
    size_t bufferBytes,
    size_t *bytesRead,
    TickType_t timeoutTicks) {
  if (i2sReadMutex == nullptr) return false;
  if (xSemaphoreTake(i2sReadMutex, pdMS_TO_TICKS(100)) != pdTRUE) return false;

  const esp_err_t result = i2s_read(
      I2S_PORT,
      rawSamples,
      bufferBytes,
      bytesRead,
      timeoutTicks);

  xSemaphoreGive(i2sReadMutex);
  return result == ESP_OK && *bytesRead > 0;
}

size_t convertRawToPcm(
    const int32_t *rawSamples,
    size_t bytesRead,
    int16_t *pcmSamples) {
  const size_t samplesRead = min(
      bytesRead / sizeof(int32_t),
      RAW_SAMPLES_PER_READ);

  for (size_t i = 0; i < samplesRead; ++i) {
    int32_t shifted = rawSamples[i] >> 14;
    if (shifted > 32767) shifted = 32767;
    if (shifted < -32768) shifted = -32768;
    pcmSamples[i] = (int16_t)shifted;
  }
  return samplesRead;
}

float calculateLevelDb(const int16_t *pcmSamples, size_t sampleCount) {
  if (sampleCount == 0) return 0.0f;

  double sumSquares = 0.0;
  for (size_t i = 0; i < sampleCount; ++i) {
    const float sample = (float)pcmSamples[i];
    sumSquares += (double)sample * (double)sample;
  }

  const float rms = sqrtf((float)(sumSquares / (double)sampleCount));
  if (rms < 1.0f) return 0.0f;

  const float db = 20.0f * log10f(rms) + DB_CALIBRATION_OFFSET;
  return constrain(db, 0.0f, 120.0f);
}

void updateCurrentLevel(const int16_t *pcmSamples, size_t sampleCount) {
  const float measuredLevelDb = calculateLevelDb(pcmSamples, sampleCount);
  currentLevelDb = measuredLevelDb;
  if (measuredLevelDb > maxLevelDb) maxLevelDb = measuredLevelDb;
  if (recordingActive) {
    recordingLevelSumDb += measuredLevelDb;
    recordingLevelBlockCount++;
    averageLevelDb = (float)(recordingLevelSumDb / recordingLevelBlockCount);
  }
  int32_t blockPeak = 0;
  for (size_t i = 0; i < sampleCount; ++i) {
    const int32_t sample = pcmSamples[i];
    const int32_t magnitude = sample < 0 ? -sample : sample;
    if (magnitude > blockPeak) blockPeak = magnitude;
  }
  lastLevelPcmPeak = blockPeak;
  levelSampleBlockCount++;
}

void writeLittleEndian(File &file, uint32_t value, uint8_t byteCount) {
  for (uint8_t i = 0; i < byteCount; ++i) {
    file.write((uint8_t)((value >> (8 * i)) & 0xFF));
  }
}

void writeWavHeader(File &file, uint32_t dataLength) {
  const uint32_t byteRate = SAMPLE_RATE * WAV_CHANNELS * (WAV_BITS_PER_SAMPLE / 8);
  const uint16_t blockAlign = WAV_CHANNELS * (WAV_BITS_PER_SAMPLE / 8);

  file.seek(0);
  file.write((const uint8_t *)"RIFF", 4);
  writeLittleEndian(file, dataLength + 36, 4);
  file.write((const uint8_t *)"WAVE", 4);
  file.write((const uint8_t *)"fmt ", 4);
  writeLittleEndian(file, 16, 4);
  writeLittleEndian(file, 1, 2);  // PCM
  writeLittleEndian(file, WAV_CHANNELS, 2);
  writeLittleEndian(file, SAMPLE_RATE, 4);
  writeLittleEndian(file, byteRate, 4);
  writeLittleEndian(file, blockAlign, 2);
  writeLittleEndian(file, WAV_BITS_PER_SAMPLE, 2);
  file.write((const uint8_t *)"data", 4);
  writeLittleEndian(file, dataLength, 4);
}

bool prepareRecordingFile() {
  if (!spiffsReady) {
    lastError = "spiffs_not_ready";
    statusMessage = "spiffs is not ready";
    return false;
  }

  if (SPIFFS.exists(LATEST_WAV_PATH)) {
    SPIFFS.remove(LATEST_WAV_PATH);
  }

  File file = SPIFFS.open(LATEST_WAV_PATH, FILE_WRITE);
  if (!file) {
    lastError = "spiffs_open_failed";
    statusMessage = "failed to create wav file";
    return false;
  }

  for (int i = 0; i < 44; ++i) {
    file.write((uint8_t)0x00);
  }
  file.close();

  wavDataBytes = 0;
  peakPcm = 0;
  currentLevelDb = 0.0f;
  maxLevelDb = 0.0f;
  averageLevelDb = 0.0f;
  recordingLevelSumDb = 0.0;
  recordingLevelBlockCount = 0;
  lastRecordingDurationMs = 0;
  return true;
}

void finalizeRecordingFile() {
  File file = SPIFFS.open(LATEST_WAV_PATH, "r+");
  if (!file) {
    lastError = "spiffs_reopen_failed";
    statusMessage = "recorded but failed to finalize wav";
    return;
  }

  writeWavHeader(file, (uint32_t)wavDataBytes);
  file.flush();
  file.close();
}

String jsonEscape(String value) {
  value.replace("\\", "\\\\");
  value.replace("\"", "\\\"");
  value.replace("\n", " ");
  value.replace("\r", " ");
  return value;
}

size_t latestFileSize() {
  if (!spiffsReady) return 0;

  File file = SPIFFS.open(LATEST_WAV_PATH, FILE_READ);
  if (!file) return 0;

  size_t size = file.size();
  file.close();
  return size;
}

String buildStatusJson(const String &message) {
  const uint32_t durationMs = recordingActive
      ? (uint32_t)(millis() - recordingStartMs)
      : lastRecordingDurationMs;

  String json = "{";
  json += "\"device\":\"" + String(DEVICE_NAME) + "\",";
  json += "\"hostname\":\"" + String(MDNS_HOSTNAME) + ".local\",";
  json += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
  json += "\"wifiConnected\":" + String(WiFi.status() == WL_CONNECTED ? "true" : "false") + ",";
  json += "\"recording\":" + String(recordingActive ? "true" : "false") + ",";
  json += "\"durationMs\":" + String(durationMs) + ",";
  json += "\"recordingLimitMs\":" + String((uint32_t)recordingLimitMs) + ",";
  json += "\"bytesWritten\":" + String((uint32_t)latestFileSize()) + ",";
  json += "\"sampleRate\":" + String(SAMPLE_RATE) + ",";
  json += "\"channels\":" + String(WAV_CHANNELS) + ",";
  json += "\"bitsPerSample\":" + String(WAV_BITS_PER_SAMPLE) + ",";
  json += "\"peakPcm\":" + String((int32_t)peakPcm) + ",";
  json += "\"levelDb\":" + String((float)currentLevelDb, 1) + ",";
  json += "\"maxLevelDb\":" + String((float)maxLevelDb, 1) + ",";
  json += "\"averageLevelDb\":" + String((float)averageLevelDb, 1) + ",";
  json += "\"thresholdDb\":" + String(AUTO_TRIGGER_DB, 1) + ",";
  json += "\"levelSampleBlockCount\":" + String((uint32_t)levelSampleBlockCount) + ",";
  json += "\"lastLevelPcmPeak\":" + String((int32_t)lastLevelPcmPeak) + ",";
  json += "\"hasRecording\":" + String(SPIFFS.exists(LATEST_WAV_PATH) ? "true" : "false") + ",";
  json += "\"latestFileName\":\"latest.wav\",";
  json += "\"message\":\"" + jsonEscape(message) + "\"";
  json += "}";
  return json;
}

void sendJson(int statusCode, const String &message) {
  server.sendHeader("Cache-Control", "no-store");
  server.send(statusCode, "application/json", buildStatusJson(message));
}

void recordingTask(void *parameter) {
  (void)parameter;

  File file = SPIFFS.open(LATEST_WAV_PATH, FILE_APPEND);
  if (!file) {
    recordingActive = false;
    recordingRequested = false;
    statusMessage = "failed to append wav data";
    lastError = "spiffs_append_failed";
    recordingTaskHandle = nullptr;
    vTaskDelete(nullptr);
    return;
  }

  int32_t rawSamples[RAW_SAMPLES_PER_READ];
  int16_t pcmSamples[RAW_SAMPLES_PER_READ];

  while (recordingRequested) {
    if (millis() - recordingStartMs >= recordingLimitMs) {
      statusMessage = "max duration reached";
      recordingRequested = false;
      break;
    }

    size_t bytesRead = 0;
    if (!readI2sSamples(
            rawSamples,
            sizeof(rawSamples),
            &bytesRead,
            pdMS_TO_TICKS(250))) {
      continue;
    }

    const size_t samplesRead = convertRawToPcm(rawSamples, bytesRead, pcmSamples);
    updateCurrentLevel(pcmSamples, samplesRead);
    for (size_t i = 0; i < samplesRead; ++i) {
      const int32_t shifted = pcmSamples[i];
      const int32_t magnitude = shifted < 0 ? -shifted : shifted;
      if (magnitude > peakPcm) peakPcm = magnitude;
    }

    const size_t bytesToWrite = samplesRead * sizeof(int16_t);
    const size_t written = file.write((const uint8_t *)pcmSamples, bytesToWrite);
    wavDataBytes += written;

    if (written != bytesToWrite) {
      statusMessage = "storage write failed";
      lastError = "spiffs_write_failed";
      recordingRequested = false;
      break;
    }
  }

  const uint32_t durationMs = (uint32_t)(millis() - recordingStartMs);
  file.flush();
  file.close();
  finalizeRecordingFile();

  lastRecordingDurationMs = durationMs;
  recordingActive = false;
  recordingRequested = false;
  if (statusMessage == "recording") statusMessage = "recording stopped";
  recordingTaskHandle = nullptr;
  vTaskDelete(nullptr);
}

void levelMonitorTask(void *parameter) {
  (void)parameter;

  int32_t rawSamples[RAW_SAMPLES_PER_READ];
  int16_t pcmSamples[RAW_SAMPLES_PER_READ];

  while (true) {
    if (!i2sReady || recordingActive) {
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }

    size_t bytesRead = 0;
    if (!readI2sSamples(
            rawSamples,
            sizeof(rawSamples),
            &bytesRead,
            pdMS_TO_TICKS(60))) {
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }

    // A recording may have started while this read was in progress. In that
    // case the recording task owns the I2S stream and this block is discarded.
    if (!recordingActive) {
      const size_t samplesRead = convertRawToPcm(rawSamples, bytesRead, pcmSamples);
      updateCurrentLevel(pcmSamples, samplesRead);
    }
  }
}

bool startLevelMonitor() {
  if (!i2sReady || levelMonitorTaskHandle != nullptr) return i2sReady;

  const BaseType_t taskResult = xTaskCreatePinnedToCore(
      levelMonitorTask,
      "i2s_level_monitor",
      4096,
      nullptr,
      1,
      &levelMonitorTaskHandle,
      0);
  if (taskResult != pdPASS) {
    levelMonitorTaskHandle = nullptr;
    Serial.println("I2S level monitor task creation failed");
    return false;
  }
  return true;
}

bool startRecording(uint32_t requestedDurationMs = MAX_RECORDING_SECONDS * 1000UL) {
  if (recordingActive || recordingTaskHandle != nullptr) {
    statusMessage = "already recording";
    return false;
  }
  if (WiFi.status() != WL_CONNECTED) {
    statusMessage = "wifi not connected";
    return false;
  }
  if (!i2sReady) {
    statusMessage = "i2s is not ready";
    lastError = "i2s_not_ready";
    return false;
  }
  if (!prepareRecordingFile()) return false;

  statusMessage = "recording";
  lastError = "";
  recordingStartMs = millis();
  recordingLimitMs = requestedDurationMs == 0
      ? MAX_RECORDING_SECONDS * 1000UL
      : min(requestedDurationMs, MAX_RECORDING_SECONDS * 1000UL);
  recordingRequested = true;
  recordingActive = true;

  BaseType_t taskResult = xTaskCreatePinnedToCore(
      recordingTask,
      "i2s_recording",
      8192,
      nullptr,
      1,
      &recordingTaskHandle,
      1);

  if (taskResult != pdPASS) {
    recordingRequested = false;
    recordingActive = false;
    recordingTaskHandle = nullptr;
    statusMessage = "failed to create recording task";
    lastError = "task_create_failed";
    return false;
  }
  return true;
}

void stopRecording() {
  if (!recordingActive && recordingTaskHandle == nullptr) {
    statusMessage = "already stopped";
    return;
  }

  recordingRequested = false;
  const unsigned long waitStart = millis();
  while (recordingTaskHandle != nullptr && millis() - waitStart < 4000) {
    delay(20);
  }

  recordingActive = false;
  if (statusMessage == "recording") statusMessage = "recording stopped";
}

void handleRoot() {
  server.send(200, "text/plain", "KNOK ESP32-S3 INMP441 recorder is running");
}

void handleStatus() {
  sendJson(200, statusMessage);
}

void handleStart() {
  uint32_t requestedDurationMs = MAX_RECORDING_SECONDS * 1000UL;
  if (server.hasArg("durationMs")) {
    const long parsedDurationMs = server.arg("durationMs").toInt();
    if (parsedDurationMs > 0) {
      requestedDurationMs = (uint32_t)parsedDurationMs;
    }
  }

  if (startRecording(requestedDurationMs)) {
    sendJson(200, "recording started");
  } else {
    sendJson(409, statusMessage);
  }
}

void handleStop() {
  stopRecording();
  sendJson(200, statusMessage);
}

void handleDownloadLatest() {
  if (!spiffsReady || !SPIFFS.exists(LATEST_WAV_PATH)) {
    server.send(404, "text/plain", "No recording available");
    return;
  }

  File file = SPIFFS.open(LATEST_WAV_PATH, FILE_READ);
  if (!file) {
    server.send(500, "text/plain", "Failed to open recording");
    return;
  }

  server.sendHeader("Content-Disposition", "attachment; filename=latest.wav");
  server.sendHeader("Cache-Control", "no-store");
  server.streamFile(file, "audio/wav");
  file.close();
}

bool setupI2S() {
  i2s_config_t i2sConfig = {};
  i2sConfig.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  i2sConfig.sample_rate = SAMPLE_RATE;
  i2sConfig.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
  i2sConfig.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  i2sConfig.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  i2sConfig.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  i2sConfig.dma_buf_count = 8;
  i2sConfig.dma_buf_len = 256;
  i2sConfig.use_apll = false;
  i2sConfig.tx_desc_auto_clear = false;
  i2sConfig.fixed_mclk = 0;

  if (i2s_driver_install(I2S_PORT, &i2sConfig, 0, nullptr) != ESP_OK) {
    Serial.println("I2S driver install failed");
    return false;
  }

  i2s_pin_config_t pinConfig = {};
  pinConfig.bck_io_num = I2S_SCK_PIN;
  pinConfig.ws_io_num = I2S_WS_PIN;
  pinConfig.data_out_num = I2S_PIN_NO_CHANGE;
  pinConfig.data_in_num = I2S_SD_PIN;

  if (i2s_set_pin(I2S_PORT, &pinConfig) != ESP_OK) {
    Serial.println("I2S pin setup failed");
    return false;
  }

  i2sReadMutex = xSemaphoreCreateMutex();
  if (i2sReadMutex == nullptr) {
    Serial.println("I2S read mutex creation failed");
    return false;
  }

  i2s_zero_dma_buffer(I2S_PORT);
  return true;
}

void startMdnsIfNeeded() {
  if (mdnsReady || WiFi.status() != WL_CONNECTED) return;

  if (MDNS.begin(MDNS_HOSTNAME)) {
    MDNS.addService("http", "tcp", 80);
    mdnsReady = true;
    Serial.printf("mDNS ready: http://%s.local\n", MDNS_HOSTNAME);
  } else {
    Serial.println("mDNS start failed; use the IP shown below");
  }
}

void setupWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setHostname(MDNS_HOSTNAME);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to Wi-Fi");
  const unsigned long startMs = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startMs < 20000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  lastWiFiStatus = WiFi.status();
  if (lastWiFiStatus == WL_CONNECTED) {
    Serial.print("Wi-Fi connected. IP: ");
    Serial.println(WiFi.localIP());
    statusMessage = "wifi connected";
    startMdnsIfNeeded();
  } else {
    Serial.println("Wi-Fi connection failed. Check SSID/password.");
    statusMessage = "wifi connect failed";
  }
}

void maintainWiFi() {
  const wl_status_t currentStatus = WiFi.status();

  if (currentStatus != lastWiFiStatus) {
    if (currentStatus == WL_CONNECTED) {
      statusMessage = "wifi connected";
      lastError = "";
      Serial.print("Wi-Fi connected/recovered. IP: ");
      Serial.println(WiFi.localIP());
      startMdnsIfNeeded();
    } else if (lastWiFiStatus == WL_CONNECTED) {
      statusMessage = "wifi disconnected";
      Serial.printf("Wi-Fi disconnected. status=%d\n", currentStatus);
    }
    lastWiFiStatus = currentStatus;
  }

  if (currentStatus != WL_CONNECTED && millis() - lastWiFiRetryMs >= 10000) {
    lastWiFiRetryMs = millis();
    Serial.println("Retrying Wi-Fi connection...");
    WiFi.reconnect();
  }
}

void setupServer() {
  server.on("/", HTTP_GET, handleRoot);
  server.on("/api/status", HTTP_GET, handleStatus);
  server.on("/api/record/start", HTTP_POST, handleStart);
  server.on("/api/record/stop", HTTP_POST, handleStop);
  server.on("/api/recording/latest", HTTP_GET, handleDownloadLatest);
  server.begin();
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println();
  Serial.println("KNOK ESP32-S3 INMP441 Wi-Fi recorder booting");

  spiffsReady = SPIFFS.begin(true);
  if (!spiffsReady) {
    Serial.println("SPIFFS mount failed");
    statusMessage = "spiffs mount failed";
  }

  i2sReady = setupI2S();
  if (!i2sReady) statusMessage = "i2s setup failed";
  if (i2sReady && !startLevelMonitor()) {
    statusMessage = "i2s level monitor setup failed";
  }

  setupWiFi();
  setupServer();

  Serial.println("HTTP endpoints:");
  Serial.println("POST /api/record/start");
  Serial.println("POST /api/record/stop");
  Serial.println("GET  /api/status");
  Serial.println("GET  /api/recording/latest");
}

void loop() {
  server.handleClient();
  maintainWiFi();

  static unsigned long lastHeartbeat = 0;
  if (millis() - lastHeartbeat >= 3000) {
    lastHeartbeat = millis();
    Serial.printf(
        "heartbeat: wifi=%d ip=%s recording=%s bytes=%u message=%s\n",
        WiFi.status(),
        WiFi.localIP().toString().c_str(),
        recordingActive ? "yes" : "no",
        (unsigned int)latestFileSize(),
        statusMessage.c_str());
    Serial.printf(
        "level: %.1f dB blocks=%u pcmPeak=%ld\n",
        (double)currentLevelDb,
        (unsigned int)levelSampleBlockCount,
        (long)lastLevelPcmPeak);
  }
}
