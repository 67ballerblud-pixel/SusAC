package me.fix.susac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Checks implements Listener {

    private final SusAC plugin;
    private final Map<UUID, Data> data = new HashMap<>();

    static class Data {
        Location last;
        long grace;
        int hover;
        int omni;
        double speedBuf, reachBuf, wallBuf, multiBuf;
        final ArrayDeque<Long> clicks = new ArrayDeque<>();
        final ArrayDeque<Long> breaks = new ArrayDeque<>();
        final ArrayDeque<Long> places = new ArrayDeque<>();
        UUID lastTarget;
        long lastHit;
        long lastEat;
        // xray / macro / automation / esp tracking
        final ArrayDeque<Boolean> xray = new ArrayDeque<>();
        final ArrayDeque<Long> gaps = new ArrayDeque<>();
        long lastClick;
        int noLook;
        int track;
        float trackYaw, trackPitch;
    }

    private int tickCount = 0;

    Checks(SusAC plugin) {
        this.plugin = plugin;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ---------- helpers ----------
    private Data data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new Data());
    }

    private double num(String path, double def) {
        return plugin.getConfig().getDouble(path, def);
    }

    private boolean on(String check) {
        return plugin.getConfig().getBoolean("checks." + check + ".enabled", true);
    }

    private boolean exempt(Player p) {
        GameMode gm = p.getGameMode();
        return p.hasPermission("sus.bypass") || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    private void grace(Player p, long ms) {
        Data d = data(p);
        d.grace = Math.max(d.grace, System.currentTimeMillis() + ms);
        d.last = null;
        d.hover = 0;
        d.speedBuf = 0;
        d.omni = 0;
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private boolean onIce(Location l) {
        World w = l.getWorld();
        for (int dy = 1; dy <= 2; dy++) {
            Material m = w.getBlockAt(l.getBlockX(), l.getBlockY() - dy, l.getBlockZ()).getType();
            if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE) return true;
        }
        return false;
    }

    /** True if the feet block and the two blocks below are all empty/passable (really in the air). */
    private boolean airBelow(Location l) {
        World w = l.getWorld();
        int y = l.getBlockY();
        double[] offs = {-0.3, 0, 0.3};
        for (double ox : offs) {
            for (double oz : offs) {
                for (int dy = 0; dy <= 2; dy++) {
                    Block b = w.getBlockAt(NumberConversions.floor(l.getX() + ox), y - dy, NumberConversions.floor(l.getZ() + oz));
                    if (!b.isPassable() || b.isLiquid()) return false;
                }
            }
        }
        return true;
    }

    private boolean specialBlocks(Location l) {
        World w = l.getWorld();
        for (int dy = 0; dy <= 1; dy++) {
            Material m = w.getBlockAt(l.getBlockX(), l.getBlockY() + dy, l.getBlockZ()).getType();
            if (Tag.CLIMBABLE.isTagged(m) || m == Material.COBWEB || m == Material.POWDER_SNOW
                    || m == Material.SWEET_BERRY_BUSH || m == Material.BUBBLE_COLUMN
                    || m == Material.WATER || m == Material.LAVA) return true;
        }
        return false;
    }

    // ---------- movement (runs every tick) ----------
    private void tick() {
        tickCount++;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Data d = data(p);
            Location cur = p.getLocation();
            Location prev = d.last;
            d.last = cur;
            if (prev == null || prev.getWorld() != cur.getWorld()) continue;

            if (exempt(p) || now < d.grace || p.getAllowFlight() || p.isFlying() || p.isGliding()
                    || p.isRiptiding() || p.isInsideVehicle() || p.isDead()) {
                d.hover = 0;
                d.speedBuf = 0;
                d.omni = 0;
                d.noLook = 0;
                continue;
            }

            double dx = cur.getX() - prev.getX();
            double dz = cur.getZ() - prev.getZ();
            double dy = cur.getY() - prev.getY();
            double h = Math.hypot(dx, dz);

            // Speed
            if (on("speed")) {
                double max = num("checks.speed.max-per-tick", 0.65);
                PotionEffect sp = p.getPotionEffect(PotionEffectType.SPEED);
                if (sp != null) max *= 1 + 0.2 * (sp.getAmplifier() + 1);
                max *= p.getWalkSpeed() / 0.2;
                if (onIce(cur)) max *= 1.8;
                if (h > max) d.speedBuf += 1;
                else d.speedBuf = Math.max(0, d.speedBuf - 0.5);
                if (d.speedBuf >= num("checks.speed.buffer", 6)) {
                    plugin.flag(p, Category.MOVEMENT, "Speed", String.format("%.2f b/tick (max %.2f)", h, max));
                    d.speedBuf = 3;
                }
            }

            // Fly (hovering in mid-air)
            if (on("fly")) {
                boolean skip = p.hasPotionEffect(PotionEffectType.LEVITATION)
                        || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        || p.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                        || specialBlocks(cur);
                if (!skip && Math.abs(dy) < 0.03 && airBelow(cur)) d.hover++;
                else d.hover = 0;
                if (d.hover >= (int) num("checks.fly.hover-ticks", 10)) {
                    plugin.flag(p, Category.MOVEMENT, "Fly", "hovering " + d.hover + " ticks in air");
                    d.hover = 0;
                }
            }

            // OmniSprint (sprinting backwards)
            if (on("omnisprint") && p.isSprinting() && h > 0.15 && !onIce(cur)) {
                double yaw = Math.toRadians(cur.getYaw());
                double dot = (dx * -Math.sin(yaw) + dz * Math.cos(yaw)) / h;
                float yawDelta = Math.abs(wrap(cur.getYaw() - prev.getYaw()));
                if (dot < -0.5 && yawDelta < 10) d.omni++;
                else d.omni = Math.max(0, d.omni - 1);
                if (d.omni >= (int) num("checks.omnisprint.ticks", 10)) {
                    plugin.flag(p, Category.PLAYER, "OmniSprint", "sprinting backwards");
                    d.omni = 0;
                }
            } else {
                d.omni = Math.max(0, d.omni - 1);
            }

            // AutoWalk (walking for a long time without ever moving the camera)
            if (on("autowalk")) {
                boolean moving = h > 0.1 && p.isOnGround() && !p.isInWater() && !onIce(cur);
                boolean still = cur.getYaw() == prev.getYaw() && cur.getPitch() == prev.getPitch();
                if (moving && still) d.noLook++;
                else if (moving) d.noLook = 0;
                int limit = (int) (num("checks.autowalk.seconds", 90) * 20);
                if (d.noLook >= limit) {
                    plugin.flag(p, Category.PLAYER, "AutoWalk", "walked " + (d.noLook / 20) + "s without moving the camera");
                    d.noLook = 0;
                }
            }
        }
        if (tickCount % 5 == 0 && on("walltrack")) trackScan();
    }

    /** ESP-style check: crosshair keeps following a player who is behind walls. */
    private void trackScan() {
        double range = num("checks.walltrack.range", 40);
        double rangeSq = range * range;
        int need = (int) num("checks.walltrack.samples", 12);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (exempt(p)) continue;
            Data d = data(p);
            Location eye = p.getEyeLocation();
            float yaw = eye.getYaw(), pitch = eye.getPitch();
            boolean lookChanged = Math.abs(wrap(yaw - d.trackYaw)) > 0.3f || Math.abs(pitch - d.trackPitch) > 0.3f;
            d.trackYaw = yaw;
            d.trackPitch = pitch;
            boolean hit = false;
            if (lookChanged) {
                Vector dir = eye.getDirection();
                for (Player t : p.getWorld().getPlayers()) {
                    if (t == p || !p.canSee(t) || t.getGameMode() == GameMode.SPECTATOR) continue;
                    Location te = t.getEyeLocation();
                    double distSq = te.distanceSquared(eye);
                    if (distSq > rangeSq || distSq < 16) continue;
                    Vector to = te.toVector().subtract(eye.toVector()).normalize();
                    double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dir.dot(to)))));
                    if (angle < 2.5 && !p.hasLineOfSight(t)) { hit = true; break; }
                }
            }
            if (hit) d.track++;
            else d.track = Math.max(0, d.track - 1);
            if (d.track >= need) {
                plugin.flag(p, Category.PLAYER, "WallTrack", "crosshair follows a player through walls");
                d.track = 0;
            }
        }
    }

    // ---------- grace events ----------
    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        grace(e.getPlayer(), (long) num("teleport-grace-ms", 2000));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        grace(e.getPlayer(), 3000);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        grace(p, 3000);
        if (!on("altlimit") || p.hasPermission("sus.bypass") || p.getAddress() == null) return;
        java.net.InetAddress ip = p.getAddress().getAddress();
        if (ip == null || ip.isLoopbackAddress()) return;
        int same = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getAddress() != null && ip.equals(o.getAddress().getAddress())) same++;
        }
        int max = (int) num("checks.altlimit.max-accounts", 2);
        if (same > max) plugin.flag(p, Category.PLAYER, "AltLimit", same + " accounts online on one IP (limit " + max + ")");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        Data d = data(e.getPlayer());
        d.grace = Math.max(d.grace, System.currentTimeMillis() + 1500);
        d.hover = 0;
        d.speedBuf = 0;
    }

    // ---------- combat ----------
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING || !on("autoclicker")) return;
        Player p = e.getPlayer();
        if (p.hasPermission("sus.bypass")) return;
        // Mining and placing also swing every tick, so only count swings not aimed at a block.
        if (p.getTargetBlockExact(5) != null) return;
        Data d = data(p);
        long now = System.currentTimeMillis();
        if (on("macroclick")) {
            if (d.lastClick != 0 && now - d.lastClick > 1500) d.gaps.clear();
            else if (d.lastClick != 0) {
                d.gaps.addLast(now - d.lastClick);
                if (d.gaps.size() > 30) d.gaps.pollFirst();
            }
            d.lastClick = now;
            if (d.gaps.size() >= 30) {
                double mean = 0;
                for (long g : d.gaps) mean += g;
                mean /= d.gaps.size();
                double var = 0;
                for (long g : d.gaps) var += (g - mean) * (g - mean);
                double std = Math.sqrt(var / d.gaps.size());
                if (mean < 250 && std < num("checks.macroclick.max-deviation-ms", 8)) {
                    plugin.flag(p, Category.COMBAT, "MacroClick", String.format("clicks every %.0fms (+-%.1f)", mean, std));
                    d.gaps.clear();
                }
            }
        }
        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) d.clicks.pollFirst();
        int max = (int) num("checks.autoclicker.max-cps", 22);
        if (d.clicks.size() > max) {
            plugin.flag(p, Category.COMBAT, "AutoClicker", d.clicks.size() + " CPS");
            d.clicks.clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (exempt(p)) return;
        Entity t = e.getEntity();
        Data d = data(p);
        long now = System.currentTimeMillis();

        // Reach
        if (on("reach")) {
            Location eye = p.getEyeLocation();
            BoundingBox bb = t.getBoundingBox();
            double cx = Math.max(bb.getMinX(), Math.min(eye.getX(), bb.getMaxX()));
            double cy = Math.max(bb.getMinY(), Math.min(eye.getY(), bb.getMaxY()));
            double cz = Math.max(bb.getMinZ(), Math.min(eye.getZ(), bb.getMaxZ()));
            double dist = Math.sqrt(Math.pow(eye.getX() - cx, 2) + Math.pow(eye.getY() - cy, 2) + Math.pow(eye.getZ() - cz, 2));
            if (dist > num("checks.reach.max", 3.9)) d.reachBuf += 1;
            else d.reachBuf = Math.max(0, d.reachBuf - 0.5);
            if (d.reachBuf >= num("checks.reach.buffer", 3)) {
                plugin.flag(p, Category.COMBAT, "Reach", String.format("%.2f blocks", dist));
                d.reachBuf = 1;
            }
        }

        // Hitting through walls
        if (on("wallhit") && t instanceof LivingEntity) {
            if (!p.hasLineOfSight(t)) d.wallBuf += 1;
            else d.wallBuf = Math.max(0, d.wallBuf - 1);
            if (d.wallBuf >= num("checks.wallhit.buffer", 5)) {
                plugin.flag(p, Category.COMBAT, "WallHit", "hit " + t.getType().name().toLowerCase() + " without line of sight");
                d.wallBuf = 2;
            }
        }

        // Multi-aura (several different targets within ~1 tick)
        if (on("multiaura")) {
            if (d.lastTarget != null && !d.lastTarget.equals(t.getUniqueId()) && now - d.lastHit < 60) d.multiBuf += 1;
            else d.multiBuf = Math.max(0, d.multiBuf - 0.25);
            d.lastTarget = t.getUniqueId();
            d.lastHit = now;
            if (d.multiBuf >= 3) {
                plugin.flag(p, Category.COMBAT, "MultiAura", "hit multiple targets in one tick");
                d.multiBuf = 0;
            }
        }
    }

    // ---------- world ----------
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        Data d = data(p);
        long now = System.currentTimeMillis();

        if (on("nuker")) {
            d.breaks.addLast(now);
            while (!d.breaks.isEmpty() && now - d.breaks.peekFirst() > 1000) d.breaks.pollFirst();
            if (d.breaks.size() > (int) num("checks.nuker.max-per-second", 12)) {
                plugin.flag(p, Category.WORLD, "Nuker", d.breaks.size() + " blocks/sec");
                d.breaks.clear();
            }
        }
        if (on("xray")) {
            Material m = e.getBlock().getType();
            boolean rare = m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE || m == Material.ANCIENT_DEBRIS;
            boolean filler = m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF || m == Material.GRANITE
                    || m == Material.DIORITE || m == Material.ANDESITE || m == Material.NETHERRACK
                    || m == Material.BASALT || m == Material.BLACKSTONE;
            boolean deep = e.getBlock().getWorld().getEnvironment() == World.Environment.NETHER || e.getBlock().getY() < 20;
            if (deep && (rare || filler)) {
                int window = (int) num("checks.xray.window", 150);
                d.xray.addLast(rare);
                while (d.xray.size() > window) d.xray.pollFirst();
                if (d.xray.size() >= window) {
                    int count = 0;
                    for (boolean b : d.xray) if (b) count++;
                    if (count >= (int) num("checks.xray.rare-ores", 8)) {
                        plugin.flag(p, Category.WORLD, "XRay", count + " diamond/debris in last " + window + " mined blocks");
                    }
                    d.xray.clear();
                }
            }
        }
        if (on("breakreach")) {
            Location c = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
            double dist = p.getEyeLocation().distance(c);
            if (dist > num("checks.breakreach.max", 6.5)) {
                plugin.flag(p, Category.WORLD, "BreakReach", String.format("%.1f blocks away", dist));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (exempt(p) || !on("fastplace")) return;
        Data d = data(p);
        long now = System.currentTimeMillis();
        d.places.addLast(now);
        while (!d.places.isEmpty() && now - d.places.peekFirst() > 1000) d.places.pollFirst();
        if (d.places.size() > (int) num("checks.fastplace.max-per-second", 16)) {
            plugin.flag(p, Category.WORLD, "FastPlace", d.places.size() + " blocks/sec");
            d.places.clear();
        }
    }

    // ---------- player ----------
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (exempt(p) || !on("fasteat")) return;
        Data d = data(p);
        long now = System.currentTimeMillis();
        if (d.lastEat != 0 && now - d.lastEat < (long) num("checks.fasteat.min-interval-ms", 600)) {
            plugin.flag(p, Category.PLAYER, "FastEat", (now - d.lastEat) + "ms between items");
        }
        d.lastEat = now;
    }
}
