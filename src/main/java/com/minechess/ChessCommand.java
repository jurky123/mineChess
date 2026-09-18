package com.minechess;

import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.minechess.chess.ChessRules;
import com.minechess.chess.DrawClaim;
import com.minechess.chess.TimeControl;
import com.minechess.match.ChessMatch;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class ChessCommand implements CommandExecutor, TabCompleter {

    private final MineChessPlugin plugin;

    public ChessCommand(MineChessPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadAll();
                sender.sendMessage("MineChess 配置已重载");
                return true;
            }
            sender.sendMessage("该命令只能由玩家执行");
            return true;
        }
        ChessMatch match = plugin.matches().matchOf(player.getUniqueId());
        ChessMatch watched = match != null ? match : plugin.matches().spectatorMatchOf(player.getUniqueId());
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            // 对局中默认打开 2D 棋盘；/chess panel 打开控制面板（chest 强制原版菜单）
            case "" -> openCurrent(player, watched, args, false);
            case "panel", "menu" -> openCurrent(player, watched, args, true);
            case "status" -> status(player, watched);
            case "help", "rules" -> help(player);
            case "create" -> createRoom(player, args);
            case "join" -> joinRoom(player, args);
            case "rooms", "list" -> plugin.ui().openRoomList(player);
            case "ready" -> plugin.matches().toggleReady(player);
            case "start" -> plugin.matches().startRoom(player);
            case "challenge" -> challenge(player, args);
            case "accept" -> plugin.matches().accept(player);
            case "decline" -> plugin.matches().decline(player);
            case "move" -> move(player, args);
            case "resign", "投降", "认输" -> plugin.matches().resign(player);
            case "draw" -> plugin.matches().offerDraw(player);
            case "acceptdraw" -> plugin.matches().acceptDraw(player);
            case "declinedraw" -> plugin.matches().declineDraw(player);
            case "claimdraw" -> claimDraw(player, args);
            case "leave" -> {
                if (match != null) plugin.matches().leave(player);
                else if (watched != null) plugin.matches().leaveSpectate(player);
                else plugin.matches().leaveRoom(player);
            }
            case "board" -> board(player, watched);
            case "history" -> history(player, watched);
            case "fen" -> fen(player, watched);
            case "spectate", "watch" -> spectate(player, args);
            case "color" -> plugin.matches().cycleColor(player);
            case "invite" -> invite(player, args);
            case "bot", "ai" -> {
                if (!plugin.matches().addBot(player)) {
                    MineChessPlugin.msg(player, "<gray>需要在你是房主且房间没有对手时使用");
                }
            }
            case "setarena" -> setArena(player, args);
            case "preview" -> {
                if (!player.hasPermission("minechess.admin")) {
                    MineChessPlugin.msg(player, "<red>没有权限");
                    return true;
                }
                plugin.matches().togglePreview(player);
            }
            case "seat" -> seat(player, args);
            case "tune" -> tune(player, args);
            case "reload" -> {
                if (!player.hasPermission("minechess.admin")) {
                    MineChessPlugin.msg(player, "<red>没有权限");
                    return true;
                }
                plugin.reloadAll();
                MineChessPlugin.msg(player, "<green>MineChess 配置已重载");
            }
            default -> help(player);
        }
        return true;
    }

    private void status(Player player, ChessMatch match) {
        if (match == null) {
            help(player);
            return;
        }
        MineChessPlugin.msg(player, "<gold>对局 <white>" + match.timeControl().describe()
                + " <gray>| 状态：" + (match.phase() == ChessMatch.Phase.PLAYING ? "<green>进行中" : "<red>已结束"));
        MineChessPlugin.msg(player, "<white>" + plugin.playerName(match.whitePlayer()) + " <gray>vs <white>"
                + plugin.playerName(match.blackPlayer()));
        MineChessPlugin.msg(player, "<gray>白方：<white>" + match.clock(Side.WHITE).format()
                + " <gray>| 黑方：<white>" + match.clock(Side.BLACK).format()
                + " <gray>| 轮到：<white>" + (match.turn() == Side.WHITE ? "白方" : "黑方"));
        if (match.result() != null) MineChessPlugin.msg(player, "<gold>结果：<white>" + match.result().describe());
        if (match.drawOffer() != null) {
            MineChessPlugin.msg(player, "<yellow>对方提议和棋：<white>/chess acceptdraw <gray>或 <white>/chess declinedraw");
        }
        MineChessPlugin.msg(player, "<gray>用 <white>/chess history <gray>看棋谱，<white>/chess move e2e4 <gray>可用命令落子");
    }

    private void openCurrent(Player player, ChessMatch match, String[] args, boolean panelWanted) {
        boolean forceChest = args.length >= 2
                && (args[1].equalsIgnoreCase("chest") || args[1].equalsIgnoreCase("原版"));
        if (match != null) {
            if (forceChest) plugin.ui().openMenuMatch(player, match);
            else if (panelWanted) plugin.ui().openPanel(player, match);
            else plugin.ui().openMatch(player, match);
            return;
        }
        var room = plugin.matches().roomOf(player.getUniqueId());
        if (room != null) plugin.ui().openRoom(player, room);
        else plugin.ui().openLobby(player);
    }

    private void help(Player player) {
        MineChessPlugin.msg(player, "<gold>MineChess 命令：");
        MineChessPlugin.msg(player, "<white>/chess <gray>打开菜单：创建房间 / 加入房间 / 对局面板");
        MineChessPlugin.msg(player, "<white>/chess create [时限] <gray>创建房间（casual / 3+2 / 5+0 / 10+0 / 15+10）");
        MineChessPlugin.msg(player, "<white>/chess join [房间号] <gray>加入房间 · <white>/chess ready <gray>准备 · <white>/chess start <gray>房主开始");
        MineChessPlugin.msg(player, "<white>/chess color <gray>房主切换执白/执黑 · <white>/chess invite <玩家> <gray>邀请进房间");
        MineChessPlugin.msg(player, "<white>/chess bot <gray>添加 AI 对手（当前为随机落子，可插拔后端）");
        MineChessPlugin.msg(player, "<white>/chess leave <gray>离开房间/对局 · <white>/chess rooms <gray>房间列表");
        MineChessPlugin.msg(player, "<white>/chess challenge <玩家> [时限] <gray>直接挑战，对方接受后立即开局");
        MineChessPlugin.msg(player, "<white>/chess draw <gray>提和 · <white>/chess acceptdraw <gray>同意 · <white>/chess declinedraw <gray>拒绝");
        MineChessPlugin.msg(player, "<white>/chess resign <gray>认输 · <white>/chess move e2e4 <gray>命令落子（升变如 e7e8q）");
        MineChessPlugin.msg(player, "<white>/chess board <gray>2D 棋盘（固定视角鼠标点格子）· <white>/chess history <gray>棋谱");
        MineChessPlugin.msg(player, "<white>/chess fen <gray>当前 FEN · <white>/chess status <gray>对局状态 · <white>/chess panel chest <gray>原版面板");
        MineChessPlugin.msg(player, "<white>/chess spectate <玩家> <gray>观战进行中的对局（房间/大厅也有观战按钮，最多 4 人）");
        MineChessPlugin.msg(player, "<gray>3D 棋盘上 <white>右键 <gray>打开对局面板，<white>左键 <gray>选子/落子；2D 棋盘两侧可聊天");
        if (player.hasPermission("minechess.admin")) {
            MineChessPlugin.msg(player, "<white>/chess setarena [序号] <gray>站在棋盘中心面向白方设置竞技场（OP）");
            MineChessPlugin.msg(player, "<white>/chess preview <gray>在当前位置摆一套棋盘用于调参（OP，再次执行移除）");
            MineChessPlugin.msg(player, "<white>/chess seat [数值] <gray>人物坐姿高度 · <white>/chess tune <gray>滚轮调视觉参数（OP）");
        }
    }

    private void board(Player player, ChessMatch match) {
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在对局中");
            return;
        }
        if (!plugin.ui().openBoard(player, match)) {
            MineChessPlugin.msg(player, "<gray>2D 棋盘需要 MineUI 客户端；可以用 <white>/chess panel <gray>打开原版面板");
        }
    }

    private void spectate(Player player, String[] args) {
        if (args.length < 2) {
            MineChessPlugin.msg(player, "<red>用法：/chess spectate <对局中的玩家>（也可在大厅「进行中对局」里点观战）");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MineChessPlugin.msg(player, "<red>玩家不在线");
            return;
        }
        ChessMatch targetMatch = plugin.matches().matchOf(target.getUniqueId());
        if (targetMatch == null) {
            MineChessPlugin.msg(player, "<red>" + target.getName() + " 不在对局中");
            return;
        }
        plugin.matches().spectate(player, targetMatch);
    }

    private void invite(Player player, String[] args) {
        if (args.length < 2) {
            MineChessPlugin.msg(player, "<red>用法：/chess invite <在线玩家>（也可以直接在房间界面点「邀请玩家」）");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MineChessPlugin.msg(player, "<red>玩家不在线");
            return;
        }
        plugin.matches().inviteToRoom(player, target);
    }

    /** /chess seat [数值]：调整人物坐姿高度（滚轮实时调）。 */
    private void seat(Player player, String[] args) {
        if (!player.hasPermission("minechess.admin")) {
            MineChessPlugin.msg(player, "<red>没有权限");
            return;
        }
        if (args.length < 2) {
            plugin.matches().startTune(player, "seat-y");
            return;
        }
        try {
            plugin.matches().setTune(player, "seat-y", Double.parseDouble(args[1]));
        } catch (NumberFormatException e) {
            MineChessPlugin.msg(player, "<red>数值不合法");
        }
    }

    /** /chess tune [参数] [数值]：滚轮实时调视觉参数。 */
    private void tune(Player player, String[] args) {
        if (!player.hasPermission("minechess.admin")) {
            MineChessPlugin.msg(player, "<red>没有权限");
            return;
        }
        if (args.length < 2) {
            if (plugin.matches().tuning(player)) {
                plugin.matches().stopTune(player);
            } else {
                MineChessPlugin.msg(player, "<gold>可调参数（先用 /chess preview 或对局中调整）：");
                for (String key : plugin.matches().tuneKeys()) {
                    MineChessPlugin.msg(player, "<white>/chess tune " + key + " <gray>当前 "
                            + plugin.getConfig().getDouble(pathOf(key), 0));
                }
                MineChessPlugin.msg(player, "<gray>用法：<white>/chess tune <参数> <gray>进入滚轮调整，再输一次保存退出");
            }
            return;
        }
        if (args.length >= 3) {
            try {
                plugin.matches().setTune(player, args[1], Double.parseDouble(args[2]));
            } catch (NumberFormatException e) {
                MineChessPlugin.msg(player, "<red>数值不合法");
            }
            return;
        }
        plugin.matches().startTune(player, args[1]);
    }

    private String pathOf(String key) {
        return switch (key) {
            case "seat-y" -> "seat.y-offset";
            case "board-height" -> "board.height";
            case "board-size" -> "board.size";
            case "piece-scale" -> "board.piece-scale";
            case "clock-height" -> "board.clock-height";
            case "model-yaw" -> "visual.model-yaw";
            default -> key;
        };
    }

    private void createRoom(Player player, String[] args) {
        TimeControl timeControl;
        if (args.length >= 2) {
            timeControl = TimeControl.parse(args[1]);
            if (timeControl == null) {
                MineChessPlugin.msg(player, "<red>时限不合法，可选：" + TimeControl.presetsText());
                return;
            }
        } else {
            timeControl = TimeControl.parse(plugin.getConfig().getString("game.default-time", "10+0"));
            if (timeControl == null) timeControl = TimeControl.parse("10+0");
        }
        plugin.matches().createRoom(player, timeControl);
    }

    private void joinRoom(Player player, String[] args) {
        if (args.length < 2) {
            plugin.ui().openRoomList(player);
            return;
        }
        String text = args[1].replace("#", "").trim();
        com.minechess.match.ChessRoom target = null;
        for (com.minechess.match.ChessRoom room : plugin.matches().rooms()) {
            if (String.valueOf(room.number()).equals(text)
                    || room.id().toString().startsWith(text.toLowerCase())) {
                target = room;
                break;
            }
        }
        if (target == null) {
            MineChessPlugin.msg(player, "<red>找不到该房间，用 /chess rooms 查看列表");
            return;
        }
        plugin.matches().joinRoom(player, target);
    }

    private void challenge(Player player, String[] args) {
        if (args.length < 2) {
            MineChessPlugin.msg(player, "<red>用法：/chess challenge <玩家> [时限]，时限可选 " + TimeControl.presetsText());
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MineChessPlugin.msg(player, "<red>玩家不在线");
            return;
        }
        TimeControl timeControl;
        if (args.length >= 3) {
            timeControl = TimeControl.parse(args[2]);
            if (timeControl == null) {
                MineChessPlugin.msg(player, "<red>时限不合法，可选：" + TimeControl.presetsText());
                return;
            }
        } else {
            timeControl = TimeControl.parse(plugin.getConfig().getString("game.default-time", "10+0"));
            if (timeControl == null) timeControl = TimeControl.parse("10+0");
        }
        plugin.matches().challenge(player, target, timeControl);
    }

    private void move(Player player, String[] args) {
        if (args.length < 2) {
            MineChessPlugin.msg(player, "<red>用法：/chess move e2e4（升变如 e7e8q = e7e8q）");
            return;
        }
        ChessMatch match = plugin.matches().matchOf(player.getUniqueId());
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在对局中");
            return;
        }
        Side side = match.sideOf(player.getUniqueId());
        String text = args[1].toLowerCase();
        Square from = ChessRules.parseSquare(text.substring(0, Math.min(2, text.length())));
        Square to = text.length() >= 4 ? ChessRules.parseSquare(text.substring(2, 4)) : null;
        if (from == null || to == null) {
            MineChessPlugin.msg(player, "<red>着法格式应为 e2e4（升变如 e7e8q）");
            return;
        }
        PieceType promotion = text.length() >= 5 ? ChessRules.promotionType(text.charAt(4)) : null;
        plugin.matches().move(player, from, to, promotion);
    }

    private void claimDraw(Player player, String[] args) {
        DrawClaim claim = DrawClaim.THREEFOLD_REPETITION;
        if (args.length >= 2) {
            claim = switch (args[1].toLowerCase()) {
                case "fifty", "50", "50move" -> DrawClaim.FIFTY_MOVE_RULE;
                default -> DrawClaim.THREEFOLD_REPETITION;
            };
        }
        plugin.matches().claimDraw(player, claim);
    }

    private void history(Player player, ChessMatch match) {
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在对局中");
            return;
        }
        if (match.moves().isEmpty()) {
            MineChessPlugin.msg(player, "<gray>还没有落子");
            return;
        }
        StringBuilder line = new StringBuilder("<gray>");
        List<String> lines = new ArrayList<>();
        int index = 0;
        for (var record : match.moves()) {
            if (index % 2 == 0) line.append("<white>").append(index / 2 + 1).append(".<gray> ");
            line.append(record.san()).append(' ');
            if (++index % 8 == 0) {
                lines.add(line.toString());
                line = new StringBuilder("<gray>");
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        MineChessPlugin.msg(player, "<gold>棋谱（SAN）：");
        for (String text : lines) MineChessPlugin.msg(player, text);
    }

    private void fen(Player player, ChessMatch match) {
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在对局中");
            return;
        }
        MineChessPlugin.msg(player, "<gray>FEN: <white>" + match.fen());
    }

    private void setArena(Player player, String[] args) {
        if (!player.hasPermission("minechess.admin")) {
            MineChessPlugin.msg(player, "<red>没有权限");
            return;
        }
        int index = 0;
        if (args.length > 1) {
            try {
                index = Math.max(0, Integer.parseInt(args[1]) - 1);
            } catch (NumberFormatException ignored) {
            }
        }
        plugin.arena().save(index, player.getLocation());
        MineChessPlugin.msg(player, "<green>竞技场 #" + (index + 1) + " 已设为当前位置："
                + player.getWorld().getName() + " " + String.format("%.1f", player.getX()) + ","
                + String.format("%.1f", player.getY()) + "," + String.format("%.1f", player.getZ())
                + " <gray>朝向 " + (int) player.getLocation().getYaw() + "°（共 " + plugin.arena().count() + " 个）");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("create", "join", "rooms", "ready", "start", "color",
                    "invite", "bot", "leave", "challenge", "accept", "decline", "move", "resign", "draw",
                    "acceptdraw", "declinedraw", "claimdraw", "panel", "board", "spectate", "status",
                    "history", "fen", "help"));
            if (sender.hasPermission("minechess.admin")) subs.add("setarena");
            if (sender.hasPermission("minechess.admin")) subs.add("preview");
            if (sender.hasPermission("minechess.admin")) subs.add("seat");
            if (sender.hasPermission("minechess.admin")) subs.add("tune");
            if (sender.hasPermission("minechess.admin")) subs.add("reload");
            return match(subs, args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "create", "join" -> {
                    List<String> options = new ArrayList<>();
                    if (args[0].equalsIgnoreCase("create")) {
                        options.addAll(List.of("casual", "3+2", "5+0", "10+0", "15+10"));
                    } else {
                        for (var room : plugin.matches().rooms()) options.add(String.valueOf(room.number()));
                    }
                    return match(options, args[1]);
                }
                case "challenge", "invite", "spectate", "watch" -> {
                    List<String> names = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (sender instanceof Player self && online.equals(self)) continue;
                        names.add(online.getName());
                    }
                    return match(names, args[1]);
                }
                case "move" -> {
                    if (sender instanceof Player player) {
                        ChessMatch match = plugin.matches().matchOf(player.getUniqueId());
                        if (match != null && match.phase() == ChessMatch.Phase.PLAYING) {
                            List<String> options = new ArrayList<>();
                            for (Move move : match.board().legalMoves()) {
                                options.add(ChessRules.uci(move));
                            }
                            return match(options, args[1]);
                        }
                    }
                    return List.of();
                }
                case "claimdraw" -> {
                    return match(List.of("threefold", "fifty"), args[1]);
                }
                case "tune" -> {
                    return match(plugin.matches().tuneKeys(), args[1]);
                }
                case "setarena" -> {
                    List<String> slots = new ArrayList<>();
                    for (int i = 1; i <= plugin.arena().count(); i++) slots.add(String.valueOf(i));
                    return match(slots, args[1]);
                }
                default -> {
                }
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("challenge")) {
            return match(List.of("casual", "3+2", "5+0", "10+0", "15+10"), args[2]);
        }
        return List.of();
    }

    private static List<String> match(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(option -> option.toLowerCase().startsWith(lower)).toList();
    }
}
