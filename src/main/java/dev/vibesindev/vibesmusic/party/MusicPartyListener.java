package dev.vibesindev.vibesmusic.party;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Handles party membership changes caused by a player disconnecting.
 */
public final class MusicPartyListener implements Listener {

    private final VibesInDevPlugin plugin;

    public MusicPartyListener(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.parties().handlePlayerJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        MusicPartyManager.PartyLeaveResult result =
                plugin.parties().handlePlayerQuit(playerId);

        if (result == null || result.party() == null) {
            return;
        }

        MusicParty party = result.party();

        if (result.wasOwner() && result.newOwner() != null) {
            Player newOwner = Bukkit.getPlayer(result.newOwner());

            if (newOwner != null && newOwner.isOnline()) {
                newOwner.sendMessage(
                        ChatColor.GREEN
                                + "♫ You are now the party owner because "
                                + event.getPlayer().getName()
                                + " disconnected."
                );
            }

            notifyRemaining(
                    party,
                    result.newOwner(),
                    ChatColor.YELLOW
                            + event.getPlayer().getName()
                            + " disconnected. Party ownership transferred to "
                            + ChatColor.AQUA
                            + playerName(result.newOwner())
                            + ChatColor.YELLOW
                            + "."
            );
        } else if (result.gracePeriodStarted()) {
            String notice = result.wasOwner()
                    ? (ChatColor.YELLOW + event.getPlayer().getName() + " disconnected. Party ownership will transfer if they do not return soon.")
                    : (ChatColor.YELLOW + event.getPlayer().getName() + " disconnected. Keeping their party spot for a few minutes.");
            notifyRemaining(
                    party,
                    result.player(),
                    notice
            );
        } else {
            notifyRemaining(
                    party,
                    null,
                    ChatColor.GRAY
                            + event.getPlayer().getName()
                            + " left the music party."
            );
        }
    }

    private void notifyRemaining(
            MusicParty party,
            UUID excluded,
            String message
    ) {
        for (UUID member : party.getMembers()) {
            if (excluded != null && excluded.equals(member)) {
                continue;
            }

            Player player = Bukkit.getPlayer(member);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private String playerName(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : uuid.toString();
    }
}
