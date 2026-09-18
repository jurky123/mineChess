package com.minechess.ui;

import com.minechess.MineChessPlugin;
import org.bukkit.Bukkit;

/**
 * 通过反射加载 MineUI 集成，避免安装旧版/未安装 MineUI 时抛 NoClassDefFoundError。
 * 与 MineSkin 的集成模式保持一致。
 */
public final class ControlUiHook {

    private ControlUiHook() {
    }

    public static ControlUi create(MineChessPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("MineUI") == null) {
            return new NoopControlUi();
        }
        try {
            Class<?> type = Class.forName("com.minechess.integration.MineUiControl");
            ControlUi ui = (ControlUi) type.getConstructor(MineChessPlugin.class).newInstance(plugin);
            return ui.available() ? ui : new NoopControlUi();
        } catch (Throwable t) {
            plugin.getLogger().warning("MineUI API 不匹配（可能是旧版 MineUI），面板功能禁用：" + t);
            return new NoopControlUi();
        }
    }
}
