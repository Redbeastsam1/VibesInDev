package dev.vibesindev.vibesmusic.gui;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
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

import java.util.List;

/**
 * Playlists menu — opens from the main music GUI (slot 26).
 *
 * Because the plugin does not have a persistent playlist store,
 * this menu acts as a quick-action launcher for common queue operations
 * and gives the player prompts to run the underlying commands.
 *
 * Layout (3×9 chest = 27 slots):
 *
 *   Slot  4 — Header
 *   Slot 10 — Play a song / URL now       (MUSIC_DISC_13)
 *   Slot 12 — Add to queue                (CHEST)
 *   Slot 14 — Clear queue                 (LAVA_BUCKET)
 *   Slot 16 — Stop all music              (BARRIER)
 *   Slot 22 — Repeat mode cycle           (REPEATER)
 *   Slot 20 — Shuffle toggle              (COMPARATOR)
 *   Slot 24 — Volume info                 (NOTE_BLOCK)
 *   Slot 26 — Back                        (ARROW)
 */
public final class PlaylistsMenu implements Listener {

    private static final String TITLE = ChatColor.LIGHT_PURPLE + "Quick Actions";

    private final VibesInDevPlugin plugin;

    public PlaylistsMenu(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================
    // OPEN
    // =========================================================

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new PlaylistsHolder(), 27, TITLE);
        fill(inv);
        populate(inv, player);
        player.openInventory(inv);
    }

    private void populate(Inventory inv, Player player) {
        RepeatMode repeat = plugin.queues().getRepeatMode(player.getUniqueId());
        boolean shuffle   = plugin.queues().isShuffle(player.getUniqueId());
        float vol = plugin.network().getVolume(player);

        // Header
        inv.setItem(4, item(Material.BOOK,
                ChatColor.LIGHT_PURPLE + "Quick Actions",
                List.of(
                        ChatColor.GRAY + "Use these shortcuts to control",
                        ChatColor.GRAY + "playback and the queue."
                )));

        // Play now
        inv.setItem(10, item(Material.MUSIC_DISC_13,
                ChatColor.GREEN + "▶ Play Song",
                List.of(
                        ChatColor.GRAY + "Close this menu and type:",
                        ChatColor.WHITE + "/music play <song name or URL>",
                        "",
                        ChatColor.GRAY + "Supports YouTube, Spotify & SoundCloud."
                )));

        // Add to queue
        inv.setItem(12, item(Material.CHEST,
                ChatColor.GOLD + "+ Add to Queue",
                List.of(
                        ChatColor.GRAY + "Close this menu and type:",
                        ChatColor.WHITE + "/music queue add <song name or URL>"
                )));

        // Clear queue
        inv.setItem(14, item(Material.LAVA_BUCKET,
                ChatColor.RED + "✖ Clear Queue",
                List.of(ChatColor.GRAY + "Remove all upcoming tracks.",
                        ChatColor.RED + "Click to clear now.")));

        // Stop music
        inv.setItem(16, item(Material.BARRIER,
                ChatColor.RED + "■ Stop Music",
                List.of(ChatColor.GRAY + "Immediately stop all playback.")));

        // Shuffle
        inv.setItem(20, item(shuffle ? Material.COMPARATOR : Material.CLOCK,
                (shuffle ? ChatColor.GREEN : ChatColor.GRAY) + "Shuffle: " + (shuffle ? "ON" : "OFF"),
                List.of(ChatColor.GRAY + "Click to toggle queue shuffle")));

        // Repeat
        ChatColor repeatColor = switch (repeat) {
            case OFF   -> ChatColor.GRAY;
            case TRACK -> ChatColor.YELLOW;
            case QUEUE -> ChatColor.GREEN;
        };
        inv.setItem(22, item(Material.REPEATER,
                repeatColor + "Repeat: " + repeat.name(),
                List.of(ChatColor.GRAY + "Click to cycle: OFF → TRACK → QUEUE")));

        // Volume display
        int pct = Math.round(vol * 100f);
        Material volMat = (pct >= 75) ? Material.JUKEBOX
                : (pct >= 40) ? Material.NOTE_BLOCK
                : Material.DEAD_BUSH;
        inv.setItem(24, item(volMat,
                ChatColor.YELLOW + "Volume: " + pct + "%",
                List.of(
                        ChatColor.GRAY + "Use Volume +/- in the main menu,",
                        ChatColor.GRAY + "or: " + ChatColor.WHITE + "/music volume <0-100>"
                )));

        // Back
        inv.setItem(26, item(Material.ARROW,
                ChatColor.RED + "← Back",
                List.of(ChatColor.GRAY + "Return to main menu")));
    }

    // =========================================================
    // CLICK
    // =========================================================

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PlaylistsHolder)) return;

        event.setCancelled(true);
        if (event.getRawSlot() >= top.getSize()) return;

        switch (event.getRawSlot()) {
            case 10 -> {
                player.closeInventory();
                player.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + "/music play <song or URL>");
            }
            case 12 -> {
                player.closeInventory();
                player.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + "/music queue add <song or URL>");
            }
            case 14 -> {
                plugin.queues().clearQueue(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "Queue cleared.");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    fill(top);
                    populate(top, player);
                });
            }
            case 16 -> {
                plugin.playback().stop(player);
                player.sendMessage(ChatColor.RED + "■ Music stopped.");
                player.closeInventory();
            }
            case 20 -> {
                boolean was = plugin.queues().isShuffle(player.getUniqueId());
                plugin.queues().setShuffle(player.getUniqueId(), !was);
                player.sendMessage(ChatColor.GREEN + "Shuffle: " + (!was ? "ON" : "OFF"));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    fill(top);
                    populate(top, player);
                });
            }
            case 22 -> {
                RepeatMode cur  = plugin.queues().getRepeatMode(player.getUniqueId());
                RepeatMode next = switch (cur) {
                    case OFF   -> RepeatMode.TRACK;
                    case TRACK -> RepeatMode.QUEUE;
                    case QUEUE -> RepeatMode.OFF;
                };
                plugin.queues().setRepeatMode(player.getUniqueId(), next);
                player.sendMessage(ChatColor.GREEN + "Repeat mode: " + next.name());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    fill(top);
                    populate(top, player);
                });
            }
            case 26 -> plugin.musicMenu().open(player);
            default -> { /* filler */ }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PlaylistsHolder) {
            event.setCancelled(true);
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

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

    private static final class PlaylistsHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
