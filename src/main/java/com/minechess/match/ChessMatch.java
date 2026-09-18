package com.minechess.match;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import com.minechess.chess.ChessResult;
import com.minechess.chess.ChessRules;
import com.minechess.chess.DrawClaim;
import com.minechess.chess.MoveOutcome;
import com.minechess.chess.MoveRecord;
import com.minechess.chess.Termination;
import com.minechess.chess.TimeControl;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一场国际象棋对局。纯 Java：不引用任何 Bukkit / 展示层代码，可在单测里直接驱动。
 */
public final class ChessMatch {

    public enum Phase { PLAYING, FINISHED }

    private final UUID id;
    private final UUID whitePlayer;
    private final UUID blackPlayer;
    private final TimeControl timeControl;
    private final Board board = new Board();
    private final MoveList moveList;
    private final List<MoveRecord> moves = new ArrayList<>();
    private final Map<Side, ChessClock> clocks = new EnumMap<>(Side.class);

    private Phase phase = Phase.PLAYING;
    private ChessResult result;
    private Side drawOffer;
    private Side pendingPromotion;
    private Square pendingFrom;
    private Square pendingTo;
    private long startedAtMillis;

    public ChessMatch(UUID id, UUID whitePlayer, UUID blackPlayer, TimeControl timeControl) {
        this(id, whitePlayer, blackPlayer, timeControl, null);
    }

    /**
     * @param startFen 自定义起始局面（null 为标准开局），棋谱与 SAN 都从这个局面开始
     */
    public ChessMatch(UUID id, UUID whitePlayer, UUID blackPlayer, TimeControl timeControl, String startFen) {
        this.id = id;
        this.whitePlayer = whitePlayer;
        this.blackPlayer = blackPlayer;
        this.timeControl = timeControl;
        if (startFen != null && !startFen.isBlank()) {
            board.loadFromFen(startFen);
        }
        this.moveList = startFen == null || startFen.isBlank() ? new MoveList() : new MoveList(startFen);
        clocks.put(Side.WHITE, ChessClock.system(timeControl.initialMillis(), timeControl.incrementMillis()));
        clocks.put(Side.BLACK, ChessClock.system(timeControl.initialMillis(), timeControl.incrementMillis()));
    }

    /** 对局开始，轮到走棋的一方开始计时。 */
    public void start() {
        startedAtMillis = System.currentTimeMillis();
        clocks.get(board.getSideToMove()).start();
    }

    // ---------- 查询 ----------

    public UUID id() {
        return id;
    }

    public UUID whitePlayer() {
        return whitePlayer;
    }

    public UUID blackPlayer() {
        return blackPlayer;
    }

    public UUID playerOf(Side side) {
        return side == Side.WHITE ? whitePlayer : blackPlayer;
    }

    public Side sideOf(UUID player) {
        if (player == null) return null;
        if (player.equals(whitePlayer)) return Side.WHITE;
        if (player.equals(blackPlayer)) return Side.BLACK;
        return null;
    }

    public boolean involves(UUID player) {
        return sideOf(player) != null;
    }

    public Phase phase() {
        return phase;
    }

    public Side turn() {
        return board.getSideToMove();
    }

    public TimeControl timeControl() {
        return timeControl;
    }

    public ChessClock clock(Side side) {
        return clocks.get(side);
    }

    public ChessResult result() {
        return result;
    }

    public MoveRecord lastMove() {
        return moves.isEmpty() ? null : moves.get(moves.size() - 1);
    }

    public List<MoveRecord> moves() {
        return Collections.unmodifiableList(moves);
    }

    public String fen() {
        return board.getFen();
    }

    public Piece pieceAt(Square square) {
        return board.getPiece(square);
    }

    public boolean isCheck() {
        return board.isKingAttacked();
    }

    public int ply() {
        return moves.size();
    }

    public int fullMoveNumber() {
        return board.getMoveCounter();
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public Board board() {
        return board;
    }

    // ---------- 走子 ----------

    /**
     * 尝试走子。promotion 为 null 时若该步需要升变，返回 NEEDS_PROMOTION 并记录待选择状态。
     */
    public MoveOutcome play(UUID player, Square from, Square to, PieceType promotion) {
        if (phase != Phase.PLAYING) return MoveOutcome.fail(MoveOutcome.Status.NOT_PLAYING);
        Side side = sideOf(player);
        if (side == null) return MoveOutcome.fail(MoveOutcome.Status.NOT_PLAYING);
        if (side != board.getSideToMove()) return MoveOutcome.fail(MoveOutcome.Status.NOT_YOUR_TURN);
        Piece piece = board.getPiece(from);
        if (piece == Piece.NONE) return MoveOutcome.fail(MoveOutcome.Status.NO_PIECE);
        if (piece.getPieceSide() != side) return MoveOutcome.fail(MoveOutcome.Status.OPPONENT_PIECE);
        List<Move> candidates = ChessRules.legalMovesTo(board, from, to);
        if (candidates.isEmpty()) return MoveOutcome.fail(MoveOutcome.Status.ILLEGAL);
        boolean promotes = candidates.stream().anyMatch(m -> m.getPromotion() != Piece.NONE);
        if (promotes && promotion == null) {
            pendingPromotion = side;
            pendingFrom = from;
            pendingTo = to;
            return MoveOutcome.promotion();
        }
        Move move = ChessRules.findMove(board, from, to, promotion);
        if (move == null) return MoveOutcome.fail(MoveOutcome.Status.ILLEGAL);
        return apply(move);
    }

    /** 完成升变选择。 */
    public MoveOutcome promote(UUID player, PieceType type) {
        if (phase != Phase.PLAYING) return MoveOutcome.fail(MoveOutcome.Status.NOT_PLAYING);
        if (pendingPromotion == null || pendingPromotion != sideOf(player)) {
            return MoveOutcome.fail(MoveOutcome.Status.ILLEGAL);
        }
        Square from = pendingFrom;
        Square to = pendingTo;
        clearPendingPromotion();
        Move move = ChessRules.findMove(board, from, to, type);
        if (move == null) return MoveOutcome.fail(MoveOutcome.Status.ILLEGAL);
        return apply(move);
    }

    public boolean promotionPending() {
        return pendingPromotion != null;
    }

    public Side promotionSide() {
        return pendingPromotion;
    }

    public Square promotionFrom() {
        return pendingFrom;
    }

    public Square promotionTo() {
        return pendingTo;
    }

    public void cancelPromotion() {
        clearPendingPromotion();
    }

    private void clearPendingPromotion() {
        pendingPromotion = null;
        pendingFrom = null;
        pendingTo = null;
    }

    private MoveOutcome apply(Move move) {
        Side mover = board.getSideToMove();
        Piece moving = board.getPiece(move.getFrom());
        Square capturedSquare = ChessRules.capturedSquare(board, move);
        Piece captured = capturedSquare == null ? Piece.NONE : board.getPiece(capturedSquare);
        boolean castle = ChessRules.isCastle(board, move);
        boolean enPassant = moving.getPieceType() == PieceType.PAWN
                && capturedSquare != null && capturedSquare != move.getTo();

        board.doMove(move);
        moveList.add(move);
        String san = ChessRules.sanArray(moveList)[moveList.size() - 1];

        boolean check = board.isKingAttacked();
        boolean mate = board.isMated();
        long elapsed = clocks.get(mover).stopAndElapsed();
        clocks.get(mover).addIncrement();

        MoveRecord record = new MoveRecord(moves.size() + 1, ChessRules.uci(move), san, moving,
                move.getFrom(), move.getTo(), captured, capturedSquare, move.getPromotion(),
                castle, enPassant, check, mate, elapsed, clocks.get(mover).remainingMillis());
        moves.add(record);

        drawOffer = null;
        clearPendingPromotion();

        if (mate) {
            result = ChessResult.win(mover, Termination.CHECKMATE);
            phase = Phase.FINISHED;
        } else if (board.isStaleMate()) {
            result = ChessResult.draw(Termination.STALEMATE);
            phase = Phase.FINISHED;
        } else if (board.isInsufficientMaterial()) {
            result = ChessResult.draw(Termination.INSUFFICIENT_MATERIAL);
            phase = Phase.FINISHED;
        } else if (board.isRepetition(5)) {
            result = ChessResult.draw(Termination.FIVEFOLD_REPETITION);
            phase = Phase.FINISHED;
        } else if (board.getHalfMoveCounter() >= 150) {
            result = ChessResult.draw(Termination.SEVENTY_FIVE_MOVE_RULE);
            phase = Phase.FINISHED;
        } else {
            clocks.get(board.getSideToMove()).start();
        }
        return MoveOutcome.ok(record, result);
    }

    // ---------- 选点提示 ----------

    public List<Square> legalTargets(Square from) {
        if (from == null) return List.of();
        Set<Square> targets = new LinkedHashSet<>();
        for (Move move : ChessRules.legalMovesFrom(board, from)) {
            targets.add(move.getTo());
        }
        return new ArrayList<>(targets);
    }

    /** 其中可以吃子的目标格（含吃过路兵）。 */
    public Set<Square> captureTargets(Square from) {
        if (from == null) return Set.of();
        Set<Square> targets = new LinkedHashSet<>();
        for (Move move : ChessRules.legalMovesFrom(board, from)) {
            Square captured = ChessRules.capturedSquare(board, move);
            if (captured != null) targets.add(move.getTo());
        }
        return targets;
    }

    // ---------- 和棋 / 认输 ----------

    public boolean canClaim(DrawClaim claim) {
        if (phase != Phase.PLAYING) return false;
        return switch (claim) {
            case THREEFOLD_REPETITION -> board.isRepetition(3);
            case FIFTY_MOVE_RULE -> board.getHalfMoveCounter() >= 100;
        };
    }

    /** 申请和棋：必须轮到自己走棋。成功返回结束结果。 */
    public ChessResult claimDraw(UUID player, DrawClaim claim) {
        if (phase != Phase.PLAYING) return null;
        Side side = sideOf(player);
        if (side == null || side != board.getSideToMove()) return null;
        if (!canClaim(claim)) return null;
        finish(ChessResult.draw(claim.termination()));
        return result;
    }

    public Side drawOffer() {
        return drawOffer;
    }

    public boolean offerDraw(UUID player) {
        if (phase != Phase.PLAYING) return false;
        Side side = sideOf(player);
        if (side == null || drawOffer != null) return false;
        drawOffer = side;
        return true;
    }

    public boolean acceptDraw(UUID player) {
        if (phase != Phase.PLAYING || drawOffer == null) return false;
        Side side = sideOf(player);
        if (side == null || side == drawOffer) return false;
        finish(ChessResult.draw(Termination.DRAW_AGREEMENT));
        return true;
    }

    public void declineDraw() {
        drawOffer = null;
    }

    public ChessResult resign(UUID player) {
        if (phase != Phase.PLAYING) return null;
        Side side = sideOf(player);
        if (side == null) return null;
        finish(ChessResult.win(side.flip(), Termination.RESIGNATION));
        return result;
    }

    /** 棋钟超时（flaggedSide 是超时的一方）。 */
    public ChessResult timeout(Side flaggedSide) {
        if (phase != Phase.PLAYING) return null;
        finish(ChessResult.win(flaggedSide.flip(), Termination.TIMEOUT));
        return result;
    }

    /** 断线判负。 */
    public ChessResult forfeit(UUID player) {
        if (phase != Phase.PLAYING) return null;
        Side side = sideOf(player);
        if (side == null) return null;
        finish(ChessResult.win(side.flip(), Termination.DISCONNECT_FORFEIT));
        return result;
    }

    private void finish(ChessResult chessResult) {
        result = chessResult;
        phase = Phase.FINISHED;
        for (ChessClock clock : clocks.values()) {
            if (clock.running()) clock.stopAndElapsed();
        }
        clearPendingPromotion();
    }

    // ---------- PGN ----------

    public String pgn(String whiteName, String blackName) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Event \"MineChess\"]\n");
        sb.append("[Site \"MineChess\"]\n");
        sb.append("[Date \"").append(LocalDate.now()).append("\"]\n");
        sb.append("[Round \"-\"]\n");
        sb.append("[White \"").append(escape(whiteName)).append("\"]\n");
        sb.append("[Black \"").append(escape(blackName)).append("\"]\n");
        sb.append("[Result \"").append(result == null ? "*" : result.score()).append("\"]\n");
        if (!timeControl.casual()) {
            sb.append("[TimeControl \"").append(timeControl.label()).append("\"]\n");
        }
        if (result != null && !result.isDraw()) {
            sb.append("[Termination \"").append(result.termination().name()).append("\"]\n");
        }
        if (result != null) {
            sb.append("[FEN \"").append(fen()).append("\"]\n");
        }
        sb.append('\n');
        String[] sans = ChessRules.sanArray(moveList);
        for (int i = 0; i < sans.length; i++) {
            if (i % 2 == 0) sb.append(i / 2 + 1).append(". ");
            sb.append(sans[i]).append(' ');
        }
        sb.append(result == null ? "*" : result.score()).append('\n');
        return sb.toString();
    }

    private static String escape(String name) {
        return name == null ? "?" : name.replace("\"", "'");
    }
}
