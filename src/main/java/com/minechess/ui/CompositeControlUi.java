package com.minechess.ui;

import com.minechess.match.ChessMatch;
import com.minechess.match.ChessRoom;
import org.bukkit.entity.Player;

/**
 * 界面分发：客户端支持 server_ui 时优先 MineUI（原版风格模板），
 * 任何一步失败都会自动回退到原版箱子菜单，保证玩家始终有可用界面。
 */
public final class CompositeControlUi implements ControlUi {

    private final ControlUi panel;
    private final ControlUi menu;

    public CompositeControlUi(ControlUi panel, ControlUi menu) {
        this.panel = panel;
        this.menu = menu;
    }

    @Override
    public boolean available() {
        return panel.available() || menu.available();
    }

    @Override
    public boolean handles(Player player) {
        return panel.handles(player) || menu.handles(player);
    }

    @Override
    public boolean openMatch(Player player, ChessMatch match) {
        if (panel.handles(player)) {
            menu.close(player);
            if (panel.openMatch(player, match)) return true;
            panel.close(player);
        }
        return menu.openMatch(player, match);
    }

    @Override
    public boolean openLobby(Player player) {
        if (panel.handles(player)) {
            menu.close(player);
            if (panel.openLobby(player)) return true;
            panel.close(player);
        }
        return menu.openLobby(player);
    }

    @Override
    public boolean openRoom(Player player, ChessRoom room) {
        if (panel.handles(player)) {
            menu.close(player);
            if (panel.openRoom(player, room)) return true;
            panel.close(player);
        }
        return menu.openRoom(player, room);
    }

    @Override
    public boolean openRoomList(Player player) {
        if (panel.handles(player)) {
            menu.close(player);
            if (panel.openRoomList(player)) return true;
            panel.close(player);
        }
        return menu.openRoomList(player);
    }

    @Override
    public boolean openMenuMatch(Player player, ChessMatch match) {
        panel.close(player);
        return menu.openMenuMatch(player, match);
    }

    @Override
    public boolean openBoard(Player player, ChessMatch match) {
        return panel.handles(player) && panel.openBoard(player, match);
    }

    @Override
    public boolean openPanel(Player player, ChessMatch match) {
        if (panel.handles(player) && panel.openPanel(player, match)) return true;
        panel.close(player);
        return menu.openMatch(player, match);
    }

    @Override
    public void close(Player player) {
        panel.close(player);
        menu.close(player);
    }

    @Override
    public void refresh(Player player) {
        if (panel.available()) panel.refresh(player);
        if (menu.available()) menu.refresh(player);
    }

    @Override
    public boolean click(Player player, InventoryControlUi.Holder holder, int slot) {
        return menu.click(player, holder, slot);
    }

    @Override
    public void update(ChessMatch match) {
        if (panel.available()) panel.update(match);
        if (menu.available()) menu.update(match);
    }

    @Override
    public void onDrawOffer(ChessMatch match) {
        if (panel.available()) panel.onDrawOffer(match);
        if (menu.available()) menu.onDrawOffer(match);
    }

    @Override
    public void onGameEnd(ChessMatch match) {
        if (panel.available()) panel.onGameEnd(match);
        if (menu.available()) menu.onGameEnd(match);
    }

    @Override
    public void onWorldChat(String sender, String message) {
        if (panel.available()) panel.onWorldChat(sender, message);
        if (menu.available()) menu.onWorldChat(sender, message);
    }
}
