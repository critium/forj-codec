package forj.json.backend;

import forj.json.spi.FieldNames;
import forj.json.spi.JsonException;
import forj.json.spi.JsonLimits;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * forj's reader: strict RFC 8259, straight from UTF-8 bytes.
 *
 * <p>Over a {@code byte[]} it reads the caller's array in place. Over an {@link InputStream} it
 * reads into one pooled buffer, refilling it as the parse moves on, so a document of any size
 * needs about one buffer of memory. A token that doesn't fit (a very long number) grows a
 * private buffer; strings are decoded as they're read and never need to fit.
 *
 * <p>The structure is a small state machine in the style of Gson's {@code JsonReader}:
 * {@link #peek()} consumes separators and validates where the next token may appear, and the
 * read methods consume the token itself.
 */
final class ForjReader implements JsonReader {
    // scope states, kept on the stack
    private static final int EMPTY_ARRAY = 1;
    private static final int NONEMPTY_ARRAY = 2;
    private static final int EMPTY_OBJECT = 3;
    private static final int DANGLING_NAME = 4;
    private static final int NONEMPTY_OBJECT = 5;
    private static final int EMPTY_DOCUMENT = 6;
    private static final int NONEMPTY_DOCUMENT = 7;
    private static final int CLOSED = 8;

    // what peek() found; for STRING and NAME, pos is just past the opening quote, and for
    // NUMBER it's at the number's first byte, which runs for numberLength bytes
    private static final int NONE = 0;
    private static final int P_BEGIN_OBJECT = 1;
    private static final int P_END_OBJECT = 2;
    private static final int P_BEGIN_ARRAY = 3;
    private static final int P_END_ARRAY = 4;
    private static final int P_TRUE = 5;
    private static final int P_FALSE = 6;
    private static final int P_NULL = 7;
    private static final int P_STRING = 8;
    private static final int P_NAME = 9;
    private static final int P_NUMBER = 10;
    private static final int P_EOF = 11;

    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] NULL = {'n', 'u', 'l', 'l'};

    private static final double[] POWERS_OF_TEN = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
        1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22
    };

    private final InputStream in;
    private final BufferPool pool;
    private final JsonLimits limits;

    private byte[] buf;
    /** The pooled buffer to give back on close; null when reading a caller's array. */
    private byte[] pooled;
    private int pos;
    private int limit;
    /** Document offset of buf[0]. */
    private long base;

    private int peeked = NONE;
    private int numberLength;
    private boolean numberIntegral;

    private int[] stack = new int[16];
    /** Document offset where each open array or object began. */
    private long[] starts = new long[16];
    /** Document offset where the token peek() found began, and where the last value read began. */
    private long tokenStart;
    private long valueStart;
    private int[] pathIndices = new int[16];
    private String[] pathNames = new String[16];
    private int depth = 1;

    /** Scratch for decoding strings that aren't plain ASCII in one buffer. */
    private char[] chars = new char[64];
    private int charCount;

    ForjReader(byte[] bytes, int offset, int length, JsonLimits limits) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        this.in = null;
        this.pool = null;
        this.limits = limits;
        this.buf = bytes;
        this.pos = offset;
        this.limit = offset + length;
        this.base = -offset;
        stack[0] = EMPTY_DOCUMENT;
        if (length > limits.maxDocumentSize()) throw error("document larger than " + limits.maxDocumentSize() + " bytes");
    }

    ForjReader(InputStream in, BufferPool pool, JsonLimits limits) {
        this.in = in;
        this.pool = pool;
        this.limits = limits;
        this.buf = this.pooled = pool.acquire();
        stack[0] = EMPTY_DOCUMENT;
    }

    // ---- input

    /** Makes {@code n} bytes available from {@code pos}; false at the end of input. */
    private boolean require(int n) {
        if (limit - pos >= n) return true;
        if (in == null) return false;
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, limit - pos);
            base += pos;
            limit -= pos;
            pos = 0;
        }
        if (n > buf.length) buf = Arrays.copyOf(buf, Math.max(n, buf.length * 2));
        try {
            while (limit < n) {
                int read = in.read(buf, limit, buf.length - limit);
                if (read < 0) return false;
                limit += read;
                if (base + limit > limits.maxDocumentSize()) {
                    throw error("document larger than " + limits.maxDocumentSize() + " bytes");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    /** The next byte that isn't whitespace, left unconsumed at pos; -1 at the end of input. */
    private int nextNonWhitespace() {
        for (;;) {
            byte[] b = buf;
            int p = pos;
            int l = limit;
            while (p < l) {
                int c = b[p];
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    pos = p;
                    return c & 0xff;
                }
                p++;
            }
            pos = p;
            if (!require(1)) return -1;
        }
    }

    // ---- structure

    @Override
    public JsonToken peek() {
        int p = peeked == NONE ? doPeek() : peeked;
        return switch (p) {
            case P_BEGIN_OBJECT -> JsonToken.BEGIN_OBJECT;
            case P_END_OBJECT -> JsonToken.END_OBJECT;
            case P_BEGIN_ARRAY -> JsonToken.BEGIN_ARRAY;
            case P_END_ARRAY -> JsonToken.END_ARRAY;
            case P_TRUE, P_FALSE -> JsonToken.BOOLEAN;
            case P_NULL -> JsonToken.NULL;
            case P_STRING -> JsonToken.STRING;
            case P_NAME -> JsonToken.NAME;
            case P_NUMBER -> JsonToken.NUMBER;
            case P_EOF -> JsonToken.END_DOCUMENT;
            default -> throw new AssertionError(p);
        };
    }

    private int peeked() {
        return peeked == NONE ? doPeek() : peeked;
    }

    private int doPeek() {
        int top = stack[depth - 1];
        int c;
        switch (top) {
            case EMPTY_ARRAY -> {
                stack[depth - 1] = NONEMPTY_ARRAY;
                if (nextNonWhitespace() == ']') {
                    pos++;
                    return peeked = P_END_ARRAY;
                }
            }
            case NONEMPTY_ARRAY -> {
                c = nextNonWhitespace();
                if (c == ']') {
                    pos++;
                    return peeked = P_END_ARRAY;
                }
                if (c != ',') throw unexpected(c, "',' or ']'");
                pos++;
            }
            case EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                if (top == NONEMPTY_OBJECT) {
                    c = nextNonWhitespace();
                    if (c == '}') {
                        pos++;
                        return peeked = P_END_OBJECT;
                    }
                    if (c != ',') throw unexpected(c, "',' or '}'");
                    pos++;
                }
                stack[depth - 1] = DANGLING_NAME;
                c = nextNonWhitespace();
                if (c == '"') {
                    pos++;
                    return peeked = P_NAME;
                }
                if (c == '}' && top == EMPTY_OBJECT) {
                    pos++;
                    return peeked = P_END_OBJECT;
                }
                throw unexpected(c, top == EMPTY_OBJECT ? "a field name or '}'" : "a field name");
            }
            case DANGLING_NAME -> {
                stack[depth - 1] = NONEMPTY_OBJECT;
                c = nextNonWhitespace();
                if (c != ':') throw unexpected(c, "':'");
                pos++;
            }
            case EMPTY_DOCUMENT -> stack[depth - 1] = NONEMPTY_DOCUMENT;
            case NONEMPTY_DOCUMENT -> {
                c = nextNonWhitespace();
                if (c == -1) return peeked = P_EOF;
                throw error("unexpected data after the document");
            }
            case CLOSED -> throw new IllegalStateException("reader is closed");
            default -> throw new AssertionError(top);
        }
        c = nextNonWhitespace();
        tokenStart = base + pos;
        switch (c) {
            case '{' -> {
                pos++;
                return peeked = P_BEGIN_OBJECT;
            }
            case '[' -> {
                pos++;
                return peeked = P_BEGIN_ARRAY;
            }
            case '"' -> {
                pos++;
                return peeked = P_STRING;
            }
            case 't' -> {
                literal(TRUE);
                return peeked = P_TRUE;
            }
            case 'f' -> {
                literal(FALSE);
                return peeked = P_FALSE;
            }
            case 'n' -> {
                literal(NULL);
                return peeked = P_NULL;
            }
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    scanNumber();
                    return peeked = P_NUMBER;
                }
                throw unexpected(c, "a value");
            }
        }
    }

    private void literal(byte[] word) {
        if (!require(word.length)) throw error("unexpected end of input");
        if (!Arrays.equals(buf, pos, pos + word.length, word, 0, word.length)) {
            throw error("invalid literal");
        }
        pos += word.length;
    }

    private void push(int scope) {
        if (depth > limits.maxDepth()) throw error("nesting deeper than " + limits.maxDepth());
        if (depth == stack.length) {
            stack = Arrays.copyOf(stack, depth * 2);
            starts = Arrays.copyOf(starts, depth * 2);
            pathIndices = Arrays.copyOf(pathIndices, depth * 2);
            pathNames = Arrays.copyOf(pathNames, depth * 2);
        }
        stack[depth] = scope;
        starts[depth] = tokenStart;
        pathIndices[depth] = 0;
        pathNames[depth] = null;
        depth++;
    }

    /** A value at the current level has been fully consumed. */
    private void afterValue() {
        valueStart = tokenStart;
        peeked = NONE;
        pathIndices[depth - 1]++;
    }

    @Override
    public void beginObject() {
        expect(P_BEGIN_OBJECT, "an object");
        peeked = NONE;
        push(EMPTY_OBJECT);
    }

    @Override
    public void endObject() {
        expect(P_END_OBJECT, "the end of the object");
        depth--;
        tokenStart = starts[depth];
        afterValue();
    }

    @Override
    public void beginArray() {
        expect(P_BEGIN_ARRAY, "an array");
        peeked = NONE;
        push(EMPTY_ARRAY);
    }

    @Override
    public void endArray() {
        expect(P_END_ARRAY, "the end of the array");
        depth--;
        tokenStart = starts[depth];
        afterValue();
    }

    @Override
    public boolean hasNext() {
        int p = peeked();
        return p != P_END_OBJECT && p != P_END_ARRAY && p != P_EOF;
    }

    @Override
    public void endDocument() {
        if (peeked() != P_EOF) throw error("expected the end of the document but was " + peek());
    }

    private void expect(int token, String what) {
        int p = peeked();
        if (p != token) throw error("expected " + what + " but was " + peek());
    }

    // ---- names

    @Override
    public String nextName() {
        expect(P_NAME, "a field name");
        String name = decodeString();
        pathNames[depth - 1] = name;
        peeked = NONE;
        return name;
    }

    @Override
    public int nextField(FieldNames names) {
        expect(P_NAME, "a field name");
        // fast path: the name is in the buffer with no escapes, so match its bytes directly
        byte[] b = buf;
        for (int p = pos, l = limit; p < l; p++) {
            int c = b[p];
            if (c == '"') {
                int index = names.indexOf(b, pos, p - pos);
                if (index >= 0) {
                    pos = p + 1;
                    pathNames[depth - 1] = names.name(index);
                    peeked = NONE;
                    return index;
                }
                break;
            }
            if (c == '\\' || (c >= 0 && c < 0x20)) break;
        }
        String name = decodeString();
        pathNames[depth - 1] = name;
        peeked = NONE;
        return names.indexOf(name);
    }

    // ---- strings

    @Override
    public String readString() {
        expect(P_STRING, "a string");
        String s = decodeString();
        afterValue();
        return s;
    }

    /** Decodes the string at pos (just past its opening quote) and consumes its closing quote. */
    private String decodeString() {
        byte[] b = buf;
        int start = pos;
        int p = start;
        for (int l = limit; p < l; p++) {
            int c = b[p];
            if (c == '"') {
                checkLength(p - start);
                pos = p + 1;
                return new String(b, start, p - start, StandardCharsets.ISO_8859_1);
            }
            if (c == '\\' || c < 0x20) break; // escapes, control characters and non-ASCII
        }
        charCount = 0;
        for (int i = start; i < p; i++) append((char) b[i]);
        pos = p;
        scanString(true);
        return new String(chars, 0, charCount);
    }

    /**
     * Reads string content from pos to its closing quote, validating escapes and UTF-8;
     * appends the chars when {@code keep}, otherwise only counts them.
     */
    private void scanString(boolean keep) {
        for (;;) {
            if (pos == limit && !require(1)) throw error("unterminated string");
            int c = buf[pos];
            if (c == '"') {
                pos++;
                return;
            }
            if (c == '\\') {
                pos++;
                escape(keep);
            } else if (c >= 0x20) {
                pos++;
                if (keep) append((char) c);
                else charCount++;
            } else if (c >= 0) {
                throw error("unescaped control character in string");
            } else {
                utf8(c & 0xff, keep);
            }
            if (charCount > limits.maxStringLength()) checkLength(charCount);
        }
    }

    private void escape(boolean keep) {
        if (!require(1)) throw error("unterminated string");
        int c = buf[pos++];
        char decoded;
        switch (c) {
            case '"' -> decoded = '"';
            case '\\' -> decoded = '\\';
            case '/' -> decoded = '/';
            case 'b' -> decoded = '\b';
            case 'f' -> decoded = '\f';
            case 'n' -> decoded = '\n';
            case 'r' -> decoded = '\r';
            case 't' -> decoded = '\t';
            case 'u' -> {
                char high = hex4();
                if (Character.isLowSurrogate(high)) throw error("unpaired surrogate in \\u escape");
                if (Character.isHighSurrogate(high)) {
                    if (!require(2) || buf[pos] != '\\' || buf[pos + 1] != 'u') {
                        throw error("unpaired surrogate in \\u escape");
                    }
                    pos += 2;
                    char low = hex4();
                    if (!Character.isLowSurrogate(low)) throw error("unpaired surrogate in \\u escape");
                    if (keep) append(high);
                    else charCount++;
                    decoded = low;
                } else {
                    decoded = high;
                }
            }
            default -> {
                pos--;
                throw error("invalid escape");
            }
        }
        if (keep) append(decoded);
        else charCount++;
    }

    private char hex4() {
        if (!require(4)) throw error("unterminated \\u escape");
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int c = buf[pos + i];
            int digit = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10
                    : -1;
            if (digit < 0) throw error("invalid \\u escape");
            value = value << 4 | digit;
        }
        pos += 4;
        return (char) value;
    }

    /** Decodes one multi-byte UTF-8 sequence starting with {@code lead}, rejecting malformed ones. */
    private void utf8(int lead, boolean keep) {
        int n;
        int min2 = 0x80;
        int max2 = 0xBF;
        if (lead >= 0xC2 && lead <= 0xDF) {
            n = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            n = 3;
            if (lead == 0xE0) min2 = 0xA0; // overlong
            if (lead == 0xED) max2 = 0x9F; // UTF-16 surrogates
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            n = 4;
            if (lead == 0xF0) min2 = 0x90; // overlong
            if (lead == 0xF4) max2 = 0x8F; // above U+10FFFF
        } else {
            throw error("invalid UTF-8");
        }
        if (!require(n)) throw error("invalid UTF-8");
        int b2 = buf[pos + 1] & 0xff;
        if (b2 < min2 || b2 > max2) throw error("invalid UTF-8");
        int cp = (lead & (0xFF >> (n + 1))) << 6 | (b2 & 0x3F);
        for (int i = 2; i < n; i++) {
            int b = buf[pos + i] & 0xff;
            if ((b & 0xC0) != 0x80) throw error("invalid UTF-8");
            cp = cp << 6 | (b & 0x3F);
        }
        pos += n;
        if (cp >= 0x10000) {
            if (keep) {
                append(Character.highSurrogate(cp));
                append(Character.lowSurrogate(cp));
            } else {
                charCount += 2;
            }
        } else if (keep) {
            append((char) cp);
        } else {
            charCount++;
        }
    }

    private void append(char c) {
        if (charCount == chars.length) chars = Arrays.copyOf(chars, charCount * 2);
        chars[charCount++] = c;
    }

    private void checkLength(int length) {
        if (length > limits.maxStringLength()) {
            throw error("string longer than " + limits.maxStringLength() + " chars");
        }
    }

    // ---- literals

    @Override
    public boolean readBoolean() {
        int p = peeked();
        if (p != P_TRUE && p != P_FALSE) throw error("expected a boolean but was " + peek());
        afterValue();
        return p == P_TRUE;
    }

    @Override
    public void readNull() {
        expect(P_NULL, "null");
        afterValue();
    }

    @Override
    public boolean readNullIfPresent() {
        if (peeked() != P_NULL) return false;
        afterValue();
        return true;
    }

    // ---- numbers

    /** Validates the number at pos against the JSON grammar, leaving it unconsumed and whole in buf. */
    private void scanNumber() {
        int k = 0;
        boolean integral = true;
        int c = byteAt(k);
        if (c == '-') c = byteAt(++k);
        if (c == '0') {
            c = byteAt(++k);
        } else if (c >= '1' && c <= '9') {
            do c = byteAt(++k); while (c >= '0' && c <= '9');
        } else {
            throw numberError(k);
        }
        if (c == '.') {
            integral = false;
            c = byteAt(++k);
            if (c < '0' || c > '9') throw numberError(k);
            do c = byteAt(++k); while (c >= '0' && c <= '9');
        }
        if (c == 'e' || c == 'E') {
            integral = false;
            c = byteAt(++k);
            if (c == '+' || c == '-') c = byteAt(++k);
            if (c < '0' || c > '9') throw numberError(k);
            do c = byteAt(++k); while (c >= '0' && c <= '9');
        }
        numberLength = k;
        numberIntegral = integral;
    }

    private int byteAt(int k) {
        if (k > limits.maxNumberLength()) throw error("number longer than " + limits.maxNumberLength() + " bytes");
        if (pos + k < limit || require(k + 1)) return buf[pos + k] & 0xff;
        return -1;
    }

    private JsonException numberError(int k) {
        pos += k;
        return error("invalid number");
    }

    @Override
    public int readInt() {
        long value = readLong();
        if (value != (int) value) throw error("number out of int range");
        return (int) value;
    }

    @Override
    public long readLong() {
        expect(P_NUMBER, "a number");
        if (!numberIntegral) throw error("expected an integer");
        byte[] b = buf;
        int p = pos;
        int end = p + numberLength;
        boolean negative = b[p] == '-';
        if (negative) p++;
        // accumulate negatively, like Long.parseLong, so Long.MIN_VALUE fits
        long result = 0;
        long limitValue = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multMin = limitValue / 10;
        for (; p < end; p++) {
            int digit = b[p] - '0';
            if (result < multMin) throw error("number out of long range");
            result *= 10;
            if (result < limitValue + digit) throw error("number out of long range");
            result -= digit;
        }
        consumeNumber();
        return negative ? result : -result;
    }

    @Override
    public double readDouble() {
        expect(P_NUMBER, "a number");
        byte[] b = buf;
        int p = pos;
        int end = p + numberLength;
        boolean negative = b[p] == '-';
        if (negative) p++;
        // Clinger's fast path: up to 18 significant digits, then an exact power of ten
        long mantissa = 0;
        int digits = 0;
        int exponent = 0;
        boolean fast = true;
        for (; p < end && b[p] >= '0' && b[p] <= '9'; p++) {
            if (digits < 18) {
                mantissa = mantissa * 10 + (b[p] - '0');
                if (mantissa != 0) digits++;
            } else {
                fast = false;
            }
        }
        if (p < end && b[p] == '.') {
            for (p++; p < end && b[p] >= '0' && b[p] <= '9'; p++) {
                if (digits < 18) {
                    mantissa = mantissa * 10 + (b[p] - '0');
                    if (mantissa != 0) digits++;
                    exponent--;
                } else {
                    fast = false;
                }
            }
        }
        if (p < end) { // exponent
            p++;
            boolean negativeExponent = b[p] == '-';
            if (b[p] == '+' || b[p] == '-') p++;
            int e = 0;
            for (; p < end; p++) {
                e = e * 10 + (b[p] - '0');
                if (e > 100_000) {
                    fast = false;
                    break;
                }
            }
            exponent += negativeExponent ? -e : e;
        }
        double value;
        if (fast && mantissa < (1L << 53) && exponent >= -22 && exponent <= 22) {
            value = exponent >= 0 ? mantissa * POWERS_OF_TEN[exponent] : mantissa / POWERS_OF_TEN[-exponent];
            if (negative) value = -value;
        } else {
            value = Double.parseDouble(new String(buf, pos, numberLength, StandardCharsets.ISO_8859_1));
        }
        if (Double.isInfinite(value)) throw error("number out of double range");
        consumeNumber();
        return value;
    }

    @Override
    public BigDecimal readBigDecimal() {
        expect(P_NUMBER, "a number");
        char[] digits = new char[numberLength];
        for (int i = 0; i < numberLength; i++) digits[i] = (char) buf[pos + i];
        BigDecimal value;
        try {
            value = new BigDecimal(digits);
        } catch (NumberFormatException e) {
            throw error("number out of BigDecimal range");
        }
        consumeNumber();
        return value;
    }

    private void consumeNumber() {
        pos += numberLength;
        afterValue();
    }

    // ---- skipping

    @Override
    public void skipValue() {
        int open = 0;
        long start = -1;
        do {
            int p = peeked();
            if (start < 0) start = tokenStart;
            switch (p) {
                case P_BEGIN_OBJECT -> {
                    peeked = NONE;
                    push(EMPTY_OBJECT);
                    open++;
                }
                case P_BEGIN_ARRAY -> {
                    peeked = NONE;
                    push(EMPTY_ARRAY);
                    open++;
                }
                case P_END_OBJECT, P_END_ARRAY -> {
                    if (open == 0) throw error("expected a value but was " + peek());
                    depth--;
                    open--;
                }
                case P_NAME -> {
                    if (open == 0) throw error("expected a value but was a field name");
                    skipString();
                }
                case P_STRING -> skipString();
                case P_NUMBER -> pos += numberLength;
                case P_EOF -> throw error("expected a value but was the end of the document");
                default -> { } // literals were consumed by peek
            }
            peeked = NONE;
        } while (open > 0);
        tokenStart = start;
        afterValue();
    }

    private void skipString() {
        byte[] b = buf;
        for (int p = pos, l = limit; p < l; p++) {
            int c = b[p];
            if (c == '"') {
                checkLength(p - pos);
                pos = p + 1;
                return;
            }
            if (c == '\\' || c < 0x20) {
                charCount = p - pos;
                pos = p;
                scanString(false);
                return;
            }
        }
        charCount = limit - pos;
        pos = limit;
        scanString(false);
    }

    // ---- position and errors

    @Override
    public String path() {
        return path(false);
    }

    @Override
    public JsonException invalidValue(String reason) {
        return new JsonException(reason, valueStart, path(true));
    }

    /** With {@code lastValue}, the path of the value just read, whose array index has moved on. */
    private String path(boolean lastValue) {
        StringBuilder out = new StringBuilder("$");
        for (int i = 1; i < depth; i++) {
            switch (stack[i]) {
                case EMPTY_ARRAY, NONEMPTY_ARRAY -> {
                    int index = lastValue && i == depth - 1 ? pathIndices[i] - 1 : pathIndices[i];
                    out.append('[').append(index).append(']');
                }
                case EMPTY_OBJECT, DANGLING_NAME, NONEMPTY_OBJECT -> {
                    String name = pathNames[i];
                    if (name != null) appendName(out, name);
                }
                default -> { }
            }
        }
        return out.toString();
    }

    private static void appendName(StringBuilder out, String name) {
        boolean plain = !name.isEmpty() && Character.isJavaIdentifierStart(name.charAt(0));
        for (int i = 1; plain && i < name.length(); i++) plain = Character.isJavaIdentifierPart(name.charAt(i));
        if (plain) {
            out.append('.').append(name);
        } else {
            out.append("['").append(name.replace("\\", "\\\\").replace("'", "\\'")).append("']");
        }
    }

    @Override
    public long offset() {
        return base + pos;
    }

    private JsonException error(String reason) {
        return new JsonException(reason, offset(), path());
    }

    private JsonException unexpected(int c, String expected) {
        if (c == -1) return error("unexpected end of input, expected " + expected);
        String found = c >= 0x20 && c < 0x7F ? "'" + (char) c + "'" : String.format("byte 0x%02x", c);
        return error("expected " + expected + " but found " + found);
    }

    @Override
    public void close() {
        if (stack[0] == CLOSED && depth == 1) return;
        depth = 1;
        stack[0] = CLOSED;
        peeked = NONE;
        if (pooled != null) {
            pool.release(pooled);
            pooled = null;
        }
        buf = null;
    }
}
