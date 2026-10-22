package forj.json.jackson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import forj.json.Codec;
import forj.json.Json;
import forj.json.spi.JsonBackend;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Jackson example in docs/guide.md, section 9, compiled and checked. */
@SuppressWarnings("unchecked")
class GuideJacksonExampleTest {
    record Customer(long id, String name, List<String> tags) {
        given Codec<Customer> codec = Codec.product(
                a -> new Customer((long) a[0], (String) a[1], (List<String>) a[2]),
                Codec.<Customer, Long>field("id", Customer::id),
                Codec.<Customer, String>field("name", Customer::name),
                Codec.<Customer, List<String>>field("tags", Customer::tags));
    }

    static final class OnJackson {
        given JsonBackend backend = JacksonBackend.DEFAULT;

        static byte[] save(Customer c) {
            return Json.encode(c);
        }
    }

    @Test
    void aClassLevelGivenSwitchesTheBackend() {
        Customer ana = new Customer(7, "Ana", List.of("vip"));
        assertArrayEquals(Json.encode(ana), OnJackson.save(ana));
    }
}
