package com.minechess.chess;

import java.util.List;
import java.util.Locale;

/**
 * 时限：initial 分钟 + 每步 increment 秒。"casual" 表示不限时。
 */
public record TimeControl(String label, long initialMillis, long incrementMillis) {

    public static final TimeControl CASUAL = new TimeControl("casual", 0, 0);

    public static List<TimeControl> presets() {
        return List.of(CASUAL, parse("3+2"), parse("5+0"), parse("10+0"), parse("15+10"));
    }

    public boolean casual() {
        return initialMillis <= 0;
    }

    /** 解析 "10+0"、"3+2"、"casual"、"∞"。非法返回 null。 */
    public static TimeControl parse(String text) {
        if (text == null) return null;
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (value.equals("casual") || value.equals("∞") || value.equals("无限") || value.equals("none")) {
            return CASUAL;
        }
        String[] parts = value.split("\\+");
        try {
            long minutes = Long.parseLong(parts[0].trim());
            long increment = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0;
            if (minutes <= 0 || minutes > 180 || increment < 0 || increment > 180) return null;
            String label = minutes + "+" + increment;
            return new TimeControl(label, minutes * 60_000L, increment * 1000L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String describe() {
        if (casual()) return "不限时";
        return label + "（" + initialMillis / 60_000L + " 分钟"
                + (incrementMillis > 0 ? " + 每步 " + incrementMillis / 1000L + " 秒" : "") + "）";
    }

    public static String presetsText() {
        return "casual / 3+2 / 5+0 / 10+0 / 15+10";
    }
}
