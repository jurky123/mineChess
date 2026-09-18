package com.minechess.ai;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 随机落子的占位 AI：后续可在 Bots 工厂里换成真正的引擎实现。 */
public final class RandomBot implements ChessBot {

    @Override
    public String id() {
        return "random";
    }

    @Override
    public String displayName() {
        return "随机 AI";
    }

    @Override
    public Move chooseMove(Board board) {
        List<Move> moves = board.legalMoves();
        if (moves.isEmpty()) return null;
        return moves.get(ThreadLocalRandom.current().nextInt(moves.size()));
    }
}
