package com.hcs.minions.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 跨世界不可变的方块坐标（record 自带 equals/hashCode）。
 * 用于持久化与 ConcurrentHashMap 键（byLocation 索引）。
 */
public record BlockLocation(String world, int x, int y, int z) {

    public static BlockLocation of(Block block) {
        return new BlockLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockLocation of(Location loc) {
        return new BlockLocation(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public Location toLocation() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x, y, z);
    }

    public Block toBlock() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    public boolean sameChunk(BlockLocation other) {
        return world.equals(other.world)
                && (x >> 4) == (other.x >> 4)
                && (z >> 4) == (other.z >> 4);
    }
}
