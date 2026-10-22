package forj.json.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import forj.json.spi.FieldNames;
import forj.json.spi.JsonException;
import forj.json.spi.JsonLimits;
import forj.json.spi.JsonReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * forj's peek-then-read {@link JsonReader} over Jackson's advance-then-read {@link JsonParser}:
 * the next Jackson token is fetched on the first {@code peek} and consumed by the read that
 * follows. Paths are rebuilt from Jackson's parsing context in forj's format, so errors read
 * the same on both backends.
 */
final class JacksonReader implements JsonReader {
    private final JsonParser parser;
    private final JsonLimits limits;

    /** The token peek() fetched, valid while peeked is true; null is the end of input. */
    private JsonToken next;
    private boolean peeked;
    private int depth;
    private boolean rootDone;
    private boolean closed;

    /** Where each open array or object began, for errors about a closed one. */
    private long[] starts = new long[16];
    private long lastContainerStart = -1;
    private boolean lastWasContainer;

    JacksonReader(JsonParser parser, JsonLimits limits) {
        this.parser = parser;
        this.limits = limits;
    }

    // ---- tokens

    private JsonToken next() {
        if (closed) throw new IllegalStateException("reader is closed");
        if (!peeked) {
            try {
                next = parser.nextToken();
            } catch (JsonProcessingException e) {
                throw wrap(e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            peeked = true;
            if (next == null && !rootDone) throw error("unexpected end of input");
            if (next != null && rootDone) throw error("unexpected data after the document");
        }
        return next;
    }

    private void consumed() {
        peeked = false;
        lastWasContainer = false;
        if (depth == 0) rootDone = true;
    }

    @Override
    public forj.json.spi.JsonToken peek() {
        JsonToken t = next();
        if (t == null) return forj.json.spi.JsonToken.END_DOCUMENT;
        return switch (t) {
            case START_OBJECT -> forj.json.spi.JsonToken.BEGIN_OBJECT;
            case END_OBJECT -> forj.json.spi.JsonToken.END_OBJECT;
            case START_ARRAY -> forj.json.spi.JsonToken.BEGIN_ARRAY;
            case END_ARRAY -> forj.json.spi.JsonToken.END_ARRAY;
            case FIELD_NAME -> forj.json.spi.JsonToken.NAME;
            case VALUE_STRING -> forj.json.spi.JsonToken.STRING;
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> forj.json.spi.JsonToken.NUMBER;
            case VALUE_TRUE, VALUE_FALSE -> forj.json.spi.JsonToken.BOOLEAN;
            case VALUE_NULL -> forj.json.spi.JsonToken.NULL;
            default -> throw error("unsupported token " + t);
        };
    }

    private void expect(JsonToken token, String what) {
        if (next() != token) throw error("expected " + what + " but was " + peek());
    }

    // ---- structure

    @Override
    public void beginObject() {
        expect(JsonToken.START_OBJECT, "an object");
        open();
    }

    @Override
    public void endObject() {
        expect(JsonToken.END_OBJECT, "the end of the object");
        close(true);
    }

    @Override
    public void beginArray() {
        expect(JsonToken.START_ARRAY, "an array");
        open();
    }

    @Override
    public void endArray() {
        expect(JsonToken.END_ARRAY, "the end of the array");
        close(true);
    }

    private void open() {
        if (depth == starts.length) starts = Arrays.copyOf(starts, depth * 2);
        starts[depth++] = parser.currentTokenLocation().getByteOffset();
        peeked = false;
    }

    private void close(boolean container) {
        lastContainerStart = starts[--depth];
        consumed();
        lastWasContainer = container;
    }

    @Override
    public boolean hasNext() {
        JsonToken t = next();
        return t != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY && t != null;
    }

    @Override
    public void endDocument() {
        if (!rootDone || next() != null) throw error("expected the end of the document but was " + peek());
    }

    // ---- names

    @Override
    public String nextName() {
        expect(JsonToken.FIELD_NAME, "a field name");
        peeked = false;
        try {
            return parser.currentName();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Jackson interns field names, so this is a map lookup with no new string. */
    @Override
    public int nextField(FieldNames names) {
        return names.indexOf(nextName());
    }

    // ---- values

    @Override
    public String readString() {
        expect(JsonToken.VALUE_STRING, "a string");
        try {
            String s = parser.getText();
            consumed();
            return s;
        } catch (JsonProcessingException e) {
            throw wrap(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean readBoolean() {
        JsonToken t = next();
        if (t != JsonToken.VALUE_TRUE && t != JsonToken.VALUE_FALSE) throw error("expected a boolean but was " + peek());
        consumed();
        return t == JsonToken.VALUE_TRUE;
    }

    @Override
    public void readNull() {
        expect(JsonToken.VALUE_NULL, "null");
        consumed();
    }

    @Override
    public boolean readNullIfPresent() {
        if (next() != JsonToken.VALUE_NULL) return false;
        consumed();
        return true;
    }

    @Override
    public int readInt() {
        long value = readLong();
        if (value != (int) value) throw error("number out of int range");
        return (int) value;
    }

    @Override
    public long readLong() {
        JsonToken t = next();
        if (t == JsonToken.VALUE_NUMBER_FLOAT) throw error("expected an integer");
        if (t != JsonToken.VALUE_NUMBER_INT) throw error("expected a number but was " + peek());
        try {
            JsonParser.NumberType type = parser.getNumberType();
            if (type == JsonParser.NumberType.BIG_INTEGER) throw error("number out of long range");
            long value = parser.getLongValue();
            consumed();
            return value;
        } catch (JsonProcessingException e) {
            throw wrap(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public double readDouble() {
        number();
        try {
            double value = parser.getDoubleValue();
            if (Double.isInfinite(value)) throw error("number out of double range");
            consumed();
            return value;
        } catch (JsonProcessingException e) {
            throw wrap(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public BigDecimal readBigDecimal() {
        number();
        try {
            BigDecimal value = parser.getDecimalValue();
            consumed();
            return value;
        } catch (NumberFormatException | ArithmeticException e) {
            throw error("number out of BigDecimal range");
        } catch (JsonProcessingException e) {
            throw wrap(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void number() {
        JsonToken t = next();
        if (t != JsonToken.VALUE_NUMBER_INT && t != JsonToken.VALUE_NUMBER_FLOAT) {
            throw error("expected a number but was " + peek());
        }
    }

    @Override
    public void skipValue() {
        JsonToken t = next();
        if (t == null || t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) {
            throw error("expected a value but was " + peek());
        }
        if (t == JsonToken.FIELD_NAME) throw error("expected a value but was a field name");
        boolean container = t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY;
        long start = container ? parser.currentTokenLocation().getByteOffset() : -1;
        if (container) {
            try {
                parser.skipChildren();
            } catch (JsonProcessingException e) {
                throw wrap(e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        consumed();
        if (container) {
            lastWasContainer = true;
            lastContainerStart = start;
        }
    }

    // ---- position and errors

    @Override
    public String path() {
        return path(parser.getParsingContext());
    }

    /**
     * Right after a read, Jackson's context still points at the value just read (an array's
     * index hasn't moved on, a closed object's parent names it), which is exactly the value
     * forj's invalidValue describes.
     */
    @Override
    public JsonException invalidValue(String reason) {
        long offset = lastWasContainer ? lastContainerStart : parser.currentTokenLocation().getByteOffset();
        return new JsonException(reason, offset, path(parser.getParsingContext()));
    }

    private static String path(JsonStreamContext context) {
        List<JsonStreamContext> chain = new ArrayList<>();
        for (JsonStreamContext c = context; c != null && !c.inRoot(); c = c.getParent()) chain.add(c);
        StringBuilder out = new StringBuilder("$");
        for (int i = chain.size() - 1; i >= 0; i--) {
            JsonStreamContext c = chain.get(i);
            if (c.inArray()) {
                out.append('[').append(Math.max(0, c.getCurrentIndex())).append(']');
            } else if (c.getCurrentName() != null) {
                appendName(out, c.getCurrentName());
            }
        }
        return out.toString();
    }

    /** forj's path format: {@code .name}, or {@code ['odd name']} for names that aren't identifiers. */
    private static void appendName(StringBuilder out, String name) {
        boolean plain = !name.isEmpty() && Character.isJavaIdentifierStart(name.charAt(0));
        for (int i = 1; plain && i < name.length(); i++) plain = Character.isJavaIdentifierPart(name.charAt(i));
        if (plain) {
            out.append('.').append(name);
        } else {
            out.append("['").append(name.replace("\\", "\\\\").replace("'", "\\'")).append("']");
        }
    }

    @Override
    public long offset() {
        return parser.currentLocation().getByteOffset();
    }

    private JsonException error(String reason) {
        return new JsonException(reason, parser.currentTokenLocation().getByteOffset(), path());
    }

    private JsonException wrap(JsonProcessingException e) {
        long offset = e.getLocation() != null ? e.getLocation().getByteOffset() : offset();
        return new JsonException(e.getOriginalMessage(), offset, path(), e);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
