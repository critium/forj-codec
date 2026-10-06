package forj.json.spi;

import java.math.BigDecimal;

/**
 * A streaming writer for one JSON document. Commas and colons are written for you; writing a
 * name outside an object or a second top-level value throws {@link IllegalStateException}.
 *
 * <p>Not thread safe. Output is buffered: {@link #flush()} pushes it to the underlying stream,
 * and {@link #close()} flushes and returns the writer's buffers to its backend. Neither closes
 * the underlying stream, which the caller owns.
 */
public interface JsonWriter extends AutoCloseable {
    JsonWriter beginObject();

    JsonWriter endObject();

    JsonWriter beginArray();

    JsonWriter endArray();

    /** Writes field {@code index} of {@code names}, using its pre-encoded bytes where it can. */
    JsonWriter name(FieldNames names, int index);

    JsonWriter name(String name);

    JsonWriter value(String value);

    JsonWriter value(boolean value);

    JsonWriter value(long value);

    /** A finite double; NaN and infinities have no JSON form and throw. */
    JsonWriter value(double value);

    JsonWriter value(BigDecimal value);

    JsonWriter nullValue();

    void flush();

    @Override
    void close();
}
