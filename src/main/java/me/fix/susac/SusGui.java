package me.fix.susac;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class SusGui implements Listener {

    private static final int[] CAT_SLOTS = {11, 12, 13, 14, 15};

    private final SusAC plugin;

    SusGui(SusAC plugin) {
        this.plugin = plugin;
    }

    static class Holder implements InventoryHolder {
        final Category cat; // null = main menu
        final int page;
        final List<UUID> entries = new ArrayList<>();
        Inventory inv;

        Holder(Category cat, int page) {
            this.cat = cat;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static Component line(String s) {
        return SusAC.color(s).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(line(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(line(s));
        im.lore(l);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    private void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    // ---------- main menu ----------
    public void openMain(Player p) {
        Holder h = new Holder(null, 0);
        Inventory inv = Bukkit.createInventory(h, 27, SusAC.color("&8SUS - Flagged Players"));
        h.inv = inv;
        fill(inv);
        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            Category c = cats[i];
            int players = 0, total = 0;
            for (Map<String, SusAC.Flag> m : plugin.allFlags().values()) {
                boolean any = false;
                for (SusAC.Flag f : m.values()) {
                    if (f.cat == c) { any = true; total += f.total; }
                }
                if (any) players++;
            }
            inv.setItem(CAT_SLOTS[i], item(c.icon, c.color + "&l" + c.display,
                    "&7Flagged players: &f" + players,
                    "&7Total flags: &f" + total,
                    "",
                    "&eClick to view"));
        }
        inv.setItem(22, item(Material.BARRIER, "&cClose"));
        boolean alertsOn = plugin.alertsOn(p.getUniqueId());
        inv.setItem(26, alertsOn
                ? item(Material.BELL, "&aFlag alerts: ON", "&7Click to turn off chat alerts")
                : item(Material.GRAY_DYE, "&cFlag alerts: OFF", "&7Click to turn on chat alerts"));
        p.openInventory(inv);
    }

    // ---------- category menu ----------
    private long lastIn(UUID id, Category cat) {
        long best = 0;
        for (SusAC.Flag f : plugin.allFlags().get(id).values()) {
            if (f.cat == cat) best = Math.max(best, f.last);
        }
        return best;
    }

    public void openCategory(Player p, Category cat, int page) {
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, SusAC.Flag>> en : plugin.allFlags().entrySet()) {
            for (SusAC.Flag f : en.getValue().values()) {
                if (f.cat == cat) { ids.add(en.getKey()); break; }
            }
        }
        ids.sort((a, b) -> Long.compare(lastIn(b, cat), lastIn(a, cat)));
        int pages = Math.max(1, (ids.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));

        Holder h = new Holder(cat, page);
        Inventory inv = Bukkit.createInventory(h, 54, SusAC.color("&8SUS - " + cat.display + " (" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= ids.size()) break;
            UUID id = ids.get(idx);
            h.entries.add(id);
            inv.setItem(i, head(id, cat));
        }
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(45, item(Material.ARROW, "&ePrevious page"));
        inv.setItem(49, item(Material.BARRIER, "&cBack"));
        if (page < pages - 1) inv.setItem(53, item(Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    private String topCheck(UUID id, Category cat) {
        String best = null;
        int bestTotal = -1;
        for (Map.Entry<String, SusAC.Flag> en : plugin.allFlags().get(id).entrySet()) {
            if (en.getValue().cat == cat && en.getValue().total > bestTotal) {
                best = en.getKey();
                bestTotal = en.getValue().total;
            }
        }
        return best;
    }

    private ItemStack head(UUID id, Category cat) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) it.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        m.setOwningPlayer(op);
        boolean online = Bukkit.getPlayer(id) != null;
        m.displayName(line((online ? "&a" : "&7") + plugin.nameOf(id)));

        List<Component> lore = new ArrayList<>();
        lore.add(line(online ? "&aOnline" : "&7Offline"));
        lore.add(line(""));
        List<Map.Entry<String, SusAC.Flag>> list = new ArrayList<>(plugin.allFlags().get(id).entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().last, a.getValue().last));
        int shown = 0;
        for (Map.Entry<String, SusAC.Flag> en : list) {
            SusAC.Flag f = en.getValue();
            if (f.cat != cat) continue;
            if (shown++ >= 8) break;
            lore.add(line("&c" + en.getKey() + " &7x&f" + f.total + " &8(" + SusAC.ago(f.last) + ")"));
            lore.add(line("  &8" + f.info));
        }
        String top = topCheck(id, cat);
        if (top != null) {
            lore.add(line(""));
            lore.add(line("&7Suggested offense: &f" + Offenses.get(top)[1]));
        }
        lore.add(line(""));
        lore.add(line("&eLeft-click: &7teleport to player"));
        lore.add(line("&eShift-click: &7suggest /offend"));
        lore.add(line("&eRight-click: &7clear these flags"));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    // ---------- clicks ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (h.cat == null) {
            Category[] cats = Category.values();
            for (int i = 0; i < cats.length; i++) {
                if (slot == CAT_SLOTS[i]) openCategory(p, cats[i], 0);
            }
            if (slot == 22) p.closeInventory();
            if (slot == 26) {
                plugin.toggleAlerts(p);
                openMain(p);
            }
            return;
        }

        if (slot == 49) { openMain(p); return; }
        if (slot == 45 && h.page > 0) { openCategory(p, h.cat, h.page - 1); return; }
        if (slot == 53) { openCategory(p, h.cat, h.page + 1); return; }
        if (slot < 0 || slot >= h.entries.size()) return;

        UUID id = h.entries.get(slot);
        if (e.isShiftClick() && e.isLeftClick()) {
            String top = topCheck(id, h.cat);
            if (top == null) return;
            String[] off = Offenses.get(top);
            String cmd = "/offend " + plugin.nameOf(id) + " " + off[0] + " ";
            p.closeInventory();
            p.sendMessage(SusAC.color("&c[SUS] &7Click to punish for &f" + off[1] + "&7: ")
                    .append(SusAC.color("&e" + cmd).clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(cmd))));
            return;
        }
        if (e.isRightClick()) {
            if (!p.hasPermission("sus.admin")) {
                p.sendMessage(SusAC.color("&cNo permission."));
                return;
            }
            plugin.clear(id, h.cat);
            openCategory(p, h.cat, h.page);
        } else {
            Player t = Bukkit.getPlayer(id);
            if (t == null) {
                p.sendMessage(SusAC.color("&cThat player is offline."));
                return;
            }
            p.closeInventory();
            p.teleport(t.getLocation());
            p.sendMessage(SusAC.color("&aTeleported to &f" + t.getName() + "&a."));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
