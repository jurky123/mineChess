#!/usr/bin/env bash
# 一键构建 MineChess 插件 + 资源包，产物输出到 out/
set -e
cd "$(dirname "$0")"

mvn -q -B package
python3 pack/gen_pack.py

mkdir -p out
cp target/MineChess-*.jar out/
cp pack/out/minechess.zip out/

echo "构建完成，产物："
ls -la out/
