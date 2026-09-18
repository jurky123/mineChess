#!/usr/bin/env bash
# 一键构建 MineChess 插件 + 资源包，产物输出到 out/
set -e
cd "$(dirname "$0")"

# 聚合仓库内：跟随相邻 mineUI 的版本并先发布到本地 Maven（独立构建时用 pom 默认版本）
MINEUI_OPT=""
MINEUI_PROPS="../mineUI/gradle.properties"
if [ -f "$MINEUI_PROPS" ]; then
    MINEUI_VERSION="$(grep -E '^mineui_version=' "$MINEUI_PROPS" | cut -d= -f2 | tr -d '[:space:]')"
    if [ -n "$MINEUI_VERSION" ]; then
        echo "==> 发布 MineUI $MINEUI_VERSION 到本地 Maven"
        (cd ../mineUI && ./gradlew -q :mineui-paper:publishToMavenLocal)
        MINEUI_OPT="-Dmineui_version=$MINEUI_VERSION"
    fi
fi

mvn -q -B $MINEUI_OPT package
python3 pack/gen_pack.py

mkdir -p out
cp target/MineChess-*.jar out/
cp pack/out/minechess.zip out/

echo "构建完成，产物："
ls -la out/
