package forj.json.bench;

import forj.json.Codec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The benchmark shapes, with hand-written forj codecs (what {@code derives} will generate).
 * Jackson reads and writes the same records through reflection.
 */
@SuppressWarnings("unchecked")
public final class Model {
    private Model() {}

    public record Address(String street, String city, Optional<String> zip) {
        given Codec<Address> codec = Codec.product(
                a -> new Address((String) a[0], (String) a[1], (Optional<String>) a[2]),
                Codec.<Address, String>field("street", Address::street),
                Codec.<Address, String>field("city", Address::city),
                Codec.<Address, Optional<String>>field("zip", Address::zip));
    }

    /** A typical API object: strings, numbers, a timestamp, an id, a list, a map, a nested object. */
    public record User(long id, String name, String email, boolean active, double balance, Instant joined,
            UUID ref, List<String> tags, Optional<Address> address, Map<String, Integer> scores) {
        given Codec<User> codec = Codec.product(
                a -> new User((long) a[0], (String) a[1], (String) a[2], (boolean) a[3], (double) a[4],
                        (Instant) a[5], (UUID) a[6], (List<String>) a[7], (Optional<Address>) a[8],
                        (Map<String, Integer>) a[9]),
                Codec.<User, Long>field("id", User::id),
                Codec.<User, String>field("name", User::name),
                Codec.<User, String>field("email", User::email),
                Codec.<User, Boolean>field("active", User::active),
                Codec.<User, Double>field("balance", User::balance),
                Codec.<User, Instant>field("joined", User::joined),
                Codec.<User, UUID>field("ref", User::ref),
                Codec.<User, List<String>>field("tags", User::tags),
                Codec.<User, Optional<Address>>field("address", User::address),
                Codec.<User, Map<String, Integer>>field("scores", User::scores));
    }

    public record Point(double lat, double lon) {
        given Codec<Point> codec = Codec.product(
                a -> new Point((double) a[0], (double) a[1]),
                Codec.<Point, Double>field("lat", Point::lat),
                Codec.<Point, Double>field("lon", Point::lon));
    }

    /** Number-heavy, like GeoJSON: mostly doubles. */
    public record Route(String name, List<Point> points) {
        given Codec<Route> codec = Codec.product(
                a -> new Route((String) a[0], (List<Point>) a[1]),
                Codec.<Route, String>field("name", Route::name),
                Codec.<Route, List<Point>>field("points", Route::points));
    }
}
