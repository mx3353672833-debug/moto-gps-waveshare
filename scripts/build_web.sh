#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${repo_dir}/build/web"

emcmake cmake \
  -S "${repo_dir}" \
  -B "${build_dir}" \
  -DMOTO_BUILD_WEB=ON \
  -DMOTO_BUILD_TESTS=OFF \
  -DCMAKE_BUILD_TYPE=Release

cmake --build "${build_dir}" --parallel

echo "Web runtime: ${repo_dir}/platforms/web/shell/runtime"
echo "Preview:      python3 -m http.server 4173 --directory \"${repo_dir}\""
