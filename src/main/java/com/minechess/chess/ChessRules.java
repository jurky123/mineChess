package com.minechess.chess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.File;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveConversionException;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveGeneratorException;
import com.github.bhlangonijr.chesslib.move.MoveList;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** chesslib 的薄包装：本插件的规则层只通过这里接触第三方库，方便测试与替换。 */
public final class ChessRules {

    private ChessRules() {
    }

    public static List<Move> legalMoves(Board board) {
        try {
            return MoveGenerator.generateLegalMoves(board);
        } catch (MoveGeneratorException e) {
            throw new IllegalStateException("无法生成合法着法", e);
        }
    }

    public static List<Move> legalMovesFrom(Board board, Square from) {
        List<Move> result = new ArrayList<>();
        for (Move move : legalMoves(board)) {
            if (move.getFrom() == from) result.add(move);
        }
        return result;
    }

    public static List<Move> legalMovesTo(Board board, Square from, Square to) {
        List<Move> result = new ArrayList<>();
        for (Move move : legalMoves(board)) {
            if (move.getFrom() == from && move.getTo() == to) result.add(move);
        }
        return result;
    }

    public static Move findMove(Board board, Square from, Square to, PieceType promotion) {
        for (Move move : legalMoves(board)) {
            if (move.getFrom() != from || move.getTo() != to) continue;
            if (promotion == null) {
                if (move.getPromotion() == Piece.NONE) return move;
            } else if (move.getPromotion() != Piece.NONE
                    && move.getPromotion().getPieceType() == promotion) {
                return move;
            }
        }
        return null;
    }

    public static boolean requiresPromotion(Board board, Square from, Square to) {
        for (Move move : legalMovesTo(board, from, to)) {
            if (move.getPromotion() != Piece.NONE) return true;
        }
        return false;
    }

    /** 这一步吃掉的棋子的位置：普通吃子在 to，吃过路兵在 to 同列的原 rank。 */
    public static Square capturedSquare(Board board, Move move) {
        if (board.getPiece(move.getTo()) != Piece.NONE) return move.getTo();
        Piece mover = board.getPiece(move.getFrom());
        if (mover.getPieceType() == PieceType.PAWN && move.getFrom().getFile() != move.getTo().getFile()) {
            return Square.encode(move.getFrom().getRank(), move.getTo().getFile());
        }
        return null;
    }

    public static boolean isCastle(Board board, Move move) {
        Piece mover = board.getPiece(move.getFrom());
        if (mover.getPieceType() != PieceType.KING) return false;
        return Math.abs(move.getTo().getFile().ordinal() - move.getFrom().getFile().ordinal()) == 2;
    }

    /** 王车易位时车的起点，非易位返回 null。 */
    public static Square castleRookFrom(Board board, Move move) {
        if (!isCastle(board, move)) return null;
        boolean kingSide = move.getTo().getFile().ordinal() > move.getFrom().getFile().ordinal();
        File file = kingSide ? File.FILE_H : File.FILE_A;
        return Square.encode(move.getFrom().getRank(), file);
    }

    /** 王车易位时车的终点，非易位返回 null。 */
    public static Square castleRookTo(Board board, Move move) {
        if (!isCastle(board, move)) return null;
        boolean kingSide = move.getTo().getFile().ordinal() > move.getFrom().getFile().ordinal();
        File file = kingSide ? File.FILE_F : File.FILE_D;
        return Square.encode(move.getFrom().getRank(), file);
    }

    public static String uci(Move move) {
        StringBuilder sb = new StringBuilder(5);
        sb.append(move.getFrom().name().toLowerCase(Locale.ROOT));
        sb.append(move.getTo().name().toLowerCase(Locale.ROOT));
        if (move.getPromotion() != Piece.NONE) {
            sb.append(promotionChar(move.getPromotion().getPieceType()));
        }
        return sb.toString();
    }

    public static char promotionChar(PieceType type) {
        return switch (type) {
            case QUEEN -> 'q';
            case ROOK -> 'r';
            case BISHOP -> 'b';
            case KNIGHT -> 'n';
            default -> ' ';
        };
    }

    public static PieceType promotionType(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'q' -> PieceType.QUEEN;
            case 'r' -> PieceType.ROOK;
            case 'b' -> PieceType.BISHOP;
            case 'n' -> PieceType.KNIGHT;
            default -> null;
        };
    }

    /** 解析 UCI（如 e2e4、e7e8q），side 用于升变棋子颜色；非法返回 null。 */
    public static Move parseUci(String text, Side side) {
        if (text == null || text.length() < 4) return null;
        Square from;
        Square to;
        try {
            from = Square.valueOf(text.substring(0, 2).toUpperCase(Locale.ROOT));
            to = Square.valueOf(text.substring(2, 4).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (from == Square.NONE || to == Square.NONE) return null;
        if (text.length() >= 5) {
            PieceType type = promotionType(text.charAt(4));
            if (type == null) return null;
            return new Move(from, to, Piece.make(side, type));
        }
        return new Move(from, to);
    }

    public static Square parseSquare(String text) {
        if (text == null || text.length() != 2) return null;
        try {
            Square square = Square.valueOf(text.toUpperCase(Locale.ROOT));
            return square == Square.NONE ? null : square;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 资源包模型名，例如 white_knight。 */
    public static String modelKey(Piece piece) {
        String side = piece.getPieceSide() == Side.WHITE ? "white" : "black";
        return side + "_" + piece.getPieceType().name().toLowerCase(Locale.ROOT);
    }

    public static String pieceName(Piece piece) {
        if (piece == null || piece == Piece.NONE) return "";
        String cn = switch (piece.getPieceType()) {
            case PAWN -> "兵";
            case KNIGHT -> "马";
            case BISHOP -> "象";
            case ROOK -> "车";
            case QUEEN -> "后";
            case KING -> "王";
            default -> "?";
        };
        return (piece.getPieceSide() == Side.WHITE ? "白" : "黑") + cn;
    }

    public static String squareName(Square square) {
        return square == null ? "??" : square.name().toLowerCase(Locale.ROOT);
    }

    public static String[] sanArray(MoveList list) {
        try {
            return list.toSanArray();
        } catch (MoveConversionException e) {
            throw new IllegalStateException("无法生成 SAN", e);
        }
    }
}
