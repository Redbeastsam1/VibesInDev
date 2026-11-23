package dev.redbeastsam.vibesindev.party;

import static org.junit.jupiter.api.Assertions.*;

import dev.redbeastsam.vibesindev.model.Track;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MusicPartyTest {
  private MusicParty party;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    party = new MusicParty(owner, 100);
  }

  @Test
  void testAddHistoryAndPrevious() {
    Track track1 = new Track("1", "Song 1", "Artist 1", 100L, "url1");
    Track track2 = new Track("2", "Song 2", "Artist 2", 120L, "url2");

    party.addHistory(track1);
    party.addHistory(track2);

    assertEquals(2, party.getHistory().size());
    assertEquals(track2, party.previous());
    assertEquals(track1, party.previous());
    assertNull(party.previous());
  }

  @Test
  void testHistoryCapacityCapAt50() {
    for (int i = 0; i < 60; i++) {
      party.addHistory(new Track("id" + i, "Title " + i, "Artist", 100L, "url"));
    }

    assertEquals(50, party.getHistory().size());
    // The oldest 10 were discarded; the newest is id59
    assertEquals("id59", party.previous().id());
  }

  @Test
  void testPushFront() {
    Track t1 = new Track("1", "Song 1", "Artist", 100L, "url");
    Track t2 = new Track("2", "Song 2", "Artist", 100L, "url");

    party.enqueue(t1);
    party.pushFront(t2);

    assertEquals(2, party.getQueueSize());
    assertEquals(t2, party.dequeue());
    assertEquals(t1, party.dequeue());
  }

  @Test
  void testClearHistory() {
    party.addHistory(new Track("1", "Song 1", "Artist", 100L, "url"));
    assertEquals(1, party.getHistory().size());

    party.clearHistory();
    assertEquals(0, party.getHistory().size());
    assertNull(party.previous());
  }
}
