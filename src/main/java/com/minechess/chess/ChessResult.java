package com.minechess.chess;

import com.github.bhlangonijr.chesslib.Side;

/** 对局结果：winner 为 null 表示和棋。 */
public record ChessResult(Side winner, Termination termination) {

    public static ChessResult win(Side side, Termination termination) {
        return new ChessResult(side, termination);
    }

    public static ChessResult draw(Termination termination) {
        return new ChessResult(null, termination);
    }

    public boolean isDraw() {
        return winner == null;
    }

    /** PGN 结果串：1-0 / 0-1 / 1/2-1/2。 */
    public String score() {
        if (winner == null) return "1/2-1/2";
        return winner == Side.WHITE ? "1-0" : "0-1";
    }

    public String describe() {
        if (winner == null) return "和棋（" + termination.label() + "）";
        return (winner == Side.WHITE ? "白方" : "黑方") + "胜（" + termination.label() + "）";
    }
}
