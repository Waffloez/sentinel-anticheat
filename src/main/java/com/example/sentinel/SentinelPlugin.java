package com.example.sentinel;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SentinelPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerData> data = new HashMap<>();

    private boolean speedEnabled;
    private double speedMaxBps;
    private int speedThreshold;
    private boolean speedBlock;
    private int speedBlockViolations;

    private boolean flyEnabled;
    private int flyMaxAirborneTicks;
    private int flyThreshold;
    private boolean flyBlock;
    private int flyBlockViolations;

    private boolean reachEnabled;
    private double reachMaxDistance;
    private int reachThreshold;
    private boolean reachBlock;
    private int reachBlockViolations;

    private boolean clickEnabled;
    private int clickMaxCps;
    private int clickThreshold;

    private long decaySeconds;

    private boolean autobanEnabled;
    private int autobanTotalViolations;
    private String autobanDuration;
    private String autobanReason;

    private static final String SUS_MENU_TITLE = ChatColor.DARK_RED + "Sentinel - Suspicious Players";
    private static final String[] FILTERS = {"ALL", "NORMAL", "NETHER", "THE_END"};

    private final Map<UUID, Map<Integer, UUID>> susMenuSlots = new HashMap<>();
    private final Map<UUID, Location> spectatorReturnLocation = new HashMap<>();
    private final Map<UUID, GameMode> spectatorReturnGameMode = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("sentinel").setExecutor(this::onSentinelCommand);
        getCommand("sus").setExecutor(this::onSusCommand);

        new BukkitRunnable() {
            @Override
            public void run() {
                decayViolations();
            }
        }.runTaskTimer(this, 20L * decaySeconds, 20L * decaySeconds);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : getServer().getOnlinePlayers()) {
                    PlayerData pd = data.get(p.getUniqueId());
                    if (pd == null) continue;
                    if (!p.isOnGround() && !p.isGliding() && !p.isSwimming()
                            && !p.isFlying() && !p.getAllowFlight()) {
                        pd.airborneTicks++;
                    } else {
                        pd.airborneTicks = 0;
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);

        getLogger().info("Sentinel enabled.");
    }

    private void loadConfigValues() {
        reloadConfig();
        var cfg = getConfig();

        speedEnabled = cfg.getBoolean("checks.speed.enabled", true);
        speedMaxBps = cfg.getDouble("checks.speed.max-blocks-per-second", 8.5);
        speedThreshold = cfg.getInt("checks.speed.violations-before-alert", 3);
        speedBlock = cfg.getBoolean("checks.speed.block", true);
        speedBlockViolations = cfg.getInt("checks.speed.block-violations", 6);

        flyEnabled = cfg.getBoolean("checks.fly.enabled", true);
        flyMaxAirborneTicks = cfg.getInt("checks.fly.max-airborne-ticks", 40);
        flyThreshold = cfg.getInt("checks.fly.violations-before-alert", 3);
        flyBlock = cfg.getBoolean("checks.fly.block", true);
        flyBlockViolations = cfg.getInt("checks.fly.block-violations", 6);

        reachEnabled = cfg.getBoolean("checks.reach.enabled", true);
        reachMaxDistance = cfg.getDouble("checks.reach.max-distance", 4.0);
        reachThreshold = cfg.getInt("checks.reach.violations-before-alert", 2);
        reachBlock = cfg.getBoolean("checks.reach.block", true);
        reachBlockViolations = cfg.getInt("checks.reach.block-violations", 3);

        clickEnabled = cfg.getBoolean("checks.autoclicker.enabled", true);
        clickMaxCps = cfg.getInt("checks.autoclicker.max-clicks-per-second", 18);
        clickThreshold = cfg.getInt("checks.autoclicker.violations-before-alert", 3);

        decaySeconds = cfg.getLong("violation-decay-seconds", 60);

        autobanEnabled = cfg.getBoolean("autoban.enabled", true);
        autobanTotalViolations = cfg.getInt("autoban.total-violations", 15);
        autobanDuration = cfg.getString("autoban.duration", "permanent");
        autobanReason = cfg.getString("autoban.reason", "Automatically banned by Sentinel for repeated cheat detections");
    }

    private boolean onSentinelCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("sentinel.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            loadConfigValues();
            sender.sendMessage(ChatColor.GREEN + "Sentinel config reloaded.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("toggle")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Only players can toggle their own alerts.");
                return true;
            }
            PlayerData pd = data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
            pd.alertsMuted = !pd.alertsMuted;
            p.sendMessage(ChatColor.YELLOW + "Sentinel alerts " + (pd.alertsMuted ? "muted" : "unmuted") + " for you.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /sentinel reload | /sentinel toggle");
        return true;
    }

    private boolean onSusCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (!viewer.hasPermission("sentinel.sus")) {
            viewer.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("back")) {
            restoreFromSpectate(viewer);
            return true;
        }

        openSusMenu(viewer, "ALL");
        return true;
    }

    private void openSusMenu(Player viewer, String filter) {
        List<Map.Entry<UUID, PlayerData>> flagged = new ArrayList<>();
        for (Map.Entry<UUID, PlayerData> entry : data.entrySet()) {
            PlayerData pd = entry.getValue();
            int total = pd.speedViolations + pd.flyViolations + pd.reachViolations + pd.clickViolations;
            if (total <= 0) continue;

            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) continue;
