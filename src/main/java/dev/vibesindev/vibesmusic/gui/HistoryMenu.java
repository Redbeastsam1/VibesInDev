package dev.vibesindev.vibesmusic.gui;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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

import java.util.ArrayList;
import java.util.List;

/**
 * History GUI - shows the last N songs the player has listened to.
 * Click a track to replay it immediately, or shift-click to add to queue.
 * Persisted across server restarts via the SQLite player_history table.
 *
 * Layout: 6-row chest (54 slots)
 *   Rows 0-4 (slots 0-44): up to 5x9 = 45 track entries (paginated in groups of 36, slots 9-44)
 *   Row 0 (slots 0-8):  header/summary bar
 *   Row 5 (slots 45-53): navigation + controls
 */
public final class HistoryMenu implements Listener {

    private static final int PAGE_SIZE = 36; // slots 9-44
    private static final String TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + " Play History";

    private final VibesInDevPlugin plugin;

    public HistoryMenu(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        List<Track> history = plugin.queues().history(player.getUniqueId());

        HistoryHolder holder = new HistoryHolder(page);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE);

        fill(inv);
        populate(inv, player, history, page);

        player.openInventory(inv);
    }

    // -----------------------------------------------------------------------
    // Populate
    // -----------------------------------------------------------------------

    private void populate(Inventory inv, Player player, List<Track> history, int page) {
        // --- Header slot 4: summary ---
        List<String> summaryLore = new ArrayList<>();
        summaryLore.add(ChatColor.GRAY + "Total tracks: " + ChatColor.WHITE + history.size());
        summaryLore.add(ChatColor.GRAY + "Page: " + ChatColor.WHITE + (page + 1));
        summaryLore.add("");
        summaryLore.add(ChatColor.YELLOW + "Click a track to replay it");
        summaryLore.add(ChatColor.AQUA + "Shift-click to add to queue");
        inv.setItem(4, item(Material.BOOK,
                ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Play History",
                summaryLore));

        if (history.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER,
                    ChatColor.GRAY + "No history yet",
                    List.of(ChatColor.DARK_GRAY + "Play some songs first!")));
        }

        // --- Track slots 9-44 ---
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, history.size());

        for (int i = start; i < end; i++) {
            Track t    = history.get(i);
            int slot   = 9 + (i - start);
            int pos    = i + 1;

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Artist: " + ChatColor.WHITE + t.artist());
            lore.add(ChatColor.GRAY + "Duration: " + ChatColor.WHITE + t.durationText());
            lore.add("");
            lore.add(ChatColor.GREEN + "Click to replay");
            lore.add(ChatColor.AQUA + "Shift-click to add to queue");

            Material disc = discForPosition(pos);
            inv.setItem(slot, item(disc,
                    ChatColor.WHITE + String.valueOf(pos) + ". " + t.title(),
                    lore));
        }

        // --- Bottom bar ---
        // Slot 45: clear history
        inv.setItem(45, item(Material.LAVA_BUCKET,
                ChatColor.RED + "Clear History",
                List.of(ChatColor.GRAY + "Remove your entire play history")));

        // Slot 46: previous page
        if (page > 0) {
            inv.setItem(46, item(Material.ARROW,
                    ChatColor.YELLOW + "<< Previous Page",
                    List.of(ChatColor.GRAY + "Page " + page)));
        }

        // Slot 52: next page
        if (end < history.size()) {
            inv.setItem(52, item(Material.ARROW,
                    ChatColor.YELLOW + "Next Page >>",
                    List.of(ChatColor.GRAY + "Page " + (page + 2))));
        }

        // Slot 49: play all (re-queue entire history)
        inv.setItem(49, item(Material.JUKEBOX,
                ChatColor.GREEN + "Play All",
                List.of(ChatColor.GRAY + "Add all history tracks to your queue")));

        // Slot 53: back
        inv.setItem(53, item(Material.BARRIER,
                ChatColor.RED + "Back",
                List.of(ChatColor.GRAY + "Return to main menu")));
    }

    // -----------------------------------------------------------------------
    // Click handler
    // -----------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof HistoryHolder hh)) return;

        event.setCancelled(true);
        if (event.getRawSlot() >= top.getSize()) return;

        int slot = event.getRawSlot();
        int page = hh.page();
        List<Track> history = plugin.queues().history(player.getUniqueId());
        boolean shift = event.isShiftClick();

        // --- Track slots 9-44 ---
        if (slot >= 9 && slot <= 44) {
            int index = page * PAGE_SIZE + (slot - 9);
            if (index < history.size()) {
                Track t = history.get(index);
                if (shift) {
                    // Add to queue
                    try {
                        plugin.queues().enqueue(player.getUniqueId(), t);
                        player.sendMessage(ChatColor.GREEN + "Added to queue: " + ChatColor.WHITE + t.title());
                    } catch (IllegalStateException e) {
                        player.sendMessage(ChatColor.RED + "Queue is full.");
                    }
                } else {
                    // Replay immediately
                    player.sendMessage(ChatColor.AQUA + "Replaying: " + ChatColor.WHITE + t.title());
                    plugin.playback().playInput(player, t.sourceUrl());
                }
            }
            refreshLater(player, page);
            return;
        }

        switch (slot) {
            case 45 -> {
                plugin.queues().clearHistory(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "Play history cleared.");
                refreshLater(player, 0);
            }
            case 46 -> {
                if (page > 0) reopenLater(player, page - 1);
            }
            case 49 -> {
                // Queue all history tracks
                int queued = 0;
                for (Track t : history) {
                    try {
                        plugin.queues().enqueue(player.getUniqueId(), t);
                        queued++;
                    } catch (IllegalStateException e) {
                        break; // queue full
                    }
                }
                player.sendMessage(ChatColor.GREEN + "Added " + queued + " tracks from history to queue.");
                refreshLater(player, page);
            }
            case 52 -> {
                if ((page + 1) * PAGE_SIZE < history.size()) reopenLater(player, page + 1);
            }
            case 53 -> plugin.musicMenu().open(player);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HistoryHolder) {
            event.setCancelled(true);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void refreshLater(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(player, page));
    }

    private void reopenLater(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(player, page));
    }

    private Material discForPosition(int pos) {
        Material[] discs = {
                Material.MUSIC_DISC_13,   Material.MUSIC_DISC_CAT,
                Material.MUSIC_DISC_BLOCKS, Material.MUSIC_DISC_CHIRP,
                Material.MUSIC_DISC_FAR,  Material.MUSIC_DISC_MALL,
                Material.MUSIC_DISC_MELLOHI, Material.MUSIC_DISC_STAL,
                Material.MUSIC_DISC_STRAD, Material.MUSIC_DISC_WARD,
                Material.MUSIC_DISC_11,   Material.MUSIC_DISC_WAIT
        };
        return discs[(pos - 1) % discs.length];
    }

    private void fill(Inventory inv) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 9; i++) inv.setItem(i, filler);            // header row
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);          // footer row
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // -----------------------------------------------------------------------
    // Holder
    // -----------------------------------------------------------------------

    private record HistoryHolder(int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}