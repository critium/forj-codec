package forj.json;

import static forj.Language.liftResults;
import static forj.json.Json.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.data.Either;
import forj.effect.IO;
import forj.json.backend.ForjBackend;
import forj.json.spi.JsonBackend;
import forj.json.spi.JsonException;
import forj.json.spi.JsonLimits;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Every example in docs/guide.md, compiled and checked. When an example in the guide changes,
 * change it here too (and the other way round).
 */
@SuppressWarnings("unchecked")
class GuideExamplesTest {
    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- 1. Getting started

    record Point(double x, double y) {
        given Codec<Point> codec = Codec.product(
                a -> new Point((double) a[0], (double) a[1]),
                Codec.<Point, Double>field("x", Point::x),
                Codec.<Point, Double>field("y", Point::y));
    }

    @Test
    void gettingStarted() {
        byte[] bytes = Json.encode(new Point(1.5, 2));
        assertEquals("{\"x\":1.5,\"y\":2.0}", text(bytes));
        Point back = Json.decode(bytes);
        assertEquals(new Point(1.5, 2), back);

        Point point = new Point(1.5, 2);
        byte[] viaExtension = point.toJson();
        Point backViaExtension = viaExtension.fromJson();
        assertEquals(point, backViaExtension);
    }

    // ---- 2. Records

    record Address(String city, Optional<String> zip) {
        given Codec<Address> codec = Codec.product(
                a -> new Address((String) a[0], (Optional<String>) a[1]),
                Codec.<Address, String>field("city", Address::city),
                Codec.<Address, Optional<String>>field("zip", Address::zip));
    }

    record Customer(long id, String name, List<String> tags, Optional<Address> address) {
        given Codec<Customer> codec = Codec.product(
                a -> new Customer((long) a[0], (String) a[1], (List<String>) a[2], (Optional<Address>) a[3]),
                Codec.<Customer, Long>field("id", Customer::id),
                Codec.<Customer, String>field("name", Customer::name),
                Codec.<Customer, List<String>>field("tags", Customer::tags),
                Codec.<Customer, Optional<Address>>field("address", Customer::address));
    }

    static final Customer ANA = new Customer(7, "Ana", List.of("vip"), Optional.of(new Address("Porto", Optional.empty())));

    @Test
    void records() {
        byte[] bytes = Json.encode(ANA);
        assertEquals("{\"id\":7,\"name\":\"Ana\",\"tags\":[\"vip\"],\"address\":{\"city\":\"Porto\"}}", text(bytes));
        Customer back = Json.decode(bytes);
        assertEquals(ANA, back);
    }

    @Test
    void recordFieldRules() {
        Customer unknownSkipped = Json.decode(utf8("{\"id\":7,\"x\":[1],\"name\":\"Ana\",\"tags\":[\"vip\"],\"address\":{\"city\":\"Porto\"}}"));
        assertEquals(ANA, unknownSkipped);
        JsonException missing = assertThrows(JsonException.class, () -> {
            List<Customer> c = Json.decode(utf8("[{\"id\":1,\"name\":\"Ana\",\"tags\":[]},{\"id\":2,\"tags\":[]}]"));
        });
        assertEquals("missing field 'name' at $[1] (byte 33)", missing.getMessage());
        assertThrows(JsonException.class, () -> {
            Customer c = Json.decode(utf8("{\"id\":1,\"id\":2,\"name\":\"A\",\"tags\":[]}"));
        });
        JsonException isNull = assertThrows(JsonException.class, () -> {
            Customer c = Json.decode(utf8("{\"id\":1,\"name\":null,\"tags\":[]}"));
        });
        assertTrue(isNull.getMessage().startsWith("expected a string but was NULL at $.name"), isNull.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Json.encode(new Customer(1, null, List.of(), Optional.empty())));
    }

    record Note(String text) {
        given Codec<Note> codec = Codec.product(
                a -> new Note((String) a[0]),
                Codec.<Note, String>field("text", Note::text)(using Codec.nullable(Codec.strings)));
    }

    @Test
    void nullableFields() {
        assertEquals("{\"text\":null}", text(Json.encode(new Note(null))));
        Note missing = Json.decode(utf8("{}"));
        assertEquals(new Note(null), missing);
    }

    record Tree(String label, List<Tree> children) {
        given Codec<Tree> codec = Codec.product(
                a -> new Tree((String) a[0], (List<Tree>) a[1]),
                Codec.<Tree, String>field("label", Tree::label),
                Codec.<Tree, List<Tree>>field("children", Tree::children)(using Codec.list(Codec.lazy(() -> Tree.codec))));
    }

    @Test
    void recursiveTypes() {
        Tree tree = new Tree("a", List.of(new Tree("b", List.of())));
        Tree back = Json.decode(Json.encode(tree));
        assertEquals(tree, back);
    }

    // ---- 3. Built-in types and collections

    @Test
    void containers() {
        Map<String, List<Instant>> history = Map.of("logins", List.of(Instant.parse("2026-10-06T12:00:00Z")));
        byte[] bytes = Json.encode(history);
        assertEquals("{\"logins\":[\"2026-10-06T12:00:00Z\"]}", text(bytes));
        Map<String, List<Instant>> back = Json.decode(bytes);
        assertEquals(history, back);
    }

    // ---- 4. Sealed types

    sealed interface Payment permits Card, Cash {
        given Codec<Payment> codec = Codec.<Payment>sealed("type")
                .when("card", Card.codec, p -> p instanceof Card c ? c : null)
                .when("cash", Cash.codec, p -> p instanceof Cash c ? c : null)
                .build();
    }

    record Card(String number) implements Payment {
        given Codec<Card> codec = Codec.product(
                a -> new Card((String) a[0]),
                Codec.<Card, String>field("number", Card::number));
    }

    record Cash() implements Payment {
        given Codec<Cash> codec = Codec.product(a -> new Cash());
    }

    record Purchase(Payment payment) {
        given Codec<Purchase> codec = Codec.product(
                a -> new Purchase((Payment) a[0]),
                Codec.<Purchase, Payment>field("payment", Purchase::payment));
    }

    @Test
    void sealedTypes() {
        assertEquals("{\"type\":\"card\",\"number\":\"4111\"}", text(Json.encode((Payment) new Card("4111"))));
        assertEquals("{\"type\":\"cash\"}", text(Json.encode((Payment) new Cash())));
        JsonException unknown = assertThrows(JsonException.class, () -> {
            Purchase p = Json.decode(utf8("{\"payment\":{\"type\":\"crypto\"}}"));
        });
        assertTrue(unknown.getMessage().startsWith("unknown type 'crypto' at $.payment.type"), unknown.getMessage());
    }

    // ---- 5. Custom codecs

    record UserId(long value) {
        given Codec<UserId> codec = Codec.longs.imap(UserId::new, UserId::value);
    }

    record Range(int from, int to) {
        given Codec<Range> codec = new Codec<>() {
            public void encode(JsonWriter out, Range r) {
                out.beginArray().value(r.from()).value(r.to()).endArray();
            }

            public Range decode(JsonReader in) {
                in.beginArray();
                int from = in.readInt();
                int to = in.readInt();
                in.endArray();
                if (from > to) throw in.invalidValue("from is after to");
                return new Range(from, to);
            }
        };
    }

    @Test
    void customCodecs() {
        assertEquals("42", text(Json.encode(new UserId(42))));
        assertEquals("[1,5]", text(Json.encode(new Range(1, 5))));
        Range r = Json.decode(utf8("[1,5]"));
        assertEquals(new Range(1, 5), r);
        JsonException e = assertThrows(JsonException.class, () -> {
            List<Range> ranges = Json.decode(utf8("[[1,2],[9,3]]"));
        });
        assertEquals("$[1]", e.path());
    }

    // ---- 6. Choosing codecs with givens

    record Event(String name, Instant at) {
        given Codec<Instant> millis = Codec.longs.imap(Instant::ofEpochMilli, Instant::toEpochMilli);

        given Codec<Event> codec = Codec.product(
                a -> new Event((String) a[0], (Instant) a[1]),
                Codec.<Event, String>field("name", Event::name),
                Codec.<Event, Instant>field("at", Event::at));
    }

    @Test
    void componentOverrides() {
        Event launch = new Event("launch", Instant.ofEpochMilli(1_700_000_000_123L));
        assertEquals("{\"name\":\"launch\",\"at\":1700000000123}", text(Json.encode(launch)));
        // elsewhere, Instant keeps its default ISO-8601 form
        assertEquals("\"2026-10-06T12:00:00Z\"", text(Json.encode(Instant.parse("2026-10-06T12:00:00Z"))));
        assertEquals("[1,5]", text(Json.encode(new Range(1, 5))(using Range.codec, JsonBackend.forj)));
    }

    // ---- 7. Errors

    @Test
    void errors() {
        byte[] bytes = utf8("[{\"id\":1,\"name\":\"Ana\",\"tags\":[]},{\"id\":2,\"tags\":[]}]");
        try {
            List<Customer> customers = Json.decode(bytes);
            throw new AssertionError("decoded " + customers);
        } catch (JsonException e) {
            assertEquals("missing field 'name' at $[1] (byte 33)", e.getMessage());
            assertEquals("$[1]", e.path());
            assertEquals(33, e.offset());
        }

        byte[] one = utf8("{\"id\":2,\"tags\":[]}");
        assertThrows(JsonException.class, () -> {
            Customer c = Json.decode(one);
        });
        Either<JsonException, Customer> result = Json.decodeEither(one);
        assertInstanceOf(Either.Left.class, result);
        IO<Customer> program = Json.decodeF(one);
        assertThrows(JsonException.class, program::unsafeRunSync);

        assertThrows(IllegalArgumentException.class, () -> Json.encode(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.encode("\uD800"));
        for (String bad : List.of("[1,]", "{'a':1}", "[1] x", "// c\n1")) {
            assertThrows(JsonException.class, () -> {
                List<Integer> xs = Json.decode(utf8(bad));
            }, bad);
        }
    }

    // ---- 8. Streams and effects

    @Test
    void streamsAndEffects() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Json.encode(ANA, outputStream);
        Customer c = Json.decode(new ByteArrayInputStream(outputStream.toByteArray()));
        assertEquals(ANA, c);
        Customer viaExtension = new ByteArrayInputStream(outputStream.toByteArray()).fromJson();
        assertEquals(ANA, viaExtension);

        byte[] bytes = Json.encode(ANA);
        IO<Customer> fromBytes = Json.decodeF(bytes);
        IO<Customer> fromStream = Json.decodeF(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IO<Void> write = Json.encodeF(ANA, out);
        assertEquals(0, out.size());
        assertEquals(ANA, fromBytes.unsafeRunSync());
        assertEquals(ANA, fromStream.unsafeRunSync());
        write.unsafeRunSync();
        assertEquals(text(bytes), out.toString(StandardCharsets.UTF_8));

        Callable<Customer> task = Json.decodeF(bytes);
        assertEquals(ANA, task.call());
    }

    // ---- 9. Backends and limits

    static final JsonBackend STRICT = ForjBackend.of(
            JsonLimits.DEFAULT.withMaxDepth(32).withMaxStringLength(10_000).withMaxDocumentSize(1_000_000),
            16_384,
            512);

    static Customer parseUpload(byte[] bytes) {
        given JsonBackend backend = STRICT;
        return Json.decode(bytes);
    }

    @Test
    void backendsAndLimits() {
        assertEquals(ANA, parseUpload(Json.encode(ANA)));
        String tooLong = "{\"id\":1,\"name\":\"" + "x".repeat(10_001) + "\",\"tags\":[]}";
        assertThrows(JsonException.class, () -> parseUpload(utf8(tooLong)));
        assertEquals(512, JsonLimits.DEFAULT.maxDepth());
        assertEquals(20_000_000, JsonLimits.DEFAULT.maxStringLength());
        assertEquals(1_000, JsonLimits.DEFAULT.maxNumberLength());
        assertEquals(Long.MAX_VALUE, JsonLimits.DEFAULT.maxDocumentSize());
    }
}
