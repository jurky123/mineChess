package com.minechess.ui;

import com.minechess.match.ChessMatch;
import org.bukkit.entity.Player;

/** 没有任何界面实现时的空实现。 */
public final class NoopControlUi implements ControlUi {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean openMatch(Player player, ChessMatch match) {
        return false;
    }

    @Override
    public boolean openLobby(Player player) {
        return false;
    }

    @Override
    public void close(Player player) {
    }
}
