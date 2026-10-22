package forj.json.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import forj.json.Json;
import forj.json.bench.Model.Route;
import forj.json.bench.Model.User;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The benchmark is only fair if every library does the same work: these check that forj and
 * Jackson write byte-identical JSON for every shape, and read each other's output back.
 */
class BenchEquivalenceTest {
    @Test
    void oneUser() throws Exception {
        User user = Corpus.oneUser();
        byte[] ours = Json.encode(user);
        assertArrayEquals(Corpus.JACKSON.writeValueAsBytes(user), ours, new String(ours));
        User back = Json.decode(ours);
        assertEquals(user, back);
        assertEquals(user, Corpus.JACKSON.readValue(ours, User.class));
    }

    @Test
    void users() throws Exception {
        List<User> users = Corpus.users();
        byte[] ours = Json.encode(users);
        assertArrayEquals(Corpus.JACKSON.writeValueAsBytes(users), ours);
        List<User> back = Json.decode(ours);
        assertEquals(users, back);
        assertEquals(users, Corpus.JACKSON.readValue(ours, new TypeReference<List<User>>() {}));
    }

    @Test
    void route() throws Exception {
        Route route = Corpus.route();
        byte[] ours = Json.encode(route);
        assertArrayEquals(Corpus.JACKSON.writeValueAsBytes(route), ours);
        Route back = Json.decode(ours);
        assertEquals(route, back);
        assertEquals(route, Corpus.JACKSON.readValue(ours, Route.class));
    }

    @Test
    void sizes() {
        System.out.printf("corpus sizes: user %d B, users %d B, route %d B%n",
                Json.encode(Corpus.oneUser()).length, Json.encode(Corpus.users()).length, Json.encode(Corpus.route()).length);
    }
}
