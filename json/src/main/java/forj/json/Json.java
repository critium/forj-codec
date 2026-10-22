package forj.json;

import forj.data.Either;
import forj.json.spi.JsonBackend;
import forj.json.spi.JsonException;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import forj.typeclass.ApplicativeError;
import forj.typeclass.Sync;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Encoding and decoding whole documents. The codec and the backend are givens:
 *
 * <pre>{@code
 * byte[] bytes = Json.encode(user);
 * User back = Json.decode(bytes);                       // A = User, from the typed local
 *
 * import static forj.json.Json.*;                        // the same as extension methods
 * byte[] bytes = user.toJson();
 * User back = bytes.fromJson();
 * }</pre>
 *
 * <p>Decoding reads exactly one value and fails on anything but whitespace after it. A failure
 * comes back in one of three ways, by method:
 * <ul>
 *   <li>{@code decode} throws {@link JsonException};</li>
 *   <li>{@code decodeEither} returns it as {@code Either.Left};</li>
 *   <li>{@code decodeF} fails the effect: {@code IO<User> u = Json.decodeF(bytes);}.</li>
 * </ul>
 */
public final class Json {
    private Json() {}

    // ---- encoding

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

    /** {@link #encode(Object, OutputStream)} as an effect: nothing is written until it runs. */
    public static <F<_>, A> F<Void> encodeF(A value, OutputStream out)(using Codec<A> codec, JsonBackend backend, Sync<F> sync) {
        return sync.delay(() -> {
            encode(value, out)(using codec, backend);
            return null;
        });
    }

    // ---- decoding

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

    /** The value, or the reason it couldn't be decoded. Never throws for bad input. */
    public static <A> Either<JsonException, A> decodeEither(byte[] bytes)(using Codec<A> codec, JsonBackend backend) {
        try {
            return Either.right(decode(bytes)(using codec, backend));
        } catch (JsonException e) {
            return Either.left(e);
        }
    }

    /**
     * Decodes into any effect that can fail with a {@code Throwable}: bad input fails the
     * effect with a {@link JsonException} instead of throwing. Decoding bytes already in memory
     * has no side effects, so it happens here and the result is lifted with {@code pure}.
     */
    public static <F<_>, A> F<A> decodeF(byte[] bytes)(using Codec<A> codec, JsonBackend backend, ApplicativeError<F, Throwable> errors) {
        try {
            return errors.pure(decode(bytes)(using codec, backend));
        } catch (JsonException e) {
            return errors.raiseError(e);
        }
    }

    /**
     * Reading a stream is a side effect, so this needs {@link Sync}: nothing is read until the
     * effect runs, and bad input or an I/O error fails it.
     */
    public static <F<_>, A> F<A> decodeF(InputStream in)(using Codec<A> codec, JsonBackend backend, Sync<F> sync) {
        return sync.delay(() -> decode(in)(using codec, backend));
    }

    private static <A> A decode(JsonReader reader, Codec<A> codec) {
        A value = codec.decode(reader);
        reader.endDocument();
        return value;
    }

    // ---- extension methods: import static forj.json.Json.*

    /** {@code user.toJson()}. */
    extension <A> byte[] toJson(A value)(using Codec<A> codec, JsonBackend backend) {
        return encode(value)(using codec, backend);
    }

    /** {@code User u = bytes.fromJson();}: the type comes from the typed local. */
    extension <A> A fromJson(byte[] bytes)(using Codec<A> codec, JsonBackend backend) {
        return decode(bytes)(using codec, backend);
    }

    /** {@code User u = in.fromJson();}: reads from the stream; doesn't close it. */
    extension <A> A fromJson(InputStream in)(using Codec<A> codec, JsonBackend backend) {
        return decode(in)(using codec, backend);
    }
}
