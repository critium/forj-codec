package forj.json.bench;

import forj.json.Json;
import forj.json.jackson.JacksonBackend;
import forj.json.spi.JsonBackend;
import forj.json.bench.Model.ByHand;
import forj.json.bench.Model.Route;
import forj.json.bench.Model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Encode (value to {@code byte[]}) and decode ({@code byte[]} to value) for each shape in
 * {@link Corpus}: forj's derived codecs on forj's backend ({@code forj}), the hand-written
 * {@code Codec.product} codecs on forj's backend ({@code forjProduct}), the derived codecs on
 * Jackson's streaming parser through the json-jackson adapter ({@code forjOnJackson}), and
 * Jackson databind ({@code jackson}). All read the same bytes, which
 * {@code BenchEquivalenceTest} checks are what all write.
 *
 * <p>Method names are {@code shape_operation_library}, so results sort by shape. The
 * {@code forjOnJackson} methods differ from {@code forj} only in a local given backend.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "-Xms2g", "-Xmx2g"})
public class CodecBench {
    private static final TypeReference<List<User>> USER_LIST = new TypeReference<>() {};

    User user;
    List<User> users;
    Route route;
    byte[] userJson;
    byte[] usersJson;
    byte[] routeJson;

    @Setup
    public void setup() {
        user = Corpus.oneUser();
        users = Corpus.users();
        route = Corpus.route();
        userJson = Json.encode(user);
        usersJson = Json.encode(users);
        routeJson = Json.encode(route);
    }

    // ---- one user

    @Benchmark
    public byte[] user_encode_forj() {
        return Json.encode(user);
    }

    @Benchmark
    public byte[] user_encode_forjProduct() {
        return Json.encode(user)(using ByHand.user, JsonBackend.forj);
    }

    @Benchmark
    public byte[] user_encode_forjOnJackson() {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        return Json.encode(user);
    }

    @Benchmark
    public byte[] user_encode_jackson() throws IOException {
        return Corpus.JACKSON.writeValueAsBytes(user);
    }

    @Benchmark
    public User user_decode_forj() {
        User u = Json.decode(userJson);
        return u;
    }

    @Benchmark
    public User user_decode_forjProduct() {
        return Json.decode(userJson)(using ByHand.user, JsonBackend.forj);
    }

    @Benchmark
    public User user_decode_forjOnJackson() {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        User value = Json.decode(userJson);
        return value;
    }

    @Benchmark
    public User user_decode_jackson() throws IOException {
        return Corpus.JACKSON.readValue(userJson, User.class);
    }

    // ---- 1,000 users

    @Benchmark
    public byte[] users_encode_forj() {
        return Json.encode(users);
    }

    @Benchmark
    public byte[] users_encode_forjProduct() {
        return Json.encode(users)(using ByHand.users, JsonBackend.forj);
    }

    @Benchmark
    public byte[] users_encode_forjOnJackson() {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        return Json.encode(users);
    }

    @Benchmark
    public byte[] users_encode_jackson() throws IOException {
        return Corpus.JACKSON.writeValueAsBytes(users);
    }

    @Benchmark
    public List<User> users_decode_forj() {
        List<User> u = Json.decode(usersJson);
        return u;
    }

    @Benchmark
    public List<User> users_decode_forjProduct() {
        return Json.decode(usersJson)(using ByHand.users, JsonBackend.forj);
    }

    @Benchmark
    public List<User> users_decode_forjOnJackson() {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        List<User> value = Json.decode(usersJson);
        return value;
    }

    @Benchmark
    public List<User> users_decode_jackson() throws IOException {
        return Corpus.JACKSON.readValue(usersJson, USER_LIST);
    }

    // ---- 10,000 points

    @Benchmark
    public byte[] route_encode_forj() {
        return Json.encode(route);
    }

    @Benchmark
    public byte[] route_encode_forjProduct() {
        return Json.encode(route)(using ByHand.route, JsonBackend.forj);
    }

    @Benchmark
    public byte[] route_encode_forjOnJackson() {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        return Json.encode(route);
    }

    @Benchmark
    public byte[] route_encode_jackson() throws IOException {
        return Corpus.JACKSON.writeValueAsBytes(route);
    }

    @Benchmark
    public Route route_decode_forj() {
        Route r = Json.decode(routeJson);
        return r;
    }

    @Benchmark
    public Route route_decode_forjProduct() {
        return Json.decode(routeJson)(using ByHand.route, JsonBackend.forj);
    }

    @Benchmark
    public Route route_decode_forjOnJackson() {
        given JsonBackend backend = JacksonBackend.DEFAULT;
        Route value = Json.decode(routeJson);
        return value;
    }

    @Benchmark
    public Route route_decode_jackson() throws IOException {
        return Corpus.JACKSON.readValue(routeJson, Route.class);
    }
}
