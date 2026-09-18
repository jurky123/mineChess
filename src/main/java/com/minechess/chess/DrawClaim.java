package com.minechess.chess;

/** 可申请的和棋类型（FIDE：三次重复与 50 回合需要申请，五次重复与 75 回合自动和棋）。 */
public enum DrawClaim {
    THREEFOLD_REPETITION(Termination.THREEFOLD_REPETITION),
    FIFTY_MOVE_RULE(Termination.FIFTY_MOVE_RULE);

    private final Termination termination;

    DrawClaim(Termination termination) {
        this.termination = termination;
    }

    public Termination termination() {
        return termination;
    }
}
