package dev.vibesindev.vibesmusic.gui;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.playback.PlaybackSession;
import dev.vibesindev.vibesmusic.playback.PlaybackState;
import dev.vibesindev.vibesmusic.queue.RepeatMode;
import java.util.ArrayList;
import java.util.List;
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

/**
 * "Now Playing" sub-menu — opens from the main music GUI (slot 20).
 *
 * <p>Layout (3×9 chest = 27 slots):
 *
 * <p>Slot 4 — Track info (MUSIC_DISC) Slot 10 — Previous track (ARROW) Slot 13 — Play / Pause
 * (NOTE_BLOCK) Slot 16 — Next track (ARROW) Slot 22 — Repeat mode toggle (REPEATER) Slot 23 —
 * Shuffle toggle (COMPARATOR) Slot 26 — Back (BARRIER)
 */
public final class NowPlayingMenu implements Listener {
  private static final String TITLE = ChatColor.DARK_AQUA + "Now Playing";
  private final VibesInDevPlugin plugin;

  public NowPlayingMenu(VibesInDevPlugin plugin) {
    this.plugin = plugin;
  }

  // =========================================================
  // OPEN
  // =========================================================

  public void open(Player player) {
    Inventory inv = Bukkit.createInventory(new NowPlayingHolder(), 27, TITLE);
    fill(inv);
    populate(inv, player);
    player.openInventory(inv);
  }

  private void populate(Inventory inv, Player player) {
    PlaybackSession session = plugin.playback().getSession(player.getUniqueId());
    Track track = (session != null) ? session.getTrack() : null;

    // --- Slot 4: track info ---
    if (track != null && session != null) {
      List<String> lore = new ArrayList<>();
      lore.add(ChatColor.GRAY + "Artist: " + ChatColor.WHITE + track.artist());
      lore.add(ChatColor.GRAY + "Duration: " + ChatColor.WHITE + track.durationText());

      long posMs = session.getPositionMs();
      long posS = posMs / 1000;
      long m = posS / 60;
      long s = posS % 60;
      String posStr = String.format("%02d:%02d", m, s);
      lore.add(
          ChatColor.GRAY + "Position: " + ChatColor.WHITE + posStr + " / " + track.durationText());

      PlaybackState state = session.getState();
      ChatColor stateColor =
          (state == PlaybackState.PLAYING)
              ? ChatColor.GREEN
              : (state == PlaybackState.PAUSED) ? ChatColor.YELLOW : ChatColor.GRAY;
      lore.add(ChatColor.GRAY + "State: " + stateColor + state.name());
      lore.add("");
      lore.add(ChatColor.DARK_GRAY + "ID: " + track.id());

      inv.setItem(4, item(Material.MUSIC_DISC_13, ChatColor.AQUA + track.title(), lore));
    } else {
      inv.setItem(
          4,
          item(
              Material.BARRIER,
              ChatColor.RED + "Nothing Playing",
              List.of(ChatColor.GRAY + "No track is currently active.")));
    }

    // --- Previous (10), Play/Pause (13), Next (16) ---
    inv.setItem(
        10,
        item(
            Material.ARROW,
            ChatColor.WHITE + "⏮ Previous",
            List.of(ChatColor.GRAY + "Go to the previous track")));

    boolean playing = plugin.network().isPlaying(player);
    boolean paused = plugin.network().isPaused(player);

    if (!playing || track == null) {
      inv.setItem(
          13,
          item(
              Material.NOTE_BLOCK,
              ChatColor.GRAY + "Nothing Playing",
              List.of(ChatColor.GRAY + "No music is active.")));
    } else if (paused) {
      inv.setItem(
          13,
          item(
              Material.NOTE_BLOCK,
              ChatColor.GREEN + "▶ Resume",
              List.of(ChatColor.GRAY + "Resume the current track")));
    } else {
      inv.setItem(
          13,
          item(
              Material.NOTE_BLOCK,
              ChatColor.YELLOW + "⏸ Pause",
              List.of(ChatColor.GRAY + "Pause the current track")));
    }

    inv.setItem(
        16,
        item(
            Material.ARROW,
            ChatColor.WHITE + "Next ⏭",
            List.of(ChatColor.GRAY + "Skip to the next track")));

    // --- Thumbs Up (11), Thumbs Down (15) ---
    if (track != null) {
      boolean isLiked = plugin.cache().isTrackLiked(player.getUniqueId(), track.id());
      inv.setItem(
          11,
          item(
              isLiked ? Material.EMERALD : Material.LIME_DYE,
              (isLiked ? ChatColor.GREEN + "❤ Liked" : ChatColor.GREEN + "👍 Like Track"),
              List.of(
                  ChatColor.GRAY + "Boosts this artist and song in your",
                  ChatColor.GRAY + "personal recommendation profile.",
                  "",
                  isLiked
                      ? ChatColor.YELLOW + "Click to unlike"
                      : ChatColor.GREEN + "Click to like (+0.5 affinity)")));
      inv.setItem(
          15,
          item(
              Material.RED_DYE,
              ChatColor.RED + "👎 Dislike & Skip",
              List.of(
                  ChatColor.GRAY + "Downweights this artist and places this track",
                  ChatColor.GRAY + "on a 7-day cooldown. Automatically skips.",
                  "",
                  ChatColor.RED + "Click to dislike and skip")));
    } else {
      inv.setItem(
          11,
          item(
              Material.GRAY_DYE,
              ChatColor.DARK_GRAY + "👍 Like Track",
              List.of(ChatColor.GRAY + "No track playing.")));
      inv.setItem(
          15,
          item(
              Material.GRAY_DYE,
              ChatColor.DARK_GRAY + "👎 Dislike Track",
              List.of(ChatColor.GRAY + "No track playing.")));
    }

    // --- Repeat (22), Shuffle (23) ---
    RepeatMode repeat = plugin.queues().getRepeatMode(player.getUniqueId());
    ChatColor repeatColor = repeatColor(repeat);
    inv.setItem(
        22,
        item(
            Material.REPEATER,
            repeatColor + "Repeat: " + repeat.name(),
            List.of(ChatColor.GRAY + "Click to cycle: OFF → TRACK → QUEUE")));

    boolean shuffle = plugin.queues().isShuffle(player.getUniqueId());
    inv.setItem(
        23,
        item(
            shuffle ? Material.COMPARATOR : Material.CLOCK,
            (shuffle ? ChatColor.GREEN : ChatColor.GRAY) + "Shuffle: " + (shuffle ? "ON" : "OFF"),
            List.of(ChatColor.GRAY + "Toggle queue shuffle")));

    // --- Autoplay (24) ---
    if (plugin.recommendations() != null) {
      boolean autoplay = plugin.recommendations().isAutoplayEnabled(player.getUniqueId());
      inv.setItem(
          24,
          item(
              autoplay ? Material.LIME_DYE : Material.GRAY_DYE,
              (autoplay ? ChatColor.GREEN : ChatColor.GRAY)
                  + "Autoplay: "
                  + (autoplay ? "ON" : "OFF"),
              List.of(
                  ChatColor.GRAY + "Automatically play recommended music",
                  ChatColor.GRAY + "when your queue is empty.",
                  "",
                  autoplay
                      ? ChatColor.GREEN + "Click to disable"
                      : ChatColor.YELLOW + "Click to enable")));
    }

    // --- Back (26) ---
    inv.setItem(
        26,
        item(
            Material.BARRIER,
            ChatColor.RED + "← Back",
            List.of(ChatColor.GRAY + "Return to main menu")));
  }

  // =========================================================
  // CLICK HANDLER
  // =========================================================

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;

    Inventory top = event.getView().getTopInventory();
    if (!(top.getHolder() instanceof NowPlayingHolder)) return;

    event.setCancelled(true);
    if (event.getRawSlot() >= top.getSize()) return;

    int slot = event.getRawSlot();

    switch (slot) {
      case 10 -> {
        plugin.playback().previous(player);
        player.sendMessage(ChatColor.YELLOW + "⏮ Previous track.");
        refreshLater(top, player);
      }
      case 11 -> {
        PlaybackSession sess = plugin.playback().getSession(player.getUniqueId());
        Track cur = (sess != null) ? sess.getTrack() : null;
        if (cur != null) {
          boolean alreadyLiked = plugin.cache().isTrackLiked(player.getUniqueId(), cur.id());
          boolean newLikedState = !alreadyLiked;
          plugin.cache().recordTrackLiked(player.getUniqueId(), cur.id(), newLikedState);
          if (plugin.recommendations() != null) {
            if (newLikedState) {
              plugin.recommendations().recordLike(player.getUniqueId(), cur);
              player.sendMessage(
                  ChatColor.GREEN
                      + "❤ Liked "
                      + ChatColor.WHITE
                      + cur.title()
                      + ChatColor.GREEN
                      + " (+0.5 taste affinity)!");
            } else {
              player.sendMessage(
                  ChatColor.YELLOW
                      + "Unliked "
                      + ChatColor.WHITE
                      + cur.title()
                      + ChatColor.YELLOW
                      + ".");
            }
          }
          refreshLater(top, player);
        } else {
          player.sendMessage(ChatColor.GRAY + "No music is playing.");
        }
      }
      case 15 -> {
        PlaybackSession sess = plugin.playback().getSession(player.getUniqueId());
        Track cur = (sess != null) ? sess.getTrack() : null;
        if (cur != null) {
          if (plugin.recommendations() != null) {
            plugin.recommendations().recordDislike(player.getUniqueId(), cur);
          }
          plugin.cache().recordTrackLiked(player.getUniqueId(), cur.id(), false);
          plugin.cache().recordTrackSkipped(player.getUniqueId(), cur.id());
          player.sendMessage(
              ChatColor.RED
                  + "👎 Disliked "
                  + ChatColor.WHITE
                  + cur.title()
                  + ChatColor.RED
                  + ". 7-day cooldown applied. Skipping...");
          plugin.playback().next(player);
          refreshLater(top, player);
        } else {
          player.sendMessage(ChatColor.GRAY + "No music is playing.");
        }
      }
      case 13 -> {
        if (!plugin.network().isPlaying(player)) {
          player.sendMessage(ChatColor.GRAY + "No music is playing.");
        } else if (plugin.network().isPaused(player)) {
          plugin.network().resume(player);
          player.sendMessage(ChatColor.GREEN + "▶ Resumed.");
        } else {
          plugin.network().pause(player);
          player.sendMessage(ChatColor.YELLOW + "⏸ Paused.");
        }
        refreshLater(top, player);
      }
      case 16 -> {
        plugin.playback().next(player);
        player.sendMessage(ChatColor.YELLOW + "⏭ Skipping to next track.");
        refreshLater(top, player);
      }
      case 22 -> {
        // Cycle repeat: OFF → TRACK → QUEUE → OFF
        RepeatMode current = plugin.queues().getRepeatMode(player.getUniqueId());
        RepeatMode next =
            switch (current) {
              case OFF -> RepeatMode.TRACK;
              case TRACK -> RepeatMode.QUEUE;
              case QUEUE -> RepeatMode.OFF;
            };
        plugin.queues().setRepeatMode(player.getUniqueId(), next);
        player.sendMessage(ChatColor.GREEN + "Repeat mode: " + next.name());
        refreshLater(top, player);
      }
      case 23 -> {
        boolean was = plugin.queues().isShuffle(player.getUniqueId());
        plugin.queues().setShuffle(player.getUniqueId(), !was);
        player.sendMessage(ChatColor.GREEN + "Shuffle: " + (!was ? "ON" : "OFF"));
        refreshLater(top, player);
      }
      case 24 -> {
        if (plugin.recommendations() != null) {
          boolean was = plugin.recommendations().isAutoplayEnabled(player.getUniqueId());
          plugin.recommendations().setAutoplay(player.getUniqueId(), !was);
          player.sendMessage(
              (!was ? ChatColor.GREEN + "✓ " : ChatColor.GRAY + "○ ")
                  + ChatColor.WHITE
                  + "Autoplay "
                  + (!was ? ChatColor.GREEN + "enabled." : ChatColor.GRAY + "disabled."));
          refreshLater(top, player);
        }
      }
      case 26 -> {
        plugin.musicMenu().open(player);
      }
      default -> {
        /* decorative */
      }
    }
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof NowPlayingHolder) {
      event.setCancelled(true);
    }
  }

  // =========================================================
  // HELPERS
  // =========================================================

  private void refreshLater(Inventory inv, Player player) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              fill(inv);
              populate(inv, player);
            });
  }

  private ChatColor repeatColor(RepeatMode mode) {
    return switch (mode) {
      case OFF -> ChatColor.GRAY;
      case TRACK -> ChatColor.YELLOW;
      case QUEUE -> ChatColor.GREEN;
    };
  }

  private void fill(Inventory inv) {
    ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
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

  // =========================================================
  // HOLDER
  // =========================================================

  private static final class NowPlayingHolder implements InventoryHolder {
    @Override
    public Inventory getInventory() {
      return null;
    }
  }
}
