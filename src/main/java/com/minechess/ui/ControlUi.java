package com.minechess.ui;

import com.minechess.match.ChessMatch;
import com.minechess.match.ChessRoom;
import org.bukkit.entity.Player;

/**
 * 界面抽象：对局中的控制面板可由 MineUI（mod 客户端）实现，大厅 / 房间列表 / 房间用原版箱子菜单。
 * open* 返回 true 表示已处理，CompositeControlUi 据此在 MineUI 不可用时回退到原版菜单。
 */
public interface ControlUi {

    boolean available();

    /** 该玩家是否由这个实现负责（CompositeControlUi 按顺序挑选）。 */
    default boolean handles(Player player) {
        return available();
    }

    boolean openMatch(Player player, ChessMatch match);

    boolean openLobby(Player player);

    default boolean openRoom(Player player, ChessRoom room) {
        return false;
    }

    default boolean openRoomList(Player player) {
        return false;
    }

    /** 强制使用原版箱子菜单展示对局面板（/chess panel chest 兜底）。 */
    default boolean openMenuMatch(Player player, ChessMatch match) {
        return false;
    }

    /** 2D 棋盘界面（固定视角、鼠标点格子，仅 MineUI 客户端）。 */
    default boolean openBoard(Player player, ChessMatch match) {
        return false;
    }

    /** 对局控制面板（棋钟/棋谱/认输/和棋）。 */
    default boolean openPanel(Player player, ChessMatch match) {
        return false;
    }

    void close(Player player);

    /** 已打开界面的轻量刷新（房间状态变化、收到挑战等）。 */
    default void refresh(Player player) {
    }

    /** 原版箱子界面的点击回调，返回 true 表示已处理。 */
    default boolean click(Player player, InventoryControlUi.Holder holder, int slot) {
        return false;
    }

    /** 对局数据变化（棋钟 / 棋谱）。 */
    default void update(ChessMatch match) {
    }

    default void onDrawOffer(ChessMatch match) {
    }

    default void onGameEnd(ChessMatch match) {
    }

    /** 世界聊天回流（2D 棋盘侧边聊天栏）。 */
    default void onWorldChat(String sender, String message) {
    }
}
