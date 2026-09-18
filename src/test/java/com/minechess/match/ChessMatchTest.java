package com.minechess.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.minechess.chess.ChessRules;
import com.minechess.chess.DrawClaim;
import com.minechess.chess.MoveOutcome;
import com.minechess.chess.Termination;
import com.minechess.chess.TimeControl;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChessMatchTest {

    private static final UUID WHITE = UUID.randomUUID();
    private static final UUID BLACK = UUID.randomUUID();

    private static ChessMatch newMatch(String fen) {
        ChessMatch match = new ChessMatch(UUID.randomUUID(), WHITE, BLACK, TimeControl.parse("10+0"), fen);
        match.start();
        return match;
    }

    private static MoveOutcome play(ChessMatch match, UUID player, String uci) {
        Side side = match.sideOf(player);
        Move move = ChessRules.parseUci(uci, side);
        assertNotNull(move, "无法解析着法 " + uci);
        PieceType promotion = move.getPromotion() == Piece.NONE ? null : move.getPromotion().getPieceType();
        return match.play(player, move.getFrom(), move.getTo(), promotion);
    }

    @Test
    void scholarMate() {
        ChessMatch match = newMatch(null);
        assertEquals(MoveOutcome.Status.OK, play(match, WHITE, "e2e4").status());
        assertEquals(MoveOutcome.Status.OK, play(match, BLACK, "e7e5").status());
        assertEquals(MoveOutcome.Status.OK, play(match, WHITE, "f1c4").status());
        assertEquals(MoveOutcome.Status.OK, play(match, BLACK, "b8c6").status());
        assertEquals(MoveOutcome.Status.OK, play(match, WHITE, "d1h5").status());
        assertEquals(MoveOutcome.Status.OK, play(match, BLACK, "g8f6").status());
        MoveOutcome mate = play(match, WHITE, "h5f7");
        assertEquals(MoveOutcome.Status.OK, mate.status());
        assertNotNull(mate.result());
        assertEquals(Side.WHITE, mate.result().winner());
        assertEquals(Termination.CHECKMATE, mate.result().termination());
        assertTrue(mate.move().checkmate());
        assertTrue(mate.move().isCapture());
        assertEquals(ChessMatch.Phase.FINISHED, match.phase());
        assertTrue(match.pgn("Alice", "Bob").contains("1-0"));
    }

    @Test
    void whiteStartsAndClocksRun() {
        ChessMatch match = newMatch(null);
        assertEquals(Side.WHITE, match.turn());
        assertTrue(match.clock(Side.WHITE).running());
        assertFalse(match.clock(Side.BLACK).running());
        play(match, WHITE, "e2e4");
        assertFalse(match.clock(Side.WHITE).running());
        assertTrue(match.clock(Side.BLACK).running());
    }

    @Test
    void rejectsIllegalAndWrongTurn() {
        ChessMatch match = newMatch(null);
        // 黑方先走
        assertEquals(MoveOutcome.Status.NOT_YOUR_TURN, play(match, BLACK, "e7e5").status());
        // 走对方的子
        assertEquals(MoveOutcome.Status.OPPONENT_PIECE, play(match, WHITE, "e7e5").status());
        // 非法着法
        assertEquals(MoveOutcome.Status.ILLEGAL, play(match, WHITE, "e2e5").status());
        // 空格
        assertEquals(MoveOutcome.Status.NO_PIECE, play(match, WHITE, "e3e4").status());
        // 非法不会改变局面
        assertEquals(Side.WHITE, match.turn());
        assertEquals(0, match.ply());
    }

    @Test
    void kingsideCastle() {
        ChessMatch match = newMatch(null);
        play(match, WHITE, "e2e4");
        play(match, BLACK, "e7e5");
        play(match, WHITE, "g1f3");
        play(match, BLACK, "b8c6");
        play(match, WHITE, "f1c4");
        play(match, BLACK, "f8c5");
        MoveOutcome castle = play(match, WHITE, "e1g1");
        assertEquals(MoveOutcome.Status.OK, castle.status());
        assertTrue(castle.move().castle());
        assertEquals(Piece.WHITE_KING, match.pieceAt(Square.G1));
        assertEquals(Piece.WHITE_ROOK, match.pieceAt(Square.F1));
        assertEquals(Piece.NONE, match.pieceAt(Square.E1));
        assertEquals(Piece.NONE, match.pieceAt(Square.H1));
    }

    @Test
    void enPassant() {
        ChessMatch match = newMatch(null);
        play(match, WHITE, "e2e4");
        play(match, BLACK, "a7a6");
        play(match, WHITE, "e4e5");
        play(match, BLACK, "d7d5");
        MoveOutcome outcome = play(match, WHITE, "e5d6");
        assertEquals(MoveOutcome.Status.OK, outcome.status());
        assertTrue(outcome.move().enPassant());
        assertEquals(Square.D5, outcome.move().capturedSquare());
        assertEquals(Piece.BLACK_PAWN, outcome.move().captured());
        assertEquals(Piece.WHITE_PAWN, match.pieceAt(Square.D6));
        assertEquals(Piece.NONE, match.pieceAt(Square.D5));
    }

    @Test
    void promotionRequiresChoice() {
        ChessMatch match = newMatch("8/P7/8/8/8/8/k7/4K3 w - - 0 1");
        MoveOutcome pending = match.play(WHITE, Square.A7, Square.A8, null);
        assertEquals(MoveOutcome.Status.NEEDS_PROMOTION, pending.status());
        assertTrue(match.promotionPending());
        MoveOutcome promoted = match.promote(WHITE, PieceType.QUEEN);
        assertEquals(MoveOutcome.Status.OK, promoted.status());
        assertEquals(Piece.WHITE_QUEEN, match.pieceAt(Square.A8));
        assertEquals(PieceType.QUEEN, promoted.move().promotion().getPieceType());
        assertFalse(match.promotionPending());
    }

    @Test
    void knightPromotionUnderpromotion() {
        ChessMatch match = newMatch("8/P7/8/8/8/8/k7/4K3 w - - 0 1");
        match.play(WHITE, Square.A7, Square.A8, null);
        MoveOutcome promoted = match.promote(WHITE, PieceType.KNIGHT);
        assertEquals(MoveOutcome.Status.OK, promoted.status());
        assertEquals(Piece.WHITE_KNIGHT, match.pieceAt(Square.A8));
    }

    @Test
    void threefoldRepetitionClaim() {
        ChessMatch match = newMatch(null);
        for (int i = 0; i < 2; i++) {
            play(match, WHITE, "g1f3");
            play(match, BLACK, "g8f6");
            play(match, WHITE, "f3g1");
            play(match, BLACK, "f6g8");
        }
        assertTrue(match.canClaim(DrawClaim.THREEFOLD_REPETITION));
        assertNotNull(match.claimDraw(WHITE, DrawClaim.THREEFOLD_REPETITION));
        assertEquals(Termination.THREEFOLD_REPETITION, match.result().termination());
        assertNull(match.result().winner());
    }

    @Test
    void fiftyMoveClaim() {
        ChessMatch match = newMatch("4k3/8/8/8/8/8/8/R3K3 w - - 99 60");
        assertFalse(match.canClaim(DrawClaim.FIFTY_MOVE_RULE));
        play(match, WHITE, "a1a2");
        assertTrue(match.canClaim(DrawClaim.FIFTY_MOVE_RULE));
        assertNotNull(match.claimDraw(BLACK, DrawClaim.FIFTY_MOVE_RULE));
        assertEquals(Termination.FIFTY_MOVE_RULE, match.result().termination());
    }

    @Test
    void seventyFiveMoveAutoDraw() {
        ChessMatch match = newMatch("4k3/8/8/8/8/8/8/R3K3 w - - 149 80");
        MoveOutcome outcome = play(match, WHITE, "a1a2");
        assertNotNull(outcome.result());
        assertEquals(Termination.SEVENTY_FIVE_MOVE_RULE, outcome.result().termination());
    }

    @Test
    void insufficientMaterialAutoDraw() {
        ChessMatch match = newMatch("8/8/8/8/8/8/4k3/6K1 w - - 0 1");
        MoveOutcome outcome = play(match, WHITE, "g1h1");
        assertNotNull(outcome.result());
        assertEquals(Termination.INSUFFICIENT_MATERIAL, outcome.result().termination());
    }

    @Test
    void stalemate() {
        ChessMatch match = newMatch("k7/8/1Q6/8/8/8/6K1 w - - 0 1");
        MoveOutcome outcome = play(match, WHITE, "g2h2");
        assertNotNull(outcome.result());
        assertEquals(Termination.STALEMATE, outcome.result().termination());
        assertNull(outcome.result().winner());
    }

    @Test
    void resignAndTimeout() {
        ChessMatch resign = newMatch(null);
        assertEquals(Termination.RESIGNATION, resign.resign(WHITE).termination());
        assertEquals(Side.BLACK, resign.result().winner());

        ChessMatch timeout = new ChessMatch(UUID.randomUUID(), WHITE, BLACK, new TimeControl("test", 1, 0));
        timeout.start();
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(timeout.clock(Side.WHITE).flagged());
        assertEquals(Termination.TIMEOUT, timeout.timeout(Side.WHITE).termination());
        assertEquals(Side.BLACK, timeout.result().winner());
    }

    @Test
    void drawOfferFlow() {
        ChessMatch match = newMatch(null);
        assertFalse(match.acceptDraw(WHITE));
        assertTrue(match.offerDraw(WHITE));
        assertFalse(match.offerDraw(WHITE));
        assertFalse(match.acceptDraw(WHITE));
        assertTrue(match.acceptDraw(BLACK));
        assertEquals(Termination.DRAW_AGREEMENT, match.result().termination());

        ChessMatch declined = newMatch(null);
        assertTrue(declined.offerDraw(WHITE));
        declined.declineDraw();
        assertNull(declined.drawOffer());
        // 落子后和棋请求自动失效
        assertTrue(declined.offerDraw(BLACK));
        play(declined, WHITE, "e2e4");
        assertNull(declined.drawOffer());
    }

    @Test
    void sanAndHistory() {
        ChessMatch match = newMatch(null);
        play(match, WHITE, "e2e4");
        play(match, BLACK, "e7e5");
        play(match, WHITE, "g1f3");
        assertEquals("e4", match.moves().get(0).san());
        assertEquals("e5", match.moves().get(1).san());
        assertEquals("Nf3", match.moves().get(2).san());
        assertEquals("e2e4", match.moves().get(0).uci());
        String pgn = match.pgn("Alice", "Bob");
        assertTrue(pgn.contains("1. e4 e5 2. Nf3 *"), pgn);
        assertTrue(pgn.contains("[White \"Alice\"]"), pgn);
        assertEquals("e2e4", match.moves().get(0).uci());
    }
}
