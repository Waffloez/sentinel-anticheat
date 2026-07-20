package com.example.sentinel;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SentinelPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerData> data = new HashMap<>();

    private boolean speedEnabled;
    private double speedMaxBps;
    private int speedThreshold;

    private boolean flyEnabled;
    private int flyMaxAirborneTicks;
    private int flyThreshold;

    private boolean reachEnabled;
    private double reachMaxDistance;
    private int reachThreshold;

    private boolean clickEnabled;
    private int clickMaxCps;
    private int clickThreshold;

    private long decaySeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("sentinel").setExecutor(this::onCommand);

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
                            && !p.isFlying() && p.getAllowFlight() == false) {
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
        speedMaxBps = cfg.getDouble("checks.speed.max-blocks-per-second", 13);
        speedThreshold = cfg.getInt("checks.speed.violations-before-alert", 3);

        flyEnabled = cfg.getBoolean("checks.fly.enabled", true);
        flyMaxAirborneTicks = cfg.getInt("checks.fly.max-airborne-ticks", 40);
        flyThreshold = cfg.getInt("checks.fly.violations-before-alert", 3);

        reachEnabled = cfg.getBoolean("checks.reach.enabled", true);
        reachMaxDistance = cfg.getDouble("checks.reach.max-distance", 4.0);
        reachThreshold = cfg.getInt("checks.reach.violations-before-alert", 2);

        clickEnabled = cfg.getBoolean("checks.autoclicker.enabled", true);
        clickMaxCps = cfg.getInt("checks.autoclicker.max-clicks-per-second", 20);
        clickThreshold = cfg.getInt("checks.autoclicker.violations-before-alert", 3);

        decaySeconds = cfg.getLong("violation-decay-seconds", 60);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        data.put(event.getPlayer().getUniqueId(), new PlayerData());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        data.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("sentinel.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        PlayerData pd = data.computeIfAbsent(player.getUniqueId(), k -> new PlayerData());
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        long now = System.currentTimeMillis();

        if (speedEnabled && pd.lastMoveTime > 0) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            double elapsedSeconds = (now - pd.lastMoveTime) / 1000.0;

            if (elapsedSeconds > 0.01) {
                double bps = horizontalDistance / elapsedSeconds;
                double allowedMax = speedMaxBps * speedMultiplier(player);

                if (bps > allowedMax) {
                    pd.speedViolations++;
                    if (pd.speedViolations >= speedThreshold) {
                        alert(player, "Speed", pd.speedViolations,
                                String.format("%.2f blocks/s (max %.2f)", bps, allowedMax));
                    }
                }
            }
        }
        pd.lastMoveTime = now;

        if (flyEnabled && !player.isOnGround() && pd.airborneTicks > flyMaxAirborneTicks
                && !player.isGliding() && !player.isSwimming()
                && player.getVehicle() == null
                && !player.hasPotionEffect(PotionEffectType.LEVITATION)
                && !isNearClimbable(player)) {
            pd.flyViolations++;
            pd.airborneTicks = 0;
            if (pd.flyViolations >= flyThreshold) {
                alert(player, "Fly", pd.flyViolations, "airborne without valid cause");
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!reachEnabled) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (attacker.hasPermission("sentinel.bypass")) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;

        double distance = attacker.getEyeLocation().distance(event.getEntity().getLocation());
        if (distance > reachMaxDistance) {
            PlayerData pd = data.computeIfAbsent(attacker.getUniqueId(), k -> new PlayerData());
            pd.reachViolations++;
            if (pd.reachViolations >= reachThreshold) {
                alert(attacker, "Reach", pd.reachViolations,
                        String.format("%.2f blocks (max %.2f)", distance, reachMaxDistance));
            }
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (!clickEnabled) return;
        Player player = event.getPlayer();
        if (player.hasPermission("sentinel.bypass")) return;

        PlayerData pd = data.computeIfAbsent(player.getUniqueId(), k -> new PlayerData());
        long now = System.currentTimeMillis();
        pd.clickTimestamps.addLast(now);

        while (!pd.clickTimestamps.isEmpty() && now - pd.clickTimestamps.peekFirst() > 1000) {
            pd.clickTimestamps.pollFirst();
        }

        if (pd.clickTimestamps.size() > clickMaxCps) {
            pd.clickViolations++;
            if (pd.clickViolations >= clickThreshold) {
                alert(player, "Autoclicker", pd.clickViolations,
                        pd.clickTimestamps.size() + " cps (max " + clickMaxCps + ")");
            }
        }
    }

    private double speedMultiplier(Player player) {
        double multiplier = 1.0;
        if (player.isSprinting()) multiplier += 0.3;
        PotionEffect speedEffect = player.getPotionEffect(PotionEffectType.SPEED);
        if (speedEffect != null) {
            multiplier += 0.2 * (speedEffect.getAmplifier() + 1);
        }
        return multiplier;
    }

    private boolean isNearClimbable(Player player) {
        var type = player.getLocation().getBlock().getType();
        String name = type.name();
        return name.equals("LADDER") || name.equals("VINE") || name.contains("SCAFFOLDING");
    }

    private void alert(Player subject, String checkName, int violations, String detail) {
        String message = ChatColor.RED + "[Sentinel] " + ChatColor.YELLOW + subject.getName()
                + ChatColor.RED + " failed " + ChatColor.WHITE + checkName
                + ChatColor.RED + " (" + violations + ") " + ChatColor.GRAY + detail;

        for (Player staff : getServer().getOnlinePlayers()) {
            if (!staff.hasPermission("sentinel.alerts")) continue;
            PlayerData staffData = data.get(staff.getUniqueId());
            if (staffData != null && staffData.alertsMuted) continue;
            staff.sendMessage(message);
        }
        getLogger().info(ChatColor.stripColor(message));
    }

    private void decayViolations() {
        for (PlayerData pd : data.values()) {
            if (pd.speedViolations > 0) pd.speedViolations--;
            if (pd.flyViolations > 0) pd.flyViolations--;
            if (pd.reachViolations > 0) pd.reachViolations--;
            if (pd.clickViolations > 0) pd.clickViolations--;
        }
    }

    private static final class PlayerData {
        long lastMoveTime = 0;
        int airborneTicks = 0;
        int speedViolations = 0;
        int flyViolations = 0;
        int reachViolations = 0;
        int clickViolations = 0;
        boolean alertsMuted = false;
        final Deque<Long> clickTimestamps = new ArrayDeque<>();
    }
}
