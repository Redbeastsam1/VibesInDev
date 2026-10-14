package dev.vibesindev.vibesmusic.client.audio;

import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import dev.vibesindev.vibesmusic.client.network.MusicNetwork;

public final class MusicAudioPlayer {

    public static final Identifier STREAM_SOUND_ID =
            Identifier.fromNamespaceAndPath(
                    "vibesindev",
                    "network_music"
            );

    private static final long INITIAL_BUFFER_BYTES =
            256L * 1024L;

    private static final long RECOVERY_REWIND_SECONDS = 2L;

    private static final long RECOVERY_TIMEOUT_MILLIS = 6000L;

    private static final int MAX_RECOVERY_ATTEMPTS = 5;

    /*
     * Decoder construction and recovery seeking are deliberately
     * moved off Minecraft's client thread. This is important because
     * recovery may have to decode a large amount of OGG data.
     */
    private static final ExecutorService DECODER_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(
                                Runnable runnable
                        ) {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "VibesInDev-AudioDecoder"
                                    );

                            thread.setDaemon(true);
                            return thread;
                        }
                    }
            );

    private static volatile StreamingSoundInstance currentSound;

    private static volatile boolean playing;

    private static final java.nio.file.Path CONFIG_FILE =
            net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("vibesindev_client.properties");

    private static volatile float volume = loadVolumeFromConfig();

    private static float loadVolumeFromConfig() {
        if (java.nio.file.Files.exists(CONFIG_FILE)) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(CONFIG_FILE)) {
                java.util.Properties props = new java.util.Properties();
                props.load(in);
                String val = props.getProperty("volume");
                if (val != null) {
                    float v = Float.parseFloat(val);
                    return Math.max(0.0F, Math.min(1.0F, v));
                }
            } catch (Exception ignored) {
            }
        }
        return 1.0F;
    }

    private static void saveVolumeToConfig(float vol) {
        try {
            java.nio.file.Files.createDirectories(CONFIG_FILE.getParent());
            java.util.Properties props = new java.util.Properties();
            props.setProperty("volume", String.valueOf(vol));
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "VibesInDev Client Configuration");
            }
        } catch (Exception ignored) {
        }
    }

    private static volatile boolean paused;

    private static volatile boolean recovering;

    private static volatile long playedPcmBytes;

    private static volatile long recoveryTargetBytes;

    private static volatile long lastBytesPerSecond;

    private static volatile long recoveryStartedAtMillis;

    private static volatile int recoveryAttempts;

    private static volatile long pendingSeekPositionMs = -1L;

    /** Tracks the current stream UUID so the completion signal can reference it. */
    private static volatile java.util.UUID currentStreamId;

    private MusicAudioPlayer() {
    }


    // ============================================================
    // STATE
    // ============================================================

    public static boolean isPlaying() {
        return playing;
    }

    public static float getVolume() {
        return volume;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static java.util.UUID getCurrentStreamId() {
        return currentStreamId;
    }

    /**
     * Returns the current playback position in milliseconds, derived from
     * decoded PCM bytes and the PCM byte-rate. Used by the HUD overlay.
     */
    public static long getPositionMs() {
        long bps = lastBytesPerSecond;
        if (bps <= 0) {
            StreamingSoundInstance s = currentSound;
            if (s != null) bps = s.bytesPerSecond;
        }
        if (bps <= 0) return 0;
        return playedPcmBytes * 1000L / bps;
    }

    /**
     * Returns the last measured PCM byte-rate. Used by the HUD overlay to
     * estimate total duration from the OGG byte count.
     */
    public static long getLastBytesPerSecond() {
        return lastBytesPerSecond;
    }


    // ============================================================
    // VOLUME
    // ============================================================

    public static void setVolume(float newVolume) {

        volume = Math.max(
                0.0F,
                Math.min(
                        1.0F,
                        newVolume
                )
        );

        StreamingSoundInstance sound =
                currentSound;

        if (sound != null) {
            sound.updateVolume();
        }

        saveVolumeToConfig(volume);

        System.out.println(
                "[VibesInDev] Client volume: "
                        + Math.round(volume * 100.0F)
                        + "% (saved to config)"
        );
    }


    // ============================================================
    // PLAY
    // ============================================================

    public static void play() {

        if (playing) {
            return;
        }

        if (!MusicStreamBuffer.hasBufferedAtLeast(
                INITIAL_BUFFER_BYTES
        )) {
            return;
        }

        recovering = false;
        paused = false;

        playedPcmBytes = 0;
        recoveryTargetBytes = 0;

        recoveryAttempts = 0;
        recoveryStartedAtMillis = 0;

        lastBytesPerSecond = 0;

        // Capture stream ID so tick() can send the right OP_END when complete
        MusicStreamBuffer.StreamSession session = MusicStreamBuffer.current();
        currentStreamId = (session != null) ? session.streamId() : null;

        Minecraft minecraft =
                Minecraft.getInstance();

        /*
         * This method is only called from the Minecraft client
         * thread. The network layer explicitly schedules it there.
         */
        minecraft.getMusicManager()
                .stopPlaying();

        long initialSeek = pendingSeekPositionMs;
        pendingSeekPositionMs = -1L;

        if (initialSeek > 0) {
            long bps = (lastBytesPerSecond > 0) ? lastBytesPerSecond : 176400L;
            recoveryTargetBytes = initialSeek * bps / 1000L;
            recovering = true;
            recoveryStartedAtMillis = System.currentTimeMillis();

            StreamingSoundInstance sound =
                    new StreamingSoundInstance(true, initialSeek);

            currentSound = sound;
            playing = true;

            minecraft.getSoundManager()
                    .play(sound);

            System.out.println(
                    "[VibesInDev] Playback started mid-track at " + initialSeek + "ms (target PCM bytes: " + recoveryTargetBytes + ")."
            );
        } else {
            StreamingSoundInstance sound =
                    new StreamingSoundInstance(false, 0L);

            currentSound = sound;
            playing = true;

            minecraft.getSoundManager()
                    .play(sound);

            System.out.println(
                    "[VibesInDev] Minecraft streaming audio playback started."
            );
        }
    }


    // ============================================================
    // PAUSE
    // ============================================================

    public static void pause() {

        if (!playing
                || paused
                || currentSound == null) {
            return;
        }

        recoveryTargetBytes =
                playedPcmBytes;

        paused = true;
        recovering = false;

        StreamingSoundInstance sound =
                currentSound;

        currentSound = null;

        Minecraft.getInstance()
                .getSoundManager()
                .stop(sound);

        System.out.println(
                "[VibesInDev] Minecraft streaming audio paused at "
                        + formatPosition(recoveryTargetBytes)
        );
    }


    // ============================================================
    // RESUME
    // ============================================================

    public static void resume() {

        if (!playing || !paused) {
            return;
        }

        paused = false;

        recovering = true;

        recoveryAttempts = 0;
        recoveryStartedAtMillis =
                System.currentTimeMillis();

        /*
         * Resume from the most recently decoded PCM position.
         * The decoder will rebuild from OGG byte 0 on the
         * decoder executor and skip to this position.
         */
        recoveryTargetBytes =
                Math.max(
                        0,
                        playedPcmBytes
                                - Math.max(
                                0,
                                lastBytesPerSecond
                                        * RECOVERY_REWIND_SECONDS
                        )
                );

        Minecraft minecraft =
                Minecraft.getInstance();

        minecraft.getMusicManager()
                .stopPlaying();

        StreamingSoundInstance sound =
                new StreamingSoundInstance(true);

        currentSound = sound;

        minecraft.getSoundManager()
                .play(sound);

        System.out.println(
                "[VibesInDev] Minecraft streaming audio resuming from "
                        + formatPosition(recoveryTargetBytes)
        );
    }


    // ============================================================
    // STOP
    // ============================================================

    public static void stop() {

        // Set all flags FIRST so that tick() running on the same
        // client thread cannot enter startRecovery() in the brief
        // window before the SoundManager finishes stopping the instance.
        recovering = false;
        paused = false;
        playing = false;

        playedPcmBytes = 0;
        recoveryTargetBytes = 0;
        lastBytesPerSecond = 0;
        recoveryAttempts = 0;
        recoveryStartedAtMillis = 0;

        StreamingSoundInstance sound =
                currentSound;

        currentSound = null;
        currentStreamId = null;

        if (sound != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getSoundManager() != null) {
                mc.getSoundManager().stop(sound);
            }
        }

        System.out.println(
                "[VibesInDev] Minecraft streaming audio playback stopped."
        );
    }


    // ============================================================
    // SEEK
    // ============================================================

    /**
     * Seek to the given position (in milliseconds) within the current track.
     * This rebuilds the decoder from OGG byte 0 and skips to the target position,
     * reusing the same recovery mechanism that resume uses.
     */
    public static void seekTo(long positionMs) {

        if (!playing) {
            pendingSeekPositionMs = positionMs;
            System.out.println(
                    "[VibesInDev] Buffering initial seek to " + positionMs + "ms (pending playback start)."
            );
            return;
        }

        long bytesPerSecond = lastBytesPerSecond;

        if (bytesPerSecond <= 0 && currentSound != null) {
            bytesPerSecond = currentSound.bytesPerSecond;
        }

        recoveryTargetBytes = (bytesPerSecond > 0)
                ? (positionMs * bytesPerSecond / 1000L)
                : (positionMs * 176400L / 1000L);

        paused = false;
        recovering = true;
        recoveryAttempts = 0;
        recoveryStartedAtMillis = System.currentTimeMillis();

        StreamingSoundInstance old = currentSound;
        StreamingSoundInstance replacement = new StreamingSoundInstance(true, positionMs);
        currentSound = replacement;

        Minecraft mc = Minecraft.getInstance();
        if (old != null) {
            mc.getSoundManager().stop(old);
        }
        mc.getSoundManager().play(replacement);

        System.out.println(
                "[VibesInDev] Seeking to " + positionMs + "ms (target PCM bytes: " + recoveryTargetBytes + ")."
        );
    }


    // ============================================================
    // CLIENT TICK / RECOVERY
    // ============================================================

    public static void tick() {

        if (!playing || paused) {
            return;
        }

        StreamingSoundInstance sound =
                currentSound;

        if (sound == null) {
            return;
        }

        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft.getSoundManager()
                .isActive(sound)) {

            if (recovering) {
                recovering = false;
                recoveryAttempts = 0;
                recoveryStartedAtMillis = 0;

                System.out.println(
                        "[VibesInDev] VibesInDev playback recovered at "
                                + formatPosition()
                );
            }

            return;
        }

        /*
         * The sound instance is no longer active.
         *
         * If the complete OGG stream has been received and the
         * decoder has reached the end, this is normal completion.
         */
        if (MusicStreamBuffer.isFinished()) {

            java.util.UUID finishedStreamId = currentStreamId;

            playing = false;
            recovering = false;
            currentSound = null;
            currentStreamId = null;

            playedPcmBytes = 0;
            recoveryTargetBytes = 0;
            lastBytesPerSecond = 0;

            recoveryAttempts = 0;
            recoveryStartedAtMillis = 0;

            System.out.println(
                    "[VibesInDev] Minecraft streaming audio playback finished."
            );

            // Signal the server so it advances the queue / plays the next track
            MusicNetwork.sendPlaybackFinished(finishedStreamId);

            return;
        }

        /*
         * The sound disappeared while the network stream is still
         * active. Start the recovery process.
         */
        long now =
                System.currentTimeMillis();

        if (!recovering) {
            startRecovery();
            return;
        }

        /*
         * A recovery sound may take time to construct because its
         * decoder has to replay OGG data from byte 0.
         *
         * Do not create another decoder every client tick.
         */
        if (now - recoveryStartedAtMillis
                < RECOVERY_TIMEOUT_MILLIS) {
            return;
        }

        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {

            System.err.println(
                    "[VibesInDev] Audio recovery failed after "
                            + MAX_RECOVERY_ATTEMPTS
                            + " attempts. Stopping local playback."
            );

            /*
             * We intentionally only stop the local SoundInstance.
             * The server stream can finish normally and advance
             * its queue.
             */
            playing = false;
            recovering = false;
            currentSound = null;

            return;
        }

        System.out.println(
                "[VibesInDev] Audio recovery attempt timed out. "
                        + "Retrying."
        );

        StreamingSoundInstance oldSound =
                currentSound;

        if (oldSound != null) {
            minecraft.getSoundManager()
                    .stop(oldSound);
        }

        startRecovery();
    }


    private static void startRecovery() {

        StreamingSoundInstance oldSound =
                currentSound;

        long bytesPerSecond =
                lastBytesPerSecond;

        if (bytesPerSecond <= 0
                && oldSound != null) {
            bytesPerSecond =
                    oldSound.bytesPerSecond;
        }

        long rewindBytes =
                Math.max(
                        0,
                        bytesPerSecond
                                * RECOVERY_REWIND_SECONDS
                );

        recoveryTargetBytes =
                Math.max(
                        0,
                        playedPcmBytes - rewindBytes
                );

        recovering = true;

        recoveryAttempts++;

        recoveryStartedAtMillis =
                System.currentTimeMillis();

        System.out.println(
                "[VibesInDev] Minecraft stopped the VibesInDev "
                        + "SoundInstance. Recovery attempt "
                        + recoveryAttempts
                        + "/"
                        + MAX_RECOVERY_ATTEMPTS
        );

        System.out.println(
                "[VibesInDev] Rebuilding decoder from OGG byte 0 "
                        + "and resuming around "
                        + formatPosition(recoveryTargetBytes)
        );

        StreamingSoundInstance replacement =
                new StreamingSoundInstance(true, 0L);

        currentSound = replacement;

        Minecraft.getInstance()
                .getSoundManager()
                .play(replacement);
    }


    // ============================================================
    // POSITION FORMATTING
    // ============================================================

    private static String formatPosition() {
        return formatPosition(
                recovering
                        ? recoveryTargetBytes
                        : playedPcmBytes
        );
    }

    private static String formatPosition(
            long bytes
    ) {

        long bytesPerSecond =
                lastBytesPerSecond;

        if (bytesPerSecond <= 0
                && currentSound != null) {
            bytesPerSecond =
                    currentSound.bytesPerSecond;
        }

        if (bytesPerSecond <= 0) {
            return "00:00";
        }

        long seconds =
                bytes / bytesPerSecond;

        long minutes =
                seconds / 60;

        long remaining =
                seconds % 60;

        return "%02d:%02d".formatted(
                minutes,
                remaining
        );
    }


    // ============================================================
    // STREAMING SOUND INSTANCE
    // ============================================================

    private static final class StreamingSoundInstance
            extends AbstractSoundInstance
            implements FabricSoundInstance, TickableSoundInstance {

        private final boolean resume;
        private final long initialSeekMs;
        private volatile long bytesPerSecond;
        private int tickCount = 0;

        private StreamingSoundInstance(
                boolean resume
        ) {
            this(resume, 0L);
        }

        private StreamingSoundInstance(
                boolean resume,
                long initialSeekMs
        ) {
            super(
                    STREAM_SOUND_ID,
                    SoundSource.RECORDS,
                    RandomSource.create()
            );

            this.resume = resume;
            this.initialSeekMs = initialSeekMs;

            // Start volume at 0 if resuming/reconnecting so we can smoothly fade in
            this.volume = resume ? 0.0F : MusicAudioPlayer.volume;

            this.pitch = 1.0F;
            this.looping = false;
            this.relative = true;
            this.attenuation =
                    Attenuation.NONE;
        }


        // ========================================================
        // DYNAMIC VOLUME
        // ========================================================

        @Override
        public float getVolume() {
            if (resume && tickCount < 10) {
                float fadeRatio = Math.min(1.0F, (float) tickCount / 10.0F);
                return MusicAudioPlayer.volume * fadeRatio;
            }
            return MusicAudioPlayer.volume;
        }

        private void updateVolume() {
            this.volume = getVolume();
        }


        // ========================================================
        // TICKABLE SOUND
        // ========================================================

        @Override
        public void tick() {
            if (resume && tickCount < 10) {
                tickCount++;
            }
            this.volume = getVolume();
        }

        @Override
        public boolean isStopped() {
            return !MusicAudioPlayer.playing
                    || MusicAudioPlayer.paused
                    || MusicAudioPlayer.currentSound != this;
        }


        // ========================================================
        // AUDIO STREAM CREATION
        // ========================================================

        @Override
        public CompletableFuture<AudioStream> getAudioStream(
                SoundBufferLibrary library,
                Identifier id,
                boolean repeatInstantly
        ) {

            MusicStreamBuffer.StreamInputStream input =
                    MusicStreamBuffer.openInputStream();

            if (input == null) {
                return CompletableFuture.failedFuture(
                        new IOException(
                                "No active VibesInDev audio stream."
                        )
                );
            }

            /*
             * Do decoder creation and recovery seeking away from
             * Minecraft's client thread. This prevents a recovery
             * from freezing the game while JOrbis decodes from byte 0.
             */
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            System.out.println(
                                    "[VibesInDev] Creating Minecraft "
                                            + "JOrbis decoder from OGG byte 0."
                            );

                            JOrbisAudioStream decoder =
                                    new JOrbisAudioStream(input);

                            AudioFormat format =
                                    decoder.getFormat();

                            bytesPerSecond =
                                    calculateBytesPerSecond(
                                            format
                                    );

                            lastBytesPerSecond =
                                    bytesPerSecond;

                            if (!resume) {

                                playedPcmBytes = 0;

                                System.out.println(
                                        "[VibesInDev] Audio format: "
                                                + format
                                );

                                System.out.println(
                                        "[VibesInDev] PCM rate: "
                                                + bytesPerSecond
                                                + " bytes/sec"
                                );

                                return new PositionTrackingAudioStream(
                                        decoder,
                                        0
                                );
                            }

                            long target = (initialSeekMs > 0 && bytesPerSecond > 0)
                                    ? (initialSeekMs * bytesPerSecond / 1000L)
                                    : recoveryTargetBytes;

                            System.out.println(
                                    "[VibesInDev] Recovery decoder "
                                            + "seeking to approximately "
                                            + target
                                            + " PCM bytes ("
                                            + (bytesPerSecond > 0 ? (target * 1000L / bytesPerSecond) : 0)
                                            + "ms)."
                            );

                            skipDecodedPcm(
                                    decoder,
                                    target
                            );

                            playedPcmBytes =
                                    target;
                            recoveryTargetBytes =
                                    target;

                            System.out.println(
                                    "[VibesInDev] Recovery decoder "
                                            + "ready at "
                                            + formatPosition(target)
                            );

                            return new PositionTrackingAudioStream(
                                    decoder,
                                    target
                            );

                        } catch (IOException e) {
                            input.close();

                            throw new CompletionException(
                                    "Could not create VibesInDev OGG/Vorbis decoder.",
                                    e
                            );
                        }
                    },
                    DECODER_EXECUTOR
            );
        }


        // ========================================================
        // PCM BYTE RATE
        // ========================================================

        private long calculateBytesPerSecond(
                AudioFormat format
        ) {

            int frameSize =
                    format.getFrameSize();

            if (frameSize <= 0) {
                int channels =
                        format.getChannels();

                int sampleBits =
                        format.getSampleSizeInBits();

                frameSize =
                        Math.max(
                                1,
                                channels
                                        * Math.max(
                                        1,
                                        sampleBits / 8
                                )
                        );
            }

            return Math.max(
                    1,
                    (long) (
                            format.getFrameRate()
                                    * frameSize
                    )
            );
        }


        // ========================================================
        // DECODER SEEK
        // ========================================================

        private void skipDecodedPcm(
                JOrbisAudioStream decoder,
                long targetBytes
        ) throws IOException {

            if (targetBytes <= 0) {
                return;
            }

            long skipped = 0;

            while (skipped < targetBytes) {

                int remaining =
                        (int) Math.min(
                                64 * 1024,
                                targetBytes - skipped
                        );

                ByteBuffer pcm =
                        decoder.read(remaining);

                if (pcm == null
                        || !pcm.hasRemaining()) {
                    break;
                }

                skipped +=
                        pcm.remaining();

                /*
                 * Do not update playedPcmBytes while seeking.
                 * The replacement decoder starts counting only
                 * after it has reached the recovery position.
                 */
            }
        }
    }


    // ============================================================
    // POSITION TRACKING AUDIO STREAM
    // ============================================================

    private static final class PositionTrackingAudioStream
            implements AudioStream {

        private final AudioStream delegate;

        private long position;

        private PositionTrackingAudioStream(
                AudioStream delegate,
                long initialPosition
        ) {
            this.delegate =
                    delegate;

            this.position =
                    initialPosition;
        }

        @Override
        public AudioFormat getFormat() {
            return delegate.getFormat();
        }

        @Override
        public ByteBuffer read(
                int expectedSize
        ) throws IOException {

            ByteBuffer buffer =
                    delegate.read(expectedSize);

            if (buffer != null) {

                int amount =
                        buffer.remaining();

                position += amount;

                playedPcmBytes =
                        position;
            }

            return buffer;
        }

        @Override
        public void close()
                throws IOException {

            delegate.close();
        }
    }
}
