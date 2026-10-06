package forj.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Checks that this build compiles forj syntax with the published plugin and runs on forj-core. */
class ForjBuildTest {
    interface Show<A> {
        String show(A a);
    }

    given Show<Integer> integer = i -> "#" + i;

    static <A> String twice(A a)(using Show<A> s) {
        return s.show(a) + s.show(a);
    }

    @Test
    void forjSyntaxCompiles() {
        Optional<Integer> sum = forj {
            x <- Optional.of(1);
            y <- Optional.of(2);
        } yield x + y;
        assertEquals("sum=3 twice=#3#3", s"sum=${sum.get()} twice=${twice(3)}");
    }
}
