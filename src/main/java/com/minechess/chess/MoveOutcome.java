package com.minechess.chess;

/** 一次走子尝试的结果。 */
public record MoveOutcome(Status status, MoveRecord move, ChessResult result) {

    public enum Status {
        OK,
        NEEDS_PROMOTION,
        NOT_PLAYING,
        NOT_YOUR_TURN,
        NO_PIECE,
        OPPONENT_PIECE,
        ILLEGAL
    }

    public static MoveOutcome ok(MoveRecord move, ChessResult result) {
        return new MoveOutcome(Status.OK, move, result);
    }

    public static MoveOutcome promotion() {
        return new MoveOutcome(Status.NEEDS_PROMOTION, null, null);
    }

    public static MoveOutcome fail(Status status) {
        return new MoveOutcome(status, null, null);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public boolean finished() {
        return result != null;
    }
}
