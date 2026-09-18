package com.minechess.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotTest {

    @Test
    void randomBotAlwaysPicksLegalMove() {
        Board board = new Board();
        RandomBot bot = new RandomBot();
        for (int i = 0; i < 40; i++) {
            Move move = bot.chooseMove(board);
            assertNotNull(move);
            List<Move> legal = board.legalMoves();
            assertTrue(legal.stream().anyMatch(candidate -> candidate.equals(move)),
                    "AI 返回了非法着法: " + move);
            board.doMove(move);
        }
    }

    @Test
    void botServiceRegistry() {
        BotService service = new BotService();
        UUID id = service.spawn(new RandomBot());
        assertTrue(service.isBot(id));
        assertEquals("随机 AI", service.name(id));
        assertNotNull(service.chooseMove(id, new Board()));
        service.remove(id);
        assertFalse(service.isBot(id));
    }
}
