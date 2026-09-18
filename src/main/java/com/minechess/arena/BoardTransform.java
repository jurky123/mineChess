package com.minechess.arena;

import com.github.bhlangonijr.chesslib.Square;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * 棋盘坐标系统：所有格子的世界坐标都由「中心点 + 白方朝向 + 每格边长」推导。
 * dx 以格为单位沿 file 方向（白方右手），dz 以格为单位沿 rank 方向（白 -> 黑），dy 为方块高度。
 */
public final class BoardTransform {

    private final Location center;
    private final float boardYaw;
    private final double squareSize;
    private final double surfaceHeight;
    private final double seatDistance;
    private final Vector forward;
    private final Vector right;

    public BoardTransform(Location center, float boardYaw, double squareSize,
                          double surfaceHeight, double seatDistance) {
        this.center = center.clone();
        this.center.setYaw(boardYaw);
        this.center.setPitch(0);
        this.boardYaw = boardYaw;
        this.squareSize = squareSize;
        this.surfaceHeight = surfaceHeight;
        this.seatDistance = seatDistance;
        this.forward = facing(boardYaw);
        this.right = rightOf(forward);
    }

    /** Minecraft yaw 对应的水平朝向向量（yaw 0 = 南 +Z）。 */
    public static Vector facing(float yawDeg) {
        double radians = Math.toRadians(yawDeg);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** 面向 forward 时右手边的方向（水平面内顺时针 90°）。 */
    public static Vector rightOf(Vector forward) {
        return new Vector(-forward.getZ(), 0, forward.getX());
    }

    public float boardYaw() {
        return boardYaw;
    }

    public double squareSize() {
        return squareSize;
    }

    public Vector forward() {
        return forward.clone();
    }

    public Vector right() {
        return right.clone();
    }

    /** 棋盘平面上的点，dx/dz 单位是格。 */
    public Location boardPoint(double dx, double dz, double dy) {
        Vector offset = right.clone().multiply(dx * squareSize)
                .add(forward.clone().multiply(dz * squareSize));
        Location location = center.clone().add(offset.getX(), surfaceHeight + dy, offset.getZ());
        location.setYaw(boardYaw);
        location.setPitch(0);
        return location;
    }

    /** 地面上的点（相对中心），dx/dz 单位是方块。 */
    public Location floorPoint(double dx, double dz) {
        Location location = center.clone().add(
                right.getX() * dx + forward.getX() * dz, 0,
                right.getZ() * dx + forward.getZ() * dz);
        location.setY(center.getY());
        location.setYaw(boardYaw);
        location.setPitch(0);
        return location;
    }

    public Location squareCenter(Square square) {
        int file = square.getFile().ordinal();
        int rank = square.getRank().ordinal();
        return boardPoint(file - 3.5, rank - 3.5, 0);
    }

    /**
     * 视线与棋盘平面（surface 之上 planeHeight）求交，换算成格子。
     * 纯几何，便于单测；看不到棋盘或超出 maxReach 返回 null。
     */
    public Square squareAt(Location eye, Vector direction, double planeHeight, double maxReach) {
        if (eye == null || direction == null) return null;
        double dy = direction.getY();
        if (Math.abs(dy) < 1.0e-4) return null;
        Location base = boardPoint(0, 0, 0);
        double t = (base.getY() + planeHeight - eye.getY()) / dy;
        if (t <= 0 || t > maxReach) return null;
        Vector hit = eye.toVector().add(direction.clone().multiply(t));
        Vector relative = hit.subtract(base.toVector());
        int file = (int) Math.floor(relative.dot(right) / squareSize + 4.0);
        int rank = (int) Math.floor(relative.dot(forward) / squareSize + 4.0);
        if (file < 0 || file > 7 || rank < 0 || rank > 7) return null;
        return Square.squareAt(rank * 8 + file);
    }

    /** 玩家座位：white 坐在 rank1 一侧，面向棋盘。 */
    public Location seat(boolean white) {
        Location location = center.clone().add(
                forward.getX() * seatDistance * (white ? -1 : 1), 0,
                forward.getZ() * seatDistance * (white ? -1 : 1));
        location.setY(center.getY());
        location.setYaw(white ? boardYaw : boardYaw + 180f);
        location.setPitch(0);
        return location;
    }

    /** 棋钟文字位置：棋盘两侧边缘上方。 */
    public Location clockPoint(boolean white, double height) {
        return boardPoint(0, white ? -4.4 : 4.4, height);
    }

    /** 升变按钮位置：己方一侧棋盘外，第 index（0..3）个。 */
    public Location promotionPoint(boolean white, int index) {
        return boardPoint(-1.5 + index, white ? -4.6 : 4.6, 0.35);
    }
}
