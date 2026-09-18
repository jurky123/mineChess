package com.minechess.view;

import com.github.bhlangonijr.chesslib.File;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.minechess.MineChessPlugin;
import com.minechess.arena.BoardTransform;
import com.minechess.chess.ChessResult;
import com.minechess.chess.MoveRecord;
import com.minechess.match.ChessMatch;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 3D 棋盘：棋盘、棋子、落点提示、交互体、棋钟、座位与动画。 */
public final class ChessTableView {

    public enum HitKind { SQUARE, PROMOTION }

    /** 注册到 MatchManager 的点击目标。 */
    public record Hit(ChessTableView table, HitKind kind, Square square, int promotionIndex) {}

    /** 棋盘模型厚度（模型单位，1 单位 = 1/16 方块）。 */
    private static final double BOARD_THICKNESS_UNITS = 1;

    private final MineChessPlugin plugin;
    private final ChessMatch match;
    private final BoardTransform transform;
    private final World world;
    private final ChessItems items;

    private final double squareSize;
    private final double pieceScale;
    private final double boardScale;
    private final double boardTop;
    private final double clockHeight;
    private final boolean preview;
    private final boolean animations, particles, sounds;
    private final float modelYawOffset;

    private final Map<Square, ItemDisplay> pieces = new EnumMap<>(Square.class);
    private final Map<Side, Location> stands = new EnumMap<>(Side.class);
    private final Map<Side, ArmorStand> seatEntities = new EnumMap<>(Side.class);
    private final List<Location> spectatorStands = new ArrayList<>();
    private final List<ArmorStand> spectatorSeats = new ArrayList<>();
    private final Map<String, ItemDisplay> markers = new HashMap<>();
    private final List<ItemDisplay> promotionDisplays = new ArrayList<>();
    private final List<Interaction> promotionHitEntities = new ArrayList<>();
    private final List<Entity> temp = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private final List<Square> selectionTargets = new ArrayList<>();
    private Square elevatedSquare;

    private ItemDisplay boardDisplay;
    private ItemDisplay selectedOverlay;
    private ItemDisplay checkOverlay;
    private ItemDisplay lastFromOverlay;
    private ItemDisplay lastToOverlay;
    private TextDisplay whiteClock;
    private TextDisplay blackClock;
    private TextDisplay resultText;
    private TextDisplay promotionLabel;
    private Side promotionSide;

    public ChessTableView(MineChessPlugin plugin, ChessMatch match, Location center) {
        this(plugin, match, center, false);
    }

    /**
     * @param preview 预览模式：不生成座位与交互体，只摆出棋盘棋子（管理员调参用）
     */
    public ChessTableView(MineChessPlugin plugin, ChessMatch match, Location center, boolean preview) {
        this.plugin = plugin;
        this.match = match;
        this.preview = preview;
        this.items = new ChessItems(plugin);
        this.squareSize = plugin.cfg("board.size", 0.70);
        // ItemDisplayTransform.NONE：模型 16 单位 = 1 方块，实体缩放即方块尺寸
        this.pieceScale = plugin.cfg("board.piece-scale", 0.7);
        this.boardScale = 8 * squareSize;
        this.boardTop = boardScale / 16.0 * BOARD_THICKNESS_UNITS;
        this.clockHeight = plugin.cfg("board.clock-height", 1.30);
        this.animations = plugin.cfg("visual.animations", true);
        this.particles = plugin.cfg("visual.particles", true);
        this.sounds = plugin.cfg("visual.sounds", true);
        this.modelYawOffset = (float) plugin.cfg("visual.model-yaw", 0.0);
        this.transform = new BoardTransform(center, center.getYaw(), squareSize,
                plugin.cfg("board.height", 0.70), plugin.cfg("board.seat-distance", 3.4));
        this.world = center.getWorld();
    }

    public ChessMatch match() {
        return match;
    }

    public BoardTransform transform() {
        return transform;
    }

    /** 白框高亮选中/移动提示由 2D 棋盘的选择状态驱动（见 MatchManager.select）。 */

    // ---------- 构建 ----------

    public void build() {
        boardDisplay = display(items.board(), transform.boardPoint(0, 0, 0),
                solid(boardScale, modelYaw()), 2.0f);
        plugin.log("[view] 棋盘生成于 " + transform.boardPoint(0, 0, 0).getWorld().getName()
                + " " + String.format("%.2f", transform.boardPoint(0, 0, 0).getX())
                + "," + String.format("%.2f", transform.boardPoint(0, 0, 0).getY())
                + "," + String.format("%.2f", transform.boardPoint(0, 0, 0).getZ())
                + " 朝向 " + (int) transform.boardYaw() + "°");

        selectedOverlay = display(items.overlay("square", "<gold>选中"), plateLocation(Square.A1),
                flat(squareSize, modelYaw()), 1.2f);
        checkOverlay = display(items.overlay("check", "<red>将军"), plateLocation(Square.A1),
                flat(squareSize, modelYaw()), 1.2f);
        lastFromOverlay = display(items.overlay("square", "<yellow>上一手"), plateLocation(Square.A1),
                flat(squareSize, modelYaw()), 1.2f);
        lastToOverlay = display(items.overlay("square", "<yellow>上一手"), plateLocation(Square.A1),
                flat(squareSize, modelYaw()), 1.2f);
        hide(selectedOverlay);
        hide(checkOverlay);
        hide(lastFromOverlay);
        hide(lastToOverlay);

        if (!preview) buildSeats();
        syncPieces();

        whiteClock = text(transform.clockPoint(true, clockHeight), "<white>—", 0.9f);
        blackClock = text(transform.clockPoint(false, clockHeight), "<white>—", 0.9f);
        updateClocks();
    }

    private void buildSeats() {
        for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
            Location stand = transform.seat(side == Side.WHITE);
            // 入场时朝向棋盘中心（之后可自由转头）
            Location from = stand.clone().add(0, plugin.cfg("seat.y-offset", 5.0) + 1.6, 0);
            from.setDirection(transform.boardPoint(0, 0, boardTop * 0.5).toVector()
                    .subtract(from.toVector()));
            stand.setYaw(from.getYaw());
            stand.setPitch(from.getPitch());
            stands.put(side, stand);
            seatEntities.put(side, spawnSeat(stand));
        }
        buildSpectatorSeats();
    }

    /** 4 个观战席：棋盘左右两侧各 2 个，面向棋盘中心。 */
    private void buildSpectatorSeats() {
        double distance = plugin.cfg("board.spectator-distance", 4.0);
        double spread = plugin.cfg("board.spectator-spread", 1.6);
        double[][] offsets = {
                {distance, -spread}, {distance, spread},
                {-distance, -spread}, {-distance, spread},
        };
        for (double[] offset : offsets) {
            Location stand = transform.floorPoint(offset[0], offset[1]);
            Location from = stand.clone().add(0, plugin.cfg("seat.y-offset", 5.0) + 1.6, 0);
            from.setDirection(transform.boardPoint(0, 0, boardTop * 0.5).toVector()
                    .subtract(from.toVector()));
            stand.setYaw(from.getYaw());
            stand.setPitch(from.getPitch());
            spectatorStands.add(stand);
            spectatorSeats.add(spawnSeat(stand));
        }
    }

    /** 棋盘面（棋盘顶面）上的贴图位置。 */
    private Location plateLocation(Square square) {
        return transform.squareCenter(square).add(0, boardTop + 0.02, 0);
    }

    /** 棋子底部落在棋盘顶面上。 */
    private Location pieceLocation(Square square) {
        return transform.squareCenter(square).add(0, boardTop, 0);
    }

    private float modelYaw() {
        return transform.boardYaw() + modelYawOffset;
    }

    /** 棋子模型正面朝 -Z；白方棋子朝黑方，故白方加 180°。 */
    private float pieceYaw(Side side) {
        return modelYaw() + (side == Side.WHITE ? 180f : 0f);
    }

    // ---------- 实体工具 ----------

    private <T extends Entity> T spawn(Location loc, Class<T> type, Consumer<T> init) {
        return world.spawn(loc, type, e -> {
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            init.accept(e);
        });
    }

    /**
     * 平铺贴图（overlay）：物品模型 16 单位 = 1 方块，NONE 变换会先把模型中心移到原点，
     * 这里再绕 X 放平。blocks 为贴图世界尺寸。
     */
    private Transformation flat(double blocks, float yaw) {
        float s = (float) blocks;
        Quaternionf rot = new Quaternionf()
                .rotateX((float) -Math.PI / 2)
                .rotateZ((float) -Math.toRadians(yaw));
        return new Transformation(new Vector3f(), rot, new Vector3f(s, s, s), new Quaternionf());
    }

    /**
     * 3D 模型（棋盘/棋子）：模型坐标 0..16、y=0 为底。NONE 变换会先 translate(-0.5)，
     * 因此把实体上移 scale/2，模型的底就正好落在实体位置上。
     */
    private Transformation solid(double scale, float yaw) {
        return solid(scale, yaw, 0);
    }

    /** extraLift：选中时把棋子抬起的高度（方块）。 */
    private Transformation solid(double scale, float yaw, double extraLift) {
        float s = (float) scale;
        Quaternionf rot = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
        return new Transformation(new Vector3f(0f, (float) (s * 0.5 + extraLift), 0f), rot,
                new Vector3f(s, s, s), new Quaternionf());
    }

    private double pieceLift(Square square) {
        return square != null && square.equals(elevatedSquare)
                ? plugin.cfg("board.select-lift", 0.18)
                : 0;
    }

    /** 抬起/放下某个棋子（选中提示）。 */
    private void applyPieceLift(Square square) {
        if (square == null) return;
        ItemDisplay display = pieces.get(square);
        Piece piece = match.pieceAt(square);
        if (display == null || !display.isValid() || piece == Piece.NONE) return;
        display.setInterpolationDuration(3);
        display.setTransformation(solid(pieceScale, pieceYaw(piece.getPieceSide()), pieceLift(square)));
    }

    private ItemDisplay display(ItemStack item, Location at, Transformation transformation, float viewRange) {
        return spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(transformation);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setViewRange(viewRange);
        });
    }

    private TextDisplay text(Location at, String mini, float scale) {
        return spawn(at, TextDisplay.class, t -> {
            t.text(MineChessPlugin.mm(mini));
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(true);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0x55000000));
            t.setShadowed(true);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            t.setViewRange(1.6f);
        });
    }

    private Interaction hitbox(Location at, float width, float height) {
        return spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(width);
            i.setInteractionHeight(height);
            i.setResponsive(true);
        });
    }

    private ArmorStand spawnSeat(Location stand) {
        Location at = stand.clone().add(0, plugin.cfg("seat.y-offset", 2.4), 0);
        return spawn(at, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setMarker(true);
            a.setBasePlate(false);
            a.setRotation(stand.getYaw(), 0);
        });
    }

    private void schedule(int delay, Runnable run) {
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            tasks.remove(holder[0]);
            run.run();
        }, delay);
        tasks.add(holder[0]);
    }

    private void sound(Sound sound, Location at, float volume, float pitch) {
        if (sounds) world.playSound(at, sound, volume, pitch);
    }

    private void dust(Location at, Color color, int count, double spread) {
        if (!particles) return;
        world.spawnParticle(Particle.DUST, at, count, spread, 0.15, spread, 0,
                new Particle.DustOptions(color, 1.1f));
    }

    // ---------- 棋子 ----------

    private void syncPieces() {
        for (Square square : Square.values()) {
            if (square == Square.NONE) continue;
            Piece piece = match.pieceAt(square);
            ItemDisplay existing = pieces.get(square);
            if (piece == Piece.NONE) {
                if (existing != null && existing.isValid()) existing.remove();
                pieces.remove(square);
                continue;
            }
            if (existing == null || !existing.isValid()) {
                ItemDisplay created = spawn(pieceLocation(square), ItemDisplay.class, d -> {
                    d.setItemStack(items.piece(piece));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setTransformation(solid(pieceScale, pieceYaw(piece.getPieceSide()), pieceLift(square)));
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setShadowRadius(0f);
                    d.setShadowStrength(0f);
                    d.setViewRange(1.8f);
                });
                pieces.put(square, created);
            } else {
                existing.setItemStack(items.piece(piece));
                existing.teleport(pieceLocation(square));
            }
        }
    }

    /** 执行一步棋的展示：移动、吃子缩小、易位、升级换模型、上一手、将军。 */
    public void applyMove(MoveRecord record) {
        clearSelection();

        if (record.isCapture() && record.capturedSquare() != null) {
            ItemDisplay victim = pieces.remove(record.capturedSquare());
            if (victim != null && victim.isValid()) {
                shrink(victim, 6);
                sound(Sound.ENTITY_ITEM_BREAK, victim.getLocation(), 0.5f, 1.2f);
            }
        }

        ItemDisplay mover = pieces.remove(record.from());
        if (mover == null || !mover.isValid()) {
            syncPieces();
        } else {
            pieces.put(record.to(), mover);
            Runnable after = record.isPromotion()
                    ? () -> {
                        if (mover.isValid()) mover.setItemStack(items.piece(record.promotion()));
                    }
                    : null;
            glide(mover, pieceLocation(record.from()), pieceLocation(record.to()),
                    record.piece().getPieceType() == PieceType.KNIGHT, after);
        }

        if (record.castle()) {
            Square rookFrom = rookSquare(record, true);
            Square rookTo = rookSquare(record, false);
            ItemDisplay rook = pieces.remove(rookFrom);
            if (rook != null && rook.isValid()) {
                pieces.put(rookTo, rook);
                glide(rook, pieceLocation(rookFrom), pieceLocation(rookTo), false, null);
            }
        }

        showLastMove(record.from(), record.to());
        sound(record.isCapture() ? Sound.ENTITY_PLAYER_ATTACK_STRONG : Sound.BLOCK_WOOD_PLACE,
                transform.boardPoint(0, 0, 0.4), 0.6f, record.isCapture() ? 0.8f : 1.4f);

        if (record.checkmate()) {
            showCheck(match.turn());
        } else if (record.check()) {
            showCheck(match.turn());
            sound(Sound.BLOCK_NOTE_BLOCK_BELL, transform.boardPoint(0, 0, 0.5), 0.9f, 1.3f);
        } else {
            clearCheck();
        }
    }

    private Square rookSquare(MoveRecord record, boolean from) {
        boolean kingSide = record.to().getFile().ordinal() > record.from().getFile().ordinal();
        File file;
        if (from) {
            file = kingSide ? File.FILE_H : File.FILE_A;
        } else {
            file = kingSide ? File.FILE_F : File.FILE_D;
        }
        return Square.encode(record.from().getRank(), file);
    }

    /** 落子动画：起手抬起 → 弧线移动 → 落点放下（马抬得更高）。 */
    private void glide(ItemDisplay display, Location from, Location to, boolean knight, Runnable after) {
        if (!animations || display.getWorld() != to.getWorld()) {
            display.teleport(to);
            if (after != null) after.run();
            return;
        }
        Location mid = from.clone().add(to).multiply(0.5).add(0, knight ? 0.65 : 0.35, 0);
        display.teleport(from);
        display.setTeleportDuration(2);
        schedule(1, () -> display.teleport(mid));
        schedule(3, () -> {
            display.setTeleportDuration(3);
            display.teleport(to);
        });
        schedule(9, () -> {
            if (particles) {
                world.spawnParticle(Particle.DUST, to.clone().add(0, 0.05, 0), 6, 0.2, 0.02, 0.2, 0,
                        new Particle.DustOptions(Color.fromRGB(0xE8DCC0), 0.8f));
            }
            if (after != null) after.run();
        });
    }

    private void shrink(Entity entity, int ticks) {
        if (!(entity instanceof ItemDisplay display) || !display.isValid()) return;
        display.setInterpolationDuration(ticks);
        display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf()));
        schedule(ticks + 2, () -> {
            if (display.isValid()) display.remove();
        });
    }

    private void showLastMove(Square from, Square to) {
        lastFromOverlay.teleport(plateLocation(from));
        lastToOverlay.teleport(plateLocation(to));
        show(lastFromOverlay, squareSize);
        show(lastToOverlay, squareSize);
    }

    // ---------- 选择与提示 ----------

    public void showSelection(Square selected, List<Square> legal, Set<Square> captures) {
        clearSelection();
        elevatedSquare = selected;
        applyPieceLift(selected);
        selectionTargets.clear();
        selectionTargets.addAll(legal);
        if (selected != null) {
            selectedOverlay.teleport(plateLocation(selected));
            show(selectedOverlay, squareSize);
        }
        for (Square square : legal) {
            boolean capture = captures.contains(square);
            ItemDisplay marker = marker(square, capture ? "ring" : "dot");
            marker.teleport(plateLocation(square).add(0, 0.015, 0));
            show(marker, squareSize * 0.85);
        }
    }

    public void clearSelection() {
        hide(selectedOverlay);
        for (ItemDisplay marker : markers.values()) hide(marker);
        selectionTargets.clear();
        if (elevatedSquare != null) {
            Square previous = elevatedSquare;
            elevatedSquare = null;
            applyPieceLift(previous);
        }
    }

    /** 合法落点的粒子提示（管理端每 10 tick 调一次）。 */
    public void selectionParticles() {
        if (!particles || selectionTargets.isEmpty()) return;
        for (Square square : selectionTargets) {
            Location at = plateLocation(square).add(0, 0.08, 0);
            world.spawnParticle(Particle.DUST, at, 2, 0.14, 0.05, 0.14, 0,
                    new Particle.DustOptions(Color.fromRGB(0x55FF55), 0.9f));
        }
        if (elevatedSquare != null) {
            Location at = pieceLocation(elevatedSquare).add(0, 0.12, 0);
            world.spawnParticle(Particle.DUST, at, 2, 0.16, 0.08, 0.16, 0,
                    new Particle.DustOptions(Color.fromRGB(0xFFD54F), 0.9f));
        }
    }

    private ItemDisplay marker(Square square, String type) {
        String key = square.name() + ":" + type;
        ItemDisplay marker = markers.get(key);
        if (marker == null || !marker.isValid()) {
            String label = type.equals("ring") ? "<green>可吃子" : "<green>可移动";
            marker = display(items.overlay(type, label), plateLocation(Square.A1),
                    flat(squareSize * 0.85, modelYaw()), 1.2f);
            markers.put(key, marker);
        }
        return marker;
    }

    private void show(ItemDisplay display, double blocks) {
        if (display == null || !display.isValid()) return;
        display.setInterpolationDuration(2);
        display.setTransformation(flat(blocks, modelYaw()));
    }

    private void hide(ItemDisplay display) {
        if (display == null || !display.isValid()) return;
        display.setInterpolationDuration(0);
        display.setTransformation(flat(0.01, modelYaw()));
    }

    public void showCheck(Side side) {
        Square king = match.board().getKingSquare(side);
        if (king == null || king == Square.NONE) return;
        checkOverlay.teleport(plateLocation(king));
        show(checkOverlay, squareSize);
    }

    public void clearCheck() {
        hide(checkOverlay);
    }

    // ---------- 升变 ----------

    public void showPromotion(Side side) {
        hidePromotion();
        promotionSide = side;
        PieceType[] types = {PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT};
        boolean white = side == Side.WHITE;
        for (int i = 0; i < types.length; i++) {
            Location at = transform.promotionPoint(white, i);
            ItemDisplay display = display(items.promotion(types[i], side), at,
                    solid(pieceScale, pieceYaw(side)), 1.8f);
            promotionDisplays.add(display);
            Interaction hit = hitbox(at.clone().add(0, 0.25, 0), 0.7f, 0.7f);
            promotionHitEntities.add(hit);
            plugin.matches().registerHit(hit.getUniqueId(), new Hit(this, HitKind.PROMOTION, null, i));
        }
        promotionLabel = text(transform.promotionPoint(white, 0).add(
                transform.right().multiply(squareSize * 1.5)).add(0, 0.85, 0),
                "<gold>选择升变 <gray>（超时自动升后）", 0.7f);
        sound(Sound.UI_BUTTON_CLICK, transform.promotionPoint(white, 1), 0.7f, 1.4f);
    }

    public void hidePromotion() {
        promotionSide = null;
        for (ItemDisplay display : promotionDisplays) {
            if (display.isValid()) display.remove();
        }
        promotionDisplays.clear();
        for (Interaction hit : promotionHitEntities) {
            plugin.matches().unregisterHit(hit.getUniqueId());
            if (hit.isValid()) hit.remove();
        }
        promotionHitEntities.clear();
        if (promotionLabel != null && promotionLabel.isValid()) promotionLabel.remove();
        promotionLabel = null;
    }

    public boolean promotionVisible() {
        return promotionSide != null;
    }

    public Side promotionSide() {
        return promotionSide;
    }

    // ---------- 棋钟 / 结果 ----------

    public void updateClocks() {
        String whiteName = plugin.playerName(match.whitePlayer());
        String blackName = plugin.playerName(match.blackPlayer());
        Side turn = match.turn();
        if (whiteClock != null && whiteClock.isValid()) {
            whiteClock.text(MineChessPlugin.mm("<white>" + whiteName + "\n"
                    + (turn == Side.WHITE ? "<yellow>▶ " : "<gray>")
                    + match.clock(Side.WHITE).format()));
        }
        if (blackClock != null && blackClock.isValid()) {
            blackClock.text(MineChessPlugin.mm("<white>" + blackName + "\n"
                    + (turn == Side.BLACK ? "<yellow>▶ " : "<gray>")
                    + match.clock(Side.BLACK).format()));
        }
    }

    public void showResult(ChessResult result) {
        hide(checkOverlay);
        String title = result.isDraw()
                ? "<yellow><bold>和棋"
                : "<gold><bold>" + (result.winner() == Side.WHITE ? "白方" : "黑方") + "获胜";
        resultText = text(transform.boardPoint(0, 0, 1.4),
                title + "\n<white>" + result.termination().label(), 1.4f);
        Location at = transform.boardPoint(0, 0, 0.6);
        sound(Sound.UI_TOAST_CHALLENGE_COMPLETE, at, 1f, 1f);
        if (particles) {
            world.spawnParticle(Particle.FIREWORK, at, 40, 1.0, 0.6, 1.0, 0.1);
        }
    }

    // ---------- 座位 ----------

    public Location stand(Side side) {
        Location location = stands.get(side);
        return location == null ? null : location.clone();
    }

    public void mount(UUID player) {
        Side side = match.sideOf(player);
        if (side == null) return;
        ArmorStand seat = seatEntities.get(side);
        Player p = Bukkit.getPlayer(player);
        if (seat == null || !seat.isValid() || p == null) return;
        if (!seat.getPassengers().contains(p)) seat.addPassenger(p);
    }

    /** 玩家偏离座位就拉回来（保留视角朝向）。 */
    public void keepSeat(UUID player) {
        Side side = match.sideOf(player);
        if (side == null) return;
        keepOn(seatEntities.get(side), stands.get(side), player);
    }

    // ---------- 观战席 ----------

    public int spectatorSeatCount() {
        return spectatorSeats.size();
    }

    public Location spectatorStand(int index) {
        if (index < 0 || index >= spectatorStands.size()) return null;
        return spectatorStands.get(index).clone();
    }

    public void mountSpectator(UUID player, int index) {
        if (index < 0 || index >= spectatorSeats.size()) return;
        ArmorStand seat = spectatorSeats.get(index);
        Player p = Bukkit.getPlayer(player);
        if (seat == null || !seat.isValid() || p == null) return;
        if (!seat.getPassengers().contains(p)) seat.addPassenger(p);
    }

    public void keepSpectatorSeat(UUID player, int index) {
        if (index < 0 || index >= spectatorSeats.size()) return;
        keepOn(spectatorSeats.get(index), spectatorStands.get(index), player);
    }

    /** 把玩家固定到座位（偏离则拉回，且保持骑乘）。 */
    private void keepOn(ArmorStand seat, Location stand, UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (seat == null || stand == null || !seat.isValid() || p == null) return;
        double radius = plugin.cfg("game.seat-lock-radius", 0.6);
        Location home = stand.clone().add(0, plugin.cfg("seat.y-offset", 2.4), 0);
        home.setYaw(stand.getYaw());
        home.setPitch(0);
        if (seat.getWorld() != home.getWorld() || seat.getLocation().distanceSquared(home) > radius * radius) {
            seat.teleport(home);
        }
        if (!seat.getPassengers().contains(p)) seat.addPassenger(p);
        // 注意：不在这里强制拉回视角。周期性的视角纠正会让准星反复跳到棋盘中心，
        // 玩家也可以自由转头看向对手；入场时的朝向由座位位置本身决定。
    }

    public void reloadSeat() {
        for (Map.Entry<Side, ArmorStand> entry : seatEntities.entrySet()) {
            ArmorStand seat = entry.getValue();
            Location stand = stands.get(entry.getKey());
            if (seat == null || stand == null || !seat.isValid()) continue;
            Location home = stand.clone().add(0, plugin.cfg("seat.y-offset", 2.4), 0);
            home.setYaw(stand.getYaw());
            home.setPitch(0);
            seat.teleport(home);
        }
        for (int i = 0; i < spectatorSeats.size(); i++) {
            ArmorStand seat = spectatorSeats.get(i);
            Location stand = spectatorStands.get(i);
            if (seat == null || stand == null || !seat.isValid()) continue;
            Location home = stand.clone().add(0, plugin.cfg("seat.y-offset", 2.4), 0);
            home.setYaw(stand.getYaw());
            home.setPitch(0);
            seat.teleport(home);
        }
    }

    // ---------- 清理 ----------

    public void remove() {
        for (BukkitTask task : tasks) task.cancel();
        tasks.clear();
        for (Entity entity : temp) if (entity.isValid()) entity.remove();
        temp.clear();

        if (resultText != null && resultText.isValid()) resultText.remove();
        if (whiteClock != null && whiteClock.isValid()) whiteClock.remove();
        if (blackClock != null && blackClock.isValid()) blackClock.remove();
        hidePromotion();

        for (ItemDisplay display : pieces.values()) if (display.isValid()) display.remove();
        pieces.clear();
        for (ItemDisplay marker : markers.values()) if (marker.isValid()) marker.remove();
        markers.clear();

        for (Entity entity : new Entity[]{boardDisplay, selectedOverlay, checkOverlay,
                lastFromOverlay, lastToOverlay}) {
            if (entity != null && entity.isValid()) entity.remove();
        }
        for (ArmorStand seat : seatEntities.values()) if (seat.isValid()) seat.remove();
        seatEntities.clear();
        for (ArmorStand seat : spectatorSeats) if (seat.isValid()) seat.remove();
        spectatorSeats.clear();
        spectatorStands.clear();
        plugin.matches().unregisterHits(this);
    }
}
