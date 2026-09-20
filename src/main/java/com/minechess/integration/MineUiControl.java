package com.minechess.integration;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minechess.MineChessPlugin;
import com.minechess.chess.ChessRules;
import com.minechess.chess.MoveRecord;
import com.minechess.chess.TimeControl;
import com.minechess.match.Challenge;
import com.minechess.match.ChessMatch;
import com.minechess.match.ChessRoom;
import com.minechess.ui.ControlUi;
import com.minechess.view.ChessTableView;
import com.mineui.api.MineUi;
import com.mineui.api.MineUiProvider;
import com.mineui.api.MineUiSession;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * MineUI 界面（原版风格模板 vanilla:*）：大厅 / 房间列表 / 房间 / 对局面板 / 2D 棋盘。
 * 仅当客户端声明 server_ui 能力时使用，其余玩家自动退回原版箱子菜单（ControlUi 抽象）。
 */
public class MineUiControl implements ControlUi {

    private static final String APP = "chess";
    private static final int ROOM_SLOTS = 8;
    /** 大厅里展示的进行中对局数量。 */
    private static final int LIVE_SLOTS = 4;
    /** 2D 棋盘两侧聊天栏各自显示的历史行数。 */
    private static final int CHAT_LINES = 8;
    /** 单条聊天在面板里的最大字符数（过长会截断）。 */
    private static final int CHAT_MAX_CHARS = 36;

    private final MineChessPlugin plugin;
    private final MineUi api;
    private final JsonObject lobbyPage;
    private final JsonObject roomsPage;
    private final JsonObject roomPage;
    private final JsonObject invitePage;
    private final JsonObject panelPage;
    private final Map<UUID, MineUiSession> sessions = new HashMap<>();
    private final Map<UUID, String> views = new HashMap<>();
    private final Map<UUID, List<UUID>> roomOrder = new HashMap<>();
    private final Map<UUID, List<UUID>> inviteOrder = new HashMap<>();
    private final Map<UUID, List<UUID>> liveOrder = new HashMap<>();
    private final Map<UUID, String> roomSignatures = new HashMap<>();
    private final Deque<String> worldChat = new ArrayDeque<>();
    private final Map<UUID, Deque<String>> matchChat = new HashMap<>();
    private boolean broken;

    public MineUiControl(MineChessPlugin plugin) {
        this.plugin = plugin;
        this.api = MineUiProvider.get();
        this.lobbyPage = load("lobby");
        this.roomsPage = load("rooms");
        this.roomPage = load("room");
        this.invitePage = load("invite");
        this.panelPage = load("panel");
    }

    private JsonObject load(String view) {
        String resource = "assets/minechess/ui/chess/" + view + ".json";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return null;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            plugin.getLogger().warning("读取 MineUI 界面定义失败 " + resource + "：" + e);
            return null;
        }
    }

    @Override
    public boolean available() {
        return !broken && api != null && lobbyPage != null && roomsPage != null
                && roomPage != null && invitePage != null && panelPage != null;
    }

    @Override
    public boolean handles(Player player) {
        if (!available()) return false;
        try {
            // 旧版 MineUI 没有 server_ui 能力，不能下发服务端定义
            return api.supportsServerUi(player);
        } catch (Throwable t) {
            broken = true;
            plugin.getLogger().warning("MineUI API 不匹配，退回原版面板：" + t);
            return false;
        }
    }

    // ---------- 打开各视图 ----------

    @Override
    public boolean openLobby(Player player) {
        MineUiSession session = open(player, "lobby", lobbyPage, this::registerLobby);
        if (session == null) return false;
        session.state("showRules", false);
        pushLobby(player, session);
        session.snapshot();
        return true;
    }

    @Override
    public boolean openRoomList(Player player) {
        // 房间列表里的 3D 头颅需要真实玩家名，按当前房间动态生成页面
        MineUiSession session = open(player, "rooms", roomListPage(), this::registerRooms);
        if (session == null) return false;
        pushRooms(player, session);
        session.snapshot();
        return true;
    }

    private JsonObject roomListPage() {
        JsonObject page = roomsPage.deepCopy();
        injectPlayerNames(page, sortedRooms().stream().map(room -> plugin.playerName(room.host())).toList());
        return page;
    }

    /** 把 head0..head7 节点的 player 替换成给定名字（不足的保留 @self，节点被 visible 隐藏）。 */
    private void injectPlayerNames(JsonElement element, List<String> names) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) injectPlayerNames(child, names);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("id") && object.get("id").isJsonPrimitive()) {
            String id = object.get("id").getAsString();
            if (id.startsWith("head")) {
                try {
                    int index = Integer.parseInt(id.substring("head".length()));
                    object.addProperty("player", index < names.size() ? names.get(index) : "@self");
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (var entry : object.entrySet()) injectPlayerNames(entry.getValue(), names);
    }

    // ---------- 邀请玩家 ----------

    private void openInvite(Player player) {
        List<Player> candidates = inviteCandidates(player);
        List<String> names = candidates.stream().map(Player::getName).toList();
        JsonObject page = invitePage.deepCopy();
        injectPlayerNames(page, names);
        MineUiSession session = open(player, "invite", page, this::registerInvite);
        if (session == null) return;
        inviteOrder.put(player.getUniqueId(), candidates.stream().map(Player::getUniqueId).toList());
        for (int i = 0; i < ROOM_SLOTS; i++) {
            boolean visible = i < candidates.size();
            session.state("v" + i, visible);
            session.state("p" + i, visible ? candidates.get(i).getName() : "");
        }
        session.state("none", candidates.isEmpty());
        session.snapshot();
    }

    private void registerInvite(MineUiSession session) {
        Player player = session.player();
        for (int i = 0; i < ROOM_SLOTS; i++) {
            final int index = i;
            session.on("invite" + i, action -> inviteListed(player, index));
        }
        session.on("back_room", action -> {
            ChessRoom room = plugin.matches().roomOf(player.getUniqueId());
            if (room != null) openRoom(player, room);
            else openLobby(player);
        });
        session.on("refresh_invite", action -> openInvite(player));
    }

    private void inviteListed(Player player, int index) {
        List<UUID> order = inviteOrder.get(player.getUniqueId());
        if (order == null || index >= order.size()) return;
        Player target = plugin.getServer().getPlayer(order.get(index));
        if (target == null) {
            openInvite(player);
            return;
        }
        if (plugin.matches().inviteToRoom(player, target)) {
            openInvite(player); // 刷新列表（被邀请者也变忙）
        }
    }

    private List<Player> inviteCandidates(Player self) {
        List<Player> others = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.equals(self)) continue;
            if (plugin.matches().busy(online.getUniqueId())) continue;
            others.add(online);
        }
        others.sort(Comparator.comparing(Player::getName));
        return others;
    }

    private List<ChessRoom> sortedRooms() {
        List<ChessRoom> rooms = new ArrayList<>(plugin.matches().rooms());
        rooms.sort(Comparator.comparingInt(ChessRoom::number));
        return rooms;
    }

    @Override
    public boolean openRoom(Player player, ChessRoom room) {
        if (room == null) return false;
        // 头像节点需要真实玩家名：按 白 / 黑 / 4 个观战席 注入 head0..head5
        JsonObject page = roomPage.deepCopy();
        List<String> headNames = new ArrayList<>();
        headNames.add(room.whitePlayer() == null ? "@self" : plugin.playerName(room.whitePlayer()));
        headNames.add(room.blackPlayer() == null ? "@self" : plugin.playerName(room.blackPlayer()));
        List<UUID> watchers = room.spectators();
        for (int i = 0; i < ChessRoom.SPECTATOR_SLOTS; i++) {
            headNames.add(i < watchers.size() ? plugin.playerName(watchers.get(i)) : "@self");
        }
        injectPlayerNames(page, headNames);
        MineUiSession session = open(player, "room", page, this::registerRoom);
        if (session == null) return false;
        roomSignatures.put(player.getUniqueId(), roomSignature(room));
        pushRoom(player, room, session);
        session.snapshot();
        return true;
    }

    /** 名单变化（换色/加入/观战席变动）时需要重建页面，头像才会更新。 */
    private String roomSignature(ChessRoom room) {
        return room.whitePlayer() + "|" + room.blackPlayer() + "|" + room.spectators();
    }

    /** 对局中默认直接进入 2D 棋盘；不可用时退回控制面板。 */
    @Override
    public boolean openMatch(Player player, ChessMatch match) {
        if (match == null) return false;
        if (openBoard(player, match)) return true;
        return openPanel(player, match);
    }

    @Override
    public boolean openPanel(Player player, ChessMatch match) {
        if (match == null) return false;
        MineUiSession session = open(player, "panel", panelPage, this::registerPanel);
        if (session == null) return false;
        session.state("showConfirm", false);
        pushPanel(match, session);
        session.snapshot();
        return true;
    }

    private MineUiSession open(Player player, String view, JsonObject page, Consumer<MineUiSession> register) {
        if (!handles(player) || page == null) return null;
        try {
            close(player);
            MineUiSession session = api.open(plugin, player, APP, view, page);
            sessions.put(player.getUniqueId(), session);
            views.put(player.getUniqueId(), view);
            session.on("close", action -> session.close());
            register.accept(session);
            plugin.log("[ui] MineUI 界面下发 " + player.getName() + " (" + view + ")");
            return session;
        } catch (Throwable t) {
            broken = true;
            plugin.getLogger().warning("MineUI 界面不可用，退回原版面板：" + t);
            return null;
        }
    }

    private void registerLobby(MineUiSession session) {
        Player player = session.player();
        session.on("create_room", action -> plugin.matches().createRoom(player, defaultTime()));
        session.on("open_rooms", action -> openRoomList(player));
        session.on("open_rules", action -> session.state("showRules", true));
        session.on("close_rules", action -> session.state("showRules", false));
        session.on("accept_incoming", action -> plugin.matches().accept(player));
        session.on("decline_incoming", action -> {
            plugin.matches().decline(player);
            pushLobby(player, session);
        });
        for (int i = 0; i < LIVE_SLOTS; i++) {
            final int index = i;
            session.on("watch_live" + i, action -> watchLiveListed(player, index));
        }
    }

    private void watchLiveListed(Player player, int index) {
        List<UUID> order = liveOrder.get(player.getUniqueId());
        if (order == null || index >= order.size()) return;
        ChessMatch match = plugin.matches().match(order.get(index));
        if (match == null) {
            pushLobby(player, sessions.get(player.getUniqueId()));
            return;
        }
        plugin.matches().spectate(player, match);
    }

    private void registerRooms(MineUiSession session) {
        Player player = session.player();
        for (int i = 0; i < ROOM_SLOTS; i++) {
            final int index = i;
            session.on("join" + i, action -> joinListed(player, index));
            session.on("watch" + i, action -> watchRoomListed(player, index));
        }
        session.on("back_lobby", action -> openLobby(player));
        session.on("refresh_rooms", action -> openRoomList(player));
    }

    private void watchRoomListed(Player player, int index) {
        List<UUID> order = roomOrder.get(player.getUniqueId());
        if (order == null || index >= order.size()) return;
        ChessRoom room = plugin.matches().room(order.get(index));
        if (room == null) {
            openRoomList(player);
            return;
        }
        plugin.matches().spectateRoom(player, room);
    }

    private void registerRoom(MineUiSession session) {
        Player player = session.player();
        session.on("toggle_ready", action -> plugin.matches().toggleReady(player));
        session.on("start_game", action -> plugin.matches().startRoom(player));
        session.on("cycle_time", action -> plugin.matches().cycleTime(player));
        session.on("swap_color", action -> plugin.matches().cycleColor(player));
        session.on("open_invite", action -> openInvite(player));
        session.on("add_bot", action -> plugin.matches().addBot(player));
        session.on("toggle_spectate", action -> toggleSpectate(player));
        session.on("leave_room", action -> {
            session.close();
            plugin.matches().leaveRoom(player);
        });
    }

    private void toggleSpectate(Player player) {
        ChessRoom room = plugin.matches().roomOf(player.getUniqueId());
        if (room == null) room = plugin.matches().spectatorRoomOf(player.getUniqueId());
        if (room == null) return;
        if (room.isSpectator(player.getUniqueId())) {
            plugin.matches().leaveSpectate(player);
            openRoom(player, room);
        } else {
            plugin.matches().spectateRoom(player, room);
        }
    }

    private void registerPanel(MineUiSession session) {
        Player player = session.player();
        session.on("resign", action -> {
            plugin.log("[ui] " + player.getName() + " 点击认输（等待确认）");
            session.state("showConfirm", true);
            player.sendActionBar(MineChessPlugin.mm("<gray>点弹窗【确认】才会认输；卡住可用 <white>/chess resign"));
        });
        session.on("resign_cancel", action -> {
            plugin.log("[ui] " + player.getName() + " 取消认输");
            session.state("showConfirm", false);
        });
        session.on("resign_confirm", action -> {
            plugin.log("[ui] " + player.getName() + " 确认认输");
            session.state("showConfirm", false);
            plugin.matches().resign(player);
        });
        session.on("draw", action -> plugin.matches().offerDraw(player));
        session.on("acceptdraw", action -> plugin.matches().acceptDraw(player));
        session.on("declinedraw", action -> plugin.matches().declineDraw(player));
        session.on("open_board", action -> openBoard(player, plugin.matches().watchedMatchOf(player.getUniqueId())));
        session.on("leave_spectate", action -> {
            plugin.matches().leaveSpectate(player);
            openLobby(player);
        });
        session.on("leave_match", action -> {
            session.close();
            plugin.matches().leave(player);
        });
    }

    // ---------- 2D 棋盘（静态像素整板 + 状态绑定的棋子/高亮，只打开一次） ----------

    private final Map<UUID, Map<String, String>> boardStateCache = new HashMap<>();
    private final Map<UUID, Boolean> boardTextureMode = new HashMap<>();

    @Override
    public boolean openBoard(Player player, ChessMatch match) {
        if (match == null) return false;
        boolean textures = plugin.cfg("ui.board-textures", false);
        JsonObject page = buildBoardPage(match, player, textures);
        int bytes = page.toString().length();
        plugin.log("[ui] 2D 棋盘页面 " + bytes + " 字节 (textures=" + textures + ")；示例状态 c_e2 = "
                + (match.pieceAt(Square.E2) == Piece.NONE ? "(空)" : cellTexture(match, Square.E2, null, List.of(), Set.of(), null, null)));
        if (bytes > 28000) plugin.getLogger().warning("2D 棋盘页面过大：" + bytes + " 字节");
        MineUiSession session = open(player, "board", page, s -> registerBoard(s, match));
        if (session == null) return false;
        boardTextureMode.put(player.getUniqueId(), textures);
        boardStateCache.remove(player.getUniqueId());
        pushBoardState(player, match, session, true);
        session.snapshot();
        return true;
    }

    private void registerBoard(MineUiSession session, ChessMatch match) {
        Player player = session.player();
        for (Square square : Square.values()) {
            if (square == Square.NONE) continue;
            String action = "cell_" + ChessRules.squareName(square);
            session.on(action, event -> {
                ChessTableView table = plugin.matches().table(match);
                if (table != null) plugin.matches().squareClick(player, table, square);
            });
        }
        // 2D 升变选择：0 后 / 1 车 / 2 象 / 3 马（与 3D 按钮一致）
        for (int i = 0; i < 4; i++) {
            final int index = i;
            session.on("promote" + i, event -> {
                ChessTableView table = plugin.matches().table(match);
                if (table != null) plugin.matches().promotionClick(player, table, index);
            });
        }
        session.on("board_panel", action -> {
            ChessMatch current = plugin.matches().watchedMatchOf(player.getUniqueId());
            if (current != null) openPanel(player, current);
        });
        session.on("chat_world", action -> sendWorldChat(player, action.string("text", "")));
        session.on("chat_match", action -> sendMatchChat(player, match, action.string("text", "")));
    }

    // ---------- 聊天栏（2D 棋盘两侧） ----------

    /** 世界聊天回流到所有打开的 2D 棋盘（主线程调用）。 */
    @Override
    public void onWorldChat(String sender, String message) {
        if (!available()) return;
        addChatLine(worldChat, sender + ": " + message);
        for (Map.Entry<UUID, MineUiSession> entry : sessions.entrySet()) {
            if (!"board".equals(views.get(entry.getKey()))) continue;
            ChessMatch match = plugin.matches().watchedMatchOf(entry.getKey());
            MineUiSession session = entry.getValue();
            if (match == null || session == null || session.closed()) continue;
            pushBoardChat(session, match, false);
        }
    }

    private void sendWorldChat(Player player, String raw) {
        String text = cleanChat(raw);
        if (text.isEmpty()) return;
        player.chat(text); // 走普通聊天管线：全服可见，并回流到面板
    }

    private void sendMatchChat(Player player, ChessMatch match, String raw) {
        String text = cleanChat(raw);
        if (text.isEmpty()) return;
        addChatLine(matchChat.computeIfAbsent(match.id(), k -> new ArrayDeque<>()),
                player.getName() + ": " + text);
        plugin.matches().send(match, "<gray>[对局] <white>" + player.getName() + " <gray>" + text);
        pushChatToMatch(match);
    }

    private String cleanChat(String raw) {
        if (raw == null) return "";
        String text = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (text.startsWith("/")) text = text.substring(1).trim();
        if (text.length() > 80) text = text.substring(0, 80);
        return text;
    }

    private void addChatLine(Deque<String> feed, String line) {
        String text = line == null ? "" : line;
        if (text.length() > CHAT_MAX_CHARS) text = text.substring(0, CHAT_MAX_CHARS - 1) + "…";
        feed.addLast(text);
        while (feed.size() > CHAT_LINES) feed.removeFirst();
    }

    private void pushChatToMatch(ChessMatch match) {
        for (UUID id : sessionsForMatch(match)) {
            MineUiSession session = sessions.get(id);
            if (session == null || session.closed() || !"board".equals(views.get(id))) continue;
            pushBoardChat(session, match, false);
        }
    }

    private void pushBoardChat(MineUiSession session, ChessMatch match, boolean initial) {
        List<String> world = new ArrayList<>(worldChat);
        Deque<String> local = matchChat.get(match.id());
        List<String> team = local == null ? List.of() : new ArrayList<>(local);
        Map<String, String> cache = boardStateCache.computeIfAbsent(session.player().getUniqueId(),
                k -> new HashMap<>());
        for (int i = 0; i < CHAT_LINES; i++) {
            stateDiff(session, cache, "wl" + i, i < world.size() ? world.get(i) : "", initial);
            stateDiff(session, cache, "ml" + i, i < team.size() ? team.get(i) : "", initial);
        }
    }

    private JsonObject buildBoardPage(ChessMatch match, Player player, boolean textures) {
        Side viewerSide = match.sideOf(player.getUniqueId());
        boolean black = viewerSide == Side.BLACK;

        JsonArray rows = new JsonArray();
        for (int row = 0; row < 8; row++) {
            int rank = black ? row + 1 : 8 - row;
            JsonArray cells = new JsonArray();
            for (int col = 0; col < 8; col++) {
                int file = black ? 7 - col : col;
                cells.add(buildCell(Square.squareAt((rank - 1) * 8 + file), textures));
            }
            JsonObject rowObject = new JsonObject();
            rowObject.addProperty("type", "row");
            rowObject.addProperty("gap", 0);
            rowObject.addProperty("justify", "center");
            rowObject.add("children", cells);
            rows.add(rowObject);
        }
        JsonObject grid = new JsonObject();
        grid.addProperty("type", "column");
        grid.addProperty("gap", 0);
        grid.addProperty("align", "center");
        grid.add("children", rows);

        JsonArray boardChildren = new JsonArray();
        boardChildren.add(imageNode(black ? "2d/board_black" : "2d/board_white", "68.8vh", "68.8vh", 256, 256));
        boardChildren.add(grid);
        JsonObject boardStack = new JsonObject();
        boardStack.addProperty("type", "stack");
        boardStack.addProperty("width", "100%");
        boardStack.addProperty("align", "center");
        boardStack.addProperty("justify", "center");
        boardStack.add("children", boardChildren);

        JsonArray pageChildren = new JsonArray();
        JsonObject header2 = new JsonObject();
        header2.addProperty("type", "row");
        header2.addProperty("gap", 6);
        header2.addProperty("align", "center");
        header2.addProperty("justify", "center");
        JsonArray header2Children = new JsonArray();
        header2Children.add(textNode("2D 棋盘 · {state.status}", "#FFFFFF", 1.0f));
        header2.add("children", header2Children);
        pageChildren.add(header2);
        JsonObject divider = new JsonObject();
        divider.addProperty("type", "box");
        divider.addProperty("width", "100%");
        divider.addProperty("height", 2);
        JsonArray gradient = new JsonArray();
        gradient.add("#00FFD54F");
        gradient.add("#FFD54F");
        divider.add("gradient", gradient);
        pageChildren.add(divider);
        JsonObject scroll = new JsonObject();
        scroll.addProperty("type", "scroll");
        scroll.addProperty("width", "68.8vh");
        scroll.addProperty("height", "74vh");
        JsonArray scrollChildren = new JsonArray();
        scrollChildren.add(boardStack);
        scroll.add("children", scrollChildren);

        // 两侧聊天栏：左世界 / 右对局，各带输入框
        JsonObject middle = new JsonObject();
        middle.addProperty("type", "row");
        middle.addProperty("width", "100%");
        middle.addProperty("gap", 4);
        middle.addProperty("align", "center");
        middle.addProperty("justify", "center");
        JsonArray middleChildren = new JsonArray();
        middleChildren.add(chatColumn("世界聊天", "wl", "chat_world", "在世界频道发言…", "#7EC8FF"));
        middleChildren.add(scroll);
        middleChildren.add(chatColumn("对局聊天", "ml", "chat_match", "和对手/观众聊天…", "#FFD54F"));
        middle.add("children", middleChildren);
        pageChildren.add(middle);
        // 2D 升变选择：仅升变方可见，点棋子图标完成升变
        if (viewerSide != null) {
            JsonObject promoRow = new JsonObject();
            promoRow.addProperty("type", "row");
            promoRow.addProperty("visible", "{state.promotion}");
            promoRow.addProperty("gap", 5);
            promoRow.addProperty("align", "center");
            promoRow.addProperty("justify", "center");
            JsonArray promoChildren = new JsonArray();
            promoChildren.add(cellText("选择升变：", "#FFD54F", 0.9f));
            String sideName = viewerSide == Side.WHITE ? "white" : "black";
            String[] kinds = {"queen", "rook", "bishop", "knight"};
            for (int i = 0; i < kinds.length; i++) {
                JsonObject pick = imageNode("2d/" + sideName + "_" + kinds[i], 26, 26);
                pick.addProperty("action", "promote" + i);
                promoChildren.add(pick);
            }
            promoRow.add("children", promoChildren);
            pageChildren.add(promoRow);
        }
        JsonObject buttonRow = new JsonObject();
        buttonRow.addProperty("type", "row");
        buttonRow.addProperty("gap", 5);
        buttonRow.addProperty("justify", "center");
        JsonArray buttons = new JsonArray();
        buttons.add(buttonNode("对局面板", "board_panel", 100));
        buttons.add(buttonNode("关闭", "close", 80));
        buttonRow.add("children", buttons);
        pageChildren.add(buttonRow);

        JsonObject column = new JsonObject();
        column.addProperty("type", "column");
        column.addProperty("skin", "vanilla:panel");
        column.addProperty("width", "92%");
        column.addProperty("gap", 3);
        column.addProperty("padding", 4);
        column.addProperty("align", "stretch");
        column.add("children", pageChildren);

        JsonObject root = new JsonObject();
        root.addProperty("type", "stack");
        root.addProperty("width", "100%");
        root.addProperty("height", "100%");
        root.addProperty("align", "center");
        root.addProperty("justify", "center");
        JsonArray rootChildren = new JsonArray();
        rootChildren.add(column);
        root.add("children", rootChildren);
        return root;
    }

    private JsonObject buildCell(Square square, boolean textures) {
        String name = ChessRules.squareName(square);
        boolean light = (square.getRank().ordinal() + square.getFile().ordinal()) % 2 == 1;
        JsonArray children = new JsonArray();
        if (textures) {
            children.add(boundImage("{state.c_" + name + "}", "7.8vh", "7.8vh"));
        } else {
            // 无贴图时的字符兜底；贴图模式全部走绑定图片，控制页面体积（32KB 上限）
            children.add(cellText("{state.w_" + name + "}", "#F5F0E2", 1.4f));
            children.add(cellText("{state.b_" + name + "}", "#2E2C34", 1.4f));
            children.add(cellText("{state.m_" + name + "}", "#55FF55", 1.2f));
        }
        JsonObject cell = new JsonObject();
        cell.addProperty("type", "stack");
        cell.addProperty("width", "8.6vh");
        cell.addProperty("height", "8.6vh");
        // 兜底底色：资源包贴图未加载时也能看到棋盘格
        cell.addProperty("background", light ? "#EBE0C6" : "#7A5B3E");
        cell.addProperty("action", "cell_" + name);
        cell.add("children", children);
        return cell;
    }

    /** 按状态增量推送：首次全量（随后 snapshot 一次下发），之后只发变化的键。 */
    private void pushBoardState(Player player, ChessMatch match, MineUiSession session, boolean initial) {
        UUID id = player.getUniqueId();
        Map<String, String> cache = boardStateCache.computeIfAbsent(id, k -> new HashMap<>());
        Square selected = plugin.matches().selection(id);
        List<Square> legal = selected == null ? List.of() : match.legalTargets(selected);
        Set<Square> captures = selected == null ? Set.of() : match.captureTargets(selected);
        MoveRecord last = match.lastMove();
        Square checked = match.isCheck() ? match.board().getKingSquare(match.turn()) : null;
        boolean textures = boardTextureMode.getOrDefault(id, false);
        for (Square square : Square.values()) {
            if (square == Square.NONE) continue;
            String name = ChessRules.squareName(square);
            if (textures) {
                // 贴图模式：棋子/高亮全部走绑定图片，省去 192 个字符节点（页面体积上限 32KB）
                stateDiff(session, cache, "c_" + name,
                        cellTexture(match, square, selected, legal, captures, last, checked), initial);
                continue;
            }
            Piece piece = match.pieceAt(square);
            String white = "";
            String blackText = "";
            if (piece != Piece.NONE) {
                String text = glyph(piece);
                if (square.equals(checked)) text = "✚" + text;
                if (captures.contains(square)) text = "✕" + text;
                if (square.equals(selected)) text = "[" + text + "]";
                if (piece.getPieceSide() == Side.WHITE) white = text;
                else blackText = text;
            }
            String marker = "";
            if (piece == Piece.NONE) {
                if (legal.contains(square)) marker = "•";
                else if (last != null && (square.equals(last.from()) || square.equals(last.to()))) marker = "·";
            }
            stateDiff(session, cache, "w_" + name, white, initial);
            stateDiff(session, cache, "b_" + name, blackText, initial);
            stateDiff(session, cache, "m_" + name, marker, initial);
        }
        boolean promotion = match.promotionPending() && match.promotionSide() == match.sideOf(id);
        stateDiff(session, cache, "promotion", String.valueOf(promotion), initial);
        stateDiff(session, cache, "status", boardStatus(match, player), initial);
        pushBoardChat(session, match, initial);
    }

    private void stateDiff(MineUiSession session, Map<String, String> cache, String key, String value, boolean initial) {
        if (!initial && value.equals(cache.get(key))) return;
        cache.put(key, value);
        session.state(key, value);
    }

    private String glyph(Piece piece) {
        boolean white = piece.getPieceSide() == Side.WHITE;
        return switch (piece.getPieceType()) {
            case KING -> white ? "♔" : "♚";
            case QUEEN -> white ? "♕" : "♛";
            case ROOK -> white ? "♖" : "♜";
            case BISHOP -> white ? "♗" : "♝";
            case KNIGHT -> white ? "♘" : "♞";
            case PAWN -> white ? "♙" : "♟";
            default -> "?";
        };
    }

    /** 格子贴图路径：棋子（含选中/可吃/将军变体）或空格提示；空串 = 不显示。 */
    private String cellTexture(ChessMatch match, Square square, Square selected, List<Square> legal,
                               Set<Square> captures, MoveRecord last, Square checked) {
        Piece piece = match.pieceAt(square);
        if (piece != Piece.NONE) {
            String side = piece.getPieceSide() == Side.WHITE ? "white_" : "black_";
            String kind = piece.getPieceType().name().toLowerCase(java.util.Locale.ROOT);
            String suffix = "";
            if (square.equals(selected)) suffix = "_sel";
            else if (captures.contains(square)) suffix = "_cap";
            else if (square.equals(checked)) suffix = "_check";
            return "minechess:textures/gui/2d/" + side + kind + suffix + ".png";
        }
        if (legal.contains(square)) return "minechess:textures/gui/2d/hl_legal.png";
        if (last != null && (square.equals(last.from()) || square.equals(last.to()))) {
            return "minechess:textures/gui/2d/hl_last.png";
        }
        return "";
    }

    private JsonObject cellText(String text, String color, float scale) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "text");
        node.addProperty("text", text);
        node.addProperty("color", color);
        node.addProperty("scale", scale);
        return node;
    }

    private JsonObject textNode(String text, String color, float scale) {
        JsonObject node = cellText(text, color, scale);
        node.addProperty("align", "center");
        node.addProperty("width", "100%");
        return node;
    }

    /** 2D 棋盘侧边聊天栏：标题 + 可滚动历史 + 输入框。 */
    private JsonObject chatColumn(String title, String keyPrefix, String action, String placeholder, String titleColor) {
        JsonObject column = new JsonObject();
        column.addProperty("type", "column");
        column.addProperty("width", "28vh");
        column.addProperty("gap", 3);
        column.addProperty("padding", 4);
        column.addProperty("radius", 6);
        column.addProperty("background", "#66101318");
        column.addProperty("align", "stretch");
        JsonArray children = new JsonArray();
        children.add(textNode(title, titleColor, 0.85f));
        JsonArray lines = new JsonArray();
        for (int i = 0; i < CHAT_LINES; i++) {
            JsonObject line = cellText("{state." + keyPrefix + i + "}", "#D8D8D8", 0.62f);
            line.addProperty("width", "100%");
            lines.add(line);
        }
        JsonObject lineBox = new JsonObject();
        lineBox.addProperty("type", "scroll");
        lineBox.addProperty("width", "100%");
        lineBox.addProperty("height", "64vh");
        lineBox.addProperty("gap", 2);
        lineBox.add("children", lines);
        children.add(lineBox);
        JsonObject input = new JsonObject();
        input.addProperty("type", "input");
        input.addProperty("placeholder", placeholder);
        input.addProperty("maxLength", 80);
        input.addProperty("action", action);
        input.addProperty("width", "100%");
        input.addProperty("height", 14);
        input.addProperty("color", "#FFFFFF");
        children.add(input);
        column.add("children", children);
        return column;
    }

    private JsonObject buttonNode(String text, String action, int width) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "button");
        node.addProperty("skin", "vanilla:button");
        node.addProperty("text", text);
        node.addProperty("action", action);
        node.addProperty("width", width);
        node.addProperty("height", 14);
        node.addProperty("hoverScale", 1.06);
        return node;
    }

    /** 任意命名空间的普通贴图节点（诊断/通用），显式指定纹理尺寸与采样区域。 */
    private JsonObject rawImageNode(String texture, int width, int height, int textureSize, int regionSize) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "image");
        node.addProperty("texture", texture);
        node.addProperty("regionWidth", regionSize);
        node.addProperty("regionHeight", regionSize);
        JsonArray size = new JsonArray();
        size.add(textureSize);
        size.add(textureSize);
        node.add("textureSize", size);
        node.addProperty("width", width);
        node.addProperty("height", height);
        return node;
    }

    /** 状态绑定贴图：texture 支持 {state.x} 模板，空值/非法路径客户端会跳过。 */
    private JsonObject boundImage(String template, Object width, Object height) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "image");
        node.addProperty("texture", template);
        node.addProperty("regionWidth", 32);
        node.addProperty("regionHeight", 32);
        JsonArray size = new JsonArray();
        size.add(32);
        size.add(32);
        node.add("textureSize", size);
        if (width instanceof Number number) node.addProperty("width", number.intValue());
        else node.addProperty("width", String.valueOf(width));
        if (height instanceof Number number) node.addProperty("height", number.intValue());
        else node.addProperty("height", String.valueOf(height));
        return node;
    }

    /** 贴图节点：尺寸支持 px 或 vh，显式指定纹理尺寸与采样区域（默认 32x32）。 */
    private JsonObject imageNode(String path, Object width, Object height) {
        return imageNode(path, width, height, 32, 32);
    }

    private JsonObject imageNode(String path, Object width, Object height, int textureSize, int regionSize) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "image");
        node.addProperty("texture", "minechess:textures/gui/" + path + ".png");
        node.addProperty("u", 0);
        node.addProperty("v", 0);
        node.addProperty("regionWidth", regionSize);
        node.addProperty("regionHeight", regionSize);
        JsonArray size = new JsonArray();
        size.add(textureSize);
        size.add(textureSize);
        node.add("textureSize", size);
        if (width instanceof Number number) node.addProperty("width", number.intValue());
        else node.addProperty("width", String.valueOf(width));
        if (height instanceof Number number) node.addProperty("height", number.intValue());
        else node.addProperty("height", String.valueOf(height));
        return node;
    }

    private String boardStatus(ChessMatch match, Player player) {
        if (match.phase() != ChessMatch.Phase.PLAYING) {
            return match.result() == null ? "对局结束" : match.result().describe();
        }
        Side side = match.sideOf(player.getUniqueId());
        if (side != null && match.turn() == side) {
            Square selected = plugin.matches().selection(player.getUniqueId());
            if (selected == null) return "轮到你：点自己的棋子";
            List<Square> legal = match.legalTargets(selected);
            return legal.isEmpty() ? "该棋子无处可走" : "已选 " + ChessRules.squareName(selected);
        }
        return "等待对方走棋";
    }

    private void joinListed(Player player, int index) {
        List<UUID> order = roomOrder.get(player.getUniqueId());
        if (order == null || index >= order.size()) return;
        ChessRoom room = plugin.matches().room(order.get(index));
        if (room == null) {
            openRoomList(player);
            return;
        }
        plugin.matches().joinRoom(player, room);
    }

    @Override
    public void close(Player player) {
        MineUiSession session = sessions.remove(player.getUniqueId());
        views.remove(player.getUniqueId());
        roomOrder.remove(player.getUniqueId());
        inviteOrder.remove(player.getUniqueId());
        liveOrder.remove(player.getUniqueId());
        roomSignatures.remove(player.getUniqueId());
        boardStateCache.remove(player.getUniqueId());
        boardTextureMode.remove(player.getUniqueId());
        try {
            if (session != null && !session.closed()) session.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 丢弃已关闭会话的 per-player 索引：客户端 ESC/指令关界面时不会走 close 动作，
     * 这里在刷新/对局更新时顺带回收，避免 closed session 引用长期残留。
     */
    private void forgetClosedSessions() {
        if (sessions.isEmpty()) return;
        Iterator<Map.Entry<UUID, MineUiSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MineUiSession> entry = iterator.next();
            MineUiSession session = entry.getValue();
            if (session != null && !session.closed()) continue;
            UUID id = entry.getKey();
            iterator.remove();
            views.remove(id);
            roomOrder.remove(id);
            inviteOrder.remove(id);
            liveOrder.remove(id);
            roomSignatures.remove(id);
            boardStateCache.remove(id);
            boardTextureMode.remove(id);
        }
    }

    // ---------- 状态推送 ----------

    @Override
    public void refresh(Player player) {
        forgetClosedSessions();
        MineUiSession session = sessions.get(player.getUniqueId());
        if (session == null || session.closed()) return;
        String view = views.getOrDefault(player.getUniqueId(), "lobby");
        switch (view) {
            case "panel" -> {
                ChessMatch match = plugin.matches().watchedMatchOf(player.getUniqueId());
                if (match != null) pushPanel(match, session);
            }
            case "board" -> {
                ChessMatch match = plugin.matches().watchedMatchOf(player.getUniqueId());
                if (match != null) pushBoardState(player, match, session, false);
            }
            case "room" -> {
                ChessRoom room = plugin.matches().roomOf(player.getUniqueId());
                if (room == null) room = plugin.matches().spectatorRoomOf(player.getUniqueId());
                if (room == null) {
                    openLobby(player);
                } else if (!roomSignature(room).equals(roomSignatures.get(player.getUniqueId()))) {
                    openRoom(player, room); // 名单变了：重建页面刷新头像
                } else {
                    pushRoom(player, room, session);
                }
            }
            case "rooms" -> pushRooms(player, session);
            case "invite" -> openInvite(player);
            default -> pushLobby(player, session);
        }
    }

    @Override
    public void update(ChessMatch match) {
        if (!available()) return;
        forgetClosedSessions();
        try {
            for (UUID id : sessionsForMatch(match)) {
                MineUiSession session = sessions.get(id);
                if (session == null || session.closed()) continue;
                String view = views.get(id);
                if ("panel".equals(view)) {
                    pushPanel(match, session);
                } else if ("board".equals(view)) {
                    Player player = plugin.getServer().getPlayer(id);
                    if (player == null) continue;
                    pushBoardState(player, match, session, false);
                }
            }
        } catch (Throwable t) {
            broken = true;
            plugin.getLogger().warning("MineUI 面板刷新失败，退回原版面板：" + t);
        }
    }

    /** 与对局相关的所有 MineUI 会话：两位棋手 + 观战者。 */
    private List<UUID> sessionsForMatch(ChessMatch match) {
        List<UUID> ids = new ArrayList<>(2 + ChessRoom.SPECTATOR_SLOTS);
        ids.add(match.whitePlayer());
        ids.add(match.blackPlayer());
        ids.addAll(plugin.matches().spectatorsOf(match));
        return ids;
    }

    @Override
    public void onDrawOffer(ChessMatch match) {
        update(match);
    }

    @Override
    public void onGameEnd(ChessMatch match) {
        update(match);
    }

    private void pushLobby(Player player, MineUiSession session) {
        if (session == null || session.closed()) return;
        Challenge incoming = plugin.matches().incoming(player.getUniqueId());
        session.state("hasIncoming", incoming != null);
        session.state("incomingName", incoming == null ? "" : plugin.playerName(incoming.challenger()));
        session.state("status", plugin.matches().rooms().isEmpty()
                ? "创建房间，或加入别人的房间"
                : "当前有 " + plugin.matches().rooms().size() + " 个等待中的房间");

        List<ChessMatch> live = sortedLive();
        liveOrder.put(player.getUniqueId(), live.stream().map(ChessMatch::id).toList());
        for (int i = 0; i < LIVE_SLOTS; i++) {
            boolean has = i < live.size();
            session.state("live" + i, has);
            session.state("ln" + i, has ? liveLabel(live.get(i)) : "");
        }
        session.state("hasLive", !live.isEmpty());
    }

    private List<ChessMatch> sortedLive() {
        List<ChessMatch> live = new ArrayList<>();
        for (ChessMatch match : plugin.matches().games()) {
            if (match.phase() == ChessMatch.Phase.PLAYING) live.add(match);
        }
        live.sort(Comparator.comparingLong(ChessMatch::startedAtMillis));
        return live;
    }

    private String liveLabel(ChessMatch match) {
        return plugin.playerName(match.whitePlayer()) + " vs " + plugin.playerName(match.blackPlayer())
                + " · " + match.timeControl().label()
                + " · 观战 " + plugin.matches().spectatorsOf(match).size() + "/" + ChessRoom.SPECTATOR_SLOTS;
    }

    private void pushRooms(Player player, MineUiSession session) {
        if (session == null || session.closed()) return;
        List<ChessRoom> rooms = sortedRooms();
        roomOrder.put(player.getUniqueId(), rooms.stream().map(ChessRoom::id).toList());
        for (int i = 0; i < ROOM_SLOTS; i++) {
            boolean has = i < rooms.size();
            session.state("r" + i, has);
            if (!has) {
                session.state("n" + i, "");
                session.state("g" + i, "");
                continue;
            }
            ChessRoom room = rooms.get(i);
            session.state("n" + i, "#" + room.number() + " " + plugin.playerName(room.host()));
            String guest = room.hasGuest()
                    ? (room.guestReady() ? "已准备" : "未准备")
                    : "可加入";
            session.state("g" + i, room.timeControl().label() + " · " + guest);
        }
        session.state("noRooms", rooms.isEmpty());
    }

    private void pushRoom(Player player, ChessRoom room, MineUiSession session) {
        if (session == null || session.closed()) return;
        UUID id = player.getUniqueId();
        boolean host = room.isHost(id);
        boolean playerInRoom = room.isPlayer(id);
        boolean spectator = room.isSpectator(id);
        session.state("roomNo", room.number());
        session.state("time", room.timeControl().describe());
        session.state("myColor", spectator ? "观战" : (room.colorOf(id) == Side.WHITE ? "白" : "黑"));
        session.state("whiteName", room.whitePlayer() == null ? "等待加入" : plugin.playerName(room.whitePlayer()));
        session.state("blackName", room.blackPlayer() == null ? "等待加入" : plugin.playerName(room.blackPlayer()));
        UUID white = room.whitePlayer();
        UUID black = room.blackPlayer();
        boolean whiteBot = white != null && plugin.matches().isBot(white);
        boolean blackBot = black != null && plugin.matches().isBot(black);
        session.state("whiteBot", whiteBot);
        session.state("blackBot", blackBot);
        session.state("whiteHead", white != null && !whiteBot);
        session.state("blackHead", black != null && !blackBot);
        session.state("hostName", plugin.playerName(room.host()));
        session.state("hostNote", "房主");
        session.state("guestName", room.hasGuest() ? plugin.playerName(room.guest()) : "等待加入");
        session.state("guestNote", room.hasGuest()
                ? (room.guestReady() ? "已准备" : "未准备")
                : "告诉朋友房间号");
        session.state("isHost", host);
        session.state("isGuest", playerInRoom && !host);
        session.state("canInvite", host && !room.hasGuest());
        session.state("readyText", room.guestReady() ? "取消准备" : "准备");
        // 观战席
        session.state("isSpectator", spectator);
        session.state("canWatch", !playerInRoom && !spectator && !room.spectatorFull());
        session.state("watchText", spectator ? "退出观战" : "观战");
        List<UUID> watchers = room.spectators();
        session.state("watchCount", "观战席 " + watchers.size() + "/" + ChessRoom.SPECTATOR_SLOTS);
        for (int i = 0; i < ChessRoom.SPECTATOR_SLOTS; i++) {
            boolean has = i < watchers.size();
            session.state("sv" + i, has);
            session.state("s" + i, has ? plugin.playerName(watchers.get(i)) : "空位 · 可点击观战");
        }
        session.state("hint", spectator
                ? "你正在观战，开局后会传送到棋盘两侧"
                : (host
                ? (room.canStart() ? "对手已准备，可以开始游戏" : "等待对手加入并准备")
                : (room.guestReady() ? "已准备，等待房主开始" : "点击「准备」，房主才能开始")));
    }

    private void pushPanel(ChessMatch match, MineUiSession session) {
        UUID id = session.player().getUniqueId();
        Side side = match.sideOf(id);
        boolean spectator = side == null;
        session.state("whiteName", plugin.playerName(match.whitePlayer()));
        session.state("blackName", plugin.playerName(match.blackPlayer()));
        session.state("whiteClock", match.clock(Side.WHITE).format());
        session.state("blackClock", match.clock(Side.BLACK).format());
        session.state("history", history(match));
        session.state("canAct", match.phase() == ChessMatch.Phase.PLAYING && !spectator);
        session.state("isSpectator", spectator);
        session.state("whiteTurn", match.phase() == ChessMatch.Phase.PLAYING && match.turn() == Side.WHITE);
        session.state("blackTurn", match.phase() == ChessMatch.Phase.PLAYING && match.turn() == Side.BLACK);
        session.state("incomingDraw", !spectator && match.drawOffer() != null && match.drawOffer() != side);
        String status;
        if (match.phase() != ChessMatch.Phase.PLAYING) {
            status = match.result() == null ? "对局结束" : match.result().describe();
        } else if (spectator) {
            status = "观战中 · " + (match.turn() == Side.WHITE ? "白方走棋" : "黑方走棋");
        } else if (match.turn() == side) {
            status = "轮到你走棋";
        } else {
            status = "等待对方走棋";
        }
        session.state("status", status);
    }

    private TimeControl defaultTime() {
        TimeControl timeControl = TimeControl.parse(plugin.getConfig().getString("game.default-time", "10+0"));
        return timeControl == null ? TimeControl.parse("10+0") : timeControl;
    }

    private String history(ChessMatch match) {
        if (match.moves().isEmpty()) return "还没有落子";
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (var record : match.moves()) {
            if (index % 2 == 0) sb.append(index / 2 + 1).append(". ");
            sb.append(record.san()).append(' ');
            if (++index % 8 == 0) sb.append('\n');
        }
        return sb.toString().trim();
    }
}
