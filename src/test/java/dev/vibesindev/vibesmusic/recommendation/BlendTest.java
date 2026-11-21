package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("null")
public class BlendTest {

    private Track makeTrack(String id, String title, String artist) {
        return new Track(id, title, artist, 180L, "http://example.com/audio.mp3");
    }

    @Test
    @DisplayName("Blended profile merges fatigue in chronological order and populates recentArtists from all 3 members")
    void testBlendFatigueChronologicalOrderAndRecentArtists() {
        PlayerTasteProfile member1 = new PlayerTasteProfile(UUID.randomUUID());
        PlayerTasteProfile member2 = new PlayerTasteProfile(UUID.randomUUID());
        PlayerTasteProfile member3 = new PlayerTasteProfile(UUID.randomUUID());

        long now = System.currentTimeMillis();
        // member 1 played track at now - 300s
        Track t1 = makeTrack("t1", "Track One", "Artist A");
        // member 2 played track at now - 200s
        Track t2 = makeTrack("t2", "Track Two", "Artist B");
        // member 3 played track at now - 100s
        Track t3 = makeTrack("t3", "Track Three", "Artist C");

        member1.restoreFatigue(List.of(new PlayerTasteProfile.FatigueEntry(t1.id(), t1.artist(), t1.title(), now - 300_000L)));
        member2.restoreFatigue(List.of(new PlayerTasteProfile.FatigueEntry(t2.id(), t2.artist(), t2.title(), now - 200_000L)));
        member3.restoreFatigue(List.of(new PlayerTasteProfile.FatigueEntry(t3.id(), t3.artist(), t3.title(), now - 100_000L)));

        PlayerTasteProfile blended = PlayerTasteProfile.blend(List.of(member1, member2, member3));

        List<PlayerTasteProfile.FatigueEntry> fatigue = blended.getFatigueHistory();
        assertEquals(3, fatigue.size(), "Blended profile should contain all 3 tracks in fatigue");

        // Verify chronological order (ascending by playedAt)
        assertEquals("t1", fatigue.get(0).trackId());
        assertEquals("t2", fatigue.get(1).trackId());
        assertEquals("t3", fatigue.get(2).trackId());

        // isFatigued catches any of the 3
        assertTrue(blended.isFatigued(t1, 20, 45 * 60_000L));
        assertTrue(blended.isFatigued(t2, 20, 45 * 60_000L));
        assertTrue(blended.isFatigued(t3, 20, 45 * 60_000L));

        // The last 4 plays contain all 3 artists
        assertEquals(1, blended.getRecentArtistCount("Artist A"));
        assertEquals(1, blended.getRecentArtistCount("Artist B"));
        assertEquals(1, blended.getRecentArtistCount("Artist C"));
    }

    @Test
    @DisplayName("Blended profile falls back to source member dynamic lineage")
    void testBlendDynamicLineageFallback() {
        PlayerTasteProfile member1 = new PlayerTasteProfile(UUID.randomUUID());
        PlayerTasteProfile member2 = new PlayerTasteProfile(UUID.randomUUID());

        // Member 1 has dynamic lineage (e.g. from Spotify or custom map)
        member1.registerLineage("custom band", "solo artist");

        PlayerTasteProfile blended = PlayerTasteProfile.blend(List.of(member1, member2));

        Set<String> connected = blended.getConnectedArtists("custom band");
        assertTrue(connected.contains("solo artist"), "Blended profile must resolve connected artists from source profiles");
    }

    @Test
    @DisplayName("Blended profile deduplicates same composition played by multiple members")
    void testBlendDeduplicatesCompositionKey() {
        PlayerTasteProfile member1 = new PlayerTasteProfile(UUID.randomUUID());
        PlayerTasteProfile member2 = new PlayerTasteProfile(UUID.randomUUID());

        long now = System.currentTimeMillis();
        // Member 1 played original, member 2 played slowed version
        Track orig = makeTrack("id_orig", "Song X", "Artist Z");
        Track slowed = makeTrack("id_slow", "Song X (Slowed)", "Artist Z");

        member1.restoreFatigue(List.of(new PlayerTasteProfile.FatigueEntry(orig.id(), orig.artist(), orig.title(), now - 200_000L)));
        member2.restoreFatigue(List.of(new PlayerTasteProfile.FatigueEntry(slowed.id(), slowed.artist(), slowed.title(), now - 100_000L)));

        PlayerTasteProfile blended = PlayerTasteProfile.blend(List.of(member1, member2));

        List<PlayerTasteProfile.FatigueEntry> fatigue = blended.getFatigueHistory();
        assertEquals(1, fatigue.size(), "Duplicate composition variants should be deduplicated to latest");
        assertEquals("id_slow", fatigue.get(0).trackId(), "Latest played variant should be retained");
    }
}
