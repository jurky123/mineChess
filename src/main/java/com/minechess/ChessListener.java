package com.minechess;

import com.minechess.match.ChessMatch;
import com.minechess.ui.InventoryControlUi;
import com.minechess.view.ChessTableView;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class ChessListener implements Listener {

    private final MineChessPlugin plugin;

    public ChessListener(MineChessPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (plugin.matches().tuning(player)) return;
        // 对局内（含观战）：右键固定打开对局面板
        ChessMatch watched = plugin.matches().watchedMatchOf(player.getUniqueId());
        if (watched != null) {
            event.setCancelled(true);
            openPanel(player, watched);
            return;
        }
        ChessTableView.Hit hit = plugin.matches().hit(event.getRightClicked().getUniqueId());
        if (hit != null) {
            event.setCancelled(true);
            handleHit(event.getPlayer(), hit);
        }
    }

    /** 左键点棋盘/升变按钮实体：作用于注册的命中目标。 */
    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction) {
            ChessTableView.Hit hit = plugin.matches().hit(event.getEntity().getUniqueId());
            if (hit != null) {
                event.setCancelled(true);
                if (event.getDamager() instanceof Player player) handleHit(player, hit);
            }
        }
    }

    /** 右键空气/方块：打开对局面板（2D 棋盘从面板进入）。 */
    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (plugin.matches().tuning(player)) return;
        ChessMatch watched = plugin.matches().watchedMatchOf(player.getUniqueId());
        if (watched == null) return;
        event.setCancelled(true);
        openPanel(player, watched);
    }

    private void openPanel(Player player, ChessMatch match) {
        plugin.ui().openPanel(player, match);
    }

    private void handleHit(Player player, ChessTableView.Hit hit) {
        ChessMatch match = plugin.matches().matchOf(player.getUniqueId());
        if (match != hit.table().match()) {
            MineChessPlugin.msg(player, "<gray>这是 <white>"
                    + plugin.playerName(hit.table().match().whitePlayer()) + " <gray>与 <white>"
                    + plugin.playerName(hit.table().match().blackPlayer()) + " <gray>的棋盘");
            return;
        }
        switch (hit.kind()) {
            case SQUARE -> plugin.matches().squareClick(player, hit.table(), hit.square());
            case PROMOTION -> plugin.matches().promotionClick(player, hit.table(), hit.promotionIndex());
        }
    }

    /** 原版箱子面板：只认自己的 Holder，点击全部走服务端逻辑。 */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof InventoryControlUi.Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof InventoryControlUi.Holder)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        plugin.ui().click(player, holder, event.getSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof InventoryControlUi.Holder) {
            event.setCancelled(true);
        }
    }

    /** 对局中不许从座位上跳下来（观战者同理）。 */
    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getDismounted() instanceof ArmorStand)) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (inMatchContext(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (inMatchContext(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (inMatchContext(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ChessMatch match = plugin.matches().matchOf(player.getUniqueId());
        if (match == null || match.phase() != ChessMatch.Phase.PLAYING) return;
        ChessTableView table = plugin.matches().table(match);
        if (table == null) return;
        Location seat = table.stand(match.sideOf(player.getUniqueId()));
        if (seat != null) event.setRespawnLocation(seat);
    }

    /** 参战或观战中（座位锁定/防破坏用）。 */
    private boolean inMatchContext(Player player) {
        return plugin.matches().watchedMatchOf(player.getUniqueId()) != null;
    }

    /** 滚轮实时调参（/chess tune / /chess seat）。 */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        if (!plugin.matches().tuning(event.getPlayer())) return;
        event.setCancelled(true);
        plugin.matches().tuneScroll(event.getPlayer(), event.getPreviousSlot(), event.getNewSlot());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.matches().quit(event.getPlayer());
    }

    /** 世界聊天回流到 2D 棋盘侧边聊天栏（本事件异步，切回主线程推送）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (text.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> plugin.ui().onWorldChat(player.getName(), text));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.matches().reconnect(event.getPlayer()), 20);
    }
}
