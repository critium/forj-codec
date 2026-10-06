package forj.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.json.backend.ForjBackend;
import forj.json.spi.JsonBackend;
import forj.json.spi.JsonException;
import forj.json.spi.JsonLimits;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class CodecTest {
    record Address(String city, Optional<String> zip) {
        given Codec<Address> codec = Codec.product(
                a -> new Address((String) a[0], (Optional<String>) a[1]),
                Codec.<Address, String>field("city", Address::city),
                Codec.<Address, Optional<String>>field("zip", Address::zip));
    }

    record User(long id, String name, Instant joined, List<String> tags, Optional<Address> address,
            Map<String, Integer> scores, UUID ref) {
        given Codec<User> codec = Codec.product(
                a -> new User((long) a[0], (String) a[1], (Instant) a[2], (List<String>) a[3],
                        (Optional<Address>) a[4], (Map<String, Integer>) a[5], (UUID) a[6]),
                Codec.<User, Long>field("id", User::id),
                Codec.<User, String>field("name", User::name),
                Codec.<User, Instant>field("joined", User::joined),
                Codec.<User, List<String>>field("tags", User::tags),
                Codec.<User, Optional<Address>>field("address", User::address),
                Codec.<User, Map<String, Integer>>field("scores", User::scores),
                Codec.<User, UUID>field("ref", User::ref));
    }

    static final User ANA = new User(1, "Ana \"A\" Ü", Instant.parse("2026-10-06T12:00:00Z"), List.of("x", "y"),
            Optional.of(new Address("Lisbon", Optional.empty())), Map.of("go", 3),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- round trips

    @Test
    void writesRecordsAsObjectsInFieldOrder() {
        assertEquals("{\"id\":1,\"name\":\"Ana \\\"A\\\" Ü\",\"joined\":\"2026-10-06T12:00:00Z\",\"tags\":[\"x\",\"y\"],"
                + "\"address\":{\"city\":\"Lisbon\"},\"scores\":{\"go\":3},\"ref\":\"123e4567-e89b-12d3-a456-426614174000\"}",
                utf8(Json.encode(ANA)));
    }

    @Test
    void roundTripsOnTheDefaultBackend() {
        User back = Json.decode(Json.encode(ANA));
        assertEquals(ANA, back);
    }

    @Test
    void roundTripsOnAnyBackendInScope() {
        given JsonBackend tiny = ForjBackend.of(JsonLimits.DEFAULT, 16, 1);
        byte[] bytes = Json.encode(ANA);
        User fromBytes = Json.decode(bytes);
        User fromStream = Json.decode(new java.io.ByteArrayInputStream(bytes));
        assertEquals(ANA, fromBytes);
        assertEquals(ANA, fromStream);
    }

    @Test
    void roundTripsLists() {
        List<User> users = List.of(ANA, new User(2, "Bo", Instant.EPOCH, List.of(), Optional.empty(), Map.of(),
                UUID.randomUUID()));
        List<User> back = Json.decode(Json.encode(users));
        assertEquals(users, back);
    }

    // ---- optional, missing, unknown, duplicate

    @Test
    void optionalFieldsMayBeMissingOrNull() {
        Address missing = Json.decode(utf8("{\"city\":\"Porto\"}"));
        Address isNull = Json.decode(utf8("{\"zip\":null,\"city\":\"Porto\"}"));
        Address present = Json.decode(utf8("{\"zip\":\"4000\",\"city\":\"Porto\"}"));
        assertEquals(new Address("Porto", Optional.empty()), missing);
        assertEquals(new Address("Porto", Optional.empty()), isNull);
        assertEquals(new Address("Porto", Optional.of("4000")), present);
    }

    @Test
    void unknownFieldsAreSkipped() {
        Address a = Json.decode(utf8("{\"x\":{\"deep\":[1,{\"y\":null}]},\"city\":\"Porto\",\"z\":\"\\u00e9\"}"));
        assertEquals("Porto", a.city());
    }

    @Test
    void escapedFieldNamesStillMatch() {
        Address a = Json.decode(utf8("{\"c\\u0069ty\":\"Porto\"}"));
        assertEquals("Porto", a.city());
    }

    @Test
    void missingRequiredFieldsNameTheObject() {
        JsonException e = assertThrows(JsonException.class, () -> {
            List<Address> addresses = Json.decode(utf8("[{\"city\":\"a\"},{\"zip\":\"b\"}]"));
        });
        assertTrue(e.getMessage().contains("missing field 'city'"), e.getMessage());
        assertEquals("$[1]", e.path());
        assertEquals(14, e.offset());
    }

    @Test
    void repeatedFieldsAreRejected() {
        JsonException e = assertThrows(JsonException.class, () -> {
            Address a = Json.decode(utf8("{\"city\":\"a\",\"city\":\"b\"}"));
        });
        assertEquals("$.city", e.path());
    }

    @Test
    void nullForARequiredFieldIsRejected() {
        JsonException e = assertThrows(JsonException.class, () -> {
            Address a = Json.decode(utf8("{\"city\":null}"));
        });
        assertEquals("$.city", e.path());
    }

    @Test
    void nullComponentsAreNotWritten() {
        assertThrows(IllegalArgumentException.class, () -> Json.encode(new Address(null, Optional.empty())));
    }

    // ---- values codecs reject after reading them

    @Test
    void invalidValuesNameTheirPath() {
        JsonException uuid = assertThrows(JsonException.class, () -> {
            List<UUID> ids = Json.decode(utf8("[\"123e4567-e89b-12d3-a456-426614174000\",\"1-2-3-4-5\"]"));
        });
        assertEquals("$[1]", uuid.path());
        assertEquals(40, uuid.offset());

        JsonException instant = assertThrows(JsonException.class, () -> {
            Map<String, Instant> m = Json.decode(utf8("{\"at\":\"yesterday\"}"));
        });
        assertEquals("$.at", instant.path());
    }

    @Test
    void numbersRespectTheirRange() {
        assertThrows(JsonException.class, () -> {
            Short s = Json.decode(utf8("40000"));
        });
        assertThrows(JsonException.class, () -> {
            Byte b = Json.decode(utf8("128"));
        });
        assertThrows(JsonException.class, () -> {
            Float f = Json.decode(utf8("1e39"));
        });
        assertThrows(JsonException.class, () -> {
            BigInteger i = Json.decode(utf8("1.5"));
        });
        Float tenth = Json.decode(utf8("0.1"));
        assertEquals(0.1f, tenth);
        assertEquals("0.1", utf8(Json.encode(0.1f)));
        BigInteger big = Json.decode(utf8("123456789012345678901234567890"));
        assertEquals(new BigInteger("123456789012345678901234567890"), big);
        BigDecimal exact = Json.decode(utf8("0.10"));
        assertEquals(new BigDecimal("0.10"), exact);
    }

    @Test
    void mapsRejectDuplicateKeys() {
        assertThrows(JsonException.class, () -> {
            Map<String, Integer> m = Json.decode(utf8("{\"a\":1,\"a\":2}"));
        });
    }

    @Test
    void documentsHoldExactlyOneValue() {
        assertThrows(JsonException.class, () -> {
            Integer i = Json.decode(utf8("1 2"));
        });
        assertThrows(IllegalArgumentException.class, () -> Json.encode((String) null));
    }

    // ---- choosing codecs with givens

    record Event(String name, Instant at) {}

    /** A class-level given beats Codec.instants for every product defined in this class. */
    static final class EpochMillis {
        given Codec<Instant> millis = Codec.longs.imap(Instant::ofEpochMilli, Instant::toEpochMilli);

        given Codec<Event> event = Codec.product(
                a -> new Event((String) a[0], (Instant) a[1]),
                Codec.<Event, String>field("name", Event::name),
                Codec.<Event, Instant>field("at", Event::at));
    }

    @Test
    void aUserGivenReplacesAComponentCodec() {
        Event e = new Event("launch", Instant.ofEpochMilli(1_700_000_000_123L));
        byte[] bytes = Json.encode(e)(using EpochMillis.event, JsonBackend.forj);
        assertEquals("{\"name\":\"launch\",\"at\":1700000000123}", utf8(bytes));
        assertEquals(e, EpochMillis.event.decode(ForjBackend.DEFAULT.reader(bytes)));
    }

    @Test
    void nullableOptsIntoNull() {
        record Note(String text) {}
        Codec<Note> codec = Codec.product(a -> new Note((String) a[0]),
                Codec.<Note, String>field("text", Note::text)(using Codec.nullable(Codec.strings)));
        assertEquals("{\"text\":null}", utf8(Json.encode(new Note(null))(using codec, JsonBackend.forj)));
        assertEquals(new Note(null), Json.decode(utf8("{}"))(using codec, JsonBackend.forj));
    }

    // ---- recursion

    record Tree(String label, List<Tree> kids) {}

    static final class Trees {
        given Codec<Tree> tree = Codec.product(
                a -> new Tree((String) a[0], (List<Tree>) a[1]),
                Codec.<Tree, String>field("label", Tree::label),
                Codec.<Tree, List<Tree>>field("kids", Tree::kids)(using Codec.list(Codec.lazy(() -> Trees.tree))));
    }

    @Test
    void recursiveTypesUseLazy() {
        Tree t = new Tree("a", List.of(new Tree("b", List.of()), new Tree("c", List.of(new Tree("d", List.of())))));
        byte[] bytes = Json.encode(t)(using Trees.tree, JsonBackend.forj);
        assertEquals("{\"label\":\"a\",\"kids\":[{\"label\":\"b\",\"kids\":[]},{\"label\":\"c\",\"kids\":"
                + "[{\"label\":\"d\",\"kids\":[]}]}]}", utf8(bytes));
        assertEquals(t, Json.decode(bytes)(using Trees.tree, JsonBackend.forj));
    }
}
