package com.minechess.chess;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;

/**
 * 一步棋的完整记录：内部 UCI、展示 SAN、移动/被吃/升变棋子、特殊着法与耗时。
 *
 * @param capturedSquare 吃子位置（吃过路兵时与 to 不同），无吃子为 null
 * @param promotion      升变后的棋子，未升变为 Piece.NONE
 */
public record MoveRecord(
        int ply,
        String uci,
        String san,
        Piece piece,
        Square from,
        Square to,
        Piece captured,
        Square capturedSquare,
        Piece promotion,
        boolean castle,
        boolean enPassant,
        boolean check,
        boolean checkmate,
        long elapsedMillis,
        long remainingMillis
) {

    public boolean isCapture() {
        return captured != null && captured != Piece.NONE;
    }

    public boolean isPromotion() {
        return promotion != null && promotion != Piece.NONE;
    }

    /** 资源包模型名，例如 white_knight。 */
    public String pieceModel() {
        return ChessRules.modelKey(piece);
    }
}
