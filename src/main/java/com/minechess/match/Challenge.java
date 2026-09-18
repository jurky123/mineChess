package com.minechess.match;

import com.minechess.chess.TimeControl;
import java.util.UUID;

/**
 * 一条待响应的邀请。
 *
 * @param roomId 指定房间时为「邀请加入房间」，接受后进入该房间等待准备；为 null 时是直接挑战，接受即开局
 */
public record Challenge(UUID challenger, UUID target, TimeControl timeControl,
                        UUID roomId, long createdAtMillis) {

    public boolean expired(long nowMillis, long timeoutMillis) {
        return nowMillis - createdAtMillis > timeoutMillis;
    }

    public boolean roomInvite() {
        return roomId != null;
    }
}
