package com.minechess.ai;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

/**
 * AI 玩家后端接口：纯 Java，只依赖棋盘局面，方便后续接入真正的引擎（如 Stockfish/UCI 进程、
 * 搜索算法、远程服务）。返回的着法必须来自 {@code board.legalMoves()}。
 */
public interface ChessBot {

    /** 配置里使用的 id（如 random）。 */
    String id();

    /** 显示名（如 "随机 AI"）。 */
    String displayName();

    /** 为当前局面选择一步合法着法。 */
    Move chooseMove(Board board);
}
