#!/usr/bin/env bash
# Build the portable inference engine for Local Dream Image (Debian/Termux).
# The engine is stable-diffusion.cpp, the upstream project local-webdream
# forks for its on-device DiT work. CPU-only; no pip, no npm, no APK.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${SDCPP_REF:-master}"
SRC="${SDCPP_SRC:-$HOME/.local/src/stable-diffusion.cpp}"
PREFIX="$HOME/.local/opt/localdream-image"
TARGET="$ROOT/bin/sd-server"

echo "== Local Dream Image engine installer =="
command -v git >/dev/null && command -v cmake >/dev/null &&
command -v ninja >/dev/null && command -v g++ >/dev/null || {
  echo "Install tools: sudo apt install -y git cmake ninja-build build-essential" >&2
  exit 1
}
if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$(dirname "$SRC")"
  git clone --recursive https://github.com/leejet/stable-diffusion.cpp.git "$SRC"
fi
git -C "$SRC" fetch --all --tags
git -C "$SRC" checkout "$REF"
git -C "$SRC" submodule update --init --recursive
mkdir -p "$SRC/build-cpu"
cmake -S "$SRC" -B "$SRC/build-cpu" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DSD_WEBP=OFF -DSD_WEBM=OFF
cmake --build "$SRC/build-cpu" --config Release --parallel 2
mkdir -p "$ROOT/bin" "$PREFIX/bin"
cp "$SRC/build-cpu/bin/sd-server" "$PREFIX/bin/sd-server"
cp "$PREFIX/bin/sd-server" "$TARGET"
chmod +x "$TARGET" "$PREFIX/bin/sd-server"
echo "Installed: $TARGET ($(git -C "$SRC" rev-parse HEAD))"
