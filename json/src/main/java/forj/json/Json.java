package forj.json;

import forj.json.spi.JsonBackend;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Encoding and decoding whole documents. The codec and the backend are givens:
 *
 * <pre>{@code
 * byte[] bytes = Json.encode(user);
 * User back = Json.decode(bytes);          // A = User, from the typed local
 * }</pre>
 *
 * Each works on a {@code byte[]} or a stream. Decoding reads exactly one value and fails on
 * anything but whitespace after it.
 */
public final class Json {
    private Json() {}

    public static <A> byte[] encode(A value)(using Codec<A> codec, JsonBackend backend) {
        if (value == null) throw new IllegalArgumentException("null document");
        try (JsonWriter writer = backend.writer()) {
            codec.encode(writer, value);
            return writer.toByteArray();
        }
    }

    /** Writes to {@code out} and flushes it; doesn't close it. */
    public static <A> void encode(A value, OutputStream out)(using Codec<A> codec, JsonBackend backend) {
        if (value == null) throw new IllegalArgumentException("null document");
        try (JsonWriter writer = backend.writer(out)) {
            codec.encode(writer, value);
        }
    }

    public static <A> A decode(byte[] bytes)(using Codec<A> codec, JsonBackend backend) {
        try (JsonReader reader = backend.reader(bytes)) {
            return decode(reader, codec);
        }
    }

    /** Reads from {@code in} as needed; doesn't close it. */
    public static <A> A decode(InputStream in)(using Codec<A> codec, JsonBackend backend) {
        try (JsonReader reader = backend.reader(in)) {
            return decode(reader, codec);
        }
    }

    private static <A> A decode(JsonReader reader, Codec<A> codec) {
        A value = codec.decode(reader);
        reader.endDocument();
        return value;
    }
}
