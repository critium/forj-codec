package forj.json.spi;

import static org.junit.jupiter.api.Assertions.assertSame;

import forj.Implicits;
import forj.json.backend.ForjBackend;
import org.junit.jupiter.api.Test;

/** The backend is picked at compile time, by given resolution. */
class GivenBackendTest {
    static JsonBackend current()(using JsonBackend backend) {
        return backend;
    }

    @Test
    void forjIsTheDefault() {
        assertSame(ForjBackend.DEFAULT, current());
        assertSame(ForjBackend.DEFAULT, Implicits.<JsonBackend>summon());
    }

    @Test
    void aLocalGivenSwapsIt() {
        given JsonBackend small = ForjBackend.of(JsonLimits.DEFAULT, 16, 1);
        assertSame(small, current());
    }
}
