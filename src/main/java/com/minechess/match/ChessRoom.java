package com.minechess.match;

import com.github.bhlangonijr.chesslib.Side;
import com.minechess.chess.TimeControl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 等待中的棋局房间（仿 MineUNO 的房间流程）：房主 + 一名挑战者，挑战者准备后房主开始。
 * 另有 4 个观战席位，开局后观战者随对局一起被送到棋盘两侧。
 * 纯 Java，可在单测里直接驱动。
 */
public final class ChessRoom {

    /** 每桌观战席位数。 */
    public static final int SPECTATOR_SLOTS = 4;

    private final UUID id;
    private final int number;
    private final long createdAtMillis;
    private final List<UUID> spectators = new ArrayList<>(SPECTATOR_SLOTS);
    private UUID host;
    private UUID guest;
    private TimeControl timeControl;
    private boolean guestReady;
    private Side hostColor = Side.WHITE;

    public ChessRoom(UUID id, int number, UUID host, TimeControl timeControl) {
        this.id = id;
        this.number = number;
        this.host = host;
        this.timeControl = timeControl;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public UUID id() {
        return id;
    }

    /** 展示用房间号（#1、#2…）。 */
    public int number() {
        return number;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public UUID host() {
        return host;
    }

    public UUID guest() {
        return guest;
    }

    public boolean hasGuest() {
        return guest != null;
    }

    public TimeControl timeControl() {
        return timeControl;
    }

    public void timeControl(TimeControl timeControl) {
        this.timeControl = timeControl;
    }

    public boolean guestReady() {
        return guestReady;
    }

    public boolean contains(UUID player) {
        return player != null && (player.equals(host) || player.equals(guest) || spectators.contains(player));
    }

    /** 只算棋手（房主/挑战者），不含观战者。 */
    public boolean isPlayer(UUID player) {
        return player != null && (player.equals(host) || player.equals(guest));
    }

    // ---------- 观战席位 ----------

    public List<UUID> spectators() {
        return Collections.unmodifiableList(spectators);
    }

    public int spectatorCount() {
        return spectators.size();
    }

    public boolean isSpectator(UUID player) {
        return player != null && spectators.contains(player);
    }

    public boolean spectatorFull() {
        return spectators.size() >= SPECTATOR_SLOTS;
    }

    /** 加入观战席；座位已满、已是棋手或已在观战中返回 false。 */
    public boolean joinSpectator(UUID player) {
        if (player == null || isEmpty() || isPlayer(player) || spectators.contains(player)) return false;
        if (spectatorFull()) return false;
        spectators.add(player);
        return true;
    }

    public boolean leaveSpectator(UUID player) {
        return player != null && spectators.remove(player);
    }

    public boolean isHost(UUID player) {
        return player != null && player.equals(host);
    }

    public boolean isEmpty() {
        return host == null;
    }

    public int size() {
        return (host == null ? 0 : 1) + (guest == null ? 0 : 1);
    }

    /** 加入房间；房主重复加入或被占满时返回 false。 */
    public boolean join(UUID player) {
        if (player == null || isEmpty() || guest != null || player.equals(host)) return false;
        if (spectators.contains(player)) return false;
        guest = player;
        guestReady = false;
        return true;
    }

    /**
     * 离开房间。房主离开时若有人等待则移交房主，否则房间清空；返回 false 表示房间已空需要删除。
     */
    public boolean leave(UUID player) {
        if (player == null) return true;
        if (player.equals(guest)) {
            guest = null;
            guestReady = false;
            return true;
        }
        if (player.equals(host)) {
            if (guest != null) {
                host = guest;
                guest = null;
                guestReady = false;
                return true;
            }
            host = null;
            return false;
        }
        return true;
    }

    /** 挑战者准备 / 取消准备。 */
    public void toggleReady(UUID player) {
        if (player != null && player.equals(guest)) guestReady = !guestReady;
    }

    public boolean canStart() {
        return host != null && guest != null && guestReady;
    }

    /** 房主执色（默认白）。 */
    public Side hostColor() {
        return hostColor;
    }

    public void swapHostColor() {
        hostColor = hostColor.flip();
    }

    /** 房主执色对应的白方玩家。 */
    public UUID whitePlayer() {
        return hostColor == Side.WHITE ? host : guest;
    }

    /** 房主执色对应的黑方玩家。 */
    public UUID blackPlayer() {
        return hostColor == Side.WHITE ? guest : host;
    }

    /** 某个玩家在该房间执什么颜色。 */
    public Side colorOf(UUID player) {
        return isHost(player) ? hostColor : hostColor.flip();
    }
}
