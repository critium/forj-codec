package forj.json.spi;

import forj.json.backend.ForjBackend;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Makes readers and writers: the one thing a JSON parser plugs in. Codecs are written against
 * {@link JsonReader} and {@link JsonWriter} only, so any backend runs any codec.
 *
 * <p>The backend is a given. forj's own is declared here, next to the type, so it's the default
 * wherever nothing closer is in scope; an app, a class or a single method swaps it with its own
 * {@code given JsonBackend backend = ...;}. There is no service lookup.
 */
public interface JsonBackend {
    // No default methods here: a class initializes the interfaces it implements only when they
    // have default methods, and then initializing ForjBackend first would read DEFAULT as null.
    given JsonBackend forj = ForjBackend.DEFAULT;

    JsonReader reader(byte[] bytes, int offset, int length);

    JsonReader reader(byte[] bytes);

    /** Reads from {@code in} as needed, blocking. It may read up to one buffer past the document. */
    JsonReader reader(InputStream in);

    JsonWriter writer(OutputStream out);
}
