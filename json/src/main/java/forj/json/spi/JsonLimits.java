package forj.json.spi;

/**
 * Bounds a reader enforces, so hostile input can't exhaust the stack or the heap.
 *
 * @param maxDepth        deepest nesting of arrays and objects
 * @param maxStringLength longest string or field name, in chars
 * @param maxNumberLength longest number literal, in bytes
 * @param maxDocumentSize largest document, in bytes
 */
public record JsonLimits(int maxDepth, int maxStringLength, int maxNumberLength, long maxDocumentSize) {
    public static final JsonLimits DEFAULT = new JsonLimits(512, 20_000_000, 1_000, Long.MAX_VALUE);

    public JsonLimits {
        if (maxDepth < 1 || maxStringLength < 0 || maxNumberLength < 1 || maxDocumentSize < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    public JsonLimits withMaxDepth(int maxDepth) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxDocumentSize);
    }

    public JsonLimits withMaxStringLength(int maxStringLength) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxDocumentSize);
    }

    public JsonLimits withMaxNumberLength(int maxNumberLength) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxDocumentSize);
    }

    public JsonLimits withMaxDocumentSize(long maxDocumentSize) {
        return new JsonLimits(maxDepth, maxStringLength, maxNumberLength, maxDocumentSize);
    }
}
