package me.fix.susac;

import org.bukkit.Material;

public enum Category {
    MOVEMENT("Movement", "&b", Material.DIAMOND_BOOTS),
    COMBAT("Combat", "&c", Material.DIAMOND_SWORD),
    WORLD("World", "&a", Material.DIAMOND_PICKAXE),
    PLAYER("Player", "&e", Material.PLAYER_HEAD),
    CHAT("Chat", "&d", Material.WRITABLE_BOOK);

    public final String display;
    public final String color;
    public final Material icon;

    Category(String display, String color, Material icon) {
        this.display = display;
        this.color = color;
        this.icon = icon;
    }
}
