package forj.json.backend;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A bounded, lock-free pool of byte buffers shared by all threads.
 *
 * <p>Not a {@code ThreadLocal} cache: with virtual threads there can be a million threads, and
 * a buffer per thread would be a million buffers. The pool keeps at most {@code slots} buffers
 * however many threads use it. When it's empty a new buffer is allocated, and when it's full a
 * returned buffer is dropped for the GC, so it never blocks.
 */
final class BufferPool {
    private final AtomicReferenceArray<byte[]> slots;
    private final int bufferSize;
    private final int mask;

    BufferPool(int slots, int bufferSize) {
        int size = Integer.highestOneBit(Math.max(1, slots) - 1) << 1;
        this.slots = new AtomicReferenceArray<>(Math.max(1, size));
        this.mask = this.slots.length() - 1;
        this.bufferSize = bufferSize;
    }

    int bufferSize() {
        return bufferSize;
    }

    byte[] acquire() {
        int start = start();
        for (int i = 0; i <= mask; i++) {
            byte[] buffer = slots.getAndSet((start + i) & mask, null);
            if (buffer != null) return buffer;
        }
        return new byte[bufferSize];
    }

    void release(byte[] buffer) {
        if (buffer.length != bufferSize) return;
        int start = start();
        for (int i = 0; i <= mask; i++) {
            if (slots.compareAndSet((start + i) & mask, null, buffer)) return;
        }
    }

    /** Spreads threads over the slots so they rarely contend for the same one. */
    private int start() {
        long id = Thread.currentThread().threadId();
        return (int) (id ^ (id >>> 32)) * 0x9E3779B9 >>> 16;
    }

    /** Buffers currently pooled; for tests. */
    int pooled() {
        int n = 0;
        for (int i = 0; i <= mask; i++) if (slots.get(i) != null) n++;
        return n;
    }
}
