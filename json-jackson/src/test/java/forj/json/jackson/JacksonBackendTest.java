package forj.json.jackson;

import forj.json.spi.JsonBackend;
import forj.json.tck.JsonBackendTck;

/** The Jackson adapter against the same TCK as forj's own backend. */
class JacksonBackendTest extends JsonBackendTck {
    @Override
    protected JsonBackend backend() {
        return JacksonBackend.DEFAULT;
    }
}
