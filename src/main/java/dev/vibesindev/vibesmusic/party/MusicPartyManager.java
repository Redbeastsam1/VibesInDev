package dev.vibesindev.vibesmusic.party;

import dev.vibesindev.vibesmusic.model.Track;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MusicPartyManager {

    private final VibesInDevPlugin plugin;

    private final Map<UUID, BukkitTask> ownerGraceTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> memberGraceTasks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastKnownParties = new ConcurrentHashMap<>();

    public UUID getLastKnownParty(UUID player) {
        return (player != null) ? lastKnownParties.get(player) : null;
    }

    public void setPlayerParty(UUID player, MusicParty party) {
        if (player != null && party != null) {
            playerParties.put(player, party);
        }
    }

    /*
     * partyId -> party
     */
    private final Map<UUID, MusicParty> parties =
            new ConcurrentHashMap<>();


    /*
     * player UUID -> party
     *
     * This makes finding a player's party O(1).
     */
    private final Map<UUID, MusicParty> playerParties =
            new ConcurrentHashMap<>();


    /*
     * player UUID -> party IDs they have been invited to
     */
    private static final long INVITE_TIMEOUT_MILLIS = 60_000L;

    private final Map<UUID, PartyInvitation> invitations =
            new ConcurrentHashMap<>();

    private record PartyInvitation(
            UUID partyId,
            long expiresAt
    ) {}

    public record PartyLeaveResult(
            MusicParty party,
            UUID player,
            boolean wasOwner,
            UUID newOwner,
            boolean gracePeriodStarted
    ) {}

    public MusicPartyManager(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }


    // ============================================================
    // CREATE
    // ============================================================

    public MusicParty createParty(
            UUID owner
    ) {

        if (owner == null) {
            throw new IllegalArgumentException(
                    "Owner cannot be null"
            );
        }


        /*
         * A player can only belong to one party.
         */
        leaveParty(owner);


        int maxQueueSize = plugin.getConfig().getInt("limits.max-queue-size", 100);
        MusicParty party =
                new MusicParty(owner, maxQueueSize);


        parties.put(
                party.getPartyId(),
                party
        );


        playerParties.put(
                owner,
                party
        );


        return party;
    }


    // ============================================================
    // GET PARTY
    // ============================================================

    public MusicParty getPartyByPlayer(
            UUID player
    ) {
        if (player == null) {
            return null;
        }

        return playerParties.get(player);
    }


    public MusicParty getParty(
            UUID partyId
    ) {

        if (partyId == null) {
            return null;
        }

        return parties.get(partyId);
    }

    public java.util.Collection<MusicParty> getAllParties() {
        return java.util.Collections.unmodifiableCollection(parties.values());
    }


    public boolean hasParty(
            UUID player
    ) {

        return player != null
                && playerParties.containsKey(player);
    }


    // ============================================================
    // JOIN
    // ============================================================

    public boolean joinParty(
            UUID player,
            UUID partyId
    ) {

        if (player == null
                || partyId == null) {

            return false;
        }


        MusicParty party =
                parties.get(partyId);


        if (party == null) {
            return false;
        }


        /*
         * Remove the player from their existing party.
         */
        MusicParty existing =
                playerParties.get(player);


        if (existing != null) {

            if (existing == party) {
                return true;
            }

            leaveParty(player);
        }


        /*
         * Remove the invitation once it is used.
         */
        invitations.remove(player);


        party.addMember(player);


        playerParties.put(
                player,
                party
        );
        
        org.bukkit.entity.Player onlinePlayer = plugin.getServer().getPlayer(player);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            plugin.playback().syncNewPartyMember(onlinePlayer, party);
        }

        return true;
    }


    // ============================================================
    // INVITE
    // ============================================================

    public boolean invite(
            UUID owner,
            UUID player
    ) {

        if (owner == null
                || player == null) {

            return false;
        }


        MusicParty party =
                playerParties.get(owner);


        if (party == null) {
            return false;
        }


        if (!party.isOwner(owner)) {
            return false;
        }


        if (party.hasMember(player)) {
            return false;
        }


        invitations.put(
                player,
                new PartyInvitation(
                        party.getPartyId(),
                        System.currentTimeMillis()
                                + INVITE_TIMEOUT_MILLIS
                )
        );


        return true;
    }


    // ============================================================
    // ACCEPT INVITATION
    // ============================================================

    public boolean acceptInvitation(
            UUID player
    ) {

        if (player == null) {
            return false;
        }


        PartyInvitation invitation =
                invitations.remove(player);

        if (invitation == null) {
            return false;
        }

        if (invitation.expiresAt() < System.currentTimeMillis()) {
            return false;
        }

        return joinParty(
                player,
                invitation.partyId()
        );
    }


    // ============================================================
    // CHECK INVITATION
    // ============================================================

    public MusicParty getInvitedParty(
            UUID player
    ) {

        if (player == null) {
            return null;
        }


        PartyInvitation invitation =
                invitations.get(player);

        if (invitation == null) {
            return null;
        }

        if (invitation.expiresAt() < System.currentTimeMillis()) {
            invitations.remove(player, invitation);
            return null;
        }

        return parties.get(invitation.partyId());
    }


    // ============================================================
    // LEAVE
    // ============================================================

    public MusicParty leaveParty(
            UUID player
    ) {
        PartyLeaveResult result = leavePartyDetailed(player);
        return result == null ? null : result.party();
    }

    public PartyLeaveResult leavePartyDetailed(
            UUID player
    ) {
        if (player == null) {
            return null;
        }

        MusicParty party = playerParties.remove(player);
        if (party == null) {
            return null;
        }

        boolean wasOwner = party.isOwner(player);
        
        if (wasOwner) {
            BukkitTask task = ownerGraceTasks.remove(party.getPartyId());
            if (task != null) task.cancel();
        }
        BukkitTask memTask = memberGraceTasks.remove(player);
        if (memTask != null) memTask.cancel();

        party.removeMember(player);

        UUID newOwner = null;

        if (wasOwner && !party.isEmpty()) {
            newOwner = party.getMembers().get(0);
            party.setOwner(newOwner);
        }

        if (party.isEmpty()) {
            parties.remove(party.getPartyId());
        }

        return new PartyLeaveResult(
                party,
                player,
                wasOwner,
                newOwner,
                false
        );
    }


    // ============================================================
    // DISBAND
    // ============================================================

    public boolean disbandParty(
            UUID player
    ) {

        MusicParty party =
                playerParties.get(player);


        if (party == null) {
            return false;
        }


        if (!party.isOwner(player)) {
            return false;
        }

        BukkitTask task = ownerGraceTasks.remove(party.getPartyId());
        if (task != null) task.cancel();

        parties.remove(
                party.getPartyId()
        );


        for (UUID member :
                party.getMembers()) {

            playerParties.remove(member);
        }


        party.clearQueue();

        party.clearCurrentTrack();

        party.setPaused(false);


        return true;
    }


    // ============================================================
    // PARTY MANAGEMENT
    // ============================================================

    /**
     * Removes a member from a party.
     * Only the current owner can kick another member.
     */
    public boolean kickMember(
            UUID owner,
            UUID player
    ) {
        if (owner == null
                || player == null
                || owner.equals(player)) {
            return false;
        }

        MusicParty party =
                playerParties.get(owner);

        if (party == null
                || !party.isOwner(owner)
                || !party.hasMember(player)) {
            return false;
        }

        if (!party.removeMember(player)) {
            return false;
        }

        playerParties.remove(player);
        invitations.remove(player);

        if (party.isEmpty()) {
            parties.remove(party.getPartyId());
        }

        return true;
    }

    /**
     * Transfers ownership to another existing party member.
     */
    public boolean transferOwnership(
            UUID owner,
            UUID newOwner
    ) {
        if (owner == null
                || newOwner == null
                || owner.equals(newOwner)) {
            return false;
        }

        MusicParty party =
                playerParties.get(owner);

        if (party == null
                || !party.isOwner(owner)
                || !party.hasMember(newOwner)) {
            return false;
        }

        BukkitTask task = ownerGraceTasks.remove(party.getPartyId());
        if (task != null) task.cancel();

        party.setOwner(newOwner);

        return true;
    }

    /**
     * Returns the owner of the player's current party.
     */
    public UUID getOwner(
            UUID player
    ) {
        MusicParty party =
                getPartyByPlayer(player);

        return party == null
                ? null
                : party.getOwner();
    }

    // ============================================================
    // PARTY MEMBERS
    // ============================================================

    public List<UUID> getMembers(
            UUID player
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return List.of();
        }


        return party.getMembers();
    }


    // ============================================================
    // PARTY QUEUE
    // ============================================================

    public boolean enqueue(
            UUID player,
            Track track
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null
                || track == null) {

            return false;
        }


        party.enqueue(track);

        return true;
    }


    public Track dequeue(
            UUID player
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return null;
        }


        return party.dequeue();
    }


    public List<Track> getQueue(
            UUID player
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return List.of();
        }


        return party.getQueue();
    }


    // ============================================================
    // CURRENT TRACK
    // ============================================================

    public Track getCurrentTrack(
            UUID player
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return null;
        }


        return party.getCurrentTrack();
    }


    public void setCurrentTrack(
            UUID player,
            Track track
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return;
        }


        party.setCurrentTrack(track);
    }


    // ============================================================
    // PARTY STATE
    // ============================================================

    public void setPaused(
            UUID player,
            boolean paused
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return;
        }


        party.setPaused(paused);
    }


    public boolean isPaused(
            UUID player
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        return party != null
                && party.isPaused();
    }


    // ============================================================
    // SHUFFLE
    // ============================================================

    public void setShuffle(
            UUID player,
            boolean shuffle
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return;
        }


        party.setShuffle(shuffle);
    }


    public boolean isShuffle(
            UUID player
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        return party != null
                && party.isShuffle();
    }


    // ============================================================
    // REPEAT
    // ============================================================

    public void setRepeatMode(
            UUID player,
            MusicParty.RepeatMode mode
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return;
        }


        party.setRepeatMode(mode);
    }


    public MusicParty.RepeatMode getRepeatMode(
            UUID player
    ) {

        MusicParty party =
                getPartyByPlayer(player);


        if (party == null) {
            return MusicParty.RepeatMode.OFF;
        }


        return party.getRepeatMode();
    }


    // ============================================================
    // REMOVE PLAYER
    // ============================================================

    public void handlePlayerJoin(UUID player) {
        MusicParty party = playerParties.get(player);
        if (party != null) {
            if (party.isOwner(player)) {
                BukkitTask task = ownerGraceTasks.remove(party.getPartyId());
                if (task != null) {
                    task.cancel();
                }
            }
            BukkitTask memberTask = memberGraceTasks.remove(player);
            if (memberTask != null) {
                memberTask.cancel();
            }
        } else {
            // Reconnecting player: check if their previous party still exists
            UUID lastPartyId = lastKnownParties.get(player);
            if (lastPartyId != null) {
                MusicParty lastParty = parties.get(lastPartyId);
                if (lastParty != null && !lastParty.isEmpty()) {
                    lastParty.addMember(player);
                    playerParties.put(player, lastParty);
                    BukkitTask memberTask = memberGraceTasks.remove(player);
                    if (memberTask != null) {
                        memberTask.cancel();
                    }
                }
            }
        }
    }

    public PartyLeaveResult handlePlayerQuit(
            UUID player
    ) {
        if (player == null) {
            return null;
        }

        invitations.remove(player);
        
        MusicParty party = playerParties.get(player);
        if (party == null) {
            return null;
        }
        
        lastKnownParties.put(player, party.getPartyId());

        boolean hasAnyOnline = false;
        for (UUID member : party.getMembers()) {
            if (member.equals(player)) continue;
            org.bukkit.entity.Player online = plugin.getServer().getPlayer(member);
            if (online != null && online.isOnline()) {
                hasAnyOnline = true;
                break;
            }
        }

        if (!hasAnyOnline) {
            // All members of the party are offline! Auto-pause party playback
            if (party.getCurrentTrack() != null && !party.isPaused()) {
                party.setAutoPaused(true);
                party.setPaused(true);
                dev.vibesindev.vibesmusic.playback.PlaybackSession session = plugin.playback().getSession(party.getPartyId());
                if (session != null && session.getState() == dev.vibesindev.vibesmusic.playback.PlaybackState.PLAYING) {
                    session.setState(dev.vibesindev.vibesmusic.playback.PlaybackState.PAUSED);
                }
                plugin.getLogger().info("[VibesInDev] Auto-paused party " + party.getPartyId() + " because all members are offline.");
            }
        }

        int graceSeconds = plugin.getConfig().getInt("party.disconnect-grace-seconds", 180);
        long ticks = graceSeconds * 20L;
        
        if (party.isOwner(player)) {
            BukkitTask oldTask = ownerGraceTasks.remove(party.getPartyId());
            if (oldTask != null) oldTask.cancel();

            BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                ownerGraceTasks.remove(party.getPartyId());
                
                if (party.isOwner(player)) {
                    UUID newOwner = null;
                    for (UUID member : party.getMembers()) {
                        if (!member.equals(player)) {
                            org.bukkit.entity.Player online = plugin.getServer().getPlayer(member);
                            if (online != null && online.isOnline()) {
                                newOwner = member;
                                break;
                            }
                        }
                    }
                    if (newOwner == null) {
                        for (UUID member : party.getMembers()) {
                            if (!member.equals(player)) {
                                newOwner = member;
                                break;
                            }
                        }
                    }
                    
                    if (newOwner != null) {
                        party.setOwner(newOwner);
                        
                        org.bukkit.entity.Player pNew = plugin.getServer().getPlayer(newOwner);
                        if (pNew != null && pNew.isOnline()) {
                            pNew.sendMessage(org.bukkit.ChatColor.GREEN + "You are now the party owner because the previous owner's grace period expired.");
                        }
                        
                        for (UUID member : party.getMembers()) {
                            if (member.equals(newOwner)) continue;
                            org.bukkit.entity.Player p = plugin.getServer().getPlayer(member);
                            if (p != null && p.isOnline()) {
                                p.sendMessage(org.bukkit.ChatColor.YELLOW + "Party ownership transferred to " + org.bukkit.ChatColor.AQUA + plugin.getServer().getOfflinePlayer(newOwner).getName() + org.bukkit.ChatColor.YELLOW + ".");
                            }
                        }
                    } else {
                        disbandParty(player);
                    }
                }
            }, ticks);
            
            ownerGraceTasks.put(party.getPartyId(), task);
            return new PartyLeaveResult(party, player, true, null, true);
        } else {
            // Disconnect grace period for members: keep member in party so they can rejoin smoothly
            BukkitTask oldTask = memberGraceTasks.remove(player);
            if (oldTask != null) oldTask.cancel();

            BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                memberGraceTasks.remove(player);
                org.bukkit.entity.Player online = plugin.getServer().getPlayer(player);
                if (online == null || !online.isOnline()) {
                    leavePartyDetailed(player);
                }
            }, ticks);

            memberGraceTasks.put(player, task);
            return new PartyLeaveResult(party, player, false, null, true);
        }
    }


    // ============================================================
    // SHUTDOWN
    // ============================================================

    public void shutdown() {

        for (BukkitTask task : ownerGraceTasks.values()) {
            task.cancel();
        }
        ownerGraceTasks.clear();

        for (BukkitTask task : memberGraceTasks.values()) {
            task.cancel();
        }
        memberGraceTasks.clear();
        lastKnownParties.clear();

        parties.clear();

        playerParties.clear();

        invitations.clear();
    }
}
