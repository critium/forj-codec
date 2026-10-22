package forj.json.backend;

import forj.json.spi.FieldNames;
import forj.json.spi.JsonWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * forj's writer: UTF-8 straight into one pooled buffer, flushed to the output stream whenever
 * it fills, so a response of any size needs about one buffer of memory.
 *
 * <p>Without an output stream it writes to memory: a full buffer is kept as a segment and
 * writing continues in a new, larger one, so nothing written is copied until
 * {@link #toByteArray()} copies every segment once into the result.
 */
final class ForjWriter implements JsonWriter {
    private static final int DOCUMENT_EMPTY = 1;
    private static final int DOCUMENT_DONE = 2;
    private static final int ARRAY_EMPTY = 3;
    private static final int ARRAY_NONEMPTY = 4;
    private static final int OBJECT_EMPTY = 5;
    private static final int OBJECT_NAMED = 6;
    private static final int OBJECT_NONEMPTY = 7;

    /** For each ASCII char, 0 if it's written as is, else the char after the backslash ('u' for \\u00XX). */
    private static final byte[] ESCAPES = new byte[128];
    private static final byte[] HEX = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    private static final byte[] LONG_MIN = "-9223372036854775808".getBytes(StandardCharsets.US_ASCII);

    static {
        for (int c = 0; c < 0x20; c++) ESCAPES[c] = 'u';
        ESCAPES['"'] = '"';
        ESCAPES['\\'] = '\\';
        ESCAPES['\n'] = 'n';
        ESCAPES['\r'] = 'r';
        ESCAPES['\t'] = 't';
        ESCAPES['\b'] = 'b';
        ESCAPES['\f'] = 'f';
    }

    private static final int MAX_SEGMENT = 1 << 18;

    /** Null for an in-memory writer. */
    private final OutputStream out;
    private final BufferPool pool;
    /** The pooled buffer, given back on close; buf moves on to fresh segments in memory mode. */
    private byte[] pooled;
    private byte[] buf;
    private int pos;
    /** In-memory mode: the filled segments before buf, and their used lengths. */
    private byte[][] segments;
    private int[] segmentLengths;
    private int segmentCount;
    private long segmentsSize;
    /** Doubles are formatted here: StringBuilder.append(double) writes digits without a String. */
    private StringBuilder digits;
    private int[] stack = new int[16];
    private int depth = 1;

    ForjWriter(OutputStream out, BufferPool pool) {
        this.out = out;
        this.pool = pool;
        this.buf = this.pooled = pool.acquire();
        stack[0] = DOCUMENT_EMPTY;
    }

    // ---- structure

    private void beforeValue() {
        switch (stack[depth - 1]) {
            case DOCUMENT_EMPTY -> stack[depth - 1] = DOCUMENT_DONE;
            case ARRAY_EMPTY -> stack[depth - 1] = ARRAY_NONEMPTY;
            case ARRAY_NONEMPTY -> writeByte(',');
            case OBJECT_NAMED -> stack[depth - 1] = OBJECT_NONEMPTY;
            case DOCUMENT_DONE -> throw new IllegalStateException("a document has one top-level value");
            case OBJECT_EMPTY, OBJECT_NONEMPTY -> throw new IllegalStateException("expected a field name");
            default -> throw new IllegalStateException("writer is closed");
        }
    }

    private void beforeName() {
        switch (stack[depth - 1]) {
            case OBJECT_EMPTY -> stack[depth - 1] = OBJECT_NAMED;
            case OBJECT_NONEMPTY -> {
                writeByte(',');
                stack[depth - 1] = OBJECT_NAMED;
            }
            case OBJECT_NAMED -> throw new IllegalStateException("expected a value after the field name");
            default -> throw new IllegalStateException("field names belong inside an object");
        }
    }

    private void open(int scope, char bracket) {
        beforeValue();
        if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
        stack[depth++] = scope;
        writeByte(bracket);
    }

    private void close(int empty, int nonempty, char bracket) {
        int top = stack[depth - 1];
        if (top != empty && top != nonempty) {
            throw new IllegalStateException(top == OBJECT_NAMED ? "expected a value after the field name" : "nothing to close here");
        }
        depth--;
        writeByte(bracket);
    }

    @Override
    public JsonWriter beginObject() {
        open(OBJECT_EMPTY, '{');
        return this;
    }

    @Override
    public JsonWriter endObject() {
        close(OBJECT_EMPTY, OBJECT_NONEMPTY, '}');
        return this;
    }

    @Override
    public JsonWriter beginArray() {
        open(ARRAY_EMPTY, '[');
        return this;
    }

    @Override
    public JsonWriter endArray() {
        close(ARRAY_EMPTY, ARRAY_NONEMPTY, ']');
        return this;
    }

    @Override
    public JsonWriter name(FieldNames names, int index) {
        beforeName();
        writeBytes(names.encodedField(index));
        return this;
    }

    @Override
    public JsonWriter name(String name) {
        beforeName();
        writeString(name);
        writeByte(':');
        return this;
    }

    // ---- values

    @Override
    public JsonWriter value(String value) {
        if (value == null) return nullValue();
        beforeValue();
        writeString(value);
        return this;
    }

    @Override
    public JsonWriter value(boolean value) {
        beforeValue();
        writeAscii(value ? "true" : "false");
        return this;
    }

    @Override
    public JsonWriter value(long value) {
        beforeValue();
        if (value == Long.MIN_VALUE) {
            writeBytes(LONG_MIN);
            return this;
        }
        if (buf.length < 20) { // tiny test buffers: the digits might not fit after a flush
            writeAscii(Long.toString(value));
            return this;
        }
        if (buf.length - pos < 20) flushBuffer();
        if (value < 0) {
            buf[pos++] = '-';
            value = -value;
        }
        int length = 1;
        for (long v = value; v >= 10; v /= 10) length++;
        int p = pos + length;
        do {
            buf[--p] = (byte) ('0' + value % 10);
            value /= 10;
        } while (value != 0);
        pos += length;
        return this;
    }

    @Override
    public JsonWriter value(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("JSON has no " + value);
        beforeValue();
        StringBuilder d = digits;
        if (d == null) d = digits = new StringBuilder(32);
        d.setLength(0);
        d.append(value); // the same shortest digits as Double.toString
        int length = d.length();
        if (length > buf.length) { // only tiny test buffers
            writeAscii(d.toString());
            return this;
        }
        if (buf.length - pos < length) flushBuffer();
        for (int i = 0; i < length; i++) buf[pos++] = (byte) d.charAt(i);
        return this;
    }

    @Override
    public JsonWriter value(BigDecimal value) {
        if (value == null) return nullValue();
        beforeValue();
        writeAscii(value.toString());
        return this;
    }

    @Override
    public JsonWriter nullValue() {
        beforeValue();
        writeAscii("null");
        return this;
    }

    // ---- bytes

    private void writeString(String s) {
        if (buf.length - pos < 2) flushBuffer();
        buf[pos++] = '"';
        int length = s.length();
        for (int i = 0; i < length; i++) {
            if (buf.length - pos < 12) flushBuffer();
            char c = s.charAt(i);
            if (c < 0x80) {
                byte escape = ESCAPES[c];
                if (escape == 0) {
                    buf[pos++] = (byte) c;
                } else if (escape == 'u') {
                    buf[pos++] = '\\';
                    buf[pos++] = 'u';
                    buf[pos++] = '0';
                    buf[pos++] = '0';
                    buf[pos++] = HEX[c >> 4];
                    buf[pos++] = HEX[c & 0xF];
                } else {
                    buf[pos++] = '\\';
                    buf[pos++] = escape;
                }
            } else if (c < 0x800) {
                buf[pos++] = (byte) (0xC0 | c >> 6);
                buf[pos++] = (byte) (0x80 | c & 0x3F);
            } else if (Character.isSurrogate(c)) {
                char low = i + 1 < length ? s.charAt(i + 1) : 0;
                if (!Character.isHighSurrogate(c) || !Character.isLowSurrogate(low)) {
                    throw new IllegalArgumentException("unpaired surrogate at index " + i);
                }
                int cp = Character.toCodePoint(c, low);
                i++;
                buf[pos++] = (byte) (0xF0 | cp >> 18);
                buf[pos++] = (byte) (0x80 | cp >> 12 & 0x3F);
                buf[pos++] = (byte) (0x80 | cp >> 6 & 0x3F);
                buf[pos++] = (byte) (0x80 | cp & 0x3F);
            } else {
                buf[pos++] = (byte) (0xE0 | c >> 12);
                buf[pos++] = (byte) (0x80 | c >> 6 & 0x3F);
                buf[pos++] = (byte) (0x80 | c & 0x3F);
            }
        }
        if (pos == buf.length) flushBuffer();
        buf[pos++] = '"';
    }

    private void writeAscii(String s) {
        int length = s.length();
        if (buf.length - pos < length) flushBuffer();
        if (length > buf.length) {
            writeBytes(s.getBytes(StandardCharsets.US_ASCII));
            return;
        }
        for (int i = 0; i < length; i++) buf[pos++] = (byte) s.charAt(i);
    }

    private void writeByte(char c) {
        if (pos == buf.length) flushBuffer();
        buf[pos++] = (byte) c;
    }

    private void writeBytes(byte[] bytes) {
        if (buf.length - pos < bytes.length) {
            flushBuffer();
            if (bytes.length > buf.length) {
                if (out == null) {
                    addSegment(bytes.clone(), bytes.length);
                    return;
                }
                try {
                    out.write(bytes);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return;
            }
        }
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
    }

    private void flushBuffer() {
        if (pos == 0) return;
        if (out == null) {
            addSegment(buf, pos);
            buf = new byte[(int) Math.min(MAX_SEGMENT, Math.max(buf.length, segmentsSize))];
            pos = 0;
            return;
        }
        try {
            out.write(buf, 0, pos);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pos = 0;
    }

    private void addSegment(byte[] segment, int length) {
        if (segments == null) {
            segments = new byte[8][];
            segmentLengths = new int[8];
        } else if (segmentCount == segments.length) {
            segments = Arrays.copyOf(segments, segmentCount * 2);
            segmentLengths = Arrays.copyOf(segmentLengths, segmentCount * 2);
        }
        segments[segmentCount] = segment;
        segmentLengths[segmentCount++] = length;
        segmentsSize += length;
    }

    @Override
    public byte[] toByteArray() {
        if (out != null) throw new IllegalStateException("this writer writes to a stream");
        if (buf == null) throw new IllegalStateException("writer is closed");
        long size = segmentsSize + pos;
        if (size > Integer.MAX_VALUE - 8) throw new IllegalStateException("document larger than a byte[]");
        byte[] result = new byte[(int) size];
        int at = 0;
        for (int i = 0; i < segmentCount; i++) {
            System.arraycopy(segments[i], 0, result, at, segmentLengths[i]);
            at += segmentLengths[i];
        }
        System.arraycopy(buf, 0, result, at, pos);
        return result;
    }

    @Override
    public void flush() {
        if (out == null) return;
        flushBuffer();
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        if (buf == null) return;
        flush();
        pool.release(pooled);
        pooled = null;
        buf = null;
        segments = null;
        stack[0] = 0;
        depth = 1;
    }
}
