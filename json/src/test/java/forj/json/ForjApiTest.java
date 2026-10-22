package forj.json;

import static forj.Language.liftResults;
import static forj.json.Json.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import forj.data.Either;
import forj.effect.IO;
import forj.json.CodecTest.Address;
import forj.json.CodecTest.User;
import forj.json.spi.JsonException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** The parts of the API built on forj: extension methods, errors as values and effects. */
class ForjApiTest {
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static final Address PORTO = new Address("Porto", Optional.of("4000"));
    static final byte[] PORTO_JSON = utf8("{\"city\":\"Porto\",\"zip\":\"4000\"}");
    static final byte[] BAD_JSON = utf8("{\"zip\":\"4000\"}");

    // ---- extension methods

    @Test
    void toJsonAndFromJsonReadLikeMethods() {
        byte[] bytes = CodecTest.ANA.toJson();
        User back = bytes.fromJson();
        assertEquals(CodecTest.ANA, back);
        assertArrayEquals(Json.encode(CodecTest.ANA), bytes);
    }

    @Test
    void fromJsonReadsStreamsToo() {
        InputStream in = new ByteArrayInputStream(PORTO_JSON);
        Address a = in.fromJson();
        assertEquals(PORTO, a);
    }

    // ---- errors as values

    @Test
    void decodeEitherReturnsTheValue() {
        Either<JsonException, Address> result = Json.decodeEither(PORTO_JSON);
        assertEquals(Either.right(PORTO), result);
    }

    @Test
    void decodeEitherReturnsTheFailureInsteadOfThrowing() {
        Either<JsonException, Address> result = Json.decodeEither(BAD_JSON);
        if (!(result instanceof Either.Left<JsonException, Address>(JsonException e))) {
            throw new AssertionError("expected a Left, got " + result);
        }
        assertEquals("$", e.path());
        assertEquals(true, e.getMessage().contains("missing field 'city'"));
    }

    // ---- effects

    @Test
    void decodeFLiftsIntoIO() throws Exception {
        IO<Address> ok = Json.decodeF(PORTO_JSON);
        IO<Address> bad = Json.decodeF(BAD_JSON);
        assertEquals(PORTO, ok.unsafeRunSync());
        assertThrows(JsonException.class, bad::unsafeRunSync);
        Either<Throwable, Address> attempted = bad.attempt().unsafeRunSync();
        assertInstanceOf(Either.Left.class, attempted);
    }

    /**
     * Callable is a JDK type, not a Kind: liftResults (imported above) lets forj infer
     * F = Callable and unwrap. The same file decodes into a List local
     * ({@code eithersAreLeftOrRightObjects}), which lifting must leave alone because decode
     * returns a plain A.
     */
    @Test
    void decodeFIntoAJdkEffectType() throws Exception {
        Callable<Address> ok = Json.decodeF(PORTO_JSON);
        Callable<Address> bad = Json.decodeF(BAD_JSON);
        assertEquals(PORTO, ok.call());
        assertThrows(JsonException.class, bad::call);
    }

    @Test
    void readingAStreamWaitsUntilTheEffectRuns() throws Exception {
        CountingStream in = new CountingStream(PORTO_JSON);
        IO<Address> program = Json.decodeF(in);
        assertEquals(0, in.reads);
        assertEquals(PORTO, program.unsafeRunSync());
        assertEquals(true, in.reads > 0);
    }

    @Test
    void writingAStreamWaitsUntilTheEffectRuns() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IO<Void> program = Json.encodeF(PORTO, out);
        assertEquals(0, out.size());
        program.unsafeRunSync();
        assertArrayEquals(PORTO_JSON, out.toByteArray());
    }

    static final class CountingStream extends ByteArrayInputStream {
        int reads;

        CountingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            reads++;
            return super.read(b, off, len);
        }
    }

    // ---- Set and Either instances

    @Test
    void setsAreArraysWithoutRepeats() {
        Set<String> tags = Set.of("a", "b");
        Set<String> back = Json.decode(Json.encode(tags));
        assertEquals(tags, back);
        JsonException e = assertThrows(JsonException.class, () -> {
            Set<Integer> s = Json.decode(utf8("[1,2,1]"));
        });
        assertEquals("$[2]", e.path());
    }

    @Test
    void eithersAreLeftOrRightObjects() {
        Either<String, Long> left = Either.left("nope");
        Either<String, Long> right = Either.right(42L);
        assertEquals("{\"left\":\"nope\"}", new String(Json.encode(left), StandardCharsets.UTF_8));
        assertEquals("{\"right\":42}", new String(Json.encode(right), StandardCharsets.UTF_8));
        Either<String, Long> back = Json.decode(Json.encode(right));
        assertEquals(right, back);
        List<Either<String, Long>> both = Json.decode(utf8("[{\"left\":\"x\"},{\"right\":1}]"));
        assertEquals(List.of(Either.left("x"), Either.right(1L)), both);
    }

    @Test
    void eithersNeedExactlyOneKey() {
        for (String bad : List.of("{}", "{\"middle\":1}", "{\"left\":\"a\",\"right\":1}")) {
            assertThrows(JsonException.class, () -> {
                Either<String, Long> e = Json.decode(utf8(bad));
            }, bad);
        }
    }
}
