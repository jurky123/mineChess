package com.minechess.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.File;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.Test;

class ChessRulesTest {

    @Test
    void parseAndFormatUci() {
        Move move = ChessRules.parseUci("e2e4", Side.WHITE);
        assertNotNull(move);
        assertEquals(Square.E2, move.getFrom());
        assertEquals(Square.E4, move.getTo());
        assertEquals("e2e4", ChessRules.uci(move));

        Move promotion = ChessRules.parseUci("e7e8q", Side.WHITE);
        assertNotNull(promotion);
        assertEquals(Piece.WHITE_QUEEN, promotion.getPromotion());
        assertEquals("e7e8q", ChessRules.uci(promotion));
        assertNull(ChessRules.parseUci("xyz", Side.WHITE));
        assertNull(ChessRules.parseUci("e2e9", Side.WHITE));
        assertNull(ChessRules.parseUci("e2e4k", Side.WHITE));
    }

    @Test
    void squareHelpers() {
        assertEquals(Square.E4, ChessRules.parseSquare("e4"));
        assertNull(ChessRules.parseSquare("z9"));
        assertEquals("white_knight", ChessRules.modelKey(Piece.WHITE_KNIGHT));
        assertEquals("black_king", ChessRules.modelKey(Piece.BLACK_KING));
        assertEquals('q', ChessRules.promotionChar(PieceType.QUEEN));
        assertEquals(PieceType.KNIGHT, ChessRules.promotionType('n'));
    }

    @Test
    void enPassantCapturedSquare() {
        Board board = new Board();
        board.loadFromFen("rnbqkbnr/1pp1pppp/p7/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3");
        Move move = ChessRules.findMove(board, Square.E5, Square.D6, null);
        assertNotNull(move);
        assertEquals(Square.D5, ChessRules.capturedSquare(board, move));
    }

    @Test
    void castleDetection() {
        Board board = new Board();
        board.loadFromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Move kingSide = ChessRules.findMove(board, Square.E1, Square.G1, null);
        assertNotNull(kingSide);
        assertTrue(ChessRules.isCastle(board, kingSide));
        assertEquals(Square.H1, ChessRules.castleRookFrom(board, kingSide));
        assertEquals(Square.F1, ChessRules.castleRookTo(board, kingSide));

        Move queenSide = ChessRules.findMove(board, Square.E1, Square.C1, null);
        assertNotNull(queenSide);
        assertEquals(Square.A1, ChessRules.castleRookFrom(board, queenSide));
        assertEquals(Square.D1, ChessRules.castleRookTo(board, queenSide));

        Move normal = ChessRules.findMove(board, Square.E1, Square.E2, null);
        assertNotNull(normal);
        assertFalse(ChessRules.isCastle(board, normal));
    }

    @Test
    void promotionDetection() {
        Board board = new Board();
        board.loadFromFen("8/P7/8/8/8/8/k7/4K3 w - - 0 1");
        assertTrue(ChessRules.requiresPromotion(board, Square.A7, Square.A8));
        assertNotNull(ChessRules.findMove(board, Square.A7, Square.A8, PieceType.ROOK));
        assertNotNull(ChessRules.findMove(board, Square.A7, Square.A8, PieceType.KNIGHT));
        assertEquals(4, ChessRules.legalMovesTo(board, Square.A7, Square.A8).size());
        assertEquals(File.FILE_A, Square.A1.getFile());
    }
}
