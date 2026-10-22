package forj.json.bench;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import forj.json.bench.Model.Address;
import forj.json.bench.Model.Point;
import forj.json.bench.Model.Route;
import forj.json.bench.Model.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** The fixed benchmark data, generated from a seed so every run and every library sees the same values. */
public final class Corpus {
    private Corpus() {}

    /**
     * Jackson set up the way most applications run it, and to write the same JSON as forj:
     * ISO-8601 instants, empty {@code Optional}s left out, unknown fields ignored.
     */
    public static final ObjectMapper JACKSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_ABSENT)
            .build();

    private static final String[] FIRST = {"Ana", "Bo", "Chen", "Dmitri", "Élodie", "Femi", "Gao", "Hana", "Ivan", "Jürgen", "Kofi", "Lucía"};
    private static final String[] LAST = {"Silva", "Nakamura", "O'Brien", "Kowalski", "Müller", "Okafor", "Dubois", "Rossi", "Smith", "Øvergaard"};
    private static final String[] CITIES = {"Lisbon", "Tokyo", "São Paulo", "Lagos", "Zürich", "New York", "Kraków", "Seoul"};
    private static final String[] TAGS = {"admin", "beta", "vip", "trial", "staff", "partner", "legacy", "eu", "us", "apac"};

    public static User user(Random random, long id) {
        String first = FIRST[random.nextInt(FIRST.length)];
        String last = LAST[random.nextInt(LAST.length)];
        List<String> tags = new ArrayList<>();
        for (int i = random.nextInt(5); i > 0; i--) tags.add(TAGS[random.nextInt(TAGS.length)]);
        Optional<Address> address = random.nextInt(4) == 0 ? Optional.empty()
                : Optional.of(new Address(random.nextInt(1, 999) + " Main St \"Apt " + random.nextInt(50) + "\"",
                        CITIES[random.nextInt(CITIES.length)],
                        random.nextBoolean() ? Optional.of(String.format("%05d", random.nextInt(100_000))) : Optional.empty()));
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (int i = random.nextInt(4); i > 0; i--) scores.put("game" + random.nextInt(20), random.nextInt(100_000));
        return new User(id, first + " " + last, (first + "." + last + "@example.com").toLowerCase(),
                random.nextBoolean(), Math.round(random.nextDouble() * 1_000_000) / 100.0,
                Instant.ofEpochSecond(1_600_000_000L + random.nextInt(200_000_000)),
                new UUID(random.nextLong(), random.nextLong()), List.copyOf(tags), address, Map.copyOf(scores));
    }

    /** One user: a small request or response body. */
    public static User oneUser() {
        return user(new Random(1), 42);
    }

    /** 1,000 users: a page of results, about 300 KB. */
    public static List<User> users() {
        Random random = new Random(2);
        List<User> users = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) users.add(user(random, i));
        return List.copyOf(users);
    }

    /** 10,000 points: number-heavy, about 400 KB. */
    public static Route route() {
        Random random = new Random(3);
        List<Point> points = new ArrayList<>();
        double lat = 38.7;
        double lon = -9.1;
        for (int i = 0; i < 10_000; i++) {
            lat += (random.nextDouble() - 0.5) / 1000;
            lon += (random.nextDouble() - 0.5) / 1000;
            points.add(new Point(lat, lon));
        }
        return new Route("Lisbon loop", List.copyOf(points));
    }
}
