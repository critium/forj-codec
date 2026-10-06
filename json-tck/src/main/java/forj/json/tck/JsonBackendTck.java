package forj.json.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import forj.json.spi.FieldNames;
import forj.json.spi.JsonBackend;
import forj.json.spi.JsonException;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonToken;
import forj.json.spi.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The tests every {@link JsonBackend} must pass, so behaviour is the same whichever backend is
 * plugged in. A backend's test class extends this and returns the backend to test:
 *
 * <pre>{@code
 * class MyBackendTest extends JsonBackendTck {
 *     protected JsonBackend backend() { return MyBackend.instance; }
 * }
 * }</pre>
 *
 * <p>Every reading test runs twice: over a {@code byte[]}, and over an {@code InputStream} that
 * hands out a few bytes per read, so every token gets split across reads. Checks a backend may
 * not be able to meet are switched off by overriding the {@code reports...} methods; the
 * backend's documentation must then say so.
 */
public abstract class JsonBackendTck {
    private static final String CASES = "/forj/json/tck/parsing/";

    protected abstract JsonBackend backend();

    /** Whether {@link JsonException#path()} names the value being read. */
    protected boolean reportsPaths() {
        return true;
    }

    /** Whether {@link JsonException#offset()} is the byte offset of the offending token. */
    protected boolean reportsOffsets() {
        return true;
    }

    // ---- JSONTestSuite: y_ accepted, n_ rejected, i_ either, but only ever with JsonException

    @TestFactory
    Stream<DynamicTest> jsonTestSuite() {
        return parsingCases().map(name -> DynamicTest.dynamicTest(name, () -> {
            byte[] bytes = resource(CASES + name);
            Outcome fromBytes = parse(bytes, false);
            Outcome fromStream = parse(bytes, true);
            assertEquals(fromBytes.accepted(), fromStream.accepted(),
                    "byte[] and InputStream disagree on " + Values.show(bytes));
            switch (name.charAt(0)) {
                case 'y' -> assertTrue(fromBytes.accepted(), () -> "rejected valid JSON: " + fromBytes.error());
                case 'n' -> assertFalse(fromBytes.accepted(), () -> "accepted invalid JSON: " + Values.show(bytes));
                default -> { } // implementation defined: either answer, as long as it's a JsonException
            }
        }));
    }

    /** The JSONTestSuite case names, such as {@code y_array_empty.json}. */
    public static Stream<String> parsingCases() {
        String index = new String(resource(CASES + "index.txt"), StandardCharsets.UTF_8);
        return index.lines().filter(line -> !line.isBlank());
    }

    /** Whether {@code backend} accepts the JSONTestSuite case {@code name}. */
    public static boolean accepts(JsonBackend backend, String name) {
        return parse(backend, resource(CASES + name), false).accepted();
    }

    record Outcome(boolean accepted, JsonException error) {}

    private Outcome parse(byte[] bytes, boolean stream) {
        return parse(backend(), bytes, stream);
    }

    private static Outcome parse(JsonBackend backend, byte[] bytes, boolean stream) {
        try (JsonReader r = stream ? backend.reader(Values.trickle(bytes, new Random(bytes.length))) : backend.reader(bytes)) {
            Values.walk(r);
            return new Outcome(true, null);
        } catch (JsonException e) {
            return new Outcome(false, e);
        }
    }

    // ---- round trips: random values, written then read back with the typed reads

    @Test
    void roundTripsRandomValues() {
        Random random = new Random(20261006);
        for (int i = 0; i < 2_000; i++) {
            Object value = Values.random(random, 0);
            byte[] bytes = write(w -> Values.write(w, value));
            boolean byFieldNames = i % 2 == 0;
            for (boolean stream : new boolean[] {false, true}) {
                Object back;
                try (JsonReader r = reader(bytes, stream, random)) {
                    back = Values.readLike(r, value, byFieldNames);
                    r.endDocument();
                } catch (JsonException e) {
                    throw new AssertionError("couldn't read back " + Values.show(bytes), e);
                }
                if (!Values.equal(value, back)) {
                    fail("round trip changed " + value + " into " + back + " via " + Values.show(bytes));
                }
            }
        }
    }

    @Test
    void roundTripsLongStrings() {
        Random random = new Random(7);
        StringBuilder s = new StringBuilder();
        while (s.length() < 100_000) s.append(Values.randomString(random));
        String value = s.toString();
        byte[] bytes = write(w -> w.value(value));
        for (boolean stream : new boolean[] {false, true}) {
            try (JsonReader r = reader(bytes, stream, random)) {
                assertEquals(value, r.readString());
                r.endDocument();
            }
        }
    }

    @Test
    void roundTripsNumberEdges() {
        long[] longs = {0, -1, 1, 9, 10, Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
        double[] doubles = {0.0, -0.0, 1.0, -1.5, 0.1, 1e-300, 4.9e-324, Double.MAX_VALUE, Double.MIN_NORMAL, 1e22, 1e23, 123456789.0};
        for (long l : longs) assertEquals(l, readOne(write(w -> w.value(l)), JsonReader::readLong));
        for (double d : doubles) {
            double back = readOne(write(w -> w.value(d)), JsonReader::readDouble);
            assertEquals(Double.doubleToLongBits(d), Double.doubleToLongBits(back), "double " + d);
        }
    }

    // ---- reading numbers

    @Test
    void readsNumbersByKind() {
        assertEquals(42, readOne(ascii("42"), JsonReader::readInt));
        assertEquals(-42L, readOne(ascii("-42"), JsonReader::readLong));
        assertEquals(1.5e10, readOne(ascii("1.5E10"), JsonReader::readDouble));
        assertEquals(100.0, readOne(ascii("1e2"), JsonReader::readDouble));
        assertEquals(0.1, readOne(ascii("0.1"), JsonReader::readDouble));
        assertEquals(new BigDecimal("1E+400"), readOne(ascii("1e400"), JsonReader::readBigDecimal));
        assertEquals(new BigDecimal("123456789012345678901234567890.5"),
                readOne(ascii("123456789012345678901234567890.5"), JsonReader::readBigDecimal));
    }

    @Test
    void rejectsNumbersThatDontFit() {
        assertThrows(JsonException.class, () -> readOne(ascii("1.5"), JsonReader::readLong));
        assertThrows(JsonException.class, () -> readOne(ascii("1e2"), JsonReader::readLong));
        assertThrows(JsonException.class, () -> readOne(ascii("9223372036854775808"), JsonReader::readLong));
        assertThrows(JsonException.class, () -> readOne(ascii("-9223372036854775809"), JsonReader::readLong));
        assertThrows(JsonException.class, () -> readOne(ascii("2147483648"), JsonReader::readInt));
        assertThrows(JsonException.class, () -> readOne(ascii("1e400"), JsonReader::readDouble));
    }

    @Test
    void rejectsTheWrongKind() {
        assertThrows(JsonException.class, () -> readOne(ascii("\"1\""), JsonReader::readLong));
        assertThrows(JsonException.class, () -> readOne(ascii("1"), JsonReader::readString));
        assertThrows(JsonException.class, () -> readOne(ascii("null"), JsonReader::readBoolean));
        assertThrows(JsonException.class, () -> readOne(ascii("[]"), r -> {
            r.beginObject();
            return null;
        }));
    }

    // ---- names

    @Test
    void matchesFieldNamesIncludingEscapedOnes() {
        FieldNames names = FieldNames.of("id", "name", "ünïcödé");
        byte[] doc = utf8("{\"name\":1,\"\\u0069d\":2,\"other\":{\"x\":[1,{}]},\"ünïcödé\":3}");
        for (boolean stream : new boolean[] {false, true}) {
            try (JsonReader r = reader(doc, stream, new Random(1))) {
                r.beginObject();
                assertEquals(1, r.nextField(names));
                assertEquals(1, r.readInt());
                assertEquals(0, r.nextField(names));
                assertEquals(2, r.readInt());
                assertEquals(-1, r.nextField(names));
                r.skipValue();
                assertEquals(2, r.nextField(names));
                assertEquals(3, r.readInt());
                assertFalse(r.hasNext());
                r.endObject();
                r.endDocument();
            }
        }
    }

    @Test
    void readsNullsOptionally() {
        try (JsonReader r = backend().reader(ascii("[null,1]"))) {
            r.beginArray();
            assertTrue(r.readNullIfPresent());
            assertFalse(r.readNullIfPresent());
            assertEquals(1, r.readInt());
            r.endArray();
            r.endDocument();
        }
    }

    @Test
    void peeksEveryKindOfToken() {
        try (JsonReader r = backend().reader(ascii("{\"a\":[\"s\",1,true,null]}"))) {
            assertEquals(JsonToken.BEGIN_OBJECT, r.peek());
            r.beginObject();
            assertEquals(JsonToken.NAME, r.peek());
            r.nextName();
            assertEquals(JsonToken.BEGIN_ARRAY, r.peek());
            r.beginArray();
            assertEquals(JsonToken.STRING, r.peek());
            r.readString();
            assertEquals(JsonToken.NUMBER, r.peek());
            r.readInt();
            assertEquals(JsonToken.BOOLEAN, r.peek());
            r.readBoolean();
            assertEquals(JsonToken.NULL, r.peek());
            r.readNull();
            assertEquals(JsonToken.END_ARRAY, r.peek());
            r.endArray();
            assertEquals(JsonToken.END_OBJECT, r.peek());
            r.endObject();
            assertEquals(JsonToken.END_DOCUMENT, r.peek());
        }
    }

    // ---- errors

    @Test
    void errorsNameThePathAndOffset() {
        String doc = "{\"users\":[{\"name\":\"ana\"},{\"name\":17}]}";
        for (boolean stream : new boolean[] {false, true}) {
            JsonException e = assertThrows(JsonException.class, () -> {
                try (JsonReader r = reader(utf8(doc), stream, new Random(3))) {
                    r.beginObject();
                    r.nextName();
                    r.beginArray();
                    while (r.hasNext()) {
                        r.beginObject();
                        r.nextName();
                        r.readString();
                        r.endObject();
                    }
                }
            });
            if (reportsPaths()) assertEquals("$.users[1].name", e.path());
            if (reportsOffsets()) assertEquals(doc.indexOf("17"), e.offset());
        }
    }

    @Test
    void errorsNameThePathOfMalformedInput() {
        String doc = "{\"a\":{\"b\":[1,2,}}}";
        JsonException e = assertThrows(JsonException.class, () -> {
            try (JsonReader r = backend().reader(utf8(doc))) {
                Values.walk(r);
            }
        });
        if (reportsPaths()) assertEquals("$.a.b[2]", e.path());
        if (reportsOffsets()) assertEquals(doc.indexOf('}'), e.offset());
    }

    @Test
    void rejectsTrailingDataUnlessAskedOnlyForTheValue() {
        try (JsonReader r = backend().reader(ascii("1 2"))) {
            assertEquals(1, r.readInt());
            assertThrows(JsonException.class, r::endDocument);
        }
    }

    @Test
    void rejectsDeepNestingWithoutOverflowingTheStack() {
        byte[] deep = ascii("[".repeat(1_000_000));
        assertThrows(JsonException.class, () -> {
            try (JsonReader r = backend().reader(deep)) {
                r.skipValue();
            }
        });
    }

    // ---- writing

    @Test
    void writesCompactJson() {
        FieldNames names = FieldNames.of("id", "tags");
        byte[] bytes = write(w -> w.beginObject()
                .name(names, 0).value(7)
                .name(names, 1).beginArray().value("a\"b").value(true).nullValue().endArray()
                .name("x").value(new BigDecimal("1.50"))
                .endObject());
        assertEquals("{\"id\":7,\"tags\":[\"a\\\"b\",true,null],\"x\":1.50}", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void escapesWhatJsonRequires() {
        byte[] bytes = write(w -> w.value("\u0000\u001f\"\\\n\té😀"));
        assertEquals("\"\\u0000\\u001f\\\"\\\\\\n\\té😀\"", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void refusesMisuse() {
        assertThrows(IllegalStateException.class, () -> write(w -> w.name("a")));
        assertThrows(IllegalStateException.class, () -> write(w -> w.value(1).value(2)));
        assertThrows(IllegalStateException.class, () -> write(w -> w.beginObject().value(1)));
        assertThrows(IllegalStateException.class, () -> write(w -> w.beginArray().endObject()));
        assertThrows(IllegalArgumentException.class, () -> write(w -> w.value(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> write(w -> w.value("\uD800")));
    }

    @Test
    void writesLargeDocumentsThroughTheBuffer() {
        List<Object> big = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) big.add(Map.of("i", (long) i, "s", "value " + i));
        byte[] bytes = write(w -> Values.write(w, big));
        try (JsonReader r = backend().reader(bytes)) {
            r.beginArray();
            int n = 0;
            while (r.hasNext()) {
                r.skipValue();
                n++;
            }
            r.endArray();
            assertEquals(big.size(), n);
        }
    }

    // ---- helpers

    protected final byte[] write(Consumer<JsonWriter> body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter w = backend().writer(out)) {
            body.accept(w);
        }
        return out.toByteArray();
    }

    private JsonReader reader(byte[] bytes, boolean stream, Random random) {
        return stream ? backend().reader(Values.trickle(bytes, random)) : backend().reader(bytes);
    }

    private <T> T readOne(byte[] bytes, java.util.function.Function<JsonReader, T> read) {
        try (JsonReader r = backend().reader(bytes)) {
            T value = read.apply(r);
            r.endDocument();
            return value;
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(String path) {
        try (InputStream in = JsonBackendTck.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing TCK resource " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
