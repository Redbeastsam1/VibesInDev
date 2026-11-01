package dev.vibesindev.vibesmusic.spotify;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Handles zero-port-forwarding Spotify authentication using a Book & Quill.
 * <p>
 * Standard Minecraft chat has a 256-character limit, but Spotify authorization URLs
 * and codes are often 280-350+ characters long. A Book & Quill page supports up to
 * 1024 characters, allowing players to paste the full URL without truncation.
 */
@SuppressWarnings("deprecation")
public final class SpotifyAuthBookListener implements Listener {

    public static final String BOOK_TITLE = ChatColor.GREEN + "" + ChatColor.BOLD + "Spotify Auth Book";

    private final VibesInDevPlugin plugin;

    public SpotifyAuthBookListener(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates a pre-labeled writable book for pasting Spotify authorization URLs.
     */
    public static ItemStack createAuthBook() {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta rawMeta = book.getItemMeta();
        if (rawMeta instanceof BookMeta meta) {
            meta.setDisplayName(BOOK_TITLE);
            meta.setLore(List.of(
                    ChatColor.YELLOW + "Paste your Spotify browser URL here!",
                    ChatColor.GRAY + "1. Press Ctrl+V to paste the URL/code.",
                    ChatColor.GRAY + "2. Click 'Done' to connect your account."
            ));
            book.setItemMeta(meta);
        }
        return book;
    }

    /**
     * Gives an auth book to the player and displays instructions.
     */
    public static void giveAuthBook(Player player) {
        ItemStack book = createAuthBook();
        if (player.getInventory().getItemInMainHand().getType() == Material.AIR) {
            player.getInventory().setItemInMainHand(book);
        } else {
            player.getInventory().addItem(book);
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "=== Spotify Auth Book ===");
        player.sendMessage(ChatColor.WHITE + "A " + BOOK_TITLE + ChatColor.WHITE + " has been placed in your inventory!");
        player.sendMessage(ChatColor.GRAY + "1. Hold the book and right-click to open it.");
        player.sendMessage(ChatColor.GRAY + "2. Press " + ChatColor.YELLOW + "Ctrl + V" + ChatColor.GRAY + " to paste your full Spotify URL or code.");
        player.sendMessage(ChatColor.GRAY + "3. Click " + ChatColor.AQUA + "'Done'" + ChatColor.GRAY + " (supports up to 1024 characters!).");
        player.sendMessage("");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();

        // Check if the item is our Spotify Auth Book
        ItemStack foundItem = player.getInventory().getItem(event.getSlot());
        if (foundItem == null || foundItem.getType() != Material.WRITABLE_BOOK) {
            if (player.getInventory().getItemInMainHand().getType() == Material.WRITABLE_BOOK) {
                foundItem = player.getInventory().getItemInMainHand();
            } else if (player.getInventory().getItemInOffHand().getType() == Material.WRITABLE_BOOK) {
                foundItem = player.getInventory().getItemInOffHand();
            } else {
                return;
            }
        }

        final ItemStack bookItem = foundItem;

        ItemMeta currentMeta = bookItem.getItemMeta();
        if (currentMeta == null || !currentMeta.hasDisplayName() || !BOOK_TITLE.equals(currentMeta.getDisplayName())) {
            return;
        }

        BookMeta newMeta = event.getNewBookMeta();
        if (newMeta == null) {
            return;
        }

        List<String> pages = newMeta.getPages();
        if (pages == null || pages.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String page : pages) {
            if (page != null) {
                sb.append(page.trim());
            }
        }

        String content = sb.toString().trim();
        if (content.isBlank()) {
            player.sendMessage(ChatColor.YELLOW + "Auth book was empty. Paste your Spotify redirect URL into the book and click Done.");
            return;
        }

        // Cancel the book edit and remove the book from the player's inventory
        event.setCancelled(true);
        final int slot = event.getSlot();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.getInventory().getItemInMainHand().isSimilar(bookItem)) {
                player.getInventory().setItemInMainHand(null);
            } else if (player.getInventory().getItemInOffHand().isSimilar(bookItem)) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItem(slot, null);
            }
            player.updateInventory();
        });

        player.sendMessage(ChatColor.GRAY + "Exchanging authorization code from Auth Book with Spotify...");

        if (plugin.spotify() != null) {
            plugin.spotify().exchangeCodeForPlayer(player, content)
                    .thenAccept(msg -> player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE + msg))
                    .exceptionally(err -> {
                        player.sendMessage(ChatColor.RED + "✖ Spotify authentication failed: " + err.getMessage());
                        player.sendMessage(ChatColor.YELLOW + "💡 Make sure you copied the complete URL from your browser address bar.");
                        return null;
                    });
        }
    }
}
