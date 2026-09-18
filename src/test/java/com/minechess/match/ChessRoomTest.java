package com.minechess.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Side;
import com.minechess.chess.TimeControl;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChessRoomTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final UUID GUEST = UUID.randomUUID();

    private static ChessRoom room() {
        return new ChessRoom(UUID.randomUUID(), 1, HOST, TimeControl.parse("10+0"));
    }

    @Test
    void joinAndReadyFlow() {
        ChessRoom room = room();
        assertEquals(1, room.size());
        assertFalse(room.hasGuest());
        assertFalse(room.canStart());

        assertFalse(room.join(HOST), "房主不能重复加入");
        assertTrue(room.join(GUEST));
        assertEquals(2, room.size());
        assertFalse(room.join(UUID.randomUUID()), "满员不能再加入");

        assertFalse(room.canStart(), "未准备不能开始");
        room.toggleReady(HOST);
        assertFalse(room.guestReady(), "房主准备无效");
        room.toggleReady(GUEST);
        assertTrue(room.guestReady());
        assertTrue(room.canStart());
        room.toggleReady(GUEST);
        assertFalse(room.canStart());
    }

    @Test
    void guestLeavesResetsReady() {
        ChessRoom room = room();
        room.join(GUEST);
        room.toggleReady(GUEST);
        assertTrue(room.leave(GUEST));
        assertTrue(room.hasGuest() == false);
        assertEquals(1, room.size());
        assertFalse(room.guestReady());
        assertTrue(room.join(GUEST));
        assertFalse(room.guestReady());
    }

    @Test
    void hostLeavePromotesGuest() {
        ChessRoom room = room();
        room.join(GUEST);
        assertTrue(room.leave(HOST));
        assertEquals(GUEST, room.host());
        assertFalse(room.hasGuest());
        assertEquals(1, room.size());
        assertTrue(room.canStart() == false);
    }

    @Test
    void emptyRoomRemoved() {
        ChessRoom room = room();
        assertFalse(room.leave(HOST));
        assertTrue(room.isEmpty());
        assertEquals(0, room.size());
    }

    @Test
    void timeControlCanChange() {
        ChessRoom room = room();
        room.timeControl(TimeControl.parse("3+2"));
        assertEquals("3+2", room.timeControl().label());
    }

    @Test
    void hostColorSwitch() {
        ChessRoom room = room();
        room.join(GUEST);
        assertEquals(Side.WHITE, room.hostColor());
        assertEquals(HOST, room.whitePlayer());
        assertEquals(GUEST, room.blackPlayer());

        room.swapHostColor();
        assertEquals(Side.BLACK, room.hostColor());
        assertEquals(GUEST, room.whitePlayer());
        assertEquals(HOST, room.blackPlayer());
        assertEquals(Side.BLACK, room.colorOf(HOST));
        assertEquals(Side.WHITE, room.colorOf(GUEST));
    }

    @Test
    void spectatorSlotsCapAtFour() {
        ChessRoom room = room();
        room.join(GUEST);
        assertFalse(room.joinSpectator(HOST), "棋手不能占观战席");
        assertFalse(room.joinSpectator(GUEST), "棋手不能占观战席");
        assertFalse(room.joinSpectator(null));

        UUID[] watchers = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (UUID watcher : watchers) {
            assertTrue(room.joinSpectator(watcher));
            assertTrue(room.isSpectator(watcher));
            assertFalse(room.joinSpectator(watcher), "不能重复占座");
        }
        assertEquals(ChessRoom.SPECTATOR_SLOTS, room.spectatorCount());
        assertTrue(room.spectatorFull());
        assertFalse(room.joinSpectator(UUID.randomUUID()), "观战席已满");

        assertTrue(room.leaveSpectator(watchers[0]));
        assertFalse(room.isSpectator(watchers[0]));
        assertFalse(room.spectatorFull());
        assertTrue(room.joinSpectator(watchers[0]));
    }

    @Test
    void spectatorIsNotGuest() {
        ChessRoom room = room();
        UUID watcher = UUID.randomUUID();
        assertTrue(room.joinSpectator(watcher));
        assertFalse(room.join(watcher), "观战者不能直接转成挑战者");
        assertTrue(room.contains(watcher));
        assertFalse(room.isPlayer(watcher));
        assertEquals(1, room.size());
        assertFalse(room.canStart());
    }
}
