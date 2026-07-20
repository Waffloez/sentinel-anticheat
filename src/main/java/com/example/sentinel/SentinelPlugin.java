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
    private String autobanOffendReason;

    private static final String SUS_MENU_TITLE = ChatColor.DARK_RED + "Sentinel - Suspicious Players";
    private static final String[] FILTERS = {"ALL", "NORMAL", "NETHER", "THE_END"};
    private static final String[] FILTER_NAMES = {"All Dimensions", "Overworld", "Nether", "The End"};
    private static final int PAGE_SIZE = 45;

    private final Map<UUID, Map<Integer, UUID>> susMenuSlots = new HashMap<>();
    private final Map<UUID, SusViewState> susViewStates = new HashMap<>();
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
        autobanOffendReason = cfg.getString("autoban.offend-reason", "cheats");
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

        openSusMenu(viewer, 0, 0);
        return true;
    }

    private void openSusMenu(Player viewer, int filterIndex, int page) {
        List<Map.Entry<UUID, PlayerData>> flagged = new ArrayList<>();
        for (Map.Entry<UUID, PlayerData> entry : data.entrySet()) {
            PlayerData pd = entry.getValue();
            int total = pd.speedViolations + pd.flyViolations + pd.reachViolations + pd.clickViolations;
            if (total <= 0) continue;

            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) continue;

            String filter = FILTERS[filterIndex];
            if (!filter.equals("ALL") && !target.getWorld().getEnvironment().name().equals(filter)) continue;

            flagged.add(entry);
        }

        flagged.sort((a, b) -> {
            int totalA = a.getValue().speedViolations + a.getValue().flyViolations + a.getValue().reachViolations + a.getValue().clickViolations;
            int totalB = b.getValue().speedViolations + b.getValue().flyViolations + b.getValue().reachViolations + b.getValue().clickViolations;
            return Integer.compare(totalB, totalA);
        });

        int totalPages = Math.max(1, (int) Math.ceil(flagged.size() / (double) PAGE_SIZE));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        Inventory inv = Bukkit.createInventory(null, 54, SUS_MENU_TITLE);

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, flagged.size());
        Map<Integer, UUID> slotMap = new HashMap<>();

        for (int i = start; i < end; i++) {
            Map.Entry<UUID, PlayerData> entry = flagged.get(i);
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null) continue;
            PlayerData pd = entry.getValue();

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.YELLOW + target.getName());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Dimension: " + ChatColor.WHITE + dimensionName(target.getWorld()));
            if (pd.speedViolations > 0) lore.add(ChatColor.RED + "Speed: " + pd.speedViolations);
            if (pd.flyViolations > 0) lore.add(ChatColor.RED + "Fly: " + pd.flyViolations);
            if (pd.reachViolations > 0) lore.add(ChatColor.RED + "Reach: " + pd.reachViolations);
            if (pd.clickViolations > 0) lore.add(ChatColor.RED + "Autoclicker: " + pd.clickViolations);
            lore.add("");
            lore.add(ChatColor.GREEN + "Click to spectate");
            meta.setLore(lore);

            head.setItemMeta(meta);

            int slot = i - start;
            inv.setItem(slot, head);
            slotMap.put(slot, entry.getKey());
        }

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        for (int s = 45; s <= 53; s++) {
            inv.setItem(s, filler);
        }

        if (page > 0) {
            inv.setItem(45, navItem(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        }
        if (page < totalPages - 1) {
            inv.setItem(53, navItem(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        }

        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta compassMeta = compass.getItemMeta();
        compassMeta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Dimension: " + ChatColor.WHITE + FILTER_NAMES[filterIndex]);
        List<String> compassLore = new ArrayList<>();
        compassLore.add(ChatColor.GRAY + "Click to switch dimension");
        compassLore.add(ChatColor.GRAY + "Page " + (page + 1) + "/" + totalPages);
        compassMeta.setLore(compassLore);
        compass.setItemMeta(compassMeta);
        inv.setItem(49, compass);

        susMenuSlots.put(viewer.getUniqueId(), slotMap);
        susViewStates.put(viewer.getUniqueId(), new SusViewState(filterIndex, page));
        viewer.openInventory(inv);
    }

    private ItemStack navItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private String dimensionName(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            default -> "Overworld";
        };
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(SUS_MENU_TITLE)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        SusViewState state = susViewStates.get(viewer.getUniqueId());
        if (state == null) return;

        if (slot == 49) {
            int nextFilter = (state.filterIndex + 1) % FILTERS.length;
            openSusMenu(viewer, nextFilter, 0);
            return;
        }

        if (slot == 45) {
            openSusMenu(viewer, state.filterIndex, state.page - 1);
            return;
        }

        if (slot == 53) {
            openSusMenu(viewer, state.filterIndex, state.page + 1);
            return;
        }

        if (slot >= 45) {
            return;
        }

        Map<Integer, UUID> slotMap = susMenuSlots.get(viewer.getUniqueId());
        if (slotMap == null) return;
        UUID targetId = slotMap.get(slot);
        if (targetId == null) return;

        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            viewer.sendMessage(ChatColor.RED + "That player is no longer online.");
            return;
        }

        spectatorReturnLocation.put(viewer.getUniqueId(), viewer.getLocation());
        spectatorReturnGameMode.put(viewer.getUniqueId(), viewer.getGameMode());

        viewer.closeInventory();
        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.teleport(target.getLocation());
        viewer.sendMessage(ChatColor.GREEN + "Now spectating " + target.getName() + ". Use " + ChatColor.WHITE + "/sus back" + ChatColor.GREEN + " to return.");
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(SUS_MENU_TITLE)) {
            susMenuSlots.remove(event.getPlayer().getUniqueId());
            susViewStates.remove(event.getPlayer().getUniqueId());
        }
    }

    private void restoreFromSpectate(Player viewer) {
        Location loc = spectatorReturnLocation.remove(viewer.getUniqueId());
        GameMode mode = spectatorReturnGameMode.remove(viewer.getUniqueId());

        if (loc == null || mode == null) {
            viewer.sendMessage(ChatColor.RED + "Nothing to return from.");
            return;
        }

        viewer.setGameMode(mode);
        viewer.teleport(loc);
        viewer.sendMessage(ChatColor.GREEN + "Returned.");
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
        if (pd.banned) return;

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
                    if (speedBlock && pd.speedViolations >= speedBlockViolations) {
                        event.setTo(from);
                        pd.lastMoveTime = now;
                        checkAutoban(player, pd);
                        return;
                    }
                    checkAutoban(player, pd);
                    if (pd.banned) return;
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
            if (flyBlock && pd.flyViolations >= flyBlockViolations) {
                Vector velocity = player.getVelocity();
                player.setVelocity(new Vector(velocity.getX(), -0.6, velocity.getZ()));
            }
            checkAutoban(player, pd);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!reachEnabled) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (attacker.hasPermission("sentinel.bypass")) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;

        PlayerData pd = data.computeIfAbsent(attacker.getUniqueId(), k -> new PlayerData());
        if (pd.banned) return;

        double distance = attacker.getEyeLocation().distance(event.getEntity().getLocation());
        if (distance > reachMaxDistance) {
            pd.reachViolations++;
            if (pd.reachViolations >= reachThreshold) {
                alert(attacker, "Reach", pd.reachViolations,
                        String.format("%.2f blocks (max %.2f)", distance, reachMaxDistance));
            }
            if (reachBlock && pd.reachViolations >= reachBlockViolations) {
                event.setCancelled(true);
            }
            checkAutoban(attacker, pd);
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (!clickEnabled) return;
        Player player = event.getPlayer();
        if (player.hasPermission("sentinel.bypass")) return;

        PlayerData pd = data.computeIfAbsent(player.getUniqueId(), k -> new PlayerData());
        if (pd.banned) return;

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
            checkAutoban(player, pd);
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

    private void checkAutoban(Player player, PlayerData pd) {
        if (!autobanEnabled || pd.banned) return;

        int total = pd.speedViolations + pd.flyViolations + pd.reachViolations + pd.clickViolations;
        if (total < autobanTotalViolations) return;

        pd.banned = true;

        String broadcast = ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + player.getName()
                + " was automatically banned after " + total + " combined violations.";
        for (Player staff : getServer().getOnlinePlayers()) {
            if (staff.hasPermission("sentinel.alerts")) {
                staff.sendMessage(broadcast);
            }
        }
        getLogger().warning(ChatColor.stripColor(broadcast));

        // Primary: run the already-working /offend command with the
        // configured reason key, so this shows up in your normal ban
        // records exactly like a staff-issued offense.
        boolean offendRan = false;
        if (getServer().getPluginManager().getPlugin("Offend") != null) {
            String cmd = "offend " + player.getName() + " " + autobanOffendReason;
            offendRan = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }

        // Safety net: if Offend isn't installed, or its command didn't run
        // for any reason, fall back to Minecraft's own guaranteed ban list
        // so the player is removed either way.
        if (!offendRan) {
            player.banPlayer("Automatically banned by Sentinel for repeated cheat detections");
        }
        player.kickPlayer(ChatColor.RED + "You have been banned for cheating.");
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
        boolean banned = false;
        final Deque<Long> clickTimestamps = new ArrayDeque<>();
    }

    private static final class SusViewState {
        final int filterIndex;
        final int page;

        SusViewState(int filterIndex, int page) {
            this.filterIndex = filterIndex;
            this.page = page;
        }
    }
}
