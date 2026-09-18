package com.minechess.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Square;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class BoardTransformTest {

    private static final double EPS = 1.0e-6;

    private static BoardTransform transform(float yaw) {
        return new BoardTransform(new Location(null, 0, 64, 0), yaw, 0.7, 0.7, 3.4);
    }

    @Test
    void facingVectors() {
        assertVector(new Vector(0, 0, 1), BoardTransform.facing(0));
        assertVector(new Vector(-1, 0, 0), BoardTransform.facing(90));
        assertVector(new Vector(0, 0, -1), BoardTransform.facing(180));
        assertVector(new Vector(1, 0, 0), BoardTransform.facing(270));
        // 面向南时右手在西
        assertVector(new Vector(-1, 0, 0), BoardTransform.rightOf(new Vector(0, 0, 1)));
        assertVector(new Vector(1, 0, 0), BoardTransform.rightOf(new Vector(0, 0, -1)));
    }

    @Test
    void squareGeometryYawZero() {
        BoardTransform t = transform(0);
        // 白方在南侧面向北？yaw 0 面向 +Z（南），所以白方在 -Z 侧，rank 增大朝 +Z
        Location a1 = t.squareCenter(Square.A1);
        Location h1 = t.squareCenter(Square.H1);
        Location a8 = t.squareCenter(Square.A8);
        Location h8 = t.squareCenter(Square.H8);
        assertEquals(64 + 0.7, a1.getY(), EPS);
        // a1 在白方左手边（东 +X），h1 在白方右手边（西 -X）
        assertEquals(2.45, a1.getX(), EPS);
        assertEquals(-2.45, a1.getZ(), EPS);
        assertEquals(-2.45, h1.getX(), EPS);
        assertEquals(2.45, a8.getX(), EPS);
        assertEquals(-2.45, h8.getX(), EPS);
        assertEquals(2.45, h8.getZ(), EPS);
        // 相邻格正好一个边长
        assertEquals(0.7, distance(a1, t.squareCenter(Square.B1)), EPS);
        assertEquals(0.7, distance(a1, t.squareCenter(Square.A2)), EPS);
    }

    private static double distance(Location a, Location b) {
        return a.toVector().distance(b.toVector());
    }

    @Test
    void rotationKeepsBoardRigid() {
        BoardTransform t = transform(37);
        Location a1 = t.squareCenter(Square.A1);
        Location h8 = t.squareCenter(Square.H8);
        Location d4 = t.squareCenter(Square.D4);
        // 白黑两底线中心与棋盘中心共线
        Location whiteCenter = a1.clone().add(t.squareCenter(Square.H1)).multiply(0.5);
        Location blackCenter = t.squareCenter(Square.A8).clone().add(t.squareCenter(Square.H8)).multiply(0.5);
        Location middle = whiteCenter.clone().add(blackCenter).multiply(0.5);
        assertEquals(0, middle.getX(), EPS);
        assertEquals(0, middle.getZ(), EPS);
        assertEquals(0.7, distance(a1, t.squareCenter(Square.B1)), EPS);
        assertEquals(0.7, distance(t.squareCenter(Square.A1), t.squareCenter(Square.A2)), EPS);
        assertEquals(7 * 0.7 * Math.sqrt(2), distance(a1, h8), 1.0e-2);
        // d4 的中心离棋盘中心半格（棋盘中心在 d4/e4 之间）
        assertEquals(0.7 * Math.sqrt(0.5), distance(d4, t.boardPoint(0, 0, 0)), EPS);
    }

    @Test
    void seatsAndPoints() {
        BoardTransform t = transform(90);
        Location white = t.seat(true);
        Location black = t.seat(false);
        // yaw 90 面向西：白方在东侧，黑方在西侧
        assertEquals(3.4, white.getX(), 1.0e-3);
        assertEquals(-3.4, black.getX(), 1.0e-3);
        assertEquals(0, white.getZ(), 1.0e-3);
        assertEquals(90f, white.getYaw(), 1.0e-3);
        assertEquals(270f, black.getYaw(), 1.0e-3);

        Location center = t.boardPoint(0, 0, 0);
        assertEquals(64.7, center.getY(), EPS);
        Location clock = t.clockPoint(true, 0.5);
        assertEquals(65.2, clock.getY(), EPS);
        Location promo = t.promotionPoint(true, 2);
        assertTrue(distance(promo, center) > 2.5);
    }

    @Test
    void aimMapsToSquares() {
        BoardTransform t = new BoardTransform(new Location(null, 0, 64, 0), 0, 0.7, 0.7, 3.4);
        Location a1 = t.squareCenter(Square.A1);
        Location eye = a1.clone().add(0, 1.5, 0);
        // 从 a1 正上方向下看
        assertEquals(Square.A1, t.squareAt(eye, new Vector(0, -1, 0), 0.35, 12));
        // 棋盘中心正上方：落在 d4/e4/d5/e5 交界，floor 取 E5
        Location aboveCenter = t.boardPoint(0, 0, 0).add(0, 2, 0);
        assertEquals(Square.E5, t.squareAt(aboveCenter, new Vector(0, -1, 0), 0.35, 12));
        // 看向天空 / 超出距离
        assertNull(t.squareAt(eye, new Vector(0, 1, 0), 0.35, 12));
        assertNull(t.squareAt(eye, new Vector(0, -1, 0), 0.35, 1.0));
        // 从白方座位斜着看向 a1 正上方的棋盘面
        Location seatEye = t.seat(true).add(0, 1.5, 0);
        Vector towardsPlane = a1.clone().add(0, 0.35, 0).toVector().subtract(seatEye.toVector());
        assertEquals(Square.A1, t.squareAt(seatEye, towardsPlane, 0.35, 12));
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), EPS);
        assertEquals(expected.getY(), actual.getY(), EPS);
        assertEquals(expected.getZ(), actual.getZ(), EPS);
    }
}
