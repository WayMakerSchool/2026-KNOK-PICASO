#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
toolroot="${KNOK_TOOLROOT:-/Users/yesung/Documents/Codex/Projects/KNOK_hardware_tools}"
mkdir -p .superpowers/firmware-build
"$toolroot/bin/arduino-cli" --config-file "$toolroot/arduino-cli.yaml" compile \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,FlashSize=4M,PartitionScheme=default,PSRAM=enabled' \
  --build-path "$PWD/.superpowers/firmware-build" esp32/KNOK_ESP32_Recorder
# This builds ONLY. There is deliberately no upload command here.
