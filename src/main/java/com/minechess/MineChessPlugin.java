package com.minechess;

import com.minechess.arena.ArenaManager;
import com.minechess.ui.CompositeControlUi;
import com.minechess.ui.ControlUi;
import com.minechess.ui.ControlUiHook;
import com.minechess.ui.InventoryControlUi;
import com.minechess.ui.NoopControlUi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineChessPlugin extends JavaPlugin {

    public static final MiniMessage MM = MiniMessage.miniMessage();
    private static MineChessPlugin instance;

    private ArenaManager arena;
    private MatchManager matches;
    private ControlUi ui = new NoopControlUi();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        arena = new ArenaManager(this);
        matches = new MatchManager(this);
        ChessCommand command = new ChessCommand(this);
        getCommand("chess").setExecutor(command);
        getCommand("chess").setTabCompleter(command);
        Bukkit.getPluginManager().registerEvents(new ChessListener(this), this);
        matches.start();
        // MineUI（mod 客户端）优先，其余玩家用原版箱子面板
        ui = new CompositeControlUi(ControlUiHook.create(this), new InventoryControlUi(this));
        getLogger().info("MineChess 已启用，竞技场数量: " + arena.count() + "，首个: " + arena.name(0));
    }

    @Override
    public void onDisable() {
        if (matches != null) matches.shutdown();
    }

    public static MineChessPlugin instance() {
        return instance;
    }

    public ArenaManager arena() {
        return arena;
    }

    public MatchManager matches() {
        return matches;
    }

    public ControlUi ui() {
        return ui;
    }

    public void reloadAll() {
        reloadConfig();
        arena.load();
        matches.reloadSeat();
    }

    public int cfg(String path, int def) {
        return getConfig().getInt(path, def);
    }

    public double cfg(String path, double def) {
        return getConfig().getDouble(path, def);
    }

    public boolean cfg(String path, boolean def) {
        return getConfig().getBoolean(path, def);
    }

    public String playerName(java.util.UUID id) {
        if (id == null) return "?";
        if (matches != null) {
            String botName = matches.bots().name(id);
            if (botName != null) return botName;
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    public static Component mm(String text) {
        return MM.deserialize(text);
    }

    public static void msg(CommandSender to, String text) {
        to.sendMessage(mm(text));
    }

    public void log(String text) {
        getLogger().info(text);
    }
}
