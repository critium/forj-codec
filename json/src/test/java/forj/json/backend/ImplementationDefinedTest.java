package forj.json.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forj.json.tck.JsonBackendTck;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * forj's answers to JSONTestSuite's implementation-defined (i_) cases, documented in the README:
 * <ul>
 *   <li>numbers are accepted whatever their size; range is checked only by the typed read
 *       that needs it ({@code readLong}, {@code readDouble}, ...);</li>
 *   <li>malformed UTF-8, unpaired surrogates (raw or escaped), other encodings and byte order
 *       marks are rejected;</li>
 *   <li>nesting is accepted up to {@code JsonLimits.maxDepth} (512 by default).</li>
 * </ul>
 */
class ImplementationDefinedTest {
    @TestFactory
    Stream<DynamicTest> decisions() {
        return JsonBackendTck.parsingCases().filter(name -> name.startsWith("i_")).map(name ->
                DynamicTest.dynamicTest(name, () -> assertEquals(
                        expectedAccept(name), JsonBackendTck.accepts(ForjBackend.DEFAULT, name))));
    }

    private static boolean expectedAccept(String name) {
        return name.startsWith("i_number_") || name.equals("i_structure_500_nested_arrays.json");
    }
}
