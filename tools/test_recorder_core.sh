#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .superpowers/test-build
clang++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  esp32/tests/recorder_core_test.cpp -o .superpowers/test-build/recorder_core_test
.superpowers/test-build/recorder_core_test

clang++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  esp32/tests/recorder_coordination_test.cpp -o .superpowers/test-build/recorder_coordination_test
.superpowers/test-build/recorder_coordination_test
