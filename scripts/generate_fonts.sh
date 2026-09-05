#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
symbols='建国路东三环中朝阳门外大街进入岛，第二出口已到达目的地前方拥堵缓行畅通偏航重新规划断网恢复直左右转掉头米公里分钟高速辅主道匝隧桥请保持车驶向终点当前路线热点在线离线获取定位无信号等待距下一动作打开手机应用正在建立连接成功选择'

cd "${repo_dir}"
npx --yes lv_font_conv \
  --font third_party/lvgl/scripts/built_in_font/SourceHanSansSC-Normal.otf \
  --size 16 \
  --bpp 4 \
  --format lvgl \
  --no-compress \
  --symbols "${symbols}" \
  -o shared/nav_ui/assets/moto_font_nav_16.c
