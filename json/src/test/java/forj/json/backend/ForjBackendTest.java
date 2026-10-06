package forj.json.backend;

import forj.json.spi.JsonBackend;
import forj.json.tck.JsonBackendTck;

/** forj's backend against the TCK, with the default 8 KB buffer. */
class ForjBackendTest extends JsonBackendTck {
    @Override
    protected JsonBackend backend() {
        return ForjBackend.DEFAULT;
    }
}
