package com.minechess.view;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.minechess.MineChessPlugin;
import com.minechess.chess.ChessRules;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 物品渲染：把资源包相关代码集中在这里，规则与展示逻辑不直接接触 DataComponent。 */
public final class ChessItems {

    private final MineChessPlugin plugin;

    public ChessItems(MineChessPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean models() {
        return plugin.cfg("visual.use-item-models", true);
    }

    public ItemStack piece(Piece piece) {
        return model("piece/" + ChessRules.modelKey(piece), ChessRules.pieceName(piece));
    }

    public ItemStack board() {
        return model("board", "<white>国际象棋棋盘");
    }

    public ItemStack overlay(String name, String label) {
        return model("overlay/" + name, label);
    }

    public ItemStack promotion(PieceType type, Side side) {
        Piece piece = Piece.make(side, type);
        return model("piece/" + ChessRules.modelKey(piece), ChessRules.pieceName(piece));
    }

    private ItemStack model(String modelKey, String name) {
        boolean models = models();
        ItemStack item = ItemStack.of(models ? Material.PAPER : fallbackMaterial(modelKey));
        if (models) {
            item.setData(DataComponentTypes.ITEM_MODEL, Key.key("minechess", modelKey));
        }
        item.editMeta(meta -> meta.displayName(MineChessPlugin.mm(name).decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    /** 资源包缺失时的原版兜底：至少区分黑白与兵种。 */
    private Material fallbackMaterial(String modelKey) {
        if (modelKey == null) return Material.PAPER;
        if (modelKey.equals("board")) return Material.BLACK_CONCRETE;
        if (modelKey.startsWith("overlay/")) return switch (modelKey.substring("overlay/".length())) {
            case "dot" -> Material.LIME_DYE;
            case "ring" -> Material.GREEN_DYE;
            case "square" -> Material.YELLOW_DYE;
            case "check" -> Material.RED_DYE;
            default -> Material.WHITE_DYE;
        };
        boolean white = modelKey.contains("white");
        String type = modelKey.substring(modelKey.indexOf('_') + 1);
        return switch (type) {
            case "pawn" -> white ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE;
            case "knight" -> white ? Material.WHITE_WOOL : Material.BLACK_WOOL;
            case "bishop" -> white ? Material.QUARTZ_BLOCK : Material.DEEPSLATE;
            case "rook" -> white ? Material.SMOOTH_STONE : Material.COBBLED_DEEPSLATE;
            case "queen" -> white ? Material.GOLD_BLOCK : Material.COAL_BLOCK;
            case "king" -> white ? Material.WHITE_GLAZED_TERRACOTTA : Material.BLACK_GLAZED_TERRACOTTA;
            default -> Material.PAPER;
        };
    }
}
