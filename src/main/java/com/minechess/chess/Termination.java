package com.minechess.chess;

/** 对局结束方式。 */
public enum Termination {
    CHECKMATE("将死"),
    STALEMATE("逼和"),
    INSUFFICIENT_MATERIAL("子力不足"),
    FIVEFOLD_REPETITION("五次重复"),
    SEVENTY_FIVE_MOVE_RULE("75 回合规则"),
    THREEFOLD_REPETITION("三次重复（申请）"),
    FIFTY_MOVE_RULE("50 回合规则（申请）"),
    DRAW_AGREEMENT("和棋协议"),
    RESIGNATION("认输"),
    TIMEOUT("超时"),
    TIMEOUT_INSUFFICIENT("超时和棋（对方无法将死）"),
    DISCONNECT_FORFEIT("断线判负");

    private final String label;

    Termination(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
