package ru.solhaven.npc;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NPCManager implements Listener {
    private final JavaPlugin plugin;
    private final ProtocolManager protocol;
    private final Map<UUID, NPCData> npcs = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> visibleTo = new HashMap<>();
    private final Set<String> pendingSpawns = ConcurrentHashMap.newKeySet();
    private PacketAdapter packetListener;
    private int nextEntityId = 1_500_000;

    public NPCManager(JavaPlugin plugin, ProtocolManager protocol) {
        this.plugin = plugin;
        this.protocol = protocol;
    }

    public int size() {
        return npcs.size();
    }

    public void load() {
        var section = plugin.getConfig().getConfigurationSection("npcs");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                String path = "npcs." + key + ".";
                NPCData data = new NPCData(
                        UUID.fromString(key),
                        plugin.getConfig().getInt(path + "entity-id"),
                        plugin.getConfig().getString(path + "name", "Solhaven"),
                        plugin.getConfig().getString(path + "skin", plugin.getConfig().getString("skin-name", "Notch")),
                        plugin.getConfig().getString(path + "world", ""),
                        plugin.getConfig().getDouble(path + "x"),
                        plugin.getConfig().getDouble(path + "y"),
                        plugin.getConfig().getDouble(path + "z"),
                        (float) plugin.getConfig().getDouble(path + "yaw"),
                        (float) plugin.getConfig().getDouble(path + "pitch")
                );
                if (data.location() != null) {
                    npcs.put(data.id(), data);
                    nextEntityId = Math.max(nextEntityId, data.entityId() + 1);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not load NPC " + key + ": " + ex.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) syncViewer(player);
        });
    }

    public void save() {
        plugin.getConfig().set("npcs", null);
        for (NPCData npc : npcs.values()) {
            String path = "npcs." + npc.id() + ".";
            plugin.getConfig().set(path + "entity-id", npc.entityId());
            plugin.getConfig().set(path + "name", npc.name());
            plugin.getConfig().set(path + "skin", npc.skinName());
            plugin.getConfig().set(path + "world", npc.worldName());
            plugin.getConfig().set(path + "x", npc.x());
            plugin.getConfig().set(path + "y", npc.y());
            plugin.getConfig().set(path + "z", npc.z());
            plugin.getConfig().set(path + "yaw", npc.yaw());
            plugin.getConfig().set(path + "pitch", npc.pitch());
        }
        plugin.saveConfig();
    }

    public void create(Location location, Player creator) {
        String skinName = plugin.getConfig().getString("skin-name", "Notch");
        String npcName = plugin.getConfig().getString("npc-name", "Solhaven");
        if (npcName == null || npcName.isBlank()) npcName = "Solhaven";
        if (npcName.length() > 16) npcName = npcName.substring(0, 16);

        final String finalNpcName = npcName;
        final Location savedLocation = location.clone();
        creator.sendMessage("§eЗагружаю скин " + skinName + "...");

        Bukkit.createProfile(skinName).update().whenComplete((profile, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || profile == null || profile.getProperties().stream().noneMatch(p -> p.getName().equals("textures"))) {
                        creator.sendMessage("§cНе удалось получить скин игрока " + skinName + ". Проверь ник и интернет сервера.");
                        if (error != null) plugin.getLogger().warning("Skin lookup failed: " + error.getMessage());
                        return;
                    }
                    UUID id = UUID.randomUUID();
                    int entityId = nextEntityId++;
                    NPCData data = new NPCData(id, entityId, finalNpcName, skinName,
                            savedLocation.getWorld().getName(), savedLocation.getX(), savedLocation.getY(),
                            savedLocation.getZ(), savedLocation.getYaw(), savedLocation.getPitch());
                    npcs.put(id, data);
                    save();
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        if (isInView(viewer, data)) {
                            spawnFor(viewer, data, profile);
                        }
                    }
                    creator.sendMessage("§aNPC создан. Скин: §f" + skinName);
                })
        );
    }

    public void delete(UUID id) {
        NPCData data = npcs.remove(id);
        if (data == null) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) destroyFor(viewer, data);
        visibleTo.remove(id);
        pendingSpawns.removeIf(key -> key.startsWith(id + ":"));
        save();
    }

    public void tick() {
        for (NPCData npc : npcs.values()) {
            Player nearest = null;
            double nearestDistance = 50.0 * 50.0;
            Location npcLoc = npc.location();
            if (npcLoc == null) continue;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.getWorld().equals(npcLoc.getWorld())) continue;
                double distance = player.getLocation().distanceSquared(npcLoc);
                if (distance <= nearestDistance) {
                    nearestDistance = distance;
                    nearest = player;
                }
            }

            if (nearest != null) {
                float[] rotation = lookAt(npcLoc, nearest.getEyeLocation());
                sendRotation(npc, rotation[0], rotation[1]);
            }

            for (Player viewer : Bukkit.getOnlinePlayers()) {
                boolean shouldSee = isInView(viewer, npc);
                boolean currentlySees = visibleTo.getOrDefault(npc.id(), Collections.emptySet()).contains(viewer.getUniqueId());
                if (shouldSee && !currentlySees) {
                    // Re-fetching profile is avoided: saved skin data is cached in profile cache by Paper.
                    loadProfileAndSpawn(viewer, npc);
                } else if (!shouldSee && currentlySees) {
                    destroyFor(viewer, npc);
                }
            }
        }
    }

    private void loadProfileAndSpawn(Player viewer, NPCData npc) {
        String pendingKey = npc.id() + ":" + viewer.getUniqueId();
        if (!pendingSpawns.add(pendingKey)) return;
        Bukkit.createProfile(npc.skinName()).update().whenComplete((profile, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingSpawns.remove(pendingKey);
                    if (error == null && profile != null && npcs.containsKey(npc.id())
                            && viewer.isOnline() && isInView(viewer, npc)
                            && !visibleTo.getOrDefault(npc.id(), Collections.emptySet()).contains(viewer.getUniqueId())) {
                        spawnFor(viewer, npc, profile);
                    }
                }));
    }

    private boolean isInView(Player player, NPCData npc) {
        Location loc = npc.location();
        if (loc == null || !player.getWorld().equals(loc.getWorld())) return false;
        double max = plugin.getConfig().getDouble("view-distance", 64.0);
        return player.getLocation().distanceSquared(loc) <= max * max;
    }

    private void spawnFor(Player viewer, NPCData npc, PlayerProfile skinProfile) {
        try {
            WrappedGameProfile gameProfile = new WrappedGameProfile(npc.id(), npc.name());
            for (ProfileProperty property : skinProfile.getProperties()) {
                if (property.getName().equals("textures")) {
                    gameProfile.getProperties().put("textures",
                            new WrappedSignedProperty("textures", property.getValue(), property.getSignature()));
                }
            }

            PacketContainer info = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO);
            info.getPlayerInfoActions().write(0, EnumSet.of(
                    EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                    EnumWrappers.PlayerInfoAction.UPDATE_LISTED
            ));
            PlayerInfoData infoData = new PlayerInfoData(
                    npc.id(), 0, true, EnumWrappers.NativeGameMode.SURVIVAL,
                    gameProfile, WrappedChatComponent.fromText(npc.name())
            );
            info.getPlayerInfoDataLists().write(1, Collections.singletonList(infoData));
            protocol.sendServerPacket(viewer, info);

            Location loc = npc.location();
            PacketContainer spawn = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            spawn.getModifier().writeDefaults();
            spawn.getIntegers().write(0, npc.entityId());
            spawn.getUUIDs().write(0, npc.id());
            spawn.getEntityTypeModifier().write(0, EntityType.PLAYER);
            spawn.getDoubles().write(0, loc.getX()).write(1, loc.getY()).write(2, loc.getZ());
            spawn.getBytes().write(0, angle(loc.getPitch()))
                    .write(1, angle(loc.getYaw()))
                    .write(2, angle(loc.getYaw()));
            spawn.getIntegers().write(1, 0);
            protocol.sendServerPacket(viewer, spawn);

            // Remove from the tab list after the client has received the player profile and spawn.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!viewer.isOnline()) return;
                try {
                    PacketContainer removeInfo = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
                    removeInfo.getUUIDLists().write(0, Collections.singletonList(npc.id()));
                    protocol.sendServerPacket(viewer, removeInfo);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Could not remove NPC from tab list: " + ex.getMessage());
                }
            }, 40L);

            visibleTo.computeIfAbsent(npc.id(), ignored -> new HashSet<>()).add(viewer.getUniqueId());
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not spawn NPC for " + viewer.getName() + ": " + ex.getMessage());
        }
    }

    private void destroyFor(Player viewer, NPCData npc) {
        try {
            PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().write(0, Collections.singletonList(npc.entityId()));
            protocol.sendServerPacket(viewer, destroy);
            PacketContainer removeInfo = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            removeInfo.getUUIDLists().write(0, Collections.singletonList(npc.id()));
            protocol.sendServerPacket(viewer, removeInfo);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not despawn NPC: " + ex.getMessage());
        }
        Set<UUID> viewers = visibleTo.get(npc.id());
        if (viewers != null) viewers.remove(viewer.getUniqueId());
    }

    private void sendRotation(NPCData npc, float yaw, float pitch) {
        byte yawByte = angle(yaw);
        byte pitchByte = angle(pitch);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!visibleTo.getOrDefault(npc.id(), Collections.emptySet()).contains(viewer.getUniqueId())) continue;
            try {
                PacketContainer look = protocol.createPacket(PacketType.Play.Server.ENTITY_LOOK);
                look.getIntegers().write(0, npc.entityId());
                look.getBytes().write(0, yawByte).write(1, pitchByte);
                look.getBooleans().write(0, true);
                protocol.sendServerPacket(viewer, look);

                PacketContainer head = protocol.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
                head.getIntegers().write(0, npc.entityId());
                head.getBytes().write(0, yawByte);
                protocol.sendServerPacket(viewer, head);
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not rotate NPC: " + ex.getMessage());
            }
        }
    }

    private static byte angle(float degrees) {
        return (byte) (degrees * 256.0F / 360.0F);
    }

    private static float[] lookAt(Location from, Location target) {
        double dx = target.getX() - from.getX();
        double dy = target.getY() - (from.getY() + 1.6);
        double dz = target.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    public NPCData findTarget(Player player) {
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection().normalize();
        double maxDistance = plugin.getConfig().getDouble("delete-target-distance", 6.0);
        NPCData best = null;
        double bestDistance = maxDistance + 0.001;

        for (NPCData npc : npcs.values()) {
            Location loc = npc.location();
            if (loc == null || !loc.getWorld().equals(player.getWorld())) continue;
            double hit = rayBox(origin, direction,
                    loc.getX() - 0.35, loc.getY(), loc.getZ() - 0.35,
                    loc.getX() + 0.35, loc.getY() + 1.8, loc.getZ() + 0.35,
                    maxDistance);
            if (hit >= 0 && hit < bestDistance) {
                bestDistance = hit;
                best = npc;
            }
        }
        return best;
    }

    private static double rayBox(Vector rayOrigin, Vector rayDir,
                                 double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ,
                                 double maxDistance) {
        double tMin = 0.0;
        double tMax = maxDistance;
        double[] o = {rayOrigin.getX(), rayOrigin.getY(), rayOrigin.getZ()};
        double[] d = {rayDir.getX(), rayDir.getY(), rayDir.getZ()};
        double[] min = {minX, minY, minZ};
        double[] max = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-9) {
                if (o[i] < min[i] || o[i] > max[i]) return -1;
            } else {
                double a = (min[i] - o[i]) / d[i];
                double b = (max[i] - o[i]) / d[i];
                if (a > b) {
                    double temp = a; a = b; b = temp;
                }
                tMin = Math.max(tMin, a);
                tMax = Math.min(tMax, b);
                if (tMax < tMin) return -1;
            }
        }
        return tMin <= maxDistance ? tMin : -1;
    }

    public void registerPacketListener() {
        packetListener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    int entityId = event.getPacket().getIntegers().read(0);
                    for (NPCData npc : npcs.values()) {
                        if (npc.entityId() == entityId) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore malformed or version-specific packet layouts.
                }
            }
        };
        protocol.addPacketListener(packetListener);
    }

    public void removePacketListener() {
        if (packetListener != null) protocol.removePacketListener(packetListener);
    }

    public void despawnAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (NPCData npc : npcs.values()) destroyFor(viewer, npc);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> syncViewer(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        for (Set<UUID> viewers : visibleTo.values()) viewers.remove(playerId);
    }

    private void syncViewer(Player player) {
        for (NPCData npc : npcs.values()) {
            if (isInView(player, npc)) loadProfileAndSpawn(player, npc);
        }
    }
}
