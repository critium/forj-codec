package forj.json.jackson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import forj.data.Either;
import forj.json.Codec;
import forj.json.Json;
import forj.json.backend.ForjBackend;
import forj.json.spi.JsonBackend;
import forj.json.spi.JsonException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The same codecs on both backends, chosen by a given and nothing else: identical bytes out,
 * identical values back, identical errors.
 */
@SuppressWarnings("unchecked")
class SameBehaviourTest {
    sealed interface Shape permits Circle, Square {
        given Codec<Shape> codec = Codec.<Shape>sealed("kind")
                .when("circle", Circle.codec, s -> s instanceof Circle c ? c : null)
                .when("square", Square.codec, s -> s instanceof Square q ? q : null)
                .build();
    }

    record Circle(double r) implements Shape {
        given Codec<Circle> codec = Codec.product(a -> new Circle((double) a[0]), Codec.<Circle, Double>field("r", Circle::r));
    }

    record Square(long side) implements Shape {
        given Codec<Square> codec = Codec.product(a -> new Square((long) a[0]), Codec.<Square, Long>field("side", Square::side));
    }

    record Doc(String title, Instant at, UUID id, Optional<String> note, Set<String> tags, Map<String, List<Shape>> shapes,
            Either<String, Long> result) {
        given Codec<Doc> codec = Codec.product(
                a -> new Doc((String) a[0], (Instant) a[1], (UUID) a[2], (Optional<String>) a[3], (Set<String>) a[4],
                        (Map<String, List<Shape>>) a[5], (Either<String, Long>) a[6]),
                Codec.<Doc, String>field("title", Doc::title),
                Codec.<Doc, Instant>field("at", Doc::at),
                Codec.<Doc, UUID>field("id", Doc::id),
                Codec.<Doc, Optional<String>>field("note", Doc::note),
                Codec.<Doc, Set<String>>field("tags", Doc::tags),
                Codec.<Doc, Map<String, List<Shape>>>field("shapes", Doc::shapes),
                Codec.<Doc, Either<String, Long>>field("result", Doc::result));
    }

    static final Doc DOC = new Doc("Ünïcode \"quoted\" \u0001 😀", Instant.parse("2026-10-22T10:00:00Z"),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), Optional.empty(), Set.of("a"),
            Map.of("first", List.of(new Circle(1.5), new Square(2))), Either.right(-9_223_372_036_854_775_808L));

    @Test
    void writesTheSameBytes() {
        byte[] forj = Json.encode(DOC);
        byte[] jackson = encodeOnJackson(DOC);
        assertArrayEquals(forj, jackson, new String(jackson, StandardCharsets.UTF_8));
    }

    static byte[] encodeOnJackson(Doc doc) {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        return Json.encode(doc);
    }

    static Doc decodeOnJackson(byte[] bytes) {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        return Json.decode(bytes);
    }

    static Doc decodeStreamOnJackson(byte[] bytes) {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        return Json.decode(new ByteArrayInputStream(bytes));
    }

    @Test
    void readsTheSameValues() {
        byte[] bytes = Json.encode(DOC);
        assertEquals(DOC, decodeOnJackson(bytes));
        assertEquals(DOC, decodeStreamOnJackson(bytes));
    }

    @Test
    void reportsTheSameErrors() {
        List<String> bad = List.of(
                "{\"title\":\"t\",\"title\":\"u\"}",
                "{\"title\":\"t\"}",
                "{\"title\":\"t\",\"at\":\"yesterday\"}",
                "{\"title\":\"t\",\"at\":\"2026-10-22T10:00:00Z\",\"id\":\"1-2-3-4-5\"}",
                "{\"title\":\"t\",\"shapes\":{\"s\":[{\"kind\":\"circle\",\"r\":1},{\"kind\":\"hexagon\"}]}}",
                "{\"title\":\"t\",\"tags\":[\"a\",\"a\"]}",
                "{\"title\":1}",
                "{\"title\":\"t\",}");
        for (String json : bad) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            JsonException forj = assertThrows(JsonException.class, () -> {
                Doc d = Json.decode(bytes);
            }, json);
            JsonException jackson = assertThrows(JsonException.class, () -> decodeOnJackson(bytes), json);
            assertEquals(forj.path(), jackson.path(), json);
            assertEquals(forj.offset(), jackson.offset(), json + " / forj: " + forj.getMessage() + " / jackson: " + jackson.getMessage());
        }
    }
}
