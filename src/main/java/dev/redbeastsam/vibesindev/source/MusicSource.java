package dev.redbeastsam.vibesindev.source;

import dev.redbeastsam.vibesindev.model.Track;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MusicSource {
  boolean supports(String input);

  CompletableFuture<List<Track>> resolve(String input);
}
