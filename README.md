# MineChess

运行在 Paper 26.2 上的国际象棋插件：两个人坐在真实的 3D 棋盘两边对弈，也支持 AI 对手、观战席和可选的 2D 棋盘界面（MineUI 客户端）。

- 完整国际象棋规则：易位、吃过路兵、升变、将军/将死/逼和，50 回合 / 三次重复 / 子力不足判和
- 3D 实体棋盘：实体棋子、双方座位、棋钟、上一手/选中/将军高亮与动画
- 2D 棋盘（MineUI）：固定视角鼠标点格子，两侧带世界聊天与对局聊天，升变直接在棋盘上选
- 观战：每桌 4 个观战席，房间/大厅/命令都能加入，开局后自动坐到棋盘两侧
- 房间流程：大厅、房间列表、邀请、准备、切换执色、添加 AI，兼容 /chess challenge 快速开局
- 资源包由 PackHost 托管：3D 棋盘与棋子模型 + 2D 像素棋子/棋盘贴图

---

## 玩法流程

1. `/chess create [时限]` 开房间，朋友在 `/chess rooms` 或大厅界面加入，也可以邀请在线玩家或添加 AI
2. 对手点「准备」后，房主选执色并「开始游戏」；也可以 `/chess challenge <玩家> [时限]` 直接挑战
3. 开局后双方自动坐到棋盘两侧的座位上：**右键打开对局面板**，面板里可以开 2D 棋盘
4. 2D 棋盘上点自己的棋子会显示选中框与可落点，再点目标格完成落子；3D 棋盘同步显示
5. 升变时 2D 棋盘底部会出现「选择升变」的四个棋子按钮（30 秒未选自动升后）
6. 认输 / 提和 / 和棋申请都在面板里，或使用命令

## 规则与终局

- 采用 [chesslib](https://github.com/bhlangonijr/chesslib) 作为规则内核（打包进插件，不依赖外部下载）
- 终局：将死、逼和、子力不足、五次重复、75 回合自动判和
- 可申请的和棋：三次重复（`/chess claimdraw threefold`）、50 回合（`/chess claimdraw fifty`），也支持双方和棋协议
- 棋钟：轮到谁走谁计时，超时判负；断线保留 60 秒，超时判负
- 时限预设：`casual`（无限制）/ `3+2` / `5+0` / `10+0` / `15+10`，房间内可随时切换

## 3D 棋盘

- 棋盘、棋子、棋钟、结果都是实体展示，棋盘位置由竞技场坐标决定（`/chess setarena` 现场设置）
- 座位为隐形坐姿：入场自动坐下并锁定俯视视角，可以自由转头但不能走动
- 选中 / 可吃 / 将军 / 上一手都有对应高亮；棋子被选中会轻微抬起
- **右键**打开对局面板；**左键**可以点 3D 棋子/棋盘实体（需要准星对准）
- 支持管理员滚轮实时调参：`/chess tune <seat-y|seat-distance|board-height|board-size|piece-scale|clock-height|model-yaw>`

## 2D 棋盘（MineUI 客户端）

- 棋子/棋盘/高亮全部是像素贴图，按当前执色自动选择正反视角
- 两侧聊天栏：左边世界聊天（全服消息回流显示），右边对局聊天（仅棋手与观战者可见）
- 输入框回车发送，发送后自动清空，可连续发言
- 升变选择按钮、选中高亮、上一步高亮、将军提示都在棋盘上
- 没有 MineUI 客户端时自动回退到原版箱子面板（`ui.vanilla-gui`）

## 观战

- 每个房间有 4 个观战席：房间界面显示席位、房间列表每行有「观战」按钮
- 对局开始后，大厅的「进行中对局」列表可以中途加入观战
- 观战者会被传送到棋盘左右两侧的座位上（`board.spectator-distance` / `board.spectator-spread` 可调）
- 观战者能收到对局内消息、打开对局面板与 2D 棋盘（只读），`/chess leave` 退出观战

## AI 对手

- 房间内点「添加 AI 对手」即可，AI 自动准备；当前内置随机落子 AI，用来陪练
- 可插拔：实现 `com.minechess.ai.ChessBot` 后，把 `ai.bot` 配置成完整类名即可替换（例如接引擎或大模型）

## 命令

| 命令 | 说明 |
| --- | --- |
| `/chess` | 打开菜单：无对局时是房间/大厅，对局中默认开 2D 棋盘 |
| `/chess create [时限]` / `join [房间号]` / `rooms` | 创建 / 加入 / 房间列表 |
| `/chess ready` / `start` / `color` / `invite <玩家>` / `bot` | 准备 / 开始 / 切换执色 / 邀请 / 加 AI |
| `/chess challenge <玩家> [时限]` / `accept` / `decline` | 直接挑战与接受/拒绝 |
| `/chess spectate <玩家>` | 观战某位玩家正在进行的对局 |
| `/chess board` / `panel [chest]` | 打开 2D 棋盘 / 对局面板（chest 强制原版菜单） |
| `/chess move e2e4` / `resign` / `draw` | 命令落子 / 认输 / 提和 |
| `/chess claimdraw <fifty\|threefold>` | 申请 50 回合 / 三次重复和棋 |
| `/chess history` / `fen` / `status` / `help` | 棋谱 / FEN / 状态 / 帮助 |
| `/chess leave` | 离开房间、对局或观战 |
| `/chess setarena [序号]`（OP） | 站在棋盘中心面向白方设置竞技场 |
| `/chess preview`（OP） | 在当前位置摆一套棋盘用于调参，再次执行移除 |
| `/chess seat [数值]` / `tune <参数>`（OP） | 调座位高度 / 滚轮调视觉参数 |
| `/chess reload`（OP） | 重载配置 |

## 配置（`plugins/MineChess/config.yml`）

- `board.*`：格子边长、棋盘高度、棋子缩放、座位距离、观战席距离/间距、棋钟高度、选中抬起高度
- `seat.y-offset`：人物坐姿高度（越高越俯视）
- `game.*`：断线保留、座位锁定半径、升变超时、结束后保留时间、默认时限
- `ai.bot` / `ai.move-delay`：AI 实现与落子停顿
- `ui.vanilla-gui` / `ui.board-textures`：原版面板兜底 / 2D 棋盘贴图模式
- `visual.*`：动画、粒子、音效、模型 Y 朝向微调

## 构建与部署

```bash
./build.sh                 # 构建插件 jar + 资源包（产物在 out/）
./deploy.sh [服务器目录]    # 构建并部署到 Paper 服务器（默认 /home/ubuntu/minecraft）
```

部署后：重载材质包（`/packhost reload` 或让玩家重进）、`/chess reload`；首次使用先执行 `/chess setarena`。

依赖 [mineUI](../mineUI) 的业务 API 编译（`mineui_version` 与本仓库 `pom.xml` 对应）：

```bash
cd ../mineUI && ./gradlew :mineui-paper:publishToMavenLocal
cd ../mineChess && mvn -B package
```

单元测试：`mvn -B test`（规则、棋钟、房间/观战、AI、坐标变换等）。

## 资源包

- 3D：棋盘与 12 个棋子模型、落点/高亮贴图，由 `pack/gen_pack.py` 程序生成
- 2D：棋盘格、整板视角、像素棋子与选中/可吃/将军变体
- 26.2 资源包格式为 **88.0**（`min_format: [88, 0]` / `max_format: 88`）
- 2D 棋子素材来自 [Lucas312 - Pixel Chess Pieces](https://opengameart.org/content/pixel-chess-pieces)（CC-BY 3.0），
  署名随包分发（`pack/assets2d/CREDITS.txt`），构建时按 42×42 原生分辨率入包

## 目录结构

```text
mineChess/
├── src/main/java/com/minechess/
│   ├── MatchManager.java        # 房间/对局/观战/落子/计时总控
│   ├── ChessCommand.java        # /chess 命令
│   ├── ChessListener.java       # 点击、聊天、断线等事件
│   ├── match/                   # ChessMatch（规则状态）、ChessRoom（房间/观战席）、棋钟
│   ├── view/                    # ChessTableView 3D 棋盘、ChessItems 模型
│   ├── arena/                   # 竞技场与棋盘坐标变换
│   ├── ai/                      # 可插拔 AI（ChessBot/RandomBot/BotService）
│   ├── integration/MineUiControl.java   # MineUI 页面（大厅/房间/面板/2D 棋盘/聊天）
│   └── ui/                      # 原版箱子界面兜底
├── src/main/resources/assets/minechess/ui/chess/*.json  # MineUI 页面定义
├── pack/                        # 资源包生成（gen_pack.py + assets2d 第三方素材）
├── build.sh / deploy.sh
└── pom.xml
```

## 依赖

- Paper 26.2（Java 25）
- MineUI 0.7.3（可选；装 mod 客户端的玩家使用 2D 棋盘与新界面，其他玩家走原版菜单）
- chesslib 1.3.7（JitPack，已 shade 进插件 jar）
