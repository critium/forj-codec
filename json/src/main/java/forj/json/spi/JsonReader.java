package forj.json.spi;

import java.math.BigDecimal;

/**
 * A pull reader over one JSON document, one token at a time. There is no tree: codecs ask for
 * exactly the values they expect, and skip the rest.
 *
 * <p>Reading the wrong kind of value (a string where a number is) throws {@link JsonException},
 * as does malformed input. Every method may block reading the underlying stream, which on a
 * virtual thread parks it instead of holding a carrier.
 *
 * <p>Not thread safe. {@link #close()} returns the reader's buffers to its backend and doesn't
 * close the underlying stream, which the caller owns.
 */
public interface JsonReader extends AutoCloseable {
    /** The kind of the next token, without consuming it. */
    JsonToken peek();

    void beginObject();

    void endObject();

    void beginArray();

    void endArray();

    /** Whether the current array or object has another element (that is, isn't at its end). */
    boolean hasNext();

    /** The next field name, as a string. Prefer {@link #nextField(FieldNames)}. */
    String nextName();

    /**
     * The next field name's index in {@code names}, or -1 if it isn't one of them. Matching is
     * done on the encoded bytes where the backend can, without building a string.
     */
    int nextField(FieldNames names);

    String readString();

    boolean readBoolean();

    /** Consumes a {@code null}. */
    void readNull();

    /** Consumes a {@code null} if one is next, and says whether it did. */
    boolean readNullIfPresent();

    /** An integer literal (no fraction or exponent) in {@code int} range. */
    int readInt();

    /** An integer literal (no fraction or exponent) in {@code long} range. */
    long readLong();

    /** Any number, rounded to the nearest double; one that overflows to infinity is an error. */
    double readDouble();

    /** Any number, exactly. */
    BigDecimal readBigDecimal();

    /** Skips the next value, nested ones included, validating but not decoding it. */
    void skipValue();

    /** Checks that the document's value has been read and only whitespace follows it. */
    void endDocument();

    /** JSON path of the current position, such as {@code $.users[3].name}. */
    String path();

    /**
     * An error about the value most recently read (a whole object or array once it's closed),
     * with that value's path and offset. For codecs that reject a well-formed value: a
     * malformed UUID, a missing field. Returned, not thrown, so callers write
     * {@code throw in.invalidValue("...")}.
     */
    JsonException invalidValue(String reason);

    /** Bytes consumed so far. */
    long offset();

    @Override
    void close();
}
