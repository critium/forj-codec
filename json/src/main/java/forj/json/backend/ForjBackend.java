package forj.json.backend;

import forj.json.spi.JsonBackend;
import forj.json.spi.JsonLimits;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * forj's own JSON backend, with no third-party code: the default {@link JsonBackend}.
 *
 * <p>Readers and writers over streams share one bounded buffer pool, so memory stays about one
 * buffer per open reader or writer however many threads there are.
 */
public final class ForjBackend implements JsonBackend {
    public static final int DEFAULT_BUFFER_SIZE = 8192;
    public static final int DEFAULT_POOLED_BUFFERS = 256;

    public static final ForjBackend DEFAULT =
            new ForjBackend(JsonLimits.DEFAULT, DEFAULT_BUFFER_SIZE, DEFAULT_POOLED_BUFFERS);

    private final JsonLimits limits;
    private final BufferPool pool;

    private ForjBackend(JsonLimits limits, int bufferSize, int pooledBuffers) {
        if (bufferSize < 16) throw new IllegalArgumentException("buffer size below 16 bytes");
        this.limits = limits;
        this.pool = new BufferPool(pooledBuffers, bufferSize);
    }

    /** A backend with its own limits and its own pool of {@code pooledBuffers} buffers. */
    public static ForjBackend of(JsonLimits limits, int bufferSize, int pooledBuffers) {
        return new ForjBackend(limits, bufferSize, pooledBuffers);
    }

    public JsonLimits limits() {
        return limits;
    }

    public int bufferSize() {
        return pool.bufferSize();
    }

    /** Buffers currently in the pool, waiting to be reused. */
    public int pooledBuffers() {
        return pool.pooled();
    }

    @Override
    public JsonReader reader(byte[] bytes, int offset, int length) {
        return new ForjReader(bytes, offset, length, limits);
    }

    @Override
    public JsonReader reader(byte[] bytes) {
        return new ForjReader(bytes, 0, bytes.length, limits);
    }

    @Override
    public JsonReader reader(InputStream in) {
        return new ForjReader(in, pool, limits);
    }

    @Override
    public JsonWriter writer(OutputStream out) {
        return new ForjWriter(out, pool);
    }

    @Override
    public String toString() {
        return "ForjBackend[" + limits + ", buffer=" + pool.bufferSize() + "]";
    }
}
