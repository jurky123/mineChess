package com.minechess.arena;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/** 竞技场坐标集合：每场对局占用一个，互不重叠。保存中心点与白方朝向。 */
public class ArenaManager {

    public record Point(String world, double x, double y, double z, float yaw) {}

    private final JavaPlugin plugin;
    private final List<Point> points = new ArrayList<>();
    private final Set<Integer> used = new HashSet<>();

    public ArenaManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        points.clear();
        List<?> list = plugin.getConfig().getList("arenas");
        if (list != null) {
            for (Object entry : list) {
                if (entry instanceof ConfigurationSection section) {
                    points.add(new Point(section.getString("world", "hub"),
                            section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                            (float) section.getDouble("yaw", 0)));
                }
            }
        }
        if (points.isEmpty()) {
            points.add(new Point("hub", 0.5, 64.0, 0.5, 0f));
        }
    }

    public int count() {
        return points.size();
    }

    public String name(int slot) {
        Point point = points.get(slot);
        return point.world() + " " + (int) point.x() + "," + (int) point.y() + "," + (int) point.z();
    }

    /** 占用一个空闲竞技场，没有空闲返回 -1。 */
    public int allocate() {
        for (int i = 0; i < points.size(); i++) {
            if (!used.contains(i)) {
                used.add(i);
                return i;
            }
        }
        return -1;
    }

    public void release(int slot) {
        used.remove(slot);
    }

    public Location center(int slot) {
        Point point = points.get(Math.max(0, Math.min(points.size() - 1, slot)));
        World world = Bukkit.getWorld(point.world());
        if (world == null) world = Bukkit.getWorlds().get(0);
        Location location = new Location(world, point.x(), point.y(), point.z());
        location.setYaw(point.yaw());
        location.setPitch(0);
        return location;
    }

    public void save(int slot, Location location) {
        Point point = new Point(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw());
        while (points.size() <= slot) {
            points.add(point);
        }
        points.set(slot, point);
        saveAll();
    }

    private void saveAll() {
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (Point point : points) {
            list.add(java.util.Map.of("world", point.world(), "x", point.x(),
                    "y", point.y(), "z", point.z(), "yaw", (double) point.yaw()));
        }
        plugin.getConfig().set("arenas", list);
        plugin.saveConfig();
    }
}
