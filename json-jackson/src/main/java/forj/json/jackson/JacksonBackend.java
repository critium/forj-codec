package forj.json.jackson;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import forj.json.spi.JsonBackend;
import forj.json.spi.JsonLimits;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * forj's {@link JsonBackend} on Jackson's streaming parser and generator (jackson-core only:
 * no databind, no reflection). Any forj codec runs on it unchanged; swap it in with a given:
 *
 * <pre>{@code
 * given JsonBackend backend = JacksonBackend.DEFAULT;
 * }</pre>
 *
 * <p>Set up to behave like forj's own backend: strict JSON (Jackson's defaults), forj's
 * {@link JsonLimits} as Jackson's read constraints, caller-owned streams never closed, open
 * objects never auto-closed, the same output bytes (lowercase hex escapes, characters outside
 * the BMP as UTF-8), and a bounded buffer pool shared by all threads instead of Jackson's
 * default per-thread buffers (which a million virtual threads would multiply).
 */
public final class JacksonBackend implements JsonBackend {
    public static final int DEFAULT_POOLED_BUFFERS = 256;

    public static final JacksonBackend DEFAULT = of(JsonLimits.DEFAULT);

    private final JsonLimits limits;
    private final JsonFactory factory;

    private JacksonBackend(JsonLimits limits, int pooledBuffers) {
        this.limits = limits;
        this.factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(limits.maxDepth())
                        .maxStringLength(limits.maxStringLength())
                        .maxNameLength(limits.maxStringLength())
                        .maxNumberLength(limits.maxNumberLength())
                        .maxDocumentLength(limits.maxDocumentSize())
                        .build())
                .recyclerPool(JsonRecyclerPools.newBoundedPool(pooledBuffers))
                .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT)
                // write what forj's backend writes: \u001f rather than \u001F, and characters
                // outside the BMP as UTF-8 rather than as escaped surrogate pairs
                .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
                .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
                .build();
    }

    public static JacksonBackend of(JsonLimits limits) {
        return new JacksonBackend(limits, DEFAULT_POOLED_BUFFERS);
    }

    public static JacksonBackend of(JsonLimits limits, int pooledBuffers) {
        return new JacksonBackend(limits, pooledBuffers);
    }

    public JsonLimits limits() {
        return limits;
    }

    @Override
    public JsonReader reader(byte[] bytes, int offset, int length) {
        if (length > limits.maxDocumentSize()) {
            throw new forj.json.spi.JsonException("document larger than " + limits.maxDocumentSize() + " bytes", 0, "$");
        }
        try {
            return new JacksonReader(factory.createParser(bytes, offset, length), limits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public JsonReader reader(byte[] bytes) {
        return reader(bytes, 0, bytes.length);
    }

    @Override
    public JsonReader reader(InputStream in) {
        try {
            return new JacksonReader(factory.createParser(in), limits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public JsonWriter writer(OutputStream out) {
        try {
            return new JacksonWriter(factory.createGenerator(out, JsonEncoding.UTF8), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public JsonWriter writer() {
        ByteArrayOutputStream memory = new ByteArrayOutputStream(256);
        try {
            return new JacksonWriter(factory.createGenerator(memory, JsonEncoding.UTF8), memory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "JacksonBackend[" + limits + "]";
    }
}
