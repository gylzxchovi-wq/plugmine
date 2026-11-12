package ru.solhaven.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public record NPCData(
        UUID id,
        int entityId,
        String name,
        String skinName,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public Location location() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }
}
