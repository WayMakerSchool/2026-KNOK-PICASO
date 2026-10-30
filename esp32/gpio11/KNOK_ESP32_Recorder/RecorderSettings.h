#pragma once
// Running-board GPIO matrix readback on 2026-10-22: BCLK4=26, WS5=27,
// I2S0 RX data selector=GPIO6; existing microphone capture is active.
constexpr bool KNOK_WIRING_CONFIRMED = true;
constexpr int I2S_BCLK_PIN = 4;
constexpr int I2S_WS_PIN = 5;
constexpr int I2S_DATA_PIN = 6;
constexpr int TILT_PIN = 8;
constexpr int LED_PIN = 7;
constexpr int LED_COUNT = 12;
constexpr bool KNOK_SERIAL_LOGGING = false;
// Piezo disc with the team's protection circuit on GPIO11 (ADC2_CH0, board label "11").
// ADC2 is shared with Wi-Fi through a hardware arbiter, so an occasional sample can
// be skipped; two reads per millisecond keep the peak. Raw millivolts, uncalibrated.
// Piezo values are recorded for analysis only; they do not trigger recording.
constexpr bool PIEZO_ENABLED = true;
constexpr int PIEZO_PIN = 11;
constexpr uint32_t PIEZO_HIT_THRESHOLD_MV = 100;
