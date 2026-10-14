package dev.vibesindev.vibesmusic.source;

import dev.vibesindev.vibesmusic.audio.YtDlpManager;
import dev.vibesindev.vibesmusic.model.Track;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SoundCloudSource implements MusicSource {

    private final YtDlpManager ytDlp;

    public SoundCloudSource(YtDlpManager ytDlp) {
        this.ytDlp = ytDlp;
    }

    @Override
    public boolean supports(String input) {
        return input.toLowerCase().contains("soundcloud.com/");
    }

    @Override
    public CompletableFuture<List<Track>> resolve(String input) {
        return ytDlp.isPlaylistUrl(input)
                ? ytDlp.resolvePlaylist(input)
                : ytDlp.resolve(input).thenApply(List::of);
    }
}
