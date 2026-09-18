package com.minechess.ai;

import com.minechess.MineChessPlugin;

/**
 * AI 工厂：内置 id 与外部实现二选一。
 * 配置 {@code ai.bot} 可以是内置 id（random），也可以是实现了 {@link ChessBot}
 * 且带无参构造器的完整类名，方便接入真正的 AI 而不改本插件代码。
 */
public final class Bots {

    private Bots() {
    }

    public static ChessBot create(MineChessPlugin plugin, String spec) {
        String value = spec == null || spec.isBlank() ? "random" : spec.trim();
        if (value.equalsIgnoreCase("random")) {
            return new RandomBot();
        }
        try {
            Class<?> type = Class.forName(value);
            Object instance = type.getDeclaredConstructor().newInstance();
            if (instance instanceof ChessBot bot) {
                return bot;
            }
            plugin.getLogger().warning("配置的 ai.bot 不是 ChessBot 实现：" + value + "，回退到随机 AI");
        } catch (Throwable t) {
            plugin.getLogger().warning("加载 ai.bot 失败：" + value + "（" + t + "），回退到随机 AI");
        }
        return new RandomBot();
    }
}
