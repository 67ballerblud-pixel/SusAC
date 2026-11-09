package me.fix.susac;

import java.util.HashMap;
import java.util.Map;

/** Maps each check to an offense id from UltimateDonutSmp's offenses.yml (used by /offend). */
public final class Offenses {
    private static final Map<String, String[]> MAP = new HashMap<>();

    private static void put(String check, String id, String name) {
        MAP.put(check, new String[]{id, name});
    }

    static {
        for (String c : new String[]{"Speed", "Fly", "Reach", "WallHit", "MultiAura", "Nuker", "BreakReach",
                "FastPlace", "FastEat", "OmniSprint"}) {
            put(c, "cheating", "Cheating / Unfair Advantage");
        }
        put("AutoClicker", "macros", "Macros");
        put("MacroClick", "macros", "Macros");
        put("AutoWalk", "baritone", "Baritone / Automation");
        put("XRay", "xray-esp-baritone", "X-Ray / ESP / Baritone");
        put("WallTrack", "esp", "ESP Hacks");
        put("AltLimit", "alt-limit", "Alt Limit Violation");
        put("ChatSpam", "chat-spam", "Chat Spam");
        put("ChatFlood", "chat-flooding", "Chat Flooding");
        put("ChatCaps", "chat-capslock", "Chat Caps Lock Spam");
        put("Advertising", "advertising", "Advertising");
        put("Doxing", "doxing", "Doxing / PII Leak");
        put("DeathThreat", "chat-death-threats", "Chat Death Threats");
        put("HateSpeech", "chat-hate", "Chat Hate Speech");
        put("Profanity", "inappropriate-language", "Inappropriate Language");
    }

    private Offenses() {}

    public static String[] get(String check) {
        return MAP.getOrDefault(check, new String[]{"anticheat", "Anticheat Detection"});
    }
}
