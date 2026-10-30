#pragma once
// GPIO2 product: the user supplied piezo GPIO2. The other pins below retain
// the GPIO11 product configuration; verify the second board wiring before upload.
constexpr bool KNOK_WIRING_CONFIRMED = true;
constexpr int I2S_BCLK_PIN = 4;
constexpr int I2S_WS_PIN = 5;
constexpr int I2S_DATA_PIN = 6;
constexpr int TILT_PIN = 8;
constexpr int LED_PIN = 7;
constexpr int LED_COUNT = 12;
constexpr bool KNOK_SERIAL_LOGGING = false;
// Piezo with external input protection on GPIO2 (ADC1_CH1, board label "2").
// This is a software setting, not verification of the protection circuit.
// Sensor values are uncalibrated; real hardware testing is still required.
// Piezo values are recorded for analysis only; they do not trigger recording.
constexpr bool PIEZO_ENABLED = true;
constexpr int PIEZO_PIN = 2;
constexpr uint32_t PIEZO_HIT_THRESHOLD_MV = 100;

static_assert(PIEZO_PIN != I2S_BCLK_PIN && PIEZO_PIN != I2S_WS_PIN &&
              PIEZO_PIN != I2S_DATA_PIN && PIEZO_PIN != TILT_PIN && PIEZO_PIN != LED_PIN,
              "Piezo pin conflicts with an existing sensor or LED pin");
