package me.fix.susac;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.regex.Pattern;

/** Chat-based flags for the offenses that can be spotted automatically. Flags only, never punishes. */
public class ChatChecks implements Listener {

    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b");
    private static final Pattern DOMAIN = Pattern.compile(
            "\\b[a-z0-9-]{2,}\\s?(?:\\.|\\(dot\\)|\\[dot\\]|\\sdot\\s)\\s?(?:com|net|org|gg|io|xyz|me|tv|co|gs|fun|club|cc|us|eu)\\b");
    private static final Pattern INVITE = Pattern.compile("discord(?:app)?\\.(?:gg|com/invite)/\\S+|dsc\\.gg/\\S+");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d{1,3}[\\s.-]?\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}");

    private static class Rec {
        final long t;
        final String msg;
        Rec(long t, String msg) { this.t = t; this.msg = msg; }
    }

    private static class CD {
        final ArrayDeque<Long> times = new ArrayDeque<>();
        final ArrayDeque<Rec> recent = new ArrayDeque<>();
    }

    private final SusAC plugin;
    private final Map<UUID, CD> data = new HashMap<>();

    ChatChecks(SusAC plugin) {
        this.plugin = plugin;
    }

    private boolean on(String check) {
        return plugin.getConfig().getBoolean("checks." + check + ".enabled", true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String raw = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> process(p, raw));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private boolean containsAny(String padded, List<String> words) {
        for (String w : words) {
            String x = w.toLowerCase(Locale.ROOT).trim();
            if (!x.isEmpty() && padded.contains(" " + x + " ")) return true;
        }
        return false;
    }

    private void process(Player p, String raw) {
        if (!p.isOnline() || p.hasPermission("sus.bypass")) return;
        long now = System.currentTimeMillis();
        CD d = data.computeIfAbsent(p.getUniqueId(), k -> new CD());
        String lower = raw.toLowerCase(Locale.ROOT);
        String info = shorten(raw);

        // Flooding: too many messages in a short window
        if (on("chatflood")) {
            long window = plugin.getConfig().getLong("checks.chatflood.window-ms", 5000);
            d.times.addLast(now);
            while (!d.times.isEmpty() && now - d.times.peekFirst() > window) d.times.pollFirst();
            int max = plugin.getConfig().getInt("checks.chatflood.max-messages", 5);
            if (d.times.size() > max) {
                plugin.flag(p, Category.CHAT, "ChatFlood", d.times.size() + " messages in " + (window / 1000) + "s");
                d.times.clear();
            }
        }

        // Spam: the same message again and again
        if (on("chatspam")) {
            String norm = lower.replaceAll("[^a-z0-9]", "");
            if (!norm.isEmpty()) {
                d.recent.addLast(new Rec(now, norm));
                while (!d.recent.isEmpty() && now - d.recent.peekFirst().t > 30000) d.recent.pollFirst();
                int same = 0;
                for (Rec r : d.recent) if (r.msg.equals(norm)) same++;
                if (same >= plugin.getConfig().getInt("checks.chatspam.repeats", 3)) {
                    plugin.flag(p, Category.CHAT, "ChatSpam", "repeated x" + same + ": " + info);
                    d.recent.clear();
                }
            }
        }

        // Caps lock
        if (on("chatcaps")) {
            int letters = 0, upper = 0;
            for (char c : raw.toCharArray()) {
                if (Character.isLetter(c)) {
                    letters++;
                    if (Character.isUpperCase(c)) upper++;
                }
            }
            if (letters >= plugin.getConfig().getInt("checks.chatcaps.min-letters", 10)
                    && upper / (double) letters >= plugin.getConfig().getDouble("checks.chatcaps.ratio", 0.7)) {
                plugin.flag(p, Category.CHAT, "ChatCaps", info);
            }
        }

        // Advertising
        if (on("advertising")) {
            boolean whitelisted = false;
            for (String w : plugin.getConfig().getStringList("chat.advertising-whitelist")) {
                if (!w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) whitelisted = true;
            }
            if (!whitelisted && (IP.matcher(lower).find() || DOMAIN.matcher(lower).find() || INVITE.matcher(lower).find())) {
                plugin.flag(p, Category.CHAT, "Advertising", info);
            }
        }

        // Doxing / personal info
        if (on("doxing") && (EMAIL.matcher(lower).find() || PHONE.matcher(lower).find())) {
            plugin.flag(p, Category.CHAT, "Doxing", "possible email/phone number: " + info);
        }

        // Word lists (edit in config.yml). Leet-speak is normalised first.
        String leet = lower.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a')
                .replace('5', 's').replace('@', 'a').replace('$', 's');
        String padded = " " + leet.replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ") + " ";

        if (on("deaththreat") && containsAny(padded, plugin.getConfig().getStringList("chat.threat-phrases"))) {
            plugin.flag(p, Category.CHAT, "DeathThreat", info);
        }
        if (on("hatespeech") && containsAny(padded, plugin.getConfig().getStringList("chat.hate-words"))) {
            plugin.flag(p, Category.CHAT, "HateSpeech", info);
        }
        if (on("profanity") && containsAny(padded, plugin.getConfig().getStringList("chat.profanity-words"))) {
            plugin.flag(p, Category.CHAT, "Profanity", info);
        }
    }
}
