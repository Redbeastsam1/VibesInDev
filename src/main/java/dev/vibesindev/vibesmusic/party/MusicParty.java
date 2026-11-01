package dev.vibesindev.vibesmusic.party;

import dev.vibesindev.vibesmusic.model.Track;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

public final class MusicParty {

    public enum RepeatMode {
        OFF,
        ONE,
        ALL
    }

    private final UUID partyId;

    private UUID owner;

    private final List<UUID> members =
            new ArrayList<>();

    private final Deque<Track> queue =
            new ArrayDeque<>();

    private Track currentTrack;

    private boolean paused;
    private boolean autoPaused;

    private boolean shuffle;

    private RepeatMode repeatMode =
            RepeatMode.OFF;

    private volatile boolean autoplay = true;

    private final int maxQueueSize;

    public MusicParty(
            UUID owner,
            int maxQueueSize
    ) {

        this.partyId =
                UUID.randomUUID();

        this.owner =
                owner;

        this.maxQueueSize = maxQueueSize;
        members.add(owner);
    }


    // ============================================================
    // PARTY ID
    // ============================================================

    public UUID getPartyId() {
        return partyId;
    }


    // ============================================================
    // OWNER
    // ============================================================

    public UUID getOwner() {
        return owner;
    }


    public void setOwner(
            UUID owner
    ) {

        this.owner =
                owner;
    }


    public boolean isOwner(
            UUID player
    ) {

        return owner.equals(player);
    }


    // ============================================================
    // MEMBERS
    // ============================================================

    public boolean addMember(
            UUID player
    ) {

        if (members.contains(player)) {
            return false;
        }

        members.add(player);

        return true;
    }


    public boolean removeMember(
            UUID player
    ) {

        return members.remove(player);
    }


    public boolean hasMember(
            UUID player
    ) {

        return members.contains(player);
    }


    public List<UUID> getMembers() {

        return Collections.unmodifiableList(
                new ArrayList<>(members)
        );
    }


    public int getMemberCount() {

        return members.size();
    }


    public boolean isEmpty() {

        return members.isEmpty();
    }


    // ============================================================
    // QUEUE
    // ============================================================

    public void enqueue(
            Track track
    ) {

        if (track == null) {
            return;
        }

        if (queue.size() >= maxQueueSize) {
            throw new IllegalStateException("Party queue is full (maximum " + maxQueueSize + " tracks).");
        }

        queue.addLast(track);
    }


    public Track dequeue() {

        return queue.pollFirst();
    }


    public Track peekNext() {
        if (repeatMode == RepeatMode.ONE) {
            return currentTrack;
        }
        Track next = queue.peekFirst();
        if (next == null && repeatMode == RepeatMode.ALL) {
            return currentTrack;
        }
        return next;
    }

    public Track peekQueue() {

        return queue.peekFirst();
    }


    public List<Track> getQueue() {

        return Collections.unmodifiableList(
                new ArrayList<>(queue)
        );
    }


    public int getQueueSize() {

        return queue.size();
    }


    public void clearQueue() {

        queue.clear();
    }


    // ============================================================
    // CURRENT TRACK
    // ============================================================

    public Track getCurrentTrack() {

        return currentTrack;
    }


    public void setCurrentTrack(
            Track track
    ) {

        currentTrack =
                track;
    }


    public void clearCurrentTrack() {

        currentTrack = null;
    }


    // ============================================================
    // PAUSED
    // ============================================================

    public boolean isPaused() {

        return paused;
    }


    public void setPaused(
            boolean paused
    ) {

        this.paused =
                paused;
    }

    public boolean isAutoPaused() {
        return autoPaused;
    }

    public void setAutoPaused(boolean autoPaused) {
        this.autoPaused = autoPaused;
    }


    // ============================================================
    // SHUFFLE
    // ============================================================

    public boolean isShuffle() {

        return shuffle;
    }


    public void setShuffle(
            boolean shuffle
    ) {

        this.shuffle =
                shuffle;
    }


    // ============================================================
    // REPEAT
    // ============================================================

    public RepeatMode getRepeatMode() {

        return repeatMode;
    }


    public void setRepeatMode(
            RepeatMode repeatMode
    ) {

        if (repeatMode == null) {
            repeatMode =
                    RepeatMode.OFF;
            return;
        }

        this.repeatMode =
                repeatMode;
    }


    // ============================================================
    // REMOVE NEXT TRACK
    // ============================================================

    public boolean removeFromQueue(
            int index
    ) {

        if (index < 0 || index >= queue.size()) {
            return false;
        }


        int currentIndex = 0;


        for (var iterator = queue.iterator();
             iterator.hasNext();
        ) {

            iterator.next();


            if (currentIndex == index) {

                iterator.remove();

                return true;
            }


            currentIndex++;
        }


        return false;
    }


    // ============================================================
    // STRING
    // ============================================================

    // ============================================================
    // AUTOPLAY
    // ============================================================

    public boolean isAutoplay() {
        return autoplay;
    }

    public void setAutoplay(boolean autoplay) {
        this.autoplay = autoplay;
    }


    @Override
    public String toString() {

        return "MusicParty{" +
                "partyId=" + partyId +
                ", owner=" + owner +
                ", members=" + members.size() +
                ", queue=" + queue.size() +
                ", currentTrack=" + currentTrack +
                ", paused=" + paused +
                ", shuffle=" + shuffle +
                ", repeatMode=" + repeatMode +
                '}';
    }
}