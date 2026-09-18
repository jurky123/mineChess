package com.minechess.ai;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 运行中的 AI 玩家注册表：把 UUID 映射到后端实现，负责名字与决策调用。 */
public final class BotService {

    private final Map<UUID, ChessBot> bots = new HashMap<>();

    public UUID spawn(ChessBot bot) {
        UUID id = UUID.randomUUID();
        bots.put(id, bot);
        return id;
    }

    public boolean isBot(UUID id) {
        return id != null && bots.containsKey(id);
    }

    public String name(UUID id) {
        ChessBot bot = bots.get(id);
        return bot == null ? null : bot.displayName();
    }

    public ChessBot bot(UUID id) {
        return bots.get(id);
    }

    public Move chooseMove(UUID id, Board board) {
        ChessBot bot = bots.get(id);
        if (bot == null) return null;
        try {
            return bot.chooseMove(board);
        } catch (Throwable t) {
            return null;
        }
    }

    public void remove(UUID id) {
        bots.remove(id);
    }

    public List<UUID> ids() {
        return List.copyOf(bots.keySet());
    }
}
