package dev.vibesindev.vibesmusic.gui;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.party.MusicParty;
import dev.vibesindev.vibesmusic.playback.PlaybackSession;
import dev.vibesindev.vibesmusic.playback.PlaybackState;
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
 * Main /music GUI - 3-row (27-slot) chest menu.
 *
 * Layout (slot indices):
 *  [0-8]   Row 1: border
 *  [9-17]  Row 2: Prev | border | NowPlaying | Play/Pause | Queue | border | Next
 *  [18-26] Row 3: Vol- | border | VolDisplay | Stop | NowPlayingLink | Party | Vol+
 */
public final class MusicMenu implements Listener {

    private static final int SLOT_PREV      = 10;
    private static final int SLOT_NOW       = 12;
    private static final int SLOT_PLAYPAUSE = 13;
    private static final int SLOT_QUEUE_BTN = 14;
    private static final int SLOT_NEXT      = 16;
    private static final int SLOT_VOL_DOWN  = 19;
    private static final int SLOT_VOL_DISP  = 21;
    private static final int SLOT_STOP      = 22;
    private static final int SLOT_NOWLINK   = 23;
    private static final int SLOT_PARTY     = 24;
    private static final int SLOT_VOL_UP    = 25;

    private static final String TITLE = ChatColor.DARK_AQUA + "" + ChatColor.BOLD + " VibesInDev";

    private final VibesInDevPlugin plugin;

    public MusicMenu(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        MusicMenuHolder holder = new MusicMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE);
        holder.inventory = inv;
        populate(inv, player);
        player.openInventory(inv);
    }

    private void populate(Inventory inv, Player player) {
        ItemStack border = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, border);

        PlaybackSession session = plugin.playback().getSession(player.getUniqueId());
        MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());
        Track track = resolveCurrentTrack(session, party);
        boolean playing = plugin.network().isPlaying(player);
        boolean paused  = plugin.network().isPaused(player);

        // Now Playing info
        if (track != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "By: " + ChatColor.WHITE + track.artist());
            lore.add(ChatColor.GRAY + "Duration: " + ChatColor.WHITE + track.durationText());
            if (session != null) {
                lore.add(ChatColor.GRAY + "Position: " + ChatColor.WHITE + formatTime(session.getPositionMs() / 1000));
            }
            lore.add("");
            PlaybackState state = session != null ? session.getState() : PlaybackState.IDLE;
            lore.add(stateColor(state) + state.name());
            if (party != null) {
                lore.add("");
                lore.add(ChatColor.LIGHT_PURPLE + "Party: " + ChatColor.WHITE + party.getMemberCount() + " members");
            }
            inv.setItem(SLOT_NOW, item(Material.JUKEBOX,
                    ChatColor.AQUA + "" + ChatColor.BOLD + track.title(), lore));
        } else {
            inv.setItem(SLOT_NOW, item(Material.MUSIC_DISC_13,
                    ChatColor.GRAY + "Nothing Playing",
                    List.of(ChatColor.DARK_GRAY + "Play a song with /music play")));
        }

        // Previous
        inv.setItem(SLOT_PREV, item(Material.ARROW,
                ChatColor.WHITE + "<< Previous",
                List.of(ChatColor.GRAY + "Go to the previous track")));

        // Play/Pause
        if (!playing || track == null) {
            inv.setItem(SLOT_PLAYPAUSE, item(Material.GRAY_DYE,
                    ChatColor.GRAY + "Idle",
                    List.of(ChatColor.DARK_GRAY + "No music is currently active.")));
        } else if (paused) {
            inv.setItem(SLOT_PLAYPAUSE, item(Material.LIME_DYE,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "Resume",
                    List.of(ChatColor.GRAY + "Click to resume playback")));
        } else {
            inv.setItem(SLOT_PLAYPAUSE, item(Material.YELLOW_DYE,
                    ChatColor.YELLOW + "" + ChatColor.BOLD + "Pause",
                    List.of(ChatColor.GRAY + "Click to pause playback")));
        }

        // Queue
        int queueSize = party != null ? party.getQueueSize()
                : plugin.queues().list(player.getUniqueId()).size();
        List<String> queueLore = new ArrayList<>();
        queueLore.add(ChatColor.GRAY + "Tracks in queue: " + ChatColor.WHITE + queueSize);
        queueLore.add("");
        queueLore.add(ChatColor.YELLOW + "Click to manage queue");
        inv.setItem(SLOT_QUEUE_BTN, item(Material.CHEST,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Queue", queueLore));

        // Next
        inv.setItem(SLOT_NEXT, item(Material.ARROW,
                ChatColor.WHITE + "Next >>",
                List.of(ChatColor.GRAY + "Skip to the next track")));

        // Volume
        float vol = plugin.network().getVolume(player);
        int volPct = Math.round(vol * 100.0F);
        inv.setItem(SLOT_VOL_DOWN, item(Material.RED_DYE,
                ChatColor.RED + "Volume -",
                List.of(ChatColor.GRAY + "Current: " + ChatColor.WHITE + volPct + "%",
                        ChatColor.GRAY + "Click to decrease by 10%")));
        inv.setItem(SLOT_VOL_DISP, item(Material.COMPARATOR,
                ChatColor.YELLOW + "Volume: " + ChatColor.WHITE + volPct + "%",
                List.of(ChatColor.GRAY + buildVolumeBar(vol))));
        inv.setItem(SLOT_VOL_UP, item(Material.LIME_DYE,
                ChatColor.GREEN + "Volume +",
                List.of(ChatColor.GRAY + "Current: " + ChatColor.WHITE + volPct + "%",
                        ChatColor.GRAY + "Click to increase by 10%")));

        // Stop
        inv.setItem(SLOT_STOP, item(Material.BARRIER,
                ChatColor.RED + "" + ChatColor.BOLD + "Stop",
                List.of(ChatColor.GRAY + "Stop music playback entirely")));

        // Now Playing (detailed - links to NowPlayingMenu)
        inv.setItem(SLOT_NOWLINK, item(Material.MUSIC_DISC_CAT,
                ChatColor.AQUA + "Now Playing",
                List.of(ChatColor.GRAY + "Repeat, Shuffle & detailed view")));

        // Party
        if (party != null) {
            List<String> partyLore = new ArrayList<>();
            partyLore.add(ChatColor.GRAY + "Members: " + ChatColor.WHITE + party.getMemberCount());
            String ownerName = Bukkit.getOfflinePlayer(party.getOwner()).getName();
            partyLore.add(ChatColor.GRAY + "Owner: " + ChatColor.AQUA + (ownerName != null ? ownerName : "Unknown"));
            partyLore.add("");
            partyLore.add(ChatColor.YELLOW + "Click to view party info");
            inv.setItem(SLOT_PARTY, item(Material.PLAYER_HEAD,
                    ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Party", partyLore));
        } else {
            inv.setItem(SLOT_PARTY, item(Material.SKELETON_SKULL,
                    ChatColor.GRAY + "No Party",
                    List.of(ChatColor.DARK_GRAY + "Use /music party create")));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MusicMenuHolder)) return;
        event.setCancelled(true);
        if (event.getRawSlot() >= top.getSize()) return;

        int slot = event.getRawSlot();
        switch (slot) {
            case SLOT_PREV -> {
                plugin.playback().previous(player);
                refreshLater(top, player);
            }
            case SLOT_PLAYPAUSE -> {
                if (!plugin.network().isPlaying(player)) {
                    player.sendMessage(ChatColor.YELLOW + "No music is currently playing.");
                } else if (plugin.network().isPaused(player)) {
                    plugin.playback().resume(player);
                    player.sendMessage(ChatColor.GREEN + "Music resumed.");
                } else {
                    plugin.playback().pause(player);
                    player.sendMessage(ChatColor.YELLOW + "Music paused.");
                }
                refreshLater(top, player);
            }
            case SLOT_QUEUE_BTN -> plugin.queueMenu().open(player);
            case SLOT_NEXT -> {
                plugin.playback().next(player);
                refreshLater(top, player);
            }
            case SLOT_VOL_DOWN -> {
                float v = plugin.network().changeVolume(player, -0.10F);
                player.sendMessage(ChatColor.YELLOW + "Volume: " + ChatColor.WHITE + Math.round(v * 100) + "%");
                refreshLater(top, player);
            }
            case SLOT_VOL_DISP -> {
                player.sendMessage(ChatColor.YELLOW + "Volume: " + ChatColor.WHITE
                        + Math.round(plugin.network().getVolume(player) * 100) + "%");
            }
            case SLOT_STOP -> {
                if (!plugin.network().isPlaying(player)) {
                    player.sendMessage(ChatColor.YELLOW + "No music is currently playing.");
                    return;
                }
                plugin.playback().stop(player);
                player.sendMessage(ChatColor.RED + "Music stopped.");
                refreshLater(top, player);
            }
            case SLOT_NOWLINK -> plugin.nowPlayingMenu().open(player);
            case SLOT_PARTY -> {
                MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());
                if (party != null) {
                    plugin.partyInfoMenu().open(player);
                } else {
                    player.sendMessage(ChatColor.GRAY + "You are not in a party. Use /music party create.");
                }
            }
            case SLOT_VOL_UP -> {
                float v = plugin.network().changeVolume(player, 0.10F);
                player.sendMessage(ChatColor.YELLOW + "Volume: " + ChatColor.WHITE + Math.round(v * 100) + "%");
                refreshLater(top, player);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MusicMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void refreshLater(Inventory inv, Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            inv.clear();
            populate(inv, player);
        });
    }

    private Track resolveCurrentTrack(PlaybackSession session, MusicParty party) {
        if (party != null && party.getCurrentTrack() != null) return party.getCurrentTrack();
        return session != null ? session.getTrack() : null;
    }

    private String formatTime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private ChatColor stateColor(PlaybackState state) {
        return switch (state) {
            case PLAYING   -> ChatColor.GREEN;
            case PAUSED    -> ChatColor.YELLOW;
            case PREPARING -> ChatColor.AQUA;
            case STOPPING, FAILED -> ChatColor.RED;
            default -> ChatColor.GRAY;
        };
    }

    private String buildVolumeBar(float volume) {
        int filled = Math.round(volume * 10);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) bar.append(i < filled ? "|" : ".");
        return bar + "]";
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

    private static final class MusicMenuHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}