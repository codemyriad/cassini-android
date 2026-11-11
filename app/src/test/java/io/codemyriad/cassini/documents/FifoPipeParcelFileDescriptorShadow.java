package io.codemyriad.cassini.documents;

import android.os.ParcelFileDescriptor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowParcelFileDescriptor;
import org.robolectric.util.ReflectionHelpers;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Robolectric shadow replacing {@link ShadowParcelFileDescriptor#createPipe()} (which uses a regular file on JVM) with
 * a real Linux kernel FIFO ({@code O_RDONLY} / {@code O_WRONLY}) plus {@code detachFd()} / {@code adoptFd(int)}
 * ownership hand-off (including {@code ContentResolver}'s {@code mWrapped} {@code ParcelFileDescriptorInner} wrapper)
 * so unit tests exercise real blocking pipe streaming, {@code EPIPE} before writer close, and native detached-fd
 * lifecycles.
 */
@Implements(ParcelFileDescriptor.class)
public class FifoPipeParcelFileDescriptorShadow extends ShadowParcelFileDescriptor {
    private static final long OPEN_TIMEOUT_SEC = 5L;
    private static final AtomicInteger NEXT_DETACHED_FD = new AtomicInteger(100_000);
    private static final Map<Integer, PipeEnd> DETACHED_BY_HANDLE = new ConcurrentHashMap<>();
    private static final Map<FileDescriptor, SharedPipeState> SHARED_BY_READ_FD = new ConcurrentHashMap<>();

    @RealObject
    private ParcelFileDescriptor realPfd;

    private PipeEnd pipeEnd;

    public static boolean isWriterClosed(FileDescriptor readFd) {
        if (!readFd.valid()) return true;
        SharedPipeState shared = SHARED_BY_READ_FD.get(readFd);
        return shared == null || shared.openWriters.get() <= 0;
    }

    public static void resetDetached() {
        for (PipeEnd end : DETACHED_BY_HANDLE.values()) {
            try {
                end.closeUnderlying();
            } catch (IOException ignored) {
                // Best-effort test cleanup.
            }
        }
        DETACHED_BY_HANDLE.clear();
    }

    @Implementation
    protected static ParcelFileDescriptor[] createPipe() throws IOException {
        File dir = Files.createTempDirectory("cassini-doc-fifo").toFile();
        File fifo = new File(dir, "pipe.fifo");
        try {
            int exit = new ProcessBuilder("mkfifo", "-m", "600", fifo.getAbsolutePath()).start().waitFor();
            if (exit != 0) throw new IOException("mkfifo failed with exit code " + exit);
            CompletableFuture<FileInputStream> readerFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new FileInputStream(fifo);
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            });
            FileOutputStream writeStream = new FileOutputStream(fifo);
            FileInputStream readStream = readerFuture.get(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS);
            SharedPipeState shared = new SharedPipeState(new AtomicInteger(1));
            SHARED_BY_READ_FD.put(readStream.getFD(), shared);
            return new ParcelFileDescriptor[]{
                wrapEnd(new ReaderEnd(readStream.getFD(), readStream)),
                wrapEnd(new WriterEnd(writeStream.getFD(), writeStream, shared))
            };
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Failed to open FIFO pipe", error);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            fifo.delete();
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    @Implementation
    protected static ParcelFileDescriptor[] createReliablePipe() throws IOException {
        return createPipe();
    }

    @Implementation
    public static ParcelFileDescriptor adoptFd(int fd) {
        PipeEnd detached = DETACHED_BY_HANDLE.remove(fd);
        if (detached == null) {
            throw new IllegalArgumentException("Unknown detached fd handle: " + fd);
        }
        detached.wrapperClosed.set(false);
        return wrapEnd(detached);
    }

    private static ParcelFileDescriptor wrapEnd(PipeEnd end) {
        ParcelFileDescriptor pfd = ReflectionHelpers.callConstructor(ParcelFileDescriptor.class);
        FifoPipeParcelFileDescriptorShadow shadow = Shadow.extract(pfd);
        shadow.pipeEnd = end;
        return pfd;
    }

    private ParcelFileDescriptor wrapped() {
        return ReflectionHelpers.getField(realPfd, "mWrapped");
    }

    @Implementation
    @Override
    protected FileDescriptor getFileDescriptor() {
        if (pipeEnd != null) {
            if (pipeEnd.wrapperClosed.get()) throw new IllegalStateException("Already closed");
            return pipeEnd.fd;
        }
        ParcelFileDescriptor inner = wrapped();
        if (inner != null) return inner.getFileDescriptor();
        return super.getFileDescriptor();
    }

    @Implementation
    @Override
    protected long getStatSize() {
        if (pipeEnd != null) return -1L;
        ParcelFileDescriptor inner = wrapped();
        if (inner != null) return inner.getStatSize();
        return super.getStatSize();
    }

    @Implementation
    @Override
    protected int getFd() {
        if (pipeEnd != null) {
            if (pipeEnd.wrapperClosed.get()) throw new IllegalStateException("Already closed");
            return ReflectionHelpers.getField(pipeEnd.fd, "fd");
        }
        ParcelFileDescriptor inner = wrapped();
        if (inner != null) return inner.getFd();
        return super.getFd();
    }

    @Implementation
    public int detachFd() {
        if (pipeEnd != null) {
            if (!pipeEnd.wrapperClosed.compareAndSet(false, true)) {
                throw new IllegalStateException("Already closed");
            }
            int handle = NEXT_DETACHED_FD.incrementAndGet();
            DETACHED_BY_HANDLE.put(handle, pipeEnd);
            return handle;
        }
        ParcelFileDescriptor inner = wrapped();
        if (inner != null) {
            int handle = inner.detachFd();
            ReflectionHelpers.callInstanceMethod(realPfd, "releaseResources");
            return handle;
        }
        throw new UnsupportedOperationException("detachFd only supported on pipe descriptors in shadow");
    }

    @Implementation
    @Override
    protected void close() throws IOException {
        if (pipeEnd != null) {
            if (pipeEnd.wrapperClosed.compareAndSet(false, true)) {
                pipeEnd.closeUnderlying();
            }
            return;
        }
        ParcelFileDescriptor inner = wrapped();
        if (inner != null) {
            try {
                inner.close();
            } finally {
                ReflectionHelpers.callInstanceMethod(realPfd, "releaseResources");
            }
            return;
        }
        super.close();
    }

    private static final class SharedPipeState {
        final AtomicInteger openWriters;

        SharedPipeState(AtomicInteger openWriters) {
            this.openWriters = openWriters;
        }
    }

    private abstract static class PipeEnd {
        final FileDescriptor fd;
        final Closeable stream;
        final AtomicBoolean wrapperClosed = new AtomicBoolean(false);

        PipeEnd(FileDescriptor fd, Closeable stream) {
            this.fd = fd;
            this.stream = stream;
        }

        abstract void closeUnderlying() throws IOException;
    }

    private static final class ReaderEnd extends PipeEnd {
        ReaderEnd(FileDescriptor fd, FileInputStream stream) {
            super(fd, stream);
        }

        @Override
        void closeUnderlying() throws IOException {
            SHARED_BY_READ_FD.remove(fd);
            stream.close();
        }
    }

    private static final class WriterEnd extends PipeEnd {
        private final SharedPipeState shared;

        WriterEnd(FileDescriptor fd, FileOutputStream stream, SharedPipeState shared) {
            super(fd, stream);
            this.shared = shared;
        }

        @Override
        void closeUnderlying() throws IOException {
            shared.openWriters.decrementAndGet();
            stream.close();
        }
    }
}
