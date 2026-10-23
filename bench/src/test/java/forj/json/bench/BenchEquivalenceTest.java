package forj.json.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import forj.json.Json;
import forj.json.bench.Model.ByHand;
import forj.json.spi.JsonBackend;
import forj.json.bench.Model.Route;
import forj.json.bench.Model.User;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The benchmark is only fair if every library does the same work: these check that forj and
 * Jackson write byte-identical JSON for every shape, and read each other's output back; and
 * that the hand-written codecs ({@code ByHand}) write and read what the derived ones do.
 */
class BenchEquivalenceTest {
    @Test
    void oneUser() throws Exception {
        User user = Corpus.oneUser();
        byte[] ours = Json.encode(user);
        assertArrayEquals(Corpus.JACKSON.writeValueAsBytes(user), ours, new String(ours));
        assertArrayEquals(ours, Json.encode(user)(using ByHand.user, JsonBackend.forj));
        assertEquals(user, Json.decode(ours)(using ByHand.user, JsonBackend.forj));
        User back = Json.decode(ours);
        assertEquals(user, back);
        assertEquals(user, Corpus.JACKSON.readValue(ours, User.class));
    }

    @Test
    void users() throws Exception {
        List<User> users = Corpus.users();
        byte[] ours = Json.encode(users);
        assertArrayEquals(Corpus.JACKSON.writeValueAsBytes(users), ours);
        assertArrayEquals(ours, Json.encode(users)(using ByHand.users, JsonBackend.forj));
        assertEquals(users, Json.decode(ours)(using ByHand.users, JsonBackend.forj));
        List<User> back = Json.decode(ours);
        assertEquals(users, back);
        assertEquals(users, Corpus.JACKSON.readValue(ours, new TypeReference<List<User>>() {}));
    }

    @Test
    void route() throws Exception {
        Route route = Corpus.route();
        byte[] ours = Json.encode(route);
        assertArrayEquals(Corpus.JACKSON.writeValueAsBytes(route), ours);
        assertArrayEquals(ours, Json.encode(route)(using ByHand.route, JsonBackend.forj));
        assertEquals(route, Json.decode(ours)(using ByHand.route, JsonBackend.forj));
        Route back = Json.decode(ours);
        assertEquals(route, back);
        assertEquals(route, Corpus.JACKSON.readValue(ours, Route.class));
    }

    @Test
    void forjRowsUseTheDerivedEncoder() {
        assertTrue(User.codec.toString().startsWith("Codec.derived"), User.codec.toString());
        assertTrue(Route.codec.toString().startsWith("Codec.derived"), Route.codec.toString());
        assertTrue(ByHand.user.toString().startsWith("Codec.product"), ByHand.user.toString());
    }

    @Test
    void sizes() {
        System.out.printf("corpus sizes: user %d B, users %d B, route %d B%n",
                Json.encode(Corpus.oneUser()).length, Json.encode(Corpus.users()).length, Json.encode(Corpus.route()).length);
    }
}
