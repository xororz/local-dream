#!/usr/bin/env bash
set -eu

patch_dir="$(cd "$(dirname "$0")" && pwd)"
dependency_dir="$patch_dir/../3rdparty"

# Keep fixes to pinned dependencies reproducible without maintaining forks.
# A reverse check makes repeat builds work with an already patched checkout.
apply_patch() {
  local dependency="$dependency_dir/$1"
  local patch="$patch_dir/$2"
  if git -C "$dependency" apply --unidiff-zero --reverse --check "$patch" >/dev/null 2>&1; then
    return
  fi
  git -C "$dependency" apply --unidiff-zero "$patch"
}

apply_patch MNN mnn-cmake.patch
apply_patch tokenizers-cpp tokenizers-build.patch
apply_patch tokenizers-cpp/msgpack msgpack-cmake.patch
apply_patch tokenizers-cpp/sentencepiece sentencepiece-cmake.patch
apply_patch xtensor xtensor-template-args.patch
