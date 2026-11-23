package dev.redbeastsam.vibesindev.gui;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.model.Track;
import dev.redbeastsam.vibesindev.playlist.MusicPlaylistManager.CustomPlaylist;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class PlaylistsMenu implements Listener {
  private static final String TITLE =
      ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Custom Playlists";
  private static final Material[] DISC_MATERIALS = {
    Material.MUSIC_DISC_CAT,
    Material.MUSIC_DISC_13,
    Material.MUSIC_DISC_BLOCKS,
    Material.MUSIC_DISC_CHIRP,
    Material.MUSIC_DISC_FAR,
    Material.MUSIC_DISC_MALL,
    Material.MUSIC_DISC_MELLOHI,
    Material.MUSIC_DISC_STAL,
    Material.MUSIC_DISC_STRAD,
    Material.MUSIC_DISC_WARD,
    Material.MUSIC_DISC_11,
    Material.MUSIC_DISC_WAIT,
    Material.MUSIC_DISC_OTHERSIDE,
    Material.MUSIC_DISC_5,
    Material.MUSIC_DISC_PIGSTEP,
    Material.MUSIC_DISC_RELIC
  };

  private final VibesInDevPlugin plugin;
  private final Map<UUID, Map<Integer, String>> slotPlaylistMap = new HashMap<>();

  public PlaylistsMenu(VibesInDevPlugin plugin) {
    this.plugin = plugin;
  }

  public void open(Player player) {
    PlaylistsHolder holder = new PlaylistsHolder();
    Inventory inv = Bukkit.createInventory(holder, 54, TITLE);
    holder.inventory = inv;
    populate(inv, player);
    player.openInventory(inv);
  }

  private void populate(Inventory inv, Player player) {
    inv.clear();
    fillBorder(inv);

    Map<String, CustomPlaylist> userPlaylists =
        plugin.playlists().getPlaylists(player.getUniqueId());
    Map<Integer, String> slotMap = new HashMap<>();

    if (userPlaylists.isEmpty()) {
      inv.setItem(
          22,
          item(
              Material.NOTE_BLOCK,
              ChatColor.YELLOW + "" + ChatColor.BOLD + "No Saved Playlists",
              List.of(
                  ChatColor.GRAY + "You haven't saved any custom playlists yet!",
                  "",
                  ChatColor.WHITE + "1. Play or queue some songs",
                  ChatColor.WHITE + "2. Type: " + ChatColor.YELLOW + "/music playlist save <name>",
                  "",
                  ChatColor.GRAY + "They will appear here as clickable music discs!")));
    } else {
      List<CustomPlaylist> sorted = new ArrayList<>(userPlaylists.values());
      sorted.sort(Comparator.comparing(p -> p.name(), String.CASE_INSENSITIVE_ORDER));

      int[] contentSlots = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
      };

      int idx = 0;
      for (CustomPlaylist pl : sorted) {
        if (idx >= contentSlots.length) break;
        int slot = contentSlots[idx];
        slotMap.put(slot, pl.name());

        Material mat = DISC_MATERIALS[idx % DISC_MATERIALS.length];
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Tracks: " + ChatColor.WHITE + pl.tracks().size());
        lore.add(
            ChatColor.GRAY
                + "Total Time: "
                + ChatColor.WHITE
                + plugin.playlists().formatTotalDuration(pl.tracks()));
        lore.add("");
        lore.add(ChatColor.YELLOW + "Preview:");
        int previewLimit = Math.min(3, pl.tracks().size());
        for (int i = 0; i < previewLimit; i++) {
          Track t = pl.tracks().get(i);
          String title = t.title().length() > 25 ? t.title().substring(0, 22) + "..." : t.title();
          lore.add(ChatColor.DARK_GRAY + " " + (i + 1) + ". " + ChatColor.WHITE + title);
        }
        if (pl.tracks().size() > 3) {
          lore.add(ChatColor.DARK_GRAY + " ... and " + (pl.tracks().size() - 3) + " more");
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "▶ Left-Click to Play");
        lore.add(ChatColor.RED + "✖ Right-Click to Delete");

        inv.setItem(slot, item(mat, ChatColor.GOLD + "" + ChatColor.BOLD + pl.name(), lore));
        idx++;
      }
    }

    slotPlaylistMap.put(player.getUniqueId(), slotMap);

    // Control buttons on bottom row
    inv.setItem(
        48,
        item(
            Material.WRITABLE_BOOK,
            ChatColor.YELLOW + "" + ChatColor.BOLD + "Save Current Queue",
            List.of(
                ChatColor.GRAY + "Save your active songs into a new playlist.",
                "",
                ChatColor.WHITE + "Type: " + ChatColor.YELLOW + "/music playlist save <name>")));

    inv.setItem(
        49,
        item(
            Material.ARROW,
            ChatColor.RED + "◀ Back to Music Menu",
            List.of(ChatColor.GRAY + "Return to main menu")));

    inv.setItem(
        50,
        item(
            Material.BOOK,
            ChatColor.AQUA + "" + ChatColor.BOLD + "Playlist Commands",
            List.of(
                ChatColor.WHITE + "/music playlist save <name>",
                ChatColor.WHITE + "/music playlist play <name>",
                ChatColor.WHITE + "/music playlist list",
                ChatColor.WHITE + "/music playlist delete <name>")));
  }

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;

    Inventory top = event.getView().getTopInventory();
    if (!(top.getHolder() instanceof PlaylistsHolder)) return;

    event.setCancelled(true);
    int slot = event.getRawSlot();
    if (slot >= top.getSize() || slot < 0) return;

    if (slot == 48) {
      player.closeInventory();
      player.sendMessage(
          ChatColor.GOLD
              + "[VibesInDev] "
              + ChatColor.YELLOW
              + "To save your current songs, type: "
              + ChatColor.WHITE
              + "/music playlist save <name>");
      return;
    }

    if (slot == 49) {
      plugin.musicMenu().open(player);
      return;
    }

    Map<Integer, String> slotMap = slotPlaylistMap.get(player.getUniqueId());
    if (slotMap != null && slotMap.containsKey(slot)) {
      String playlistName = slotMap.get(slot);
      CustomPlaylist pl = plugin.playlists().getPlaylist(player.getUniqueId(), playlistName);
      if (pl == null) return;

      if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
        // Delete playlist
        plugin.playlists().deletePlaylist(player.getUniqueId(), playlistName);
        player.sendMessage(ChatColor.RED + "Deleted playlist: " + ChatColor.GOLD + playlistName);
        Bukkit.getScheduler().runTask(plugin, () -> populate(top, player));
      } else {
        // Play playlist
        player.closeInventory();
        plugin.playback().playCustomPlaylist(player, pl.name(), pl.tracks());
      }
    }
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof PlaylistsHolder) {
      event.setCancelled(true);
    }
  }

  private void fillBorder(Inventory inv) {
    ItemStack border = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    for (int i = 0; i < 9; i++) inv.setItem(i, border);
    for (int i = 45; i < 54; i++) inv.setItem(i, border);
    for (int i = 9; i < 45; i += 9) {
      inv.setItem(i, border);
      inv.setItem(i + 8, border);
    }
  }

  private ItemStack item(Material mat, String name, List<String> lore) {
    ItemStack stack = new ItemStack(mat);
    ItemMeta meta = stack.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(name);
      meta.setLore(lore);
      meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
      stack.setItemMeta(meta);
    }
    return stack;
  }

  private static final class PlaylistsHolder implements InventoryHolder {
    private Inventory inventory;

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }
}
