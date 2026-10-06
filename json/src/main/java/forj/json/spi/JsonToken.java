package forj.json.spi;

/** What {@link JsonReader#peek()} sees next. */
public enum JsonToken {
    BEGIN_OBJECT,
    END_OBJECT,
    BEGIN_ARRAY,
    END_ARRAY,
    /** A field name inside an object. */
    NAME,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    /** The document's single top-level value has been read and only whitespace follows. */
    END_DOCUMENT
}
