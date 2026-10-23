package forj.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.json.spi.JsonBackend;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code derives Codec}: the generated straight-line encoder writes what {@link Codec#product} writes. */
@SuppressWarnings("unchecked")
class DerivesTest {
    record Prims(long l, int i, short s, byte b, double d, float f, char c, boolean z, String str) derives Codec { }

    record Mixed(Long boxed, Instant at, List<String> tags, Optional<String> nick, Prims prims) derives Codec { }

    /** Prims's codec as written by hand: the generic path. */
    static final Codec<Prims> PRIMS_BY_HAND = Codec.product(
            a -> new Prims((long) a[0], (int) a[1], (short) a[2], (byte) a[3], (double) a[4], (float) a[5],
                    (char) a[6], (boolean) a[7], (String) a[8]),
            Codec.<Prims, Long>field("l", Prims::l),
            Codec.<Prims, Integer>field("i", Prims::i),
            Codec.<Prims, Short>field("s", Prims::s),
            Codec.<Prims, Byte>field("b", Prims::b),
            Codec.<Prims, Double>field("d", Prims::d),
            Codec.<Prims, Float>field("f", Prims::f),
            Codec.<Prims, Character>field("c", Prims::c),
            Codec.<Prims, Boolean>field("z", Prims::z),
            Codec.<Prims, String>field("str", Prims::str));

    static final Prims PRIMS = new Prims(Long.MIN_VALUE, -7, (short) 300, (byte) -2, 0.1, 1.1f, 'é', true, "a\"b");

    /** Givens here replace the built-in long and int codecs for records declared inside. */
    static final class Custom {
        given Codec<Long> longsAsStrings = Codec.strings.imap(Long::parseLong, String::valueOf);
        given Codec<Integer> intsAsStrings = Codec.strings.imap(Integer::parseInt, String::valueOf);

        record Ids(long id, int n, String name) derives Codec { }
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void derivedCodecsUseTheGeneratedEncoder() {
        assertTrue(Prims.codec.toString().startsWith("Codec.derived"), Prims.codec.toString());
        assertTrue(Mixed.codec.toString().startsWith("Codec.derived"), Mixed.codec.toString());
    }

    @Test
    void writesWhatTheHandWrittenProductWrites() {
        String expected = utf8(Json.encode(PRIMS)(using PRIMS_BY_HAND, JsonBackend.forj));
        assertEquals("{\"l\":-9223372036854775808,\"i\":-7,\"s\":300,\"b\":-2,\"d\":0.1,\"f\":1.1,\"c\":\"é\",\"z\":true,"
                + "\"str\":\"a\\\"b\"}", expected);
        assertEquals(expected, utf8(Json.encode(PRIMS)));
    }

    @Test
    void roundTripsBoxedOptionalAndNestedFields() {
        Mixed m = new Mixed(5L, Instant.parse("2026-10-23T08:00:00Z"), List.of("x"), Optional.empty(), PRIMS);
        String json = utf8(Json.encode(m));
        assertEquals("{\"boxed\":5,\"at\":\"2026-10-23T08:00:00Z\",\"tags\":[\"x\"],\"prims\":"
                + utf8(Json.encode(PRIMS)) + "}", json);
        Mixed back = Json.decode(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(m, back);
    }

    @Test
    void givensInScopeWinOverTheTypedFastPaths() {
        Custom.Ids ids = new Custom.Ids(7, 3, "x");
        String json = utf8(Json.encode(ids));
        assertEquals("{\"id\":\"7\",\"n\":\"3\",\"name\":\"x\"}", json);
        Custom.Ids back = Json.decode(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(ids, back);
    }

    @Test
    void nullFieldsFailAsTheyDoInAProduct() {
        Prims nullString = new Prims(1, 1, (short) 1, (byte) 1, 1, 1, 'x', false, null);
        var expected = assertThrows(IllegalArgumentException.class,
                () -> Json.encode(nullString)(using PRIMS_BY_HAND, JsonBackend.forj));
        var derived = assertThrows(IllegalArgumentException.class, () -> Json.encode(nullString));
        assertEquals(expected.getMessage(), derived.getMessage());

        Mixed nullBoxed = new Mixed(null, Instant.EPOCH, List.of(), Optional.empty(), PRIMS);
        var boxed = assertThrows(IllegalArgumentException.class, () -> Json.encode(nullBoxed));
        assertEquals("field 'boxed' of Mixed is null; use Optional or Codec.nullable for absent values",
                boxed.getMessage());
    }
}
