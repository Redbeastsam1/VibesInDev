package dev.vibesindev.vibesmusic.gui;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.queue.RepeatMode;
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
import dev.vibesindev.vibesmusic.party.MusicParty;

/**
 * Queue browser sub-menu — opens from the main music GUI (slot 21).
 *
 * Layout (6×9 chest = 54 slots):
 *
 *   Slot  4 — Queue info (CHEST): size, repeat, shuffle
 *   Slots 9-44 (up to 27 tracks) — one track per slot, click removes it
 *   Slot 45 — Clear queue (LAVA_BUCKET)
 *   Slot 48 — Shuffle toggle (COMPARATOR)
 *   Slot 49 — Repeat cycle (REPEATER)
 *   Slot 53 — Back (BARRIER)
 *
 *   Page navigation:
 *   Slot 46 — Prev page (ARROW)  — only if page > 0
 *   Slot 52 — Next page (ARROW)  — only if more tracks remain
 */
public final class QueueMenu implements Listener {

    private static final String TITLE    = ChatColor.GOLD + "Music Queue";
    private static final int    PAGE_SIZE = 36; // slots 9-44

    private final VibesInDevPlugin plugin;

    public QueueMenu(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================
    // OPEN
    // =========================================================

    public void open(Player player) {
        open(player, 0);
    }

    private void open(Player player, int page) {
        Inventory inv = Bukkit.createInventory(
                new QueueHolder(page), 54, TITLE + (page > 0 ? " (" + (page + 1) + ")" : ""));
        fill(inv);
        populate(inv, player, page);
        player.openInventory(inv);
    }

    private void populate(Inventory inv, Player player, int page) {
        MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());

        List<Track> queue;
        Track current;
        String repeatName;
        boolean shuffle;

        if (party != null) {
            queue = new ArrayList<>(party.getQueue());
            current = party.getCurrentTrack();
            repeatName = party.getRepeatMode().name();
            shuffle = party.isShuffle();
        } else {
            queue = plugin.queues().list(player.getUniqueId());
            current = plugin.queues().current(player.getUniqueId());
            repeatName = plugin.queues().getRepeatMode(player.getUniqueId()).name();
            shuffle = plugin.queues().isShuffle(player.getUniqueId());
        }

        // --- Slot 4: queue summary ---
        List<String> summaryLore = new ArrayList<>();
        if (party != null) {
            summaryLore.add(ChatColor.LIGHT_PURPLE + " Party Queue Active");
        }
        summaryLore.add(ChatColor.GRAY + "Current: " + ChatColor.WHITE
                + (current != null ? current.title() : "None"));
        summaryLore.add(ChatColor.GRAY + "Queued: " + ChatColor.WHITE + queue.size() + " tracks");
        summaryLore.add(ChatColor.GRAY + "Repeat: " + repeatColor(repeatName) + repeatName);
        summaryLore.add(ChatColor.GRAY + "Shuffle: " + (shuffle ? ChatColor.GREEN + "ON" : ChatColor.GRAY + "OFF"));

        inv.setItem(4, item(Material.CHEST, ChatColor.GOLD + "Queue Info", summaryLore));

        // --- Slots 9-44: track list ---
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, queue.size());

        for (int i = start; i < end; i++) {
            Track t      = queue.get(i);
            int    slot  = 9 + (i - start);
            int    pos   = i + 1;

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Artist: " + ChatColor.WHITE + t.artist());
            lore.add(ChatColor.GRAY + "Duration: " + ChatColor.WHITE + t.durationText());
            lore.add("");
            lore.add(ChatColor.RED + "Click to remove from queue");

            Material disc = discForPosition(pos);
            inv.setItem(slot, item(disc, ChatColor.WHITE.toString() + pos + ". " + t.title(), lore));
        }

        // --- Bottom bar ---
        // Slot 45: clear queue
        inv.setItem(45, item(Material.LAVA_BUCKET,
                ChatColor.RED + "✖ Clear Queue",
                List.of(ChatColor.GRAY + "Remove all tracks from the queue")));

        // Slot 47: history
        inv.setItem(47, item(Material.BOOK,
                ChatColor.LIGHT_PURPLE + "History",
                List.of(ChatColor.GRAY + "View your previously played tracks")));

        // Slot 48: shuffle
        inv.setItem(48, item(shuffle ? Material.COMPARATOR : Material.CLOCK,
                (shuffle ? ChatColor.GREEN : ChatColor.GRAY) + "Shuffle: " + (shuffle ? "ON" : "OFF"),
                List.of(ChatColor.GRAY + "Toggle queue shuffle")));

        // Slot 49: repeat
        inv.setItem(49, item(Material.REPEATER,
                repeatColor(repeatName) + "Repeat: " + repeatName,
                List.of(ChatColor.GRAY + "Click to cycle: OFF → TRACK → QUEUE")));

        // Slot 46: prev page
        if (page > 0) {
            inv.setItem(46, item(Material.ARROW,
                    ChatColor.YELLOW + "◀ Previous Page", List.of(ChatColor.GRAY + "Page " + page)));
        }

        // Slot 52: next page
        if (end < queue.size()) {
            inv.setItem(52, item(Material.ARROW,
                    ChatColor.YELLOW + "Next Page ▶", List.of(ChatColor.GRAY + "Page " + (page + 2))));
        }

        // Slot 53: back
        inv.setItem(53, item(Material.BARRIER,
                ChatColor.RED + "← Back", List.of(ChatColor.GRAY + "Return to main menu")));
    }

    // =========================================================
    // CLICK
    // =========================================================

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof QueueHolder qh)) return;

        event.setCancelled(true);
        if (event.getRawSlot() >= top.getSize()) return;

        int slot = event.getRawSlot();
        int page = qh.page();

        MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());

        // --- Track slots 9-44 ---
        if (slot >= 9 && slot <= 44) {
            if (party != null && !party.isOwner(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Only the party owner can modify the queue.");
                return;
            }

            int index = page * PAGE_SIZE + (slot - 9);
            List<Track> queue = party != null ? new ArrayList<>(party.getQueue()) : plugin.queues().list(player.getUniqueId());
            if (index < queue.size()) {
                boolean removed = party != null ? party.removeFromQueue(index) : plugin.queues().remove(player.getUniqueId(), index);
                if (removed) {
                    player.sendMessage(ChatColor.GREEN + "Removed track from queue.");
                }
            }
            refreshLater(player, page);
            return;
        }

        switch (slot) {
            case 45 -> {
                if (party != null && !party.isOwner(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Only the party owner can clear the queue.");
                    return;
                }
                if (party != null) {
                    party.clearQueue();
                } else {
                    plugin.queues().clearQueue(player.getUniqueId());
                }
                player.sendMessage(ChatColor.RED + "Queue cleared.");
                refreshLater(player, 0);
            }
            case 46 -> {
                if (page > 0) reopenLater(player, page - 1);
            }
            case 47 -> plugin.historyMenu().open(player);
            case 48 -> {
                if (party != null && !party.isOwner(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Only the party owner can shuffle.");
                    return;
                }
                boolean was = party != null ? party.isShuffle() : plugin.queues().isShuffle(player.getUniqueId());
                if (party != null) {
                    party.setShuffle(!was);
                } else {
                    plugin.queues().setShuffle(player.getUniqueId(), !was);
                }
                player.sendMessage(ChatColor.GREEN + "Shuffle: " + (!was ? "ON" : "OFF"));
                refreshLater(player, page);
            }
            case 49 -> {
                if (party != null && !party.isOwner(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Only the party owner can change repeat mode.");
                    return;
                }
                
                String nextName;
                if (party != null) {
                    MusicParty.RepeatMode cur = party.getRepeatMode();
                    MusicParty.RepeatMode next = switch (cur) {
                        case OFF   -> MusicParty.RepeatMode.ONE;
                        case ONE   -> MusicParty.RepeatMode.ALL;
                        case ALL   -> MusicParty.RepeatMode.OFF;
                    };
                    party.setRepeatMode(next);
                    nextName = next.name();
                } else {
                    RepeatMode cur  = plugin.queues().getRepeatMode(player.getUniqueId());
                    RepeatMode next = switch (cur) {
                        case OFF   -> RepeatMode.TRACK;
                        case TRACK -> RepeatMode.QUEUE;
                        case QUEUE -> RepeatMode.OFF;
                    };
                    plugin.queues().setRepeatMode(player.getUniqueId(), next);
                    nextName = next.name();
                }
                player.sendMessage(ChatColor.GREEN + "Repeat mode: " + nextName);
                refreshLater(player, page);
            }
            case 52 -> {
                List<Track> queue = party != null ? new ArrayList<>(party.getQueue()) : plugin.queues().list(player.getUniqueId());
                if ((page + 1) * PAGE_SIZE < queue.size()) reopenLater(player, page + 1);
            }
            case 53 -> plugin.musicMenu().open(player);
            default -> { /* filler */ }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof QueueHolder) {
            event.setCancelled(true);
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private void refreshLater(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(player, page));
    }

    private void reopenLater(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(player, page));
    }

    /** Pick a visually distinct disc per position (cycles through available discs). */
    private Material discForPosition(int pos) {
        Material[] discs = {
                Material.MUSIC_DISC_13,
                Material.MUSIC_DISC_CAT,
                Material.MUSIC_DISC_BLOCKS,
                Material.MUSIC_DISC_CHIRP,
                Material.MUSIC_DISC_FAR,
                Material.MUSIC_DISC_MALL,
                Material.MUSIC_DISC_MELLOHI,
                Material.MUSIC_DISC_STAL,
                Material.MUSIC_DISC_STRAD,
                Material.MUSIC_DISC_WARD,
                Material.MUSIC_DISC_11,
                Material.MUSIC_DISC_WAIT
        };
        return discs[(pos - 1) % discs.length];
    }

    private ChatColor repeatColor(String mode) {
        return switch (mode) {
            case "OFF"   -> ChatColor.GRAY;
            case "TRACK" -> ChatColor.YELLOW;
            case "QUEUE" -> ChatColor.GREEN;
            default -> ChatColor.GREEN;
        };
    }

    private void fill(Inventory inv) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta   = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // =========================================================
    // HOLDER
    // =========================================================

    private record QueueHolder(int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
