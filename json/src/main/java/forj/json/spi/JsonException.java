package forj.json.spi;

/**
 * Malformed input, a value of the wrong kind, or a limit exceeded.
 *
 * <p>{@link #offset()} is the byte offset into the document where the problem was found, and
 * {@link #path()} the JSON path of the value being read ({@code $.users[3].name}). A backend
 * that can't tell reports {@code -1} and {@code null}.
 */
public final class JsonException extends RuntimeException {
    private final long offset;
    private final String path;

    public JsonException(String reason, long offset, String path) {
        super(reason + (path == null ? "" : " at " + path) + (offset < 0 ? "" : " (byte " + offset + ")"));
        this.offset = offset;
        this.path = path;
    }

    public JsonException(String reason, long offset, String path, Throwable cause) {
        this(reason, offset, path);
        initCause(cause);
    }

    /** Byte offset into the document, or -1 if unknown. */
    public long offset() {
        return offset;
    }

    /** JSON path of the value being read, such as {@code $.users[3].name}, or null if unknown. */
    public String path() {
        return path;
    }
}
