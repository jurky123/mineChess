package com.minechess;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.minechess.ai.BotService;
import com.minechess.ai.Bots;
import com.minechess.ai.ChessBot;
import com.minechess.chess.ChessResult;
import com.minechess.chess.DrawClaim;
import com.minechess.chess.MoveOutcome;
import com.minechess.chess.TimeControl;
import com.minechess.match.Challenge;
import com.minechess.match.ChessMatch;
import com.minechess.match.ChessRoom;
import com.minechess.view.ChessTableView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** 对局管理：挑战、开局、落子、棋钟、断线、表现层调度。 */
public class MatchManager {

    private final MineChessPlugin plugin;
    private final Map<UUID, ChessMatch> matches = new LinkedHashMap<>();
    private final Map<UUID, UUID> playerMatch = new HashMap<>();
    private final Map<UUID, ChessTableView> tables = new HashMap<>();
    private final Map<UUID, Integer> slots = new HashMap<>();
    private final Map<UUID, ChessTableView.Hit> hits = new HashMap<>();
    private final Map<UUID, Location> back = new HashMap<>();
    private final Map<UUID, Long> grace = new HashMap<>();
    private final Map<UUID, Challenge> incoming = new HashMap<>();
    private final Map<UUID, Square> selected = new HashMap<>();
    private final Map<UUID, Long> promotionDeadline = new HashMap<>();
    private final Map<UUID, Double> reachBackup = new HashMap<>();
    private final Map<UUID, ChessRoom> rooms = new LinkedHashMap<>();
    private final Map<UUID, UUID> playerRoom = new HashMap<>();
    /** 观战者 -> 对局；席位表 matchId -> 固定 4 个槽位（null 为空）。 */
    private final Map<UUID, UUID> spectatorMatch = new HashMap<>();
    private final Map<UUID, List<UUID>> matchSpectatorSeats = new HashMap<>();
    private final Collection<UUID> cleanupScheduled = new java.util.HashSet<>();
    private final BotService bots = new BotService();
    private final Collection<UUID> botThinking = new java.util.HashSet<>();
    private BukkitTask ticker;
    private int tickCount;
    private int roomCounter;

    public MatchManager(MineChessPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 10);
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (ChessMatch match : new ArrayList<>(matches.values())) cleanup(match, false);
        removePreview();
        tuning.clear();
    }

    // ---------- 预览棋盘（管理员调参用，不占用竞技场、不产生交互体） ----------

    private ChessTableView preview;
    private Location previewCenter;

    public void togglePreview(Player player) {
        if (preview != null) {
            removePreview();
            MineChessPlugin.msg(player, "<gray>已移除预览棋盘");
            return;
        }
        createPreview(player.getLocation());
        MineChessPlugin.msg(player, "<green>已在当前位置生成预览棋盘（白方朝向 = 你的朝向）");
        MineChessPlugin.msg(player, "<gray>用 <white>/chess tune <gray>滚轮调参可实时生效；再次 <white>/chess preview <gray>移除");
    }

    private void createPreview(Location center) {
        ChessMatch dummy = new ChessMatch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TimeControl.CASUAL);
        previewCenter = center.clone();
        preview = new ChessTableView(plugin, dummy, center, true);
        preview.build();
        preview.updateClocks();
    }

    public void removePreview() {
        if (preview != null) {
            preview.remove();
            preview = null;
        }
        previewCenter = null;
    }

    // ---------- 滚轮实时调参 ----------

    public record TuneSpec(String path, double step, double min, double max, String label) {}

    private static final Map<String, TuneSpec> TUNES = new LinkedHashMap<>();

    static {
        TUNES.put("seat-y", new TuneSpec("seat.y-offset", 0.1, -2.0, 12.0, "人物高度（越高越俯视）"));
        TUNES.put("seat-distance", new TuneSpec("board.seat-distance", 0.1, 0.0, 6.0, "座位离棋盘中心的距离"));
        TUNES.put("board-height", new TuneSpec("board.height", 0.05, 0.0, 4.0, "棋盘高度"));
        TUNES.put("board-size", new TuneSpec("board.size", 0.02, 0.4, 1.2, "每格边长"));
        TUNES.put("piece-scale", new TuneSpec("board.piece-scale", 0.02, 0.2, 1.5, "棋子大小"));
        TUNES.put("clock-height", new TuneSpec("board.clock-height", 0.05, -0.5, 4.0, "棋钟高度"));
        TUNES.put("model-yaw", new TuneSpec("visual.model-yaw", 15, -180, 180, "模型朝向"));
    }

    private final Map<UUID, String> tuning = new HashMap<>();

    public List<String> tuneKeys() {
        return new ArrayList<>(TUNES.keySet());
    }

    public boolean tuning(Player player) {
        return tuning.containsKey(player.getUniqueId());
    }

    public void startTune(Player player, String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        if (!TUNES.containsKey(normalized)) {
            MineChessPlugin.msg(player, "<red>可调参数：" + String.join(" / ", TUNES.keySet()));
            return;
        }
        if (!player.hasPermission("minechess.admin")) {
            MineChessPlugin.msg(player, "<red>没有权限");
            return;
        }
        tuning.put(player.getUniqueId(), normalized);
        showTune(player);
        MineChessPlugin.msg(player, "<gray>滚轮调整 <white>" + normalized + " <gray>，输入 <white>/chess tune <gray>保存并退出");
    }

    public void stopTune(Player player) {
        String key = tuning.remove(player.getUniqueId());
        if (key == null) return;
        plugin.saveConfig();
        player.sendActionBar(Component.empty());
        MineChessPlugin.msg(player, "<green>已保存 <white>" + key + " = <yellow>"
                + String.format("%.2f", plugin.getConfig().getDouble(TUNES.get(key).path(), 0)));
    }

    public void tuneScroll(Player player, int previous, int now) {
        String key = tuning.get(player.getUniqueId());
        if (key == null) return;
        TuneSpec spec = TUNES.get(key);
        int delta = now - previous;
        if (delta > 4) delta -= 9;
        if (delta < -4) delta += 9;
        if (delta == 0) return;
        double value = plugin.getConfig().getDouble(spec.path(), plugin.cfg(spec.path(), 0));
        value = Math.max(spec.min(), Math.min(spec.max(), value + delta * spec.step()));
        plugin.getConfig().set(spec.path(), value);
        applyTune(key);
        showTune(player);
    }

    public void setTune(Player player, String key, double value) {
        TuneSpec spec = TUNES.get(key == null ? "" : key.toLowerCase());
        if (spec == null) {
            MineChessPlugin.msg(player, "<red>可调参数：" + String.join(" / ", TUNES.keySet()));
            return;
        }
        value = Math.max(spec.min(), Math.min(spec.max(), value));
        plugin.getConfig().set(spec.path(), value);
        plugin.saveConfig();
        applyTune(key.toLowerCase());
        MineChessPlugin.msg(player, "<green>" + spec.path() + " = <yellow>" + String.format("%.2f", value));
    }

    private void showTune(Player player) {
        String key = tuning.get(player.getUniqueId());
        if (key == null) return;
        TuneSpec spec = TUNES.get(key);
        player.sendActionBar(MineChessPlugin.mm("<gold>" + spec.label() + " <white>" + spec.path() + " <yellow>"
                + String.format("%.2f", plugin.getConfig().getDouble(spec.path(), 0))
                + " <dark_gray>| <gray>滚轮调整 · <white>/chess tune <gray>保存退出"));
    }

    /** 调参实时生效：坐姿直接挪座位；棋盘/棋子参数重建展示层（对局状态不受影响）。 */
    private void applyTune(String key) {
        if ("seat-y".equals(key)) {
            for (ChessTableView table : tables.values()) table.reloadSeat();
            if (preview != null) preview.reloadSeat();
            return;
        }
        rebuildTables();
        if (preview != null && previewCenter != null) {
            Location center = previewCenter.clone();
            removePreview();
            createPreview(center);
        }
    }

    private void rebuildTables() {
        for (UUID matchId : new ArrayList<>(tables.keySet())) {
            ChessMatch match = matches.get(matchId);
            ChessTableView old = tables.get(matchId);
            Integer slot = slots.get(matchId);
            if (match == null || old == null || slot == null) continue;
            ChessTableView fresh = new ChessTableView(plugin, match, plugin.arena().center(slot));
            fresh.build();
            fresh.updateClocks();
            tables.put(matchId, fresh);
            for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
                UUID id = match.playerOf(side);
                if (Bukkit.getPlayer(id) != null) fresh.mount(id);
            }
            if (match.result() != null) fresh.showResult(match.result());
            old.remove();
        }
    }

    public Collection<ChessMatch> games() {
        return java.util.Collections.unmodifiableCollection(matches.values());
    }

    public ChessMatch match(UUID id) {
        return id == null ? null : matches.get(id);
    }

    public ChessMatch matchOf(UUID player) {
        return player == null ? null : matches.get(playerMatch.get(player));
    }

    public ChessTableView table(ChessMatch match) {
        return match == null ? null : tables.get(match.id());
    }

    public Collection<ChessRoom> rooms() {
        return java.util.Collections.unmodifiableCollection(rooms.values());
    }

    public BotService bots() {
        return bots;
    }

    /** 玩家当前选中的格子（2D 棋盘界面用）。 */
    public Square selection(UUID player) {
        return selected.get(player);
    }

    public boolean isBot(UUID player) {
        return bots.isBot(player);
    }

    public ChessRoom room(UUID id) {
        return id == null ? null : rooms.get(id);
    }

    public ChessRoom roomOf(UUID player) {
        return player == null ? null : rooms.get(playerRoom.get(player));
    }

    /** 观战中的玩家所在的等待房间（房间观战席，开局前）。 */
    public ChessRoom spectatorRoomOf(UUID player) {
        if (player == null) return null;
        for (ChessRoom room : rooms.values()) {
            if (room.isSpectator(player)) return room;
        }
        return null;
    }

    /** 玩家观战中的对局（已开局）。 */
    public ChessMatch spectatorMatchOf(UUID player) {
        UUID matchId = player == null ? null : spectatorMatch.get(player);
        return matchId == null ? null : matches.get(matchId);
    }

    /** 玩家当前可操作/查看的对局：自己参战或正在观战。 */
    public ChessMatch watchedMatchOf(UUID player) {
        ChessMatch match = matchOf(player);
        return match != null ? match : spectatorMatchOf(player);
    }

    public boolean isSpectator(UUID player) {
        return player != null && spectatorMatch.containsKey(player);
    }

    /** 当前对局的观战者（按席位顺序，可能少于 4 人）。 */
    public List<UUID> spectatorsOf(ChessMatch match) {
        List<UUID> slots = match == null ? null : matchSpectatorSeats.get(match.id());
        if (slots == null) return List.of();
        List<UUID> result = new ArrayList<>(slots.size());
        for (UUID id : slots) {
            if (id != null) result.add(id);
        }
        return result;
    }

    /** 观战席位数（含空位）。 */
    public int spectatorSlotsOf(ChessMatch match) {
        return match == null ? 0 : ChessRoom.SPECTATOR_SLOTS;
    }

    /** 玩家是否已经在对局、房间或观战中（挑战/加入房间前的占用检查）。 */
    public boolean busy(UUID player) {
        return matchOf(player) != null || roomOf(player) != null
                || spectatorMatch.containsKey(player) || spectatorRoomOf(player) != null;
    }

    public void reloadSeat() {
        for (ChessTableView table : tables.values()) table.reloadSeat();
    }

    // ---------- 房间（创建 / 加入 / 准备 / 开始） ----------

    public void createRoom(Player host, TimeControl timeControl) {
        if (busy(host.getUniqueId())) {
            MineChessPlugin.msg(host, "<red>你已经在房间或对局中了");
            return;
        }
        if (timeControl == null) timeControl = TimeControl.parse("10+0");
        ChessRoom room = new ChessRoom(UUID.randomUUID(), ++roomCounter, host.getUniqueId(), timeControl);
        rooms.put(room.id(), room);
        playerRoom.put(host.getUniqueId(), room.id());
        plugin.log("[room] " + host.getName() + " 创建房间 #" + room.number() + " (" + timeControl.label() + ")");
        plugin.ui().openRoom(host, room);
    }

    public void joinRoom(Player player, ChessRoom room) {
        if (room == null) return;
        if (busy(player.getUniqueId())) {
            MineChessPlugin.msg(player, "<red>你已经在房间或对局中了");
            return;
        }
        if (!room.join(player.getUniqueId())) {
            MineChessPlugin.msg(player, "<red>无法加入该房间（已满或不存在）");
            plugin.ui().refresh(player);
            return;
        }
        playerRoom.put(player.getUniqueId(), room.id());
        plugin.log("[room] " + player.getName() + " 加入房间 #" + room.number());
        plugin.ui().openRoom(player, room);
        refreshRoom(room);
    }

    public void leaveRoom(Player player) {
        ChessRoom room = roomOf(player.getUniqueId());
        if (room == null) {
            ChessRoom watched = spectatorRoomOf(player.getUniqueId());
            if (watched == null) {
                MineChessPlugin.msg(player, "<red>你不在房间中");
                return;
            }
            plugin.log("[watch] " + player.getName() + " 退出房间 #" + watched.number() + " 观战");
            watched.leaveSpectator(player.getUniqueId());
            plugin.ui().openLobby(player);
            refreshRoom(watched);
            MineChessPlugin.msg(player, "<gray>已退出观战");
            return;
        }
        plugin.log("[room] " + player.getName() + " 离开房间 #" + room.number());
        removeFromRoom(player.getUniqueId());
        plugin.ui().openLobby(player);
    }

    // ---------- 观战（开局前占房间观战席，开局后进入对局观战） ----------

    /** 加入等待中房间的观战席（4 个席位）。 */
    public boolean spectateRoom(Player player, ChessRoom room) {
        if (room == null || player == null) return false;
        UUID id = player.getUniqueId();
        if (room.isPlayer(id)) {
            MineChessPlugin.msg(player, "<red>你是本局棋手，不能观战");
            return false;
        }
        if (room.isSpectator(id)) return true;
        if (busy(id)) {
            MineChessPlugin.msg(player, "<red>你已经在房间或对局中了");
            return false;
        }
        if (!room.joinSpectator(id)) {
            MineChessPlugin.msg(player, "<red>该房间观战席已满");
            plugin.ui().refresh(player);
            return false;
        }
        plugin.log("[watch] " + player.getName() + " 加入房间 #" + room.number() + " 观战");
        MineChessPlugin.msg(player, "<green>已加入观战，开局后会传送到棋盘两侧");
        plugin.ui().openRoom(player, room);
        refreshRoom(room);
        return true;
    }

    /** 加入进行中对局的观战（最多 4 人，座位在棋盘两侧）。 */
    public boolean spectate(Player player, ChessMatch match) {
        if (match == null || player == null) return false;
        UUID id = player.getUniqueId();
        if (match.involves(id)) {
            MineChessPlugin.msg(player, "<red>你是本局棋手，不能观战");
            return false;
        }
        if (spectatorMatch.containsKey(id)) {
            return spectatorMatch.get(id).equals(match.id());
        }
        if (busy(id)) {
            MineChessPlugin.msg(player, "<red>你已经在房间或对局中了");
            return false;
        }
        int slot = freeSpectatorSlot(match);
        if (slot < 0) {
            MineChessPlugin.msg(player, "<red>该对局观战席已满（4/4）");
            return false;
        }
        addSpectator(match, player, slot);
        send(match, "<gray>" + player.getName() + " 进入观战");
        plugin.log("[watch] " + player.getName() + " 观战 "
                + plugin.playerName(match.whitePlayer()) + " vs " + plugin.playerName(match.blackPlayer()));
        MineChessPlugin.msg(player, "<green>已进入观战（右键打开对局面板，面板里可开 2D 棋盘）");
        plugin.ui().refresh(player);
        return true;
    }

    /** 退出观战：房间观战席或对局观战席都处理。 */
    public void leaveSpectate(Player player) {
        UUID id = player.getUniqueId();
        ChessRoom room = spectatorRoomOf(id);
        if (room != null) {
            room.leaveSpectator(id);
            MineChessPlugin.msg(player, "<gray>已退出观战");
            refreshRoom(room);
            return;
        }
        ChessMatch match = spectatorMatchOf(id);
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在观战中");
            return;
        }
        removeSpectator(id, true);
        send(match, "<gray>" + player.getName() + " 退出观战");
        MineChessPlugin.msg(player, "<gray>已退出观战");
    }

    private int freeSpectatorSlot(ChessMatch match) {
        List<UUID> slots = matchSpectatorSeats.computeIfAbsent(match.id(),
                k -> new ArrayList<>(java.util.Collections.nCopies(ChessRoom.SPECTATOR_SLOTS, null)));
        return slots.indexOf(null);
    }

    private void addSpectator(ChessMatch match, Player player, int slot) {
        List<UUID> slots = matchSpectatorSeats.computeIfAbsent(match.id(),
                k -> new ArrayList<>(java.util.Collections.nCopies(ChessRoom.SPECTATOR_SLOTS, null)));
        if (slot >= 0 && slot < slots.size()) slots.set(slot, player.getUniqueId());
        spectatorMatch.put(player.getUniqueId(), match.id());
        back.put(player.getUniqueId(), player.getLocation());
        placeSpectator(player, match, slot);
    }

    /** 把观战者放到第 slot 个观战席（棋盘侧翼）。 */
    private void placeSpectator(Player player, ChessMatch match, int slot) {
        ChessTableView table = tables.get(match.id());
        if (table == null) return;
        Location stand = table.spectatorStand(slot);
        if (stand != null) player.teleport(stand);
        table.mountSpectator(player.getUniqueId(), slot);
        table.keepSpectatorSeat(player.getUniqueId(), slot);
    }

    private void removeSpectator(UUID id, boolean teleportBack) {
        UUID matchId = spectatorMatch.remove(id);
        if (matchId != null) {
            List<UUID> slots = matchSpectatorSeats.get(matchId);
            if (slots != null) {
                slots.set(slots.indexOf(id), null);
                if (slots.stream().allMatch(java.util.Objects::isNull)) matchSpectatorSeats.remove(matchId);
            }
        }
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            if (player.getVehicle() != null) player.leaveVehicle();
            plugin.ui().close(player);
            if (teleportBack && back.containsKey(id)) player.teleport(back.get(id));
        }
        back.remove(id);
    }

    private void removeFromRoom(UUID player) {
        ChessRoom room = roomOf(player);
        if (room == null) {
            room = spectatorRoomOf(player);
            if (room == null) return;
            room.leaveSpectator(player);
            refreshRoom(room);
            return;
        }
        boolean botRoom = bots.isBot(room.host()) || bots.isBot(room.guest());
        boolean hostLeavesWithBot = room.isHost(player) && bots.isBot(room.guest());
        playerRoom.remove(player);
        boolean alive = room.leave(player);
        if (!alive || room.isEmpty() || hostLeavesWithBot || botRoom) {
            final UUID roomId = room.id();
            playerRoom.values().removeIf(id -> id.equals(roomId));
            rooms.remove(roomId);
            cleanupRoomBots(room);
        } else {
            refreshRoom(room);
        }
    }

    /** 房间解散/开始时清掉还在等待的 AI 占位。 */
    private void cleanupRoomBots(ChessRoom room) {
        for (UUID id : new UUID[]{room.host(), room.guest()}) {
            if (bots.isBot(id)) bots.remove(id);
        }
    }

    public void toggleReady(Player player) {
        ChessRoom room = roomOf(player.getUniqueId());
        if (room == null || room.isHost(player.getUniqueId())) return;
        room.toggleReady(player.getUniqueId());
        refreshRoom(room);
    }

    public void cycleTime(Player player) {
        ChessRoom room = roomOf(player.getUniqueId());
        if (room == null || !room.isHost(player.getUniqueId())) return;
        List<TimeControl> presets = TimeControl.presets();
        int index = presets.indexOf(room.timeControl());
        room.timeControl(presets.get((index + 1) % presets.size()));
        MineChessPlugin.msg(player, "<gray>时限已切换为 <white>" + room.timeControl().describe());
        refreshRoom(room);
    }

    public void startRoom(Player player) {
        ChessRoom room = roomOf(player.getUniqueId());
        if (room == null) {
            MineChessPlugin.msg(player, "<red>你不在房间中");
            return;
        }
        if (!room.isHost(player.getUniqueId())) {
            MineChessPlugin.msg(player, "<red>只有房主可以开始游戏");
            return;
        }
        if (!room.canStart()) {
            MineChessPlugin.msg(player, "<gray>需要对手加入并准备后才能开始");
            return;
        }
        UUID host = room.host();
        UUID guest = room.guest();
        TimeControl timeControl = room.timeControl();
        UUID white = room.whitePlayer();
        UUID black = room.blackPlayer();
        List<UUID> watchers = new ArrayList<>(room.spectators());
        // 先占用竞技场并建好棋盘：失败时房间原样保留，玩家可以重试
        int slot = plugin.arena().allocate();
        if (slot < 0) {
            MineChessPlugin.msg(player, "<red>没有空闲棋盘，请稍后再试或让管理员增加竞技场");
            return;
        }
        ChessMatch match = new ChessMatch(UUID.randomUUID(), white, black, timeControl);
        ChessTableView table = buildTable(match, slot);
        if (table == null) {
            plugin.arena().release(slot);
            return;
        }
        playerRoom.remove(host);
        playerRoom.remove(guest);
        rooms.remove(room.id());
        plugin.log("[room] #" + room.number() + " 开始对局（房主执" + (room.hostColor() == Side.WHITE ? "白" : "黑")
                + (watchers.isEmpty() ? "）" : "，观战 " + watchers.size() + " 人）"));
        if (commitMatch(match, table, slot, watchers)) return;
        // 提交失败：把房间挂回，玩家可以重试
        rooms.put(room.id(), room);
        playerRoom.put(host, room.id());
        if (guest != null) playerRoom.put(guest, room.id());
        for (UUID id : new UUID[]{host, guest}) {
            Player member = Bukkit.getPlayer(id);
            if (member != null) plugin.ui().openRoom(member, room);
        }
        MineChessPlugin.msg(player, "<red>开始对局失败，房间已保留，请重试");
    }

    /** 房主切换执色（白/黑）。 */
    public void cycleColor(Player player) {
        ChessRoom room = roomOf(player.getUniqueId());
        if (room == null || !room.isHost(player.getUniqueId())) return;
        room.swapHostColor();
        MineChessPlugin.msg(player, "<gray>你执 <white>"
                + (room.hostColor() == Side.WHITE ? "白" : "黑") + " <gray>，对手执白"
                + (room.hostColor() == Side.WHITE ? "黑" : ""));
        refreshRoom(room);
    }

    private void refreshRoom(ChessRoom room) {
        List<UUID> ids = new ArrayList<>();
        if (room.host() != null) ids.add(room.host());
        if (room.guest() != null) ids.add(room.guest());
        ids.addAll(room.spectators());
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) plugin.ui().refresh(player);
        }
    }

    /** 房主邀请在线玩家加入房间。 */
    public boolean inviteToRoom(Player host, Player target) {
        ChessRoom room = roomOf(host.getUniqueId());
        if (room == null || !room.isHost(host.getUniqueId())) {
            MineChessPlugin.msg(host, "<red>只有房主可以邀请玩家");
            return false;
        }
        if (room.hasGuest()) {
            MineChessPlugin.msg(host, "<red>房间已经有对手了");
            return false;
        }
        if (target.getUniqueId().equals(host.getUniqueId())) {
            MineChessPlugin.msg(host, "<red>不能邀请自己");
            return false;
        }
        if (busy(target.getUniqueId())) {
            MineChessPlugin.msg(host, "<red>" + target.getName() + " 已经在房间或对局中了");
            return false;
        }
        Challenge challenge = new Challenge(host.getUniqueId(), target.getUniqueId(),
                room.timeControl(), room.id(), System.currentTimeMillis());
        incoming.put(target.getUniqueId(), challenge);
        plugin.log("[invite] " + host.getName() + " -> " + target.getName() + " 房间 #" + room.number());
        plugin.ui().refresh(target);
        MineChessPlugin.msg(host, "<gray>已邀请 <white>" + target.getName() + " <gray>加入房间 #" + room.number());
        target.sendMessage(MineChessPlugin.mm("<gold>" + host.getName() + " <white>邀请你加入国际象棋房间 #"
                + room.number() + " <gray>（" + room.timeControl().describe() + "）"));
        Component acceptButton = MineChessPlugin.mm(
                "<click:run_command:'/chess accept'><hover:show_text:'接受邀请'>"
                        + "<green><bold>[接受]</bold></hover></click>");
        Component declineButton = MineChessPlugin.mm(
                "<click:run_command:'/chess decline'><hover:show_text:'拒绝邀请'>"
                        + "<red><bold>[拒绝]</bold></hover></click>");
        target.sendMessage(acceptButton.append(Component.space()).append(declineButton));
        return true;
    }

    /** 房主添加一个 AI 对手（后端由 ai.bot 配置决定，默认随机落子）。 */
    public boolean addBot(Player host) {
        ChessRoom room = roomOf(host.getUniqueId());
        if (room == null || !room.isHost(host.getUniqueId())) {
            MineChessPlugin.msg(host, "<red>只有房主可以添加 AI");
            return false;
        }
        if (room.hasGuest()) {
            MineChessPlugin.msg(host, "<red>房间已经有对手了");
            return false;
        }
        ChessBot bot = Bots.create(plugin, plugin.getConfig().getString("ai.bot", "random"));
        UUID botId = bots.spawn(bot);
        room.join(botId);
        room.toggleReady(botId); // AI 自动准备
        plugin.log("[bot] " + host.getName() + " 添加 AI 对手 " + bot.id() + " -> 房间 #" + room.number());
        MineChessPlugin.msg(host, "<green>已添加 AI 对手：<white>" + bot.displayName()
                + " <gray>（当前实现：随机落子，后续可换真正引擎）");
        plugin.ui().openRoom(host, room);
        return true;
    }

    /** 房间里的 AI 占位符（供 UI 显示）。 */
    public boolean roomHasBot(ChessRoom room) {
        return room != null && bots.isBot(room.guest());
    }

    // ---------- 交互体注册 ----------

    public void registerHit(UUID entityId, ChessTableView.Hit hit) {
        hits.put(entityId, hit);
    }

    public ChessTableView.Hit hit(UUID entityId) {
        return hits.get(entityId);
    }

    public void unregisterHit(UUID entityId) {
        hits.remove(entityId);
    }

    public void unregisterHits(ChessTableView table) {
        hits.entrySet().removeIf(entry -> entry.getValue().table() == table);
    }

    // ---------- 挑战 ----------

    public Challenge incoming(UUID player) {
        return incoming.get(player);
    }

    public boolean challenge(Player challenger, Player target, TimeControl timeControl) {
        if (challenger.getUniqueId().equals(target.getUniqueId())) {
            MineChessPlugin.msg(challenger, "<red>不能挑战自己");
            return false;
        }
        if (busy(challenger.getUniqueId())) {
            MineChessPlugin.msg(challenger, "<red>你已经在房间或对局中了");
            return false;
        }
        if (busy(target.getUniqueId())) {
            MineChessPlugin.msg(challenger, "<red>" + target.getName() + " 正在房间或对局中");
            return false;
        }
        Challenge challenge = new Challenge(challenger.getUniqueId(), target.getUniqueId(),
                timeControl, null, System.currentTimeMillis());
        incoming.put(target.getUniqueId(), challenge);
        plugin.log("[challenge] " + challenger.getName() + " -> " + target.getName()
                + " (" + timeControl.label() + ")");
        plugin.ui().refresh(target);
        MineChessPlugin.msg(challenger, "<gray>已向 <white>" + target.getName() + " <gray>发起挑战：<white>"
                + timeControl.describe());
        target.sendMessage(MineChessPlugin.mm("<gold>" + challenger.getName() + " <white>邀请你进行国际象棋对局"));
        Component acceptButton = MineChessPlugin.mm(
                "<click:run_command:'/chess accept'><hover:show_text:'接受挑战'>"
                        + "<green><bold>[接受]</bold></hover></click>");
        Component declineButton = MineChessPlugin.mm(
                "<click:run_command:'/chess decline'><hover:show_text:'拒绝挑战'>"
                        + "<red><bold>[拒绝]</bold></hover></click>");
        target.sendMessage(MineChessPlugin.mm("<gray>时限：<white>" + timeControl.describe() + " <gray>| 你执黑  ")
                .append(acceptButton).append(Component.space()).append(declineButton));
        return true;
    }

    public void accept(Player player) {
        Challenge challenge = incoming.remove(player.getUniqueId());
        if (challenge == null) {
            MineChessPlugin.msg(player, "<red>没有待响应的邀请");
            return;
        }
        if (challenge.expired(System.currentTimeMillis(), plugin.cfg("game.challenge-timeout", 60) * 1000L)) {
            MineChessPlugin.msg(player, "<red>邀请已过期");
            return;
        }
        Player challenger = Bukkit.getPlayer(challenge.challenger());
        if (challenger == null) {
            MineChessPlugin.msg(player, "<red>对方已离线");
            return;
        }
        if (busy(player.getUniqueId())) {
            MineChessPlugin.msg(player, "<red>你已经在房间或对局中了");
            return;
        }
        if (challenge.roomInvite()) {
            ChessRoom room = room(challenge.roomId());
            if (room == null || room.hasGuest() || !room.isHost(challenger.getUniqueId()) || busy(challenger.getUniqueId())) {
                MineChessPlugin.msg(player, "<red>房间已失效或已满");
                return;
            }
            joinRoom(player, room);
            MineChessPlugin.msg(player, "<gray>已加入 <white>" + challenger.getName() + " <gray>的房间，点「准备」等待开始");
            return;
        }
        if (busy(challenger.getUniqueId())) {
            MineChessPlugin.msg(player, "<red>对方已经在房间或对局中了");
            return;
        }
        ChessMatch match = new ChessMatch(UUID.randomUUID(), challenger.getUniqueId(),
                player.getUniqueId(), challenge.timeControl());
        startMatch(match);
    }

    public void decline(Player player) {
        Challenge challenge = incoming.remove(player.getUniqueId());
        if (challenge == null) {
            MineChessPlugin.msg(player, "<red>没有待响应的挑战");
            return;
        }
        Player challenger = Bukkit.getPlayer(challenge.challenger());
        if (challenger != null) {
            MineChessPlugin.msg(challenger, "<gray>" + player.getName() + " 拒绝了你的挑战");
        }
        MineChessPlugin.msg(player, "<gray>已拒绝挑战");
        plugin.ui().refresh(player);
    }

    private void startMatch(ChessMatch match) {
        startMatch(match, List.of());
    }

    private void startMatch(ChessMatch match, List<UUID> spectators) {
        int slot = plugin.arena().allocate();
        if (slot < 0) {
            send(match, "<red>没有空闲棋盘，请稍后再试或让管理员增加竞技场");
            return;
        }
        ChessTableView table = buildTable(match, slot);
        if (table == null) {
            plugin.arena().release(slot);
            return;
        }
        commitMatch(match, table, slot, spectators);
    }

    /** 构建棋盘；失败时清掉半成品并返回 null（此时不占用任何对局资源）。 */
    private ChessTableView buildTable(ChessMatch match, int slot) {
        ChessTableView table = new ChessTableView(plugin, match, plugin.arena().center(slot));
        try {
            table.build();
            return table;
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "棋盘生成失败", e);
            try {
                table.remove();
            } catch (Throwable ignored) {
            }
            send(match, "<red>棋盘生成失败，已取消本局");
            return null;
        }
    }

    /** 提交对局：登记资源 → 传送玩家/观战者 → 启动计时；失败整体回收并返回 false。 */
    private boolean commitMatch(ChessMatch match, ChessTableView table, int slot, List<UUID> spectators) {
        try {
            beginMatch(match, table, slot, spectators);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "开始对局失败", e);
            cleanup(match, true);
            return false;
        }
    }

    /**
     * 先把资源登记进 maps，任何后续异常都能被 {@link #cleanup} 完整回收。
     */
    private void beginMatch(ChessMatch match, ChessTableView table, int slot, List<UUID> spectators) {
        slots.put(match.id(), slot);
        matches.put(match.id(), match);
        tables.put(match.id(), table);

        for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
            UUID id = match.playerOf(side);
            Player player = Bukkit.getPlayer(id);
            playerMatch.put(id, match.id());
            if (player == null) continue;
            // 先关掉房间/大厅界面，再打开对局面板（原版箱子界面也能正确切换）
            plugin.ui().close(player);
            player.closeInventory();
            back.put(id, player.getLocation());
            Location seat = table.stand(side);
            if (seat != null) player.teleport(seat);
            table.mount(id);
            table.keepSeat(id); // 入场立即锁定俯视视角
            grantReach(player);
            plugin.ui().openMatch(player, match);
        }

        // 房间观战席随开局迁移到棋盘两侧
        int watcherIndex = 0;
        for (UUID watcher : spectators) {
            if (watcherIndex >= table.spectatorSeatCount()) break;
            int watcherSlot = freeSpectatorSlot(match);
            if (watcherSlot < 0) break;
            matchSpectatorSeats.get(match.id()).set(watcherSlot, watcher);
            spectatorMatch.put(watcher, match.id());
            Player watcherPlayer = Bukkit.getPlayer(watcher);
            if (watcherPlayer != null) {
                back.put(watcher, watcherPlayer.getLocation());
                placeSpectator(watcherPlayer, match, watcherSlot);
                MineChessPlugin.msg(watcherPlayer, "<green>对局开始，你在棋盘侧翼观战（右键打开对局面板）");
            }
            watcherIndex++;
        }

        match.start();
        table.updateClocks();
        plugin.log("[match] " + plugin.playerName(match.whitePlayer()) + "(白) vs "
                + plugin.playerName(match.blackPlayer()) + "(黑) " + match.timeControl().label()
                + " @ " + plugin.arena().name(slot));
        send(match, "<gold>对局开始！<white>" + plugin.playerName(match.whitePlayer()) + " <gray>执白 vs <white>"
                + plugin.playerName(match.blackPlayer()) + " <gray>执黑 | <white>" + match.timeControl().describe());
        notifyTurn(match);
        // 开局提示只发给在线真人：AI 用虚拟 UUID，Bukkit.getPlayer 会返回 null
        for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
            Player player = Bukkit.getPlayer(match.playerOf(side));
            if (player != null) {
                MineChessPlugin.msg(player, "<gray>点击自己的棋子选择，再点绿色提示格落子；"
                        + "<white>/chess resign <gray>认输，<white>/chess draw <gray>提和");
            }
        }
    }

    // ---------- 落子 ----------

    public void squareClick(Player player, ChessTableView table, Square square) {
        ChessMatch match = table.match();
        if (matchOf(player.getUniqueId()) != match) return;
        if (match.phase() != ChessMatch.Phase.PLAYING) return;
        if (match.promotionPending()) {
            MineChessPlugin.msg(player, "<gray>请先选择升变的棋子");
            return;
        }
        UUID id = player.getUniqueId();
        Side side = match.sideOf(id);
        Square current = selected.get(id);
        Piece piece = match.pieceAt(square);

        if (current == null) {
            if (piece != Piece.NONE && piece.getPieceSide() == side) {
                select(player, match, table, square);
            } else if (match.turn() != side) {
                MineChessPlugin.msg(player, "<gray>现在不是你的回合");
            } else {
                MineChessPlugin.msg(player, "<gray>请选择自己的棋子");
            }
            return;
        }
        if (current == square) {
            selected.remove(id);
            table.clearSelection();
            plugin.ui().update(match);
            return;
        }
        if (piece != Piece.NONE && piece.getPieceSide() == side) {
            select(player, match, table, square);
            return;
        }
        MoveOutcome outcome = match.play(id, current, square, null);
        handleOutcome(player, match, table, outcome, current, square);
    }

    private void select(Player player, ChessMatch match, ChessTableView table, Square square) {
        selected.put(player.getUniqueId(), square);
        table.showSelection(square, match.legalTargets(square), match.captureTargets(square));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
        plugin.ui().update(match);
    }

    private void handleOutcome(Player player, ChessMatch match, ChessTableView table,
                               MoveOutcome outcome, Square from, Square to) {
        UUID id = player.getUniqueId();
        switch (outcome.status()) {
            case OK -> {
                selected.remove(id);
                table.applyMove(outcome.move());
                plugin.ui().update(match);
                if (outcome.result() != null) finish(match, outcome.result());
                else notifyTurn(match);
            }
            case NEEDS_PROMOTION -> {
                selected.remove(id);
                table.clearSelection();
                table.showPromotion(match.sideOf(id));
                promotionDeadline.put(match.id(), System.currentTimeMillis()
                        + plugin.cfg("game.promotion-timeout", 30) * 1000L);
                plugin.ui().update(match);
            }
            case NOT_YOUR_TURN -> MineChessPlugin.msg(player, "<gray>现在不是你的回合");
            case OPPONENT_PIECE -> MineChessPlugin.msg(player, "<gray>那是对方的棋子");
            case NO_PIECE -> MineChessPlugin.msg(player, "<gray>这里没有棋子");
            case ILLEGAL -> {
                selected.remove(id);
                table.clearSelection();
                plugin.ui().update(match);
                MineChessPlugin.msg(player, "<red>" + com.minechess.chess.ChessRules.squareName(from)
                        + " → " + com.minechess.chess.ChessRules.squareName(to) + " 不是合法着法");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            }
            default -> {
            }
        }
    }

    /** 命令 /chess move e2e4 用（方便测试与键盘操作）。 */
    public void move(Player player, Square from, Square to, PieceType promotion) {
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在对局中");
            return;
        }
        ChessTableView table = tables.get(match.id());
        MoveOutcome outcome = match.play(player.getUniqueId(), from, to, promotion);
        if (table != null) handleOutcome(player, match, table, outcome, from, to);
        else if (outcome.result() != null) finish(match, outcome.result());
    }

    public void promotionClick(Player player, ChessTableView table, int index) {
        ChessMatch match = table.match();
        if (matchOf(player.getUniqueId()) != match) return;
        if (!match.promotionPending() || match.promotionSide() != match.sideOf(player.getUniqueId())) {
            MineChessPlugin.msg(player, "<gray>现在不需要选择升变");
            return;
        }
        PieceType type = switch (index) {
            case 0 -> PieceType.QUEEN;
            case 1 -> PieceType.ROOK;
            case 2 -> PieceType.BISHOP;
            case 3 -> PieceType.KNIGHT;
            default -> null;
        };
        if (type == null) return;
        promotionDeadline.remove(match.id());
        MoveOutcome outcome = match.promote(player.getUniqueId(), type);
        if (outcome.ok()) {
            table.hidePromotion();
            table.applyMove(outcome.move());
            plugin.ui().update(match);
            if (outcome.result() != null) finish(match, outcome.result());
        } else {
            MineChessPlugin.msg(player, "<red>升变失败：" + outcome.status());
        }
    }

    // ---------- 认输 / 和棋 ----------

    public void resign(Player player) {
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null || match.phase() != ChessMatch.Phase.PLAYING) {
            MineChessPlugin.msg(player, "<red>你不在进行中的对局里");
            return;
        }
        send(match, "<yellow>" + player.getName() + " <gray>认输");
        finish(match, match.resign(player.getUniqueId()));
    }

    public void offerDraw(Player player) {
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null || match.phase() != ChessMatch.Phase.PLAYING) {
            MineChessPlugin.msg(player, "<red>你不在进行中的对局里");
            return;
        }
        if (!match.offerDraw(player.getUniqueId())) {
            MineChessPlugin.msg(player, "<gray>已经有一条未响应的和棋请求");
            return;
        }
        send(match, "<yellow>" + player.getName() + " <gray>提议和棋");
        UUID opponent = match.playerOf(match.sideOf(player.getUniqueId()).flip());
        Player other = Bukkit.getPlayer(opponent);
        if (other != null) {
            Component acceptButton = MineChessPlugin.mm(
                    "<click:run_command:'/chess acceptdraw'><hover:show_text:'接受和棋'>"
                            + "<green><bold>[接受]</bold></hover></click>");
            Component declineButton = MineChessPlugin.mm(
                    "<click:run_command:'/chess declinedraw'><hover:show_text:'拒绝和棋'>"
                            + "<red><bold>[拒绝]</bold></hover></click>");
            other.sendMessage(MineChessPlugin.mm("<yellow>对方提议和棋： ")
                    .append(acceptButton).append(Component.space()).append(declineButton));
        }
        plugin.ui().onDrawOffer(match);
    }

    public void acceptDraw(Player player) {
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null || !match.acceptDraw(player.getUniqueId())) {
            MineChessPlugin.msg(player, "<red>没有可接受的和棋请求");
            return;
        }
        send(match, "<yellow>双方同意和棋");
        finish(match, match.result());
    }

    public void declineDraw(Player player) {
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null || match.drawOffer() == null) {
            MineChessPlugin.msg(player, "<red>没有可拒绝的和棋请求");
            return;
        }
        match.declineDraw();
        send(match, "<gray>" + player.getName() + " 拒绝了和棋");
        plugin.ui().update(match);
    }

    public void claimDraw(Player player, DrawClaim claim) {
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在对局中");
            return;
        }
        ChessResult result = match.claimDraw(player.getUniqueId(), claim);
        if (result == null) {
            MineChessPlugin.msg(player, "<red>现在不能申请该和棋（需要轮到你走棋且条件成立）");
            return;
        }
        send(match, "<yellow>" + player.getName() + " 申请和棋：" + result.termination().label());
        finish(match, result);
    }

    public void leave(Player player) {
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null) {
            MineChessPlugin.msg(player, "<red>你不在对局中");
            return;
        }
        if (match.phase() == ChessMatch.Phase.PLAYING) {
            send(match, "<yellow>" + player.getName() + " <gray>离开了对局（认输）");
            finish(match, match.resign(player.getUniqueId()));
        } else {
            cleanup(match, true);
        }
    }

    // ---------- 结束 / 清理 ----------

    public void finish(ChessMatch match, ChessResult result) {
        if (result == null) return;
        ChessTableView table = tables.get(match.id());
        promotionDeadline.remove(match.id());
        if (table != null) {
            table.hidePromotion();
            table.clearSelection();
            table.updateClocks();
            table.showResult(result);
        }
        selected.keySet().removeIf(id -> match.involves(id));
        plugin.log("[match] " + plugin.playerName(match.whitePlayer()) + " vs "
                + plugin.playerName(match.blackPlayer()) + " 结束：" + result.describe());
        send(match, "<gold>对局结束：<white>" + result.describe());
        for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
            Player player = Bukkit.getPlayer(match.playerOf(side));
            if (player == null) continue;
            String title = result.isDraw()
                    ? "<yellow><bold>和棋"
                    : (result.winner() == side ? "<gold><bold>你赢了！" : "<red><bold>你输了");
            player.showTitle(Title.title(MineChessPlugin.mm(title),
                    MineChessPlugin.mm("<white>" + result.termination().label()), 10, 60, 20));
        }
        plugin.ui().onGameEnd(match);
        scheduleCleanup(match);
    }

    private void scheduleCleanup(ChessMatch match) {
        if (!cleanupScheduled.add(match.id())) return;
        int stay = plugin.cfg("game.result-stay", 160);
        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(match, true), stay);
    }

    public void cleanup(ChessMatch match, boolean teleportBack) {
        if (!matches.containsKey(match.id())) return;
        matches.remove(match.id());
        promotionDeadline.remove(match.id());
        cleanupScheduled.remove(match.id());
        ChessTableView table = tables.remove(match.id());
        if (table != null) table.remove();
        Integer slot = slots.remove(match.id());
        if (slot != null) plugin.arena().release(slot);

        for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
            UUID id = match.playerOf(side);
            playerMatch.remove(id);
            selected.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                if (player.getVehicle() != null) player.leaveVehicle();
                revokeReach(player);
                plugin.ui().close(player);
                if (teleportBack && back.containsKey(id)) player.teleport(back.get(id));
            }
            back.remove(id);
            grace.remove(id);
            reachBackup.remove(id);
            if (bots.isBot(id)) bots.remove(id);
        }

        // 观战者一并送回原处
        List<UUID> watchers = spectatorsOf(match);
        matchSpectatorSeats.remove(match.id());
        for (UUID id : watchers) {
            spectatorMatch.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                if (player.getVehicle() != null) player.leaveVehicle();
                plugin.ui().close(player);
                if (teleportBack && back.containsKey(id)) player.teleport(back.get(id));
            }
            back.remove(id);
        }
    }

    // ---------- 断线 ----------

    public void quit(Player player) {
        // 玩家退出：清掉 MineUI 会话与调参状态，避免 closed session / UUID 残留
        tuning.remove(player.getUniqueId());
        plugin.ui().close(player);
        // 对局观战者断线：直接离开观战席（人已离线，不传送）
        ChessMatch watched = spectatorMatchOf(player.getUniqueId());
        if (watched != null) {
            removeSpectator(player.getUniqueId(), false);
            send(watched, "<gray>" + player.getName() + " 结束观战");
            return;
        }
        // 房间观战者断线：移出观战席
        ChessRoom watchedRoom = spectatorRoomOf(player.getUniqueId());
        if (watchedRoom != null) {
            watchedRoom.leaveSpectator(player.getUniqueId());
            refreshRoom(watchedRoom);
            return;
        }
        ChessMatch match = matchOf(player.getUniqueId());
        if (match != null && match.phase() == ChessMatch.Phase.PLAYING) {
            grace.put(player.getUniqueId(), System.currentTimeMillis());
            send(match, "<gray>" + player.getName() + " 断线了，等待重连（棋钟继续）…");
            return;
        }
        // 等待中的房间：直接移除（房主离开则移交给等待者）
        if (roomOf(player.getUniqueId()) != null) {
            plugin.log("[room] " + player.getName() + " 断线离开房间");
            removeFromRoom(player.getUniqueId());
        }
    }

    public void reconnect(Player player) {
        // 观战者重连：回到原观战席
        ChessMatch watched = spectatorMatchOf(player.getUniqueId());
        if (watched != null) {
            List<UUID> slots = matchSpectatorSeats.get(watched.id());
            int slot = slots == null ? -1 : slots.indexOf(player.getUniqueId());
            if (slot >= 0) placeSpectator(player, watched, slot);
            return;
        }
        // 房间观战者重连：回到房间界面
        ChessRoom watchedRoom = spectatorRoomOf(player.getUniqueId());
        if (watchedRoom != null) {
            plugin.ui().openRoom(player, watchedRoom);
            return;
        }
        ChessMatch match = matchOf(player.getUniqueId());
        if (match == null || match.phase() != ChessMatch.Phase.PLAYING) return;
        if (grace.remove(player.getUniqueId()) == null) return;
        ChessTableView table = tables.get(match.id());
        if (table != null) {
            Location seat = table.stand(match.sideOf(player.getUniqueId()));
            if (seat != null) player.teleport(seat);
            table.mount(player.getUniqueId());
            table.updateClocks();
        }
        grantReach(player);
        plugin.ui().openMatch(player, match);
        send(match, "<green>" + player.getName() + " 重新连接");
    }

    // ---------- 计时 ----------

    private void tick() {
        long now = System.currentTimeMillis();
        tickCount++;
        boolean uiSecond = tickCount % 2 == 0;
        for (ChessMatch match : new ArrayList<>(matches.values())) {
            if (match.phase() != ChessMatch.Phase.PLAYING) continue;
            ChessTableView table = tables.get(match.id());

            Long deadline = promotionDeadline.get(match.id());
            if (deadline != null && now >= deadline) {
                promotionDeadline.remove(match.id());
                Side side = match.promotionSide();
                if (table != null) table.hidePromotion();
                if (side != null) {
                    MoveOutcome outcome = match.promote(match.playerOf(side), PieceType.QUEEN);
                    send(match, "<gray>升变超时，自动升后");
                    if (table != null && outcome.ok()) table.applyMove(outcome.move());
                    if (outcome.result() != null) finish(match, outcome.result());
                }
                continue;
            }

            Side turn = match.turn();
            if (match.clock(turn).flagged()) {
                if (bots.isBot(match.playerOf(turn))) {
                    // AI 不判超时：立刻替它走一步
                    botMove(match);
                    continue;
                }
                send(match, "<red>" + plugin.playerName(match.playerOf(turn)) + " 超时判负");
                finish(match, match.timeout(turn));
                continue;
            }
            if (table != null) {
                table.updateClocks();
                for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
                    UUID id = match.playerOf(side);
                    if (Bukkit.getPlayer(id) != null) table.keepSeat(id);
                }
                List<UUID> watcherSlots = matchSpectatorSeats.get(match.id());
                if (watcherSlots != null) {
                    for (int i = 0; i < watcherSlots.size(); i++) {
                        UUID id = watcherSlots.get(i);
                        if (id != null && Bukkit.getPlayer(id) != null) table.keepSpectatorSeat(id, i);
                    }
                }
                if (!selected.isEmpty()) table.selectionParticles();
            }
            // AI 回合：稍作停顿后落子
            if (bots.isBot(match.playerOf(turn)) && botThinking.add(match.id())) {
                long delay = Math.max(5, plugin.cfg("ai.move-delay", 30));
                Bukkit.getScheduler().runTaskLater(plugin, () -> botMove(match), delay);
            }
            if (uiSecond) plugin.ui().update(match);
        }

        Iterator<Map.Entry<UUID, Long>> it = grace.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now - entry.getValue() <= plugin.cfg("game.reconnect-grace", 60) * 1000L) continue;
            it.remove();
            ChessMatch match = matchOf(entry.getKey());
            if (match != null && match.phase() == ChessMatch.Phase.PLAYING) {
                send(match, "<red>" + plugin.playerName(entry.getKey()) + " 断线超时，判负");
                finish(match, match.forfeit(entry.getKey()));
            }
        }

        if (uiSecond) {
            Iterator<Map.Entry<UUID, Challenge>> challenges = incoming.entrySet().iterator();
            while (challenges.hasNext()) {
                Map.Entry<UUID, Challenge> entry = challenges.next();
                Challenge challenge = entry.getValue();
                if (!challenge.expired(now, plugin.cfg("game.challenge-timeout", 60) * 1000L)) continue;
                challenges.remove();
                Player challenger = Bukkit.getPlayer(challenge.challenger());
                if (challenger != null) MineChessPlugin.msg(challenger, "<gray>挑战已超时");
                Player target = Bukkit.getPlayer(entry.getKey());
                if (target != null) plugin.ui().refresh(target);
            }
        }
    }

    // ---------- AI 与回合提示 ----------

    /** AI 走一步（纯展示层调度，规则判断仍走 ChessMatch）。 */
    private void botMove(ChessMatch match) {
        botThinking.remove(match.id());
        if (!matches.containsKey(match.id()) || match.phase() != ChessMatch.Phase.PLAYING) return;
        UUID botId = match.playerOf(match.turn());
        if (!bots.isBot(botId)) return;
        Move move = bots.chooseMove(botId, match.board());
        ChessTableView table = tables.get(match.id());
        if (move == null) return;
        PieceType promotion = move.getPromotion() == Piece.NONE ? null : move.getPromotion().getPieceType();
        MoveOutcome outcome = match.play(botId, move.getFrom(), move.getTo(), promotion);
        if (outcome.status() == MoveOutcome.Status.NEEDS_PROMOTION) {
            outcome = match.promote(botId, PieceType.QUEEN);
        }
        if (!outcome.ok()) return;
        if (table != null) table.applyMove(outcome.move());
        plugin.ui().update(match);
        if (outcome.result() != null) {
            finish(match, outcome.result());
        } else {
            notifyTurn(match);
        }
    }

    /** 轮到谁：参照 UNO 的做法给本人弹标题 + 音效。 */
    private void notifyTurn(ChessMatch match) {
        UUID next = match.playerOf(match.turn());
        if (bots.isBot(next)) {
            Player opponent = Bukkit.getPlayer(match.playerOf(match.turn().flip()));
            if (opponent != null) opponent.sendActionBar(MineChessPlugin.mm("<gray>AI 思考中…"));
            return;
        }
        Player player = Bukkit.getPlayer(next);
        if (player == null) return;
        if (plugin.cfg("turn.sound", true)) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.5f);
        }
        if (plugin.cfg("turn.title", true)) {
            player.showTitle(Title.title(MineChessPlugin.mm("<gold><bold>▶ 你的回合"),
                    MineChessPlugin.mm("<gray>点击棋子走棋"), 5, 25, 8));
        }
    }

    // ---------- 工具 ----------

    public void send(ChessMatch match, String mini) {
        Component message = MineChessPlugin.mm(mini);
        for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
            Player player = Bukkit.getPlayer(match.playerOf(side));
            if (player != null) player.sendMessage(message);
        }
        for (UUID watcher : spectatorsOf(match)) {
            Player player = Bukkit.getPlayer(watcher);
            if (player != null) player.sendMessage(message);
        }
    }

    private void grantReach(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attribute == null) return;
        reachBackup.putIfAbsent(player.getUniqueId(), attribute.getBaseValue());
        attribute.setBaseValue(Math.max(attribute.getBaseValue(), plugin.cfg("game.interaction-range", 8.0)));
    }

    private void revokeReach(Player player) {
        Double old = reachBackup.remove(player.getUniqueId());
        if (old == null) return;
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attribute != null) attribute.setBaseValue(old);
    }
}
