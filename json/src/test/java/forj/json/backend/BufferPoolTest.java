package forj.json.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.json.spi.JsonLimits;
import forj.json.spi.JsonReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BufferPoolTest {
    @Test
    void manyVirtualThreadsShareABoundedPool() throws Exception {
        ForjBackend backend = ForjBackend.of(JsonLimits.DEFAULT, 8192, 64);
        byte[] doc = "{\"name\":\"ana\",\"tags\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
        AtomicInteger read = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100_000; i++) {
                executor.submit(() -> {
                    try (JsonReader r = backend.reader(new ByteArrayInputStream(doc))) {
                        r.skipValue();
                        r.endDocument();
                    }
                    read.incrementAndGet();
                });
            }
        }
        assertEquals(100_000, read.get());
        // every reader gave its buffer back, and the pool kept no more than its slots
        assertTrue(backend.pooledBuffers() <= 64, "pooled " + backend.pooledBuffers());
        assertTrue(backend.pooledBuffers() > 0);
    }

    @Test
    void closingTwiceReturnsTheBufferOnce() {
        ForjBackend backend = ForjBackend.of(JsonLimits.DEFAULT, 64, 8);
        JsonReader r = backend.reader(new ByteArrayInputStream(new byte[] {'1'}));
        r.close();
        r.close();
        assertEquals(1, backend.pooledBuffers());
    }
}
