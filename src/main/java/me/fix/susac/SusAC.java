package me.fix.susac;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class SusAC extends JavaPlugin implements CommandExecutor, TabCompleter {

    public static class Flag {
        Category cat;
        int total;
        long last;
        String info = "";
    }

    private final Map<UUID, Map<String, Flag>> flags = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<String, Long> alertCooldown = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private File dataFile;
    private SusGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "flags.yml");
        load();
        gui = new SusGui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        Checks checks = new Checks(this);
        getServer().getPluginManager().registerEvents(checks, this);
        checks.start();
        getServer().getPluginManager().registerEvents(new ChatChecks(this), this);
        getCommand("sus").setExecutor(this);
        getCommand("sus").setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------- helpers ----------
    public static Component color(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    /** Toggles flag alerts for one staff member (saved across restarts). Returns true if alerts are now ON. */
    public boolean toggleAlerts(Player p) {
        UUID id = p.getUniqueId();
        boolean nowOn;
        if (alertsOff.remove(id)) {
            nowOn = true;
        } else {
            alertsOff.add(id);
            nowOn = false;
        }
        save();
        return nowOn;
    }

    public boolean alertsOn(UUID id) {
        return !alertsOff.contains(id);
    }

    public Map<UUID, Map<String, Flag>> allFlags() {
        return flags;
    }

    public String nameOf(UUID id) {
        return names.getOrDefault(id, "Unknown");
    }

    private UUID findId(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public void clear(UUID id, Category cat) {
        Map<String, Flag> m = flags.get(id);
        if (m == null) return;
        if (cat == null) m.clear();
        else m.values().removeIf(f -> f.cat == cat);
        if (m.isEmpty()) flags.remove(id);
    }

    // ---------- flagging ----------
    public void flag(Player p, Category cat, String check, String info) {
        UUID id = p.getUniqueId();
        names.put(id, p.getName());
        Flag f = flags.computeIfAbsent(id, k -> new LinkedHashMap<>()).computeIfAbsent(check, k -> new Flag());
        f.cat = cat;
        f.total++;
        f.last = System.currentTimeMillis();
        f.info = info;

        long cd = getConfig().getLong("alert-cooldown-seconds", 3) * 1000L;
        String key = id + check;
        Long lastAlert = alertCooldown.get(key);
        if (lastAlert != null && f.last - lastAlert < cd) return;
        alertCooldown.put(key, f.last);

        Component msg = Component.text("[SUS] ", NamedTextColor.RED)
                .append(Component.text(p.getName(), NamedTextColor.WHITE))
                .append(Component.text(" flagged ", NamedTextColor.GRAY))
                .append(Component.text(check, NamedTextColor.RED))
                .append(Component.text(" (" + cat.display + ") ", NamedTextColor.DARK_GRAY))
                .append(Component.text(info + " x" + f.total, NamedTextColor.GRAY))
                .clickEvent(ClickEvent.runCommand("/sus tp " + p.getName()));
        if (getConfig().getBoolean("alerts.enabled", true)) {
            for (Player s : Bukkit.getOnlinePlayers()) {
                if (s.hasPermission("sus.alerts") && !alertsOff.contains(s.getUniqueId())) s.sendMessage(msg);
            }
        }
        if (getConfig().getBoolean("alerts.console-log", true)) {
            getLogger().info(p.getName() + " flagged " + check + " (" + info + ") x" + f.total);
        }
    }

    // ---------- storage ----------
    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : y.getStringList("alerts-off")) {
            try {
                alertsOff.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String k : ps.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(k);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            names.put(id, ps.getString(k + ".name", "Unknown"));
            ConfigurationSection cs = ps.getConfigurationSection(k + ".checks");
            if (cs == null) continue;
            for (String c : cs.getKeys(false)) {
                Flag f = new Flag();
                try {
                    f.cat = Category.valueOf(cs.getString(c + ".cat", ""));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                f.total = cs.getInt(c + ".total");
                f.last = cs.getLong(c + ".last");
                f.info = cs.getString(c + ".info", "");
                flags.computeIfAbsent(id, x -> new LinkedHashMap<>()).put(c, f);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> off = new ArrayList<>();
        for (UUID u : alertsOff) off.add(u.toString());
        y.set("alerts-off", off);
        for (Map.Entry<UUID, Map<String, Flag>> e : flags.entrySet()) {
            String base = "players." + e.getKey();
            y.set(base + ".name", nameOf(e.getKey()));
            for (Map.Entry<String, Flag> c : e.getValue().entrySet()) {
                String p = base + ".checks." + c.getKey();
                y.set(p + ".cat", c.getValue().cat.name());
                y.set(p + ".total", c.getValue().total);
                y.set(p + ".last", c.getValue().last);
                y.set(p + ".info", c.getValue().info);
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (Exception ex) {
            getLogger().warning("Could not save flags.yml: " + ex.getMessage());
        }
    }

    // ---------- commands ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("sus.use")) {
            sender.sendMessage(color("&cNo permission."));
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player p) gui.openMain(p);
            else sender.sendMessage("Players only.");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("sus.admin")) { sender.sendMessage(color("&cNo permission.")); return true; }
                reloadConfig();
                sender.sendMessage(color("&aSusAC config reloaded."));
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) return true;
                p.sendMessage(color(toggleAlerts(p) ? "&aSUS alerts on." : "&cSUS alerts off."));
            }
            case "tp" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) { p.sendMessage(color("&cThat player is offline.")); return true; }
                p.teleport(t.getLocation());
            }
            case "flag" -> {
                if (!sender.hasPermission("sus.admin")) { sender.sendMessage(color("&cNo permission.")); return true; }
                if (args.length < 4) { sender.sendMessage(color("&cUsage: /sus flag <player> <category> <check> [info]")); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) { sender.sendMessage(color("&cThat player is offline.")); return true; }
                Category c;
                try {
                    c = Category.valueOf(args[2].toUpperCase());
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(color("&cCategories: movement, combat, world, player, chat"));
                    return true;
                }
                String info = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : "external flag";
                flag(t, c, args[3], info);
                sender.sendMessage(color("&aFlagged &f" + t.getName() + "&a."));
            }
            case "clear" -> {
                if (!sender.hasPermission("sus.admin")) { sender.sendMessage(color("&cNo permission.")); return true; }
                if (args.length < 2) { sender.sendMessage(color("&cUsage: /sus clear <player|all>")); return true; }
                if (args[1].equalsIgnoreCase("all")) {
                    flags.clear();
                    sender.sendMessage(color("&aCleared all flags."));
                } else {
                    UUID id = findId(args[1]);
                    if (id == null) { sender.sendMessage(color("&cNo flags for that player.")); return true; }
                    clear(id, null);
                    sender.sendMessage(color("&aCleared flags for &f" + args[1] + "&a."));
                }
            }
            default -> {
                UUID id = findId(args[0]);
                Map<String, Flag> m = id == null ? null : flags.get(id);
                if (m == null || m.isEmpty()) { sender.sendMessage(color("&7No flags for &f" + args[0] + "&7.")); return true; }
                sender.sendMessage(color("&8&m----&r &cSUS: &f" + nameOf(id) + " &8&m----"));
                List<Map.Entry<String, Flag>> list = new ArrayList<>(m.entrySet());
                list.sort((a, b) -> Long.compare(b.getValue().last, a.getValue().last));
                for (Map.Entry<String, Flag> en : list) {
                    Flag f = en.getValue();
                    sender.sendMessage(color("&c" + en.getKey() + " &8(" + f.cat.display + ") &7x&f" + f.total
                            + " &8- &7" + f.info + " &8(" + ago(f.last) + ")"));
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("alerts", "clear", "flag", "reload", "tp"));
            out.addAll(names.values());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("flag")) {
            for (Category c : Category.values()) out.add(c.name().toLowerCase());
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("flag"))) {
            out.addAll(names.values());
            if (args[0].equalsIgnoreCase("clear")) out.add("all");
        }
        String start = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(start));
        return out;
    }
}
