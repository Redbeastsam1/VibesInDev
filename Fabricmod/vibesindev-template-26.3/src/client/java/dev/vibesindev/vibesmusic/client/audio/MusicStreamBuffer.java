package dev.vibesindev.vibesmusic.client.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MusicStreamBuffer {

    private static final long READ_WAIT_MILLIS = 250L;
    private static final long MAX_UNDERRUN_WAIT_MILLIS = 3000L;

    private static volatile StreamSession current;

    private MusicStreamBuffer() {
    }

    public static synchronized void begin(
            UUID streamId,
            long totalBytes,
            String title,
            String artist
    ) {
        if (current != null) {
            current.close();
        }

        current = new StreamSession(
                streamId,
                totalBytes,
                title,
                artist
        );

        System.out.println(
                "[VibesInDev] Audio stream started: " + title
        );
    }

    public static synchronized void append(
            UUID streamId,
            int sequence,
            byte[] data,
            int offset,
            int length
    ) {
        StreamSession session = current;

        if (session == null
                || !session.streamId.equals(streamId)
                || session.closed) {
            return;
        }

        if (sequence != session.nextSequence) {
            System.err.println(
                    "[VibesInDev] Audio packet sequence error. Expected "
                            + session.nextSequence
                            + ", received "
                            + sequence
            );

            session.close();
            return;
        }

        if (data == null
                || offset < 0
                || length <= 0
                || offset + length > data.length) {
            return;
        }

        session.nextSequence++;

        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);

        synchronized (session.lock) {
            session.chunks.add(copy);
            session.receivedBytes += length;
            session.lock.notifyAll();
        }
    }

    public static synchronized void end(UUID streamId) {
        StreamSession session = current;

        if (session == null
                || !session.streamId.equals(streamId)) {
            return;
        }

        synchronized (session.lock) {
            session.ended = true;
            session.lock.notifyAll();
        }

        System.out.println(
                "[VibesInDev] Audio stream ended."
        );
    }

    public static synchronized void stop(UUID streamId) {
        StreamSession session = current;

        if (session == null
                || !session.streamId.equals(streamId)) {
            return;
        }

        session.close();
        current = null;

        System.out.println(
                "[VibesInDev] Audio stream stopped."
        );
    }

    /**
     * Immediately closes whichever stream is currently active.
     *
     * This is used when the Minecraft server connection disappears.
     * There is no server-side OP_STOP packet available after the
     * connection has already closed, so the client must close the
     * shared stream itself and wake any decoder thread blocked in
     * StreamInputStream.read().
     */
    public static synchronized void stopCurrent() {
        StreamSession session = current;

        if (session == null) {
            return;
        }

        session.close();
        current = null;

        System.out.println(
                "[VibesInDev] Active audio stream cleared."
        );
    }

    public static synchronized StreamInputStream openInputStream() {
        StreamSession session = current;

        if (session == null || session.closed) {
            return null;
        }

        return new StreamInputStream(session);
    }

    public static StreamSession current() {
        return current;
    }

    public static boolean hasBufferedAtLeast(long bytes) {
        StreamSession session = current;

        if (session == null || session.closed) {
            return false;
        }

        synchronized (session.lock) {
            return session.receivedBytes >= bytes;
        }
    }

    public static boolean isFinished() {
        StreamSession session = current;

        if (session == null) {
            return true;
        }

        synchronized (session.lock) {
            return session.closed
                    || (
                    session.ended
                            && (
                            session.totalBytes <= 0
                                    || session.receivedBytes >= session.totalBytes
                    )
            );
        }
    }

    public static long receivedBytes() {
        StreamSession session = current;

        if (session == null) {
            return 0;
        }

        synchronized (session.lock) {
            return session.receivedBytes;
        }
    }

    public static final class StreamSession {

        private final UUID streamId;
        private final long totalBytes;
        private final String title;
        private final String artist;

        private final List<byte[]> chunks =
                new ArrayList<>();

        private final Object lock =
                new Object();

        private volatile boolean ended;
        private volatile boolean closed;

        private int nextSequence;
        private long receivedBytes;

        private StreamSession(
                UUID streamId,
                long totalBytes,
                String title,
                String artist
        ) {
            this.streamId = streamId;
            this.totalBytes = totalBytes;
            this.title = title;
            this.artist = artist;
        }

        public UUID streamId() {
            return streamId;
        }

        public long totalBytes() {
            return totalBytes;
        }

        public String title() {
            return title;
        }

        public String artist() {
            return artist;
        }

        public long receivedBytes() {
            synchronized (lock) {
                return receivedBytes;
            }
        }

        private void close() {
            closed = true;
            ended = true;

            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    public static final class StreamInputStream
            extends InputStream {

        private final StreamSession session;

        private int chunkIndex;
        private int chunkOffset;
        private long position;

        private long lastDataSeenNanos =
                System.nanoTime();

        private final byte[] singleByteBuffer = new byte[1];

        private StreamInputStream(StreamSession session) {
            this.session = session;
        }

        @Override
        public int read() throws IOException {
            int count = read(singleByteBuffer, 0, 1);

            if (count == -1) {
                return -1;
            }

            return singleByteBuffer[0] & 0xFF;
        }

        @Override
        public int read(
                byte[] buffer,
                int offset,
                int length
        ) throws IOException {

            if (buffer == null) {
                throw new NullPointerException("buffer");
            }

            if (offset < 0
                    || length < 0
                    || offset + length > buffer.length) {
                throw new IndexOutOfBoundsException();
            }

            if (length == 0) {
                return 0;
            }

            while (true) {
                synchronized (session.lock) {
                    if (session.closed) {
                        return -1;
                    }

                    if (position < session.receivedBytes) {
                        int copied = copyAvailable(
                                buffer,
                                offset,
                                length
                        );

                        if (copied > 0) {
                            lastDataSeenNanos =
                                    System.nanoTime();

                            return copied;
                        }
                    }

                    if (session.ended) {
                        return -1;
                    }

                    long waitedMillis =
                            (System.nanoTime()
                                    - lastDataSeenNanos)
                                    / 1_000_000L;

                    if (waitedMillis >= MAX_UNDERRUN_WAIT_MILLIS) {
                        throw new IOException(
                                "VibesInDev audio buffer underrun."
                        );
                    }

                    try {
                        session.lock.wait(
                                Math.min(
                                        READ_WAIT_MILLIS,
                                        MAX_UNDERRUN_WAIT_MILLIS
                                                - waitedMillis
                                )
                        );
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();

                        throw new IOException(
                                "Audio stream interrupted.",
                                e
                        );
                    }
                }
            }
        }

        private int copyAvailable(
                byte[] destination,
                int destinationOffset,
                int requested
        ) {
            int copied = 0;

            while (copied < requested
                    && position < session.receivedBytes) {

                while (chunkIndex < session.chunks.size()
                        && chunkOffset
                        >= session.chunks.get(chunkIndex).length) {
                    chunkIndex++;
                    chunkOffset = 0;
                }

                if (chunkIndex >= session.chunks.size()) {
                    break;
                }

                byte[] chunk =
                        session.chunks.get(chunkIndex);

                int available =
                        chunk.length - chunkOffset;

                int amount =
                        Math.min(
                                available,
                                requested - copied
                        );

                System.arraycopy(
                        chunk,
                        chunkOffset,
                        destination,
                        destinationOffset + copied,
                        amount
                );

                chunkOffset += amount;
                position += amount;
                copied += amount;
            }

            return copied;
        }

        public boolean isEof() {
            synchronized (session.lock) {
                return session.ended && position >= session.receivedBytes;
            }
        }

        @Override
        public void close() {
            /*
             * Deliberately do not close the shared stream session.
             * Minecraft may close this decoder while VibesInDev
             * recovers by creating a replacement decoder.
             */
        }
    }
}
