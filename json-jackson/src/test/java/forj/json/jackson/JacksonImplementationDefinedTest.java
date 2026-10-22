package forj.json.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forj.json.tck.JsonBackendTck;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The Jackson adapter's answers to JSONTestSuite's implementation-defined (i_) cases, which
 * differ from forj's backend in 15 of them (documented in the README). Pinned so a Jackson
 * upgrade that changes them is noticed. Jackson accepts, where forj rejects:
 * <ul>
 *   <li>unpaired surrogates written as {@code \\u} escapes;</li>
 *   <li>an overlong 2-byte UTF-8 sequence, and a code point above U+10FFFF;</li>
 *   <li>UTF-16 input (Jackson detects the encoding);</li>
 *   <li>a UTF-8 byte order mark (skipped).</li>
 * </ul>
 */
class JacksonImplementationDefinedTest {
    static final Set<String> ACCEPTED_UNLIKE_FORJ = Set.of(
            "i_string_1st_surrogate_but_2nd_missing.json",
            "i_string_1st_valid_surrogate_2nd_invalid.json",
            "i_string_incomplete_surrogate_and_escape_valid.json",
            "i_string_incomplete_surrogate_pair.json",
            "i_string_incomplete_surrogates_escape_valid.json",
            "i_string_invalid_lonely_surrogate.json",
            "i_string_invalid_surrogate.json",
            "i_string_inverted_surrogates_U+1D11E.json",
            "i_string_lone_second_surrogate.json",
            "i_string_not_in_unicode_range.json",
            "i_string_overlong_sequence_2_bytes.json",
            "i_string_UTF-16LE_with_BOM.json",
            "i_string_utf16BE_no_BOM.json",
            "i_string_utf16LE_no_BOM.json",
            "i_structure_UTF-8_BOM_empty_object.json");

    @TestFactory
    Stream<DynamicTest> decisions() {
        return JsonBackendTck.parsingCases().filter(name -> name.startsWith("i_")).map(name ->
                DynamicTest.dynamicTest(name, () -> assertEquals(
                        expectedAccept(name), JsonBackendTck.accepts(JacksonBackend.DEFAULT, name))));
    }

    /** forj's rule (numbers and nesting accepted, encoding problems rejected) plus Jackson's differences. */
    private static boolean expectedAccept(String name) {
        return name.startsWith("i_number_") || name.equals("i_structure_500_nested_arrays.json")
                || ACCEPTED_UNLIKE_FORJ.contains(name);
    }
}
