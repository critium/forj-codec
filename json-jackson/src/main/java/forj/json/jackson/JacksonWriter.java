package forj.json.jackson;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import forj.json.spi.FieldNames;
import forj.json.spi.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * forj's {@link JsonWriter} over Jackson's {@link JsonGenerator}. It checks the document's
 * structure itself, before Jackson does, so misuse fails exactly as on forj's backend
 * ({@link IllegalStateException}), and a string with an unpaired surrogate, which Jackson
 * would escape as is, is an {@link IllegalArgumentException}.
 */
final class JacksonWriter implements JsonWriter {
    private static final int DOCUMENT_EMPTY = 1;
    private static final int DOCUMENT_DONE = 2;
    private static final int ARRAY = 3;
    private static final int OBJECT_EMPTY = 4;
    private static final int OBJECT_NAMED = 5;
    private static final int OBJECT_NONEMPTY = 6;
    private static final int CLOSED = 7;

    /** Each FieldNames' names as Jackson's pre-encoded strings; FieldNames are long-lived, per shape. */
    private static final ConcurrentHashMap<FieldNames, SerializableString[]> SERIALIZED = new ConcurrentHashMap<>();

    private final JsonGenerator generator;
    /** Null for a writer over a caller's stream. */
    private final ByteArrayOutputStream memory;
    private int[] stack = new int[16];
    private int depth = 1;

    JacksonWriter(JsonGenerator generator, ByteArrayOutputStream memory) {
        this.generator = generator;
        this.memory = memory;
        stack[0] = DOCUMENT_EMPTY;
    }

    // ---- structure

    private void beforeValue() {
        switch (stack[depth - 1]) {
            case DOCUMENT_EMPTY -> stack[depth - 1] = DOCUMENT_DONE;
            case ARRAY -> { }
            case OBJECT_NAMED -> stack[depth - 1] = OBJECT_NONEMPTY;
            case DOCUMENT_DONE -> throw new IllegalStateException("a document has one top-level value");
            case OBJECT_EMPTY, OBJECT_NONEMPTY -> throw new IllegalStateException("expected a field name");
            default -> throw new IllegalStateException("writer is closed");
        }
    }

    private void beforeName() {
        switch (stack[depth - 1]) {
            case OBJECT_EMPTY, OBJECT_NONEMPTY -> stack[depth - 1] = OBJECT_NAMED;
            case OBJECT_NAMED -> throw new IllegalStateException("expected a value after the field name");
            default -> throw new IllegalStateException("field names belong inside an object");
        }
    }

    private void push(int scope) {
        if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
        stack[depth++] = scope;
    }

    private void pop(boolean object) {
        int top = stack[depth - 1];
        boolean ok = object ? top == OBJECT_EMPTY || top == OBJECT_NONEMPTY : top == ARRAY;
        if (!ok) {
            throw new IllegalStateException(top == OBJECT_NAMED ? "expected a value after the field name" : "nothing to close here");
        }
        depth--;
    }

    @Override
    public JsonWriter beginObject() {
        beforeValue();
        push(OBJECT_EMPTY);
        try {
            generator.writeStartObject();
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter endObject() {
        pop(true);
        try {
            generator.writeEndObject();
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter beginArray() {
        beforeValue();
        push(ARRAY);
        try {
            generator.writeStartArray();
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter endArray() {
        pop(false);
        try {
            generator.writeEndArray();
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter name(FieldNames names, int index) {
        beforeName();
        SerializableString[] serialized = SERIALIZED.computeIfAbsent(names, JacksonWriter::serialize);
        try {
            generator.writeFieldName(serialized[index]);
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    private static SerializableString[] serialize(FieldNames names) {
        SerializableString[] out = new SerializableString[names.size()];
        for (int i = 0; i < out.length; i++) out[i] = new SerializedString(names.name(i));
        return out;
    }

    @Override
    public JsonWriter name(String name) {
        checkSurrogates(name);
        beforeName();
        try {
            generator.writeFieldName(name);
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    /** Jackson escapes an unpaired surrogate instead of refusing it; forj refuses it. */
    private static void checkSurrogates(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (!Character.isSurrogate(c)) continue;
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else {
                throw new IllegalArgumentException("unpaired surrogate at index " + i);
            }
        }
    }

    // ---- values

    @Override
    public JsonWriter value(String value) {
        if (value == null) return nullValue();
        checkSurrogates(value);
        beforeValue();
        try {
            generator.writeString(value);
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter value(boolean value) {
        beforeValue();
        try {
            generator.writeBoolean(value);
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter value(long value) {
        beforeValue();
        try {
            generator.writeNumber(value);
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter value(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("JSON has no " + value);
        beforeValue();
        try {
            generator.writeNumber(value);
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter value(BigDecimal value) {
        if (value == null) return nullValue();
        beforeValue();
        try {
            generator.writeNumber(value);
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    @Override
    public JsonWriter nullValue() {
        beforeValue();
        try {
            generator.writeNull();
        } catch (IOException e) {
            throw io(e);
        }
        return this;
    }

    // ---- output

    @Override
    public void flush() {
        try {
            generator.flush();
        } catch (IOException e) {
            throw io(e);
        }
    }

    @Override
    public byte[] toByteArray() {
        if (memory == null) throw new IllegalStateException("this writer writes to a stream");
        if (stack[0] == CLOSED) throw new IllegalStateException("writer is closed");
        flush();
        return memory.toByteArray();
    }

    @Override
    public void close() {
        if (stack[0] == CLOSED) return;
        try {
            generator.close();
        } catch (IOException e) {
            throw io(e);
        }
        depth = 1;
        stack[0] = CLOSED;
    }

    private static RuntimeException io(IOException e) {
        if (e instanceof JsonGenerationException g) return new IllegalStateException(g.getOriginalMessage(), g);
        return new UncheckedIOException(e);
    }
}
