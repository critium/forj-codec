package forj.json.backend;

import forj.json.spi.JsonBackend;
import forj.json.spi.JsonLimits;
import forj.json.tck.JsonBackendTck;

/** The TCK again with a 16-byte buffer, so nearly every token straddles a refill. */
class SmallBufferTest extends JsonBackendTck {
    private static final ForjBackend BACKEND = ForjBackend.of(JsonLimits.DEFAULT, 16, 4);

    @Override
    protected JsonBackend backend() {
        return BACKEND;
    }
}
