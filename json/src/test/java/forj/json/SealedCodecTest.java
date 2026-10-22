package forj.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.json.backend.ForjBackend;
import forj.json.spi.JsonBackend;
import forj.json.spi.JsonException;
import forj.json.spi.JsonLimits;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class SealedCodecTest {
    sealed interface Payment permits Card, Bank, Cash {
        given Codec<Payment> codec = Codec.<Payment>sealed("type")
                .when("card", Card.codec, p -> p instanceof Card c ? c : null)
                .when("bank", Bank.codec, p -> p instanceof Bank b ? b : null)
                .when("cash", Cash.codec, p -> p instanceof Cash c ? c : null)
                .build();
    }

    record Card(String number, String expiry) implements Payment {
        given Codec<Card> codec = Codec.product(
                a -> new Card((String) a[0], (String) a[1]),
                Codec.<Card, String>field("number", Card::number),
                Codec.<Card, String>field("expiry", Card::expiry));
    }

    record Bank(String iban, Optional<String> bic) implements Payment {
        given Codec<Bank> codec = Codec.product(
                a -> new Bank((String) a[0], (Optional<String>) a[1]),
                Codec.<Bank, String>field("iban", Bank::iban),
                Codec.<Bank, Optional<String>>field("bic", Bank::bic));
    }

    record Cash() implements Payment {
        given Codec<Cash> codec = Codec.product(a -> new Cash());
    }

    record Order(long id, Payment payment) {
        given Codec<Order> codec = Codec.product(
                a -> new Order((long) a[0], (Payment) a[1]),
                Codec.<Order, Long>field("id", Order::id),
                Codec.<Order, Payment>field("payment", Order::payment));
    }

    static final Card CARD = new Card("4111", "12/29");
    static final Bank BANK = new Bank("PT50", Optional.empty());

    static String json(Payment p) {
        return new String(Json.encode(p), StandardCharsets.UTF_8);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- writing and reading

    @Test
    void writesTheDiscriminatorFirst() {
        assertEquals("{\"type\":\"card\",\"number\":\"4111\",\"expiry\":\"12/29\"}", json(CARD));
        assertEquals("{\"type\":\"bank\",\"iban\":\"PT50\"}", json(BANK));
        assertEquals("{\"type\":\"cash\"}", json(new Cash()));
    }

    @Test
    void roundTripsEveryCase() {
        for (Payment p : List.<Payment>of(CARD, BANK, new Bank("PT50", Optional.of("BCOM")), new Cash())) {
            Payment back = Json.decode(Json.encode(p));
            assertEquals(p, back);
        }
    }

    @Test
    void worksInsideListsAndRecords() {
        List<Order> orders = List.of(new Order(1, CARD), new Order(2, new Cash()));
        byte[] bytes = Json.encode(orders);
        assertEquals("[{\"id\":1,\"payment\":{\"type\":\"card\",\"number\":\"4111\",\"expiry\":\"12/29\"}},"
                + "{\"id\":2,\"payment\":{\"type\":\"cash\"}}]", new String(bytes, StandardCharsets.UTF_8));
        List<Order> back = Json.decode(bytes);
        assertEquals(orders, back);
    }

    @Test
    void worksOnAnyBackend() {
        given JsonBackend tiny = ForjBackend.of(JsonLimits.DEFAULT, 16, 1);
        List<Payment> payments = List.of(CARD, BANK, new Cash());
        List<Payment> back = Json.decode(Json.encode(payments));
        assertEquals(payments, back);
    }

    @Test
    void caseCodecsStillSkipUnknownFields() {
        Payment p = Json.decode(utf8("{\"type\":\"bank\",\"note\":{\"x\":[1]},\"iban\":\"PT50\"}"));
        assertEquals(BANK, p);
    }

    // ---- errors

    @Test
    void unknownTagsNameThePath() {
        JsonException e = assertThrows(JsonException.class, () -> {
            List<Payment> p = Json.decode(utf8("[{\"type\":\"cash\"},{\"type\":\"crypto\"}]"));
        });
        assertTrue(e.getMessage().contains("unknown type 'crypto'"), e.getMessage());
        assertEquals("$[1].type", e.path());
    }

    @Test
    void theDiscriminatorMustComeFirst() {
        JsonException late = assertThrows(JsonException.class, () -> {
            Payment p = Json.decode(utf8("{\"iban\":\"PT50\",\"type\":\"bank\"}"));
        });
        assertTrue(late.getMessage().contains("\"type\" field first"), late.getMessage());
        assertThrows(JsonException.class, () -> {
            Payment p = Json.decode(utf8("{}"));
        });
        assertThrows(JsonException.class, () -> {
            Payment p = Json.decode(utf8("{\"type\":1}"));
        });
        assertThrows(JsonException.class, () -> {
            Payment p = Json.decode(utf8("[\"card\"]"));
        });
    }

    @Test
    void caseFieldErrorsNameTheCaseObject() {
        JsonException e = assertThrows(JsonException.class, () -> {
            List<Order> o = Json.decode(utf8("[{\"id\":1,\"payment\":{\"type\":\"card\",\"number\":\"4111\"}}]"));
        });
        assertTrue(e.getMessage().contains("missing field 'expiry'"), e.getMessage());
        assertEquals("$[0].payment", e.path());
    }

    // ---- building

    sealed interface Shape permits Circle, Square {}

    record Circle(double r) implements Shape {
        given Codec<Circle> codec = Codec.product(
                a -> new Circle((double) a[0]),
                Codec.<Circle, Double>field("r", Circle::r));
    }

    record Square(double side) implements Shape {}

    @Test
    void aCaseLeftOutFailsWhenEncoded() {
        Codec<Shape> shapes = Codec.<Shape>sealed("kind")
                .when("circle", Circle.codec, s -> s instanceof Circle c ? c : null)
                .build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Json.encode((Shape) new Square(1))(using shapes, JsonBackend.forj));
        assertTrue(e.getMessage().contains("Square"), e.getMessage());
    }

    @Test
    void buildingChecksTheCases() {
        assertThrows(IllegalArgumentException.class, () -> Codec.<Shape>sealed("kind")
                .when("circle", Circle.codec, s -> s instanceof Circle c ? c : null)
                .when("circle", Circle.codec, s -> s instanceof Circle c ? c : null));
        IllegalArgumentException cycle = assertThrows(IllegalArgumentException.class, () -> Codec.<Shape>sealed("kind")
                .when("circle", (Codec<Circle>) null, s -> s instanceof Circle c ? c : null));
        assertTrue(cycle.getMessage().contains("Codec.lazy"), cycle.getMessage());
        assertThrows(IllegalStateException.class, () -> Codec.<Shape>sealed("kind").build());
    }
}
