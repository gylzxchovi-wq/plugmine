package ru.solhaven.npc;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class SolhavenNPC extends JavaPlugin {
    private ProtocolManager protocol;
    private NPCManager npcManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib is missing. Install ProtocolLib 5.4.0 or compatible, then restart.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        protocol = ProtocolLibrary.getProtocolManager();
        npcManager = new NPCManager(this, protocol);
        npcManager.load();

        Bukkit.getPluginManager().registerEvents(npcManager, this);
        npcManager.registerPacketListener();

        int interval = Math.max(1, getConfig().getInt("head-update-interval-ticks", 5));
        Bukkit.getScheduler().runTaskTimer(this, npcManager::tick, interval, interval);

        getLogger().info("SolhavenNPC enabled. Loaded NPCs: " + npcManager.size());
    }

    @Override
    public void onDisable() {
        if (npcManager != null) {
            npcManager.despawnAll();
            npcManager.save();
            npcManager.removePacketListener();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эту команду может использовать только игрок.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage("§cТолько OP может использовать эту команду.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("NPC")) {
            player.sendMessage("§eИспользование: /" + label + " NPC");
            return true;
        }

        if (command.getName().equalsIgnoreCase("create")) {
            Location location = player.getLocation().clone();
            npcManager.create(location, player);
            return true;
        }
        if (command.getName().equalsIgnoreCase("del")) {
            NPCData target = npcManager.findTarget(player);
            if (target == null) {
                player.sendMessage("§cТы не смотришь на NPC SolhavenNPC (дистанция до 6 блоков).");
            } else {
                npcManager.delete(target.id());
                player.sendMessage("§aNPC удалён.");
            }
            return true;
        }
        return true;
    }
}
