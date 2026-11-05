#!/usr/bin/env bash
# Builds the folder unpacker payload (payload-src/unpack) for the PS5 and puts it in the app, after its tests pass on
# Linux. The SDK is a fixed release, checked against its known checksum.
set -euo pipefail
SDK_VERSION=v0.45
SDK_SHA256=5fac64093d0e3e69f2584ce7935f24f703f622d9681f1495c85b9f00d1291a2c
work="${RUNNER_TEMP:-/tmp}/ps5sdk"
mkdir -p "$work"
curl -sSL -o "$work/sdk.zip" "https://github.com/ps5-payload-dev/sdk/releases/download/$SDK_VERSION/ps5-payload-sdk.zip"
echo "$SDK_SHA256  $work/sdk.zip" | sha256sum -c -
unzip -qo "$work/sdk.zip" -d "$work"
command -v clang-18 > /dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq clang-18 lld-18; }
make -C payload-src/unpack test
PS5_PAYLOAD_SDK="$work/ps5-payload-sdk" make -C payload-src/unpack crimson-unpack.elf
mkdir -p app/src/main/assets
cp payload-src/unpack/crimson-unpack.elf app/src/main/assets/crimson-unpack.elf
ls -l app/src/main/assets/crimson-unpack.elf
