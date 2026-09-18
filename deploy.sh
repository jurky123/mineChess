#!/usr/bin/env bash
# 构建并部署 MineChess（插件 jar + 资源包）到 Paper 服务器
# 用法: ./deploy.sh [服务器根目录]   （默认 /home/ubuntu/minecraft）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERVER="${1:-/home/ubuntu/minecraft}"

if [ ! -d "$SERVER/plugins" ]; then
    echo "找不到服务器目录: $SERVER（应包含 plugins/）" >&2
    exit 1
fi

echo "==> 构建 MineChess（含单测与 chesslib 打包）"
(cd "$ROOT" && mvn -q -B package)

echo "==> 生成资源包"
python3 "$ROOT/pack/gen_pack.py"

echo "==> 部署到 $SERVER"
rm -f "$SERVER/plugins/"MineChess-*.jar
cp "$ROOT"/target/MineChess-*.jar "$SERVER/plugins/"

mkdir -p "$SERVER/plugins/PackHost/packs"
cp "$ROOT/pack/out/minechess.zip" "$SERVER/plugins/PackHost/packs/"

echo
echo "部署完成："
echo "  $SERVER/plugins/$(basename "$(ls -t "$ROOT"/target/MineChess-*.jar | head -n1)")"
echo "  $SERVER/plugins/PackHost/packs/minechess.zip"
echo
echo "下一步："
echo "  1. 重载材质包：游戏内执行 /packhost reload（或让玩家重进服务器）"
echo "  2. 重载插件：/chess reload；首次使用先站在棋盘中心执行 /chess setarena"
echo "  3. /chess challenge <玩家> [时限] 开始对局"
