package com.minechess.ui;

import com.github.bhlangonijr.chesslib.Side;
import com.minechess.MineChessPlugin;
import com.minechess.chess.MoveRecord;
import com.minechess.chess.TimeControl;
import com.minechess.match.Challenge;
import com.minechess.match.ChessMatch;
import com.minechess.match.ChessRoom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 原版箱子菜单（参考 MineUNO 的 Menus）：
 * 大厅（创建/加入房间、接受挑战）→ 房间列表 → 房间（准备/开始/切换时限）→ 对局面板（棋钟、棋谱、和棋、认输）。
 */
public final class InventoryControlUi implements ControlUi {

    private static final String VIEW_LOBBY = "lobby";
    private static final String VIEW_ROOMS = "rooms";
    private static final String VIEW_ROOM = "room";
    private static final String VIEW_MAIN = "main";
    private static final String VIEW_CONFIRM = "confirm";
    private static final String VIEW_HISTORY = "history";
    private static final String VIEW_RULES = "rules";
    private static final String VIEW_INVITE = "invite";

    private final MineChessPlugin plugin;
    private final NamespacedKey roomKey;
    private final NamespacedKey inviteKey;

    public InventoryControlUi(MineChessPlugin plugin) {
        this.plugin = plugin;
        this.roomKey = new NamespacedKey(plugin, "room");
        this.inviteKey = new NamespacedKey(plugin, "invite");
    }

    /** 箱子界面的标识：事件里靠它识别我们的界面；subject 随视图不同。 */
    public record Holder(UUID playerId, UUID matchId, UUID roomId, String view) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public boolean available() {
        return plugin.cfg("ui.vanilla-gui", true);
    }

    @Override
    public boolean openMatch(Player player, ChessMatch match) {
        if (!available() || match == null) return false;
        open(view -> create(VIEW_MAIN, player, match, null), player, VIEW_MAIN);
        return true;
    }

    @Override
    public boolean openMenuMatch(Player player, ChessMatch match) {
        return openMatch(player, match);
    }

    @Override
    public boolean openLobby(Player player) {
        if (!available()) return false;
        open(view -> create(VIEW_LOBBY, player, null, null), player, VIEW_LOBBY);
        return true;
    }

    @Override
    public boolean openRoom(Player player, ChessRoom room) {
        if (!available() || room == null) return false;
        open(view -> create(VIEW_ROOM, player, null, room), player, VIEW_ROOM);
        return true;
    }

    /** 邀请玩家列表（从房间内打开）。 */
    public void openInvite(Player player) {
        if (!available()) return;
        open(view -> create(VIEW_INVITE, player, null, plugin.matches().roomOf(player.getUniqueId())),
                player, VIEW_INVITE);
    }

    @Override
    public boolean openRoomList(Player player) {
        if (!available()) return false;
        open(view -> create(VIEW_ROOMS, player, null, null), player, VIEW_ROOMS);
        return true;
    }

    private void open(java.util.function.Function<String, Inventory> factory, Player player, String view) {
        player.openInventory(factory.apply(view));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.4f);
        plugin.log("[ui] 原版菜单打开 " + player.getName() + " (" + view + ")");
    }

    @Override
    public void close(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
            player.closeInventory();
        }
    }

    @Override
    public void refresh(Player player) {
        if (!available()) return;
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder)) return;
        ChessMatch match = plugin.matches().match(holder.matchId());
        ChessRoom room = plugin.matches().room(holder.roomId());
        if (holder.view().equals(VIEW_MAIN) || holder.view().equals(VIEW_CONFIRM)
                || holder.view().equals(VIEW_HISTORY)) {
            if (match == null) {
                player.closeInventory();
                return;
            }
        }
        if (holder.view().equals(VIEW_ROOM) && room == null) {
            // 房间已开始或解散：跟随最新状态（对局面板或大厅）
            if (match != null) openMatch(player, match);
            else openLobby(player);
            return;
        }
        if (holder.view().equals(VIEW_ROOMS) && plugin.matches().roomOf(player.getUniqueId()) != null) {
            openRoom(player, plugin.matches().roomOf(player.getUniqueId()));
            return;
        }
        fill(player.getOpenInventory().getTopInventory(), holder.view(), match, room, player);
    }

    @Override
    public void update(ChessMatch match) {
        if (!available()) return;
        for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
            Player player = Bukkit.getPlayer(match.playerOf(side));
            if (player == null) continue;
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder)) continue;
            if (!match.id().equals(holder.matchId())) continue;
            fill(player.getOpenInventory().getTopInventory(), holder.view(), match, null, player);
        }
    }

    @Override
    public void onDrawOffer(ChessMatch match) {
        update(match);
    }

    @Override
    public void onGameEnd(ChessMatch match) {
        update(match);
    }

    // ---------- 渲染 ----------

    private Inventory create(String view, Player player, ChessMatch match, ChessRoom room) {
        boolean big = view.equals(VIEW_HISTORY) || view.equals(VIEW_ROOMS) || view.equals(VIEW_INVITE);
        Inventory inventory = Bukkit.createInventory(
                new Holder(player.getUniqueId(),
                        match == null ? null : match.id(),
                        room == null ? null : room.id(),
                        view),
                big ? 54 : 27,
                title(view, room));
        fill(inventory, view, match, room, player);
        return inventory;
    }

    private net.kyori.adventure.text.Component title(String view, ChessRoom room) {
        return MineChessPlugin.mm(switch (view) {
            case VIEW_CONFIRM -> "<red>确认认输？";
            case VIEW_HISTORY -> "<aqua>棋谱";
            case VIEW_LOBBY -> "<gold>MineChess 国际象棋";
            case VIEW_ROOMS -> "<gold>房间列表（" + plugin.matches().rooms().size() + "）";
            case VIEW_ROOM -> "<gold>房间 #" + (room == null ? "?" : room.number());
            case VIEW_RULES -> "<gold>玩法说明";
            case VIEW_INVITE -> "<gold>邀请玩家";
            default -> "<gold>MineChess 对局面板";
        });
    }

    private void fill(Inventory inventory, String view, ChessMatch match, ChessRoom room, Player player) {
        inventory.clear();
        switch (view) {
            case VIEW_LOBBY -> fillLobby(inventory, player);
            case VIEW_ROOMS -> fillRoomList(inventory);
            case VIEW_ROOM -> {
                if (room != null) fillRoom(inventory, room, player);
            }
            case VIEW_RULES -> fillRules(inventory);
            case VIEW_INVITE -> fillInvite(inventory, player);
            case VIEW_CONFIRM -> {
                inventory.setItem(11, item(Material.RED_DYE, "<red><bold>确认认输",
                        List.of("<gray>认输后本局直接判负")));
                inventory.setItem(15, item(Material.LIME_DYE, "<green>取消", List.of("<gray>返回对局面板")));
            }
            case VIEW_HISTORY -> fillHistory(inventory, match);
            default -> fillMain(inventory, match, player);
        }
    }

    // ---------- 大厅 ----------

    private void fillLobby(Inventory inventory, Player player) {
        Challenge incoming = plugin.matches().incoming(player.getUniqueId());
        if (incoming != null) {
            inventory.setItem(4, item(Material.PAPER, "<yellow><bold>"
                            + plugin.playerName(incoming.challenger()) + " 邀请你下棋",
                    List.of("<gray>时限：<white>" + incoming.timeControl().describe())));
            inventory.setItem(6, item(Material.LIME_DYE, "<green><bold>接受挑战", List.of()));
            inventory.setItem(8, item(Material.RED_DYE, "<red><bold>拒绝挑战", List.of()));
        } else {
            inventory.setItem(4, item(Material.PAPER, "<gold><bold>MineChess 国际象棋",
                    List.of("<gray>双人国际象棋，完整规则 + 3D 棋盘 + 棋钟",
                            "<gray>进行中的对局：<white>" + plugin.matches().games().size(),
                            "<gray>等待中的房间：<white>" + plugin.matches().rooms().size())));
        }

        inventory.setItem(10, item(Material.EMERALD, "<green><bold>创建房间",
                List.of("<gray>创建一个棋局房间", "<gray>可以邀请朋友加入，准备好后开始")));
        inventory.setItem(12, item(Material.COMPASS, "<gold><bold>加入房间",
                List.of("<gray>浏览等待中的房间并加入",
                        "<gray>当前房间数：<white>" + plugin.matches().rooms().size())));
        inventory.setItem(14, item(Material.BOOK, "<aqua>玩法说明",
                List.of("<gray>点击查看规则与操作")));
        inventory.setItem(16, item(Material.SUNFLOWER, "<yellow>刷新",
                List.of("<gray>刷新房间/挑战状态")));

        int slot = 18;
        for (Player other : onlineOthers(player)) {
            if (slot > 24) break;
            inventory.setItem(slot++, head(other, "<yellow>点击发起挑战（使用默认时限）"));
        }
        inventory.setItem(26, item(Material.GRAY_DYE, "<gray>关闭", List.of()));
    }

    // ---------- 房间列表 ----------

    private void fillRoomList(Inventory inventory) {
        List<ChessRoom> rooms = sortedRooms();
        if (rooms.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "<gray>当前没有等待中的房间",
                    List.of("<gray>点击 <white>创建房间 <gray>开一桌")));
        }
        int slot = 0;
        for (ChessRoom room : rooms) {
            if (slot >= 45) break;
            String guest = room.hasGuest()
                    ? plugin.playerName(room.guest()) + (room.guestReady() ? " <green>已准备" : " <gray>未准备")
                    : "<dark_gray>等待加入";
            List<String> lore = new ArrayList<>();
            lore.add("<gray>房主：<white>" + plugin.playerName(room.host()));
            lore.add("<gray>对手：<white>" + guest);
            lore.add("<gray>时限：<white>" + room.timeControl().describe());
            lore.add("");
            lore.add(room.hasGuest() ? "<red>房间已满" : "<yellow>点击加入房间");
            ItemStack stack = playerHead(room.host(), "<gold>房间 #" + room.number()
                    + " <white>" + plugin.playerName(room.host()), lore);
            stack.editMeta(meta -> meta.getPersistentDataContainer()
                    .set(roomKey, PersistentDataType.STRING, room.id().toString()));
            inventory.setItem(slot++, stack);
        }
        inventory.setItem(49, item(Material.ARROW, "<gray>返回大厅", List.of()));
        inventory.setItem(53, item(Material.SUNFLOWER, "<yellow>刷新", List.of()));
    }

    // ---------- 邀请 ----------

    private void fillInvite(Inventory inventory, Player player) {
        List<Player> candidates = onlineOthers(player);
        if (candidates.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "<gray>暂无可邀请的在线玩家",
                    List.of("<gray>对方可能在房间或对局中")));
        }
        int slot = 0;
        for (Player candidate : candidates) {
            ItemStack head = head(candidate, "<yellow>点击邀请加入房间");
            head.editMeta(meta -> meta.getPersistentDataContainer()
                    .set(inviteKey, PersistentDataType.STRING, candidate.getUniqueId().toString()));
            inventory.setItem(slot++, head);
        }
        inventory.setItem(49, item(Material.ARROW, "<gray>返回房间", List.of()));
    }

    private void clickInvite(Player player, int slot) {
        if (slot == 49) {
            ChessRoom room = plugin.matches().roomOf(player.getUniqueId());
            if (room != null) openRoom(player, room);
            else openLobby(player);
            return;
        }
        ItemStack clicked = player.getOpenInventory().getTopInventory().getItem(slot);
        if (clicked == null) return;
        String raw = clicked.getPersistentDataContainer().get(inviteKey, PersistentDataType.STRING);
        if (raw == null) return;
        Player target = Bukkit.getPlayer(UUID.fromString(raw));
        if (target == null) {
            openInvite(player);
            return;
        }
        if (plugin.matches().inviteToRoom(player, target)) {
            openInvite(player);
        }
    }

    // ---------- 房间 ----------

    private void fillRoom(Inventory inventory, ChessRoom room, Player player) {
        boolean host = room.isHost(player.getUniqueId());
        boolean hostWhite = room.colorOf(player.getUniqueId()) == Side.WHITE;
        inventory.setItem(4, item(Material.PAPER, "<gold><bold>房间 #" + room.number(),
                List.of("<gray>时限：<white>" + room.timeControl().describe(),
                        "<gray>你执<white>" + (hostWhite ? "白" : "黑"),
                        host ? "<gray>把界面分享给朋友，或让对方在「加入房间」里找到你"
                                : (room.guestReady() ? "<green>你已准备" : "<yellow>点击「准备」后等待房主开始"))));

        inventory.setItem(10, playerHead(room.host(), "<white>房主 " + plugin.playerName(room.host()),
                List.of("<gray>房主可以切换时限并开始游戏")));
        boolean botGuest = plugin.matches().roomHasBot(room);
        if (room.hasGuest()) {
            inventory.setItem(16, playerHead(room.guest(), "<white>" + plugin.playerName(room.guest()),
                    List.of(botGuest ? "<aqua>AI 对手（随机落子）"
                            : (room.guestReady() ? "<green>已准备" : "<gray>未准备"))));
        } else {
            inventory.setItem(16, item(Material.LIGHT_GRAY_DYE, "<gray>等待玩家加入",
                    List.of("<gray>对方可在「加入房间」里找到本房间",
                            "<gray>也可以点下方按钮邀请或添加 AI")));
        }

        if (host && !room.hasGuest()) {
            inventory.setItem(15, item(Material.PLAYER_HEAD, "<green>邀请玩家",
                    List.of("<gray>从在线玩家中选择一位")));
            inventory.setItem(17, item(Material.IRON_GOLEM_SPAWN_EGG, "<aqua>添加 AI 对手",
                    List.of("<gray>当前实现：随机落子", "<gray>后端可在配置 ai.bot 中替换")));
        }

        if (host) {
            inventory.setItem(11, item(room.hostColor() == Side.WHITE ? Material.WHITE_WOOL : Material.BLACK_WOOL,
                    "<white>切换执色",
                    List.of("<gray>你目前执<white>" + (room.hostColor() == Side.WHITE ? "白" : "黑"),
                            "<gray>点击切换为执" + (room.hostColor() == Side.WHITE ? "黑" : "白"))));
            inventory.setItem(13, item(Material.CLOCK, "<gold>切换时限",
                    List.of("<gray>当前：<white>" + room.timeControl().describe(),
                            "<gray>可选：" + TimeControl.presetsText())));
            if (room.canStart()) {
                inventory.setItem(22, item(Material.LIME_CONCRETE, "<green><bold>开始游戏",
                        List.of("<gray>对手已准备，点击开局")));
            } else {
                inventory.setItem(22, item(Material.BARRIER, "<gray>等待对手准备",
                        List.of("<gray>对手加入并点准备后才能开始")));
            }
        } else {
            inventory.setItem(20, room.guestReady()
                    ? item(Material.ORANGE_DYE, "<yellow>取消准备", List.of())
                    : item(Material.LIME_DYE, "<green><bold>准备", List.of("<gray>准备好后房主可以开始")));
        }

        inventory.setItem(24, item(Material.BARRIER, "<gray>离开房间", List.of()));
        inventory.setItem(26, item(Material.GRAY_DYE, "<gray>关闭", List.of()));
    }

    private List<ChessRoom> sortedRooms() {
        List<ChessRoom> rooms = new ArrayList<>(plugin.matches().rooms());
        rooms.sort(Comparator.comparingInt(ChessRoom::number));
        return rooms;
    }

    // ---------- 对局面板 ----------

    private void fillMain(Inventory inventory, ChessMatch match, Player player) {
        boolean playing = match.phase() == ChessMatch.Phase.PLAYING;
        Side side = match.sideOf(player.getUniqueId());
        String whiteName = plugin.playerName(match.whitePlayer());
        String blackName = plugin.playerName(match.blackPlayer());
        boolean whiteTurn = match.turn() == Side.WHITE;

        inventory.setItem(4, item(Material.PAPER, "<gold><bold>MineChess",
                List.of("<gray>时限：<white>" + match.timeControl().describe(),
                        "<gray>步数：<white>" + match.ply(),
                        match.result() != null
                                ? "<gold>结果：<white>" + match.result().describe()
                                : "<gray>状态：" + (playing ? "<green>进行中" : "<red>已结束"))));

        inventory.setItem(10, item(Material.WHITE_WOOL, "<white>白方 " + whiteName,
                clockLore(match, Side.WHITE, whiteTurn, side == Side.WHITE)));
        inventory.setItem(16, item(Material.BLACK_WOOL, "<gray>黑方 " + blackName,
                clockLore(match, Side.BLACK, !whiteTurn, side == Side.BLACK)));

        inventory.setItem(13, item(Material.BOOK, "<aqua>棋谱",
                List.of("<gray>共 <white>" + match.ply() + " <gray>步",
                        "<gray>点击查看全部着法")));

        if (playing) {
            boolean incoming = match.drawOffer() != null && match.drawOffer() != side;
            if (incoming) {
                inventory.setItem(19, item(Material.LIME_DYE, "<green><bold>接受和棋",
                        List.of("<gray>对方提议和棋")));
                inventory.setItem(21, item(Material.RED_DYE, "<red><bold>拒绝和棋", List.of()));
            } else {
                inventory.setItem(20, item(Material.YELLOW_DYE, "<yellow>提议和棋",
                        List.of("<gray>向对手发起和棋请求")));
            }
            inventory.setItem(22, item(Material.RED_DYE, "<red>认输",
                    List.of("<gray>点击后需要再次确认")));
        }

        inventory.setItem(24, item(Material.BARRIER,
                playing ? "<gray>离开对局（认输）" : "<gray>离开对局",
                playing ? List.of("<red>离开会判负") : List.of("<gray>对局已结束")));
        inventory.setItem(26, item(Material.GRAY_DYE, "<gray>关闭", List.of()));
    }

    private void fillRules(Inventory inventory) {
        inventory.setItem(4, item(Material.BOOK, "<gold><bold>MineChess 玩法",
                List.of("<gray>双人国际象棋，规则完整（chesslib）")));
        inventory.setItem(10, item(Material.PAPER, "<white>1. 开一桌",
                List.of("<gray>「创建房间」后朋友在「加入房间」里加入",
                        "<gray>双方各执一色，房主执白")));
        inventory.setItem(12, item(Material.PAPER, "<white>2. 准备与开始",
                List.of("<gray>对手点击「准备」，房主再点「开始游戏」",
                        "<gray>两人会被传送到棋盘两侧的座位")));
        inventory.setItem(14, item(Material.PAPER, "<white>3. 走棋",
                        List.of("<gray>点击自己的棋子，格子出现绿色提示点",
                                "<gray>点击提示格落子；可吃子的格子是圆环",
                                "<gray>也可以用 /chess move e2e4 命令走棋")));
        inventory.setItem(16, item(Material.PAPER, "<white>4. 特殊规则",
                List.of("<gray>王车易位、吃过路兵、兵升变（棋盘边选择）",
                        "<gray>将军 / 将死 / 逼和 / 三次重复 / 50 回合",
                        "<gray>棋钟超时判负，断线 60 秒判负")));
        inventory.setItem(26, item(Material.ARROW, "<gray>返回大厅", List.of()));
    }

    private List<String> clockLore(ChessMatch match, Side side, boolean turn, boolean self) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>剩余时间：<white>" + match.clock(side).format());
        if (match.phase() == ChessMatch.Phase.PLAYING) {
            lore.add(turn ? "<yellow>▶ 该方走棋" : "<gray>等待中");
        }
        if (self) lore.add("<dark_gray>（你）");
        return lore;
    }

    private void fillHistory(Inventory inventory, ChessMatch match) {
        List<MoveRecord> moves = match.moves();
        if (moves.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "<gray>还没有落子", List.of()));
        }
        int slot = 0;
        for (int i = 0; i < moves.size() && slot < 45; i += 8) {
            List<String> lore = new ArrayList<>();
            for (int j = i; j < Math.min(i + 8, moves.size()); j += 2) {
                String white = moves.get(j).san();
                String black = j + 1 < moves.size() ? moves.get(j + 1).san() : "…";
                lore.add("<white>" + (j / 2 + 1) + ". " + white + " <gray>" + black);
            }
            inventory.setItem(slot++, item(Material.PAPER, "<white>第 " + (i / 2 + 1) + " 回合起", lore));
        }
        inventory.setItem(49, item(Material.ARROW, "<gray>返回", List.of()));
    }

    // ---------- 物品 ----------

    private List<Player> onlineOthers(Player self) {
        List<Player> others = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(self)) continue;
            if (plugin.matches().busy(online.getUniqueId())) continue;
            others.add(online);
        }
        others.sort(Comparator.comparing(Player::getName));
        return others;
    }

    private ItemStack head(Player player, String hint) {
        ItemStack stack = ItemStack.of(Material.PLAYER_HEAD);
        stack.editMeta(meta -> {
            meta.displayName(MineChessPlugin.mm("<white>" + player.getName())
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(MineChessPlugin.mm("<gray>" + hint)
                    .decoration(TextDecoration.ITALIC, false)));
            if (meta instanceof SkullMeta skull) skull.setOwningPlayer(player);
        });
        return stack;
    }

    private ItemStack playerHead(UUID uuid, String name, List<String> lore) {
        ItemStack stack = ItemStack.of(Material.PLAYER_HEAD);
        stack.editMeta(meta -> {
            meta.displayName(MineChessPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> MineChessPlugin.mm(line).decoration(TextDecoration.ITALIC, false))
                        .toList());
            }
            if (meta instanceof SkullMeta skull) skull.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        });
        return stack;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(MineChessPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> MineChessPlugin.mm(line).decoration(TextDecoration.ITALIC, false))
                        .toList());
            }
        });
        return stack;
    }

    // ---------- 点击 ----------

    @Override
    public boolean click(Player player, Holder holder, int slot) {
        switch (holder.view()) {
            case VIEW_LOBBY -> clickLobby(player, slot);
            case VIEW_ROOMS -> clickRooms(player, slot);
            case VIEW_ROOM -> clickRoom(player, holder, slot);
            case VIEW_INVITE -> clickInvite(player, slot);
            case VIEW_RULES -> {
                if (slot == 26) openLobby(player);
            }
            case VIEW_CONFIRM -> {
                ChessMatch match = plugin.matches().match(holder.matchId());
                if (match == null) {
                    player.closeInventory();
                } else if (slot == 11) {
                    player.closeInventory();
                    plugin.matches().resign(player);
                } else if (slot == 15) {
                    openMatch(player, match);
                }
            }
            case VIEW_HISTORY -> {
                ChessMatch match = plugin.matches().match(holder.matchId());
                if (match != null && slot == 49) openMatch(player, match);
            }
            default -> clickMain(player, holder, slot);
        }
        return true;
    }

    private void clickLobby(Player player, int slot) {
        Challenge incoming = plugin.matches().incoming(player.getUniqueId());
        switch (slot) {
            case 6 -> {
                if (incoming != null) {
                    player.closeInventory();
                    plugin.matches().accept(player);
                }
            }
            case 8 -> {
                if (incoming != null) plugin.matches().decline(player);
            }
            case 10 -> {
                player.closeInventory();
                TimeControl timeControl = TimeControl.parse(
                        plugin.getConfig().getString("game.default-time", "10+0"));
                plugin.matches().createRoom(player, timeControl == null ? TimeControl.parse("10+0") : timeControl);
            }
            case 12 -> openRoomList(player);
            case 14 -> player.openInventory(create(VIEW_RULES, player, null, null));
            case 16 -> openLobby(player);
            case 26 -> player.closeInventory();
            default -> {
                if (slot >= 18 && slot <= 24) {
                    List<Player> others = onlineOthers(player);
                    int index = slot - 18;
                    if (index < others.size()) {
                        TimeControl timeControl = TimeControl.parse(
                                plugin.getConfig().getString("game.default-time", "10+0"));
                        if (timeControl == null) timeControl = TimeControl.parse("10+0");
                        player.closeInventory();
                        plugin.matches().challenge(player, others.get(index), timeControl);
                    }
                }
            }
        }
    }

    private void clickRooms(Player player, int slot) {
        if (slot == 49) {
            openLobby(player);
            return;
        }
        if (slot == 53) {
            openRoomList(player);
            return;
        }
        if (slot < 0 || slot >= 45) return;
        ItemStack clicked = player.getOpenInventory().getTopInventory().getItem(slot);
        if (clicked == null) return;
        String raw = clicked.getPersistentDataContainer().get(roomKey, PersistentDataType.STRING);
        if (raw == null) return;
        ChessRoom room = plugin.matches().room(UUID.fromString(raw));
        if (room == null) {
            player.closeInventory();
            openLobby(player);
            return;
        }
        if (room.isHost(player.getUniqueId())) {
            openRoom(player, room);
            return;
        }
        if (room.hasGuest()) {
            MineChessPlugin.msg(player, "<red>该房间已满");
            refresh(player);
            return;
        }
        player.closeInventory();
        plugin.matches().joinRoom(player, room);
    }

    private void clickRoom(Player player, Holder holder, int slot) {
        ChessRoom room = plugin.matches().room(holder.roomId());
        if (room == null) {
            openLobby(player);
            return;
        }
        switch (slot) {
            case 11 -> {
                if (room.isHost(player.getUniqueId())) plugin.matches().cycleColor(player);
            }
            case 13 -> {
                if (room.isHost(player.getUniqueId())) plugin.matches().cycleTime(player);
            }
            case 15 -> {
                if (room.isHost(player.getUniqueId()) && !room.hasGuest()) openInvite(player);
            }
            case 17 -> {
                if (room.isHost(player.getUniqueId()) && !room.hasGuest()) plugin.matches().addBot(player);
            }
            case 20 -> {
                if (!room.isHost(player.getUniqueId())) plugin.matches().toggleReady(player);
            }
            case 22 -> {
                if (room.isHost(player.getUniqueId())) plugin.matches().startRoom(player);
            }
            case 24 -> {
                player.closeInventory();
                plugin.matches().leaveRoom(player);
            }
            case 26 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void clickMain(Player player, Holder holder, int slot) {
        ChessMatch match = plugin.matches().match(holder.matchId());
        if (match == null) {
            player.closeInventory();
            return;
        }
        Side side = match.sideOf(player.getUniqueId());
        boolean playing = match.phase() == ChessMatch.Phase.PLAYING;
        switch (slot) {
            case 13 -> player.openInventory(create(VIEW_HISTORY, player, match, null));
            case 19 -> {
                if (playing && match.drawOffer() != null && match.drawOffer() != side) {
                    plugin.matches().acceptDraw(player);
                }
            }
            case 20 -> {
                if (playing) plugin.matches().offerDraw(player);
            }
            case 21 -> {
                if (playing && match.drawOffer() != null && match.drawOffer() != side) {
                    plugin.matches().declineDraw(player);
                }
            }
            case 22 -> {
                if (playing) player.openInventory(create(VIEW_CONFIRM, player, match, null));
            }
            case 24 -> {
                player.closeInventory();
                plugin.matches().leave(player);
            }
            case 26 -> player.closeInventory();
            default -> {
            }
        }
    }
}
