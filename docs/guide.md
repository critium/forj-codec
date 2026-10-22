# Using forj-codec

How to turn Java values into JSON and back with forj-codec. Every example here is compiled
and checked by `json/src/test/java/forj/json/GuideExamplesTest.java`.

1. [Getting started](#1-getting-started)
2. [Records](#2-records)
3. [Built-in types and collections](#3-built-in-types-and-collections)
4. [Sealed types](#4-sealed-types)
5. [Custom codecs](#5-custom-codecs)
6. [Choosing codecs with givens](#6-choosing-codecs-with-givens)
7. [Errors](#7-errors)
8. [Streams and effects](#8-streams-and-effects)
9. [Backends and limits](#9-backends-and-limits)

## 1. Getting started

forj-codec is built with forj, a javac plugin, and needs the same pinned JDK 28 EA with
`--enable-preview` (see forj's README, "Using forj from another build"). Publish both to your
local Maven repository:

```sh
(cd forj && ./mill __.publishM2Local)
(cd forj-mega-project/codec && ./mill json.publishM2Local)
```

Then, in a Mill module that mixes in forj's `ForjModule` recipe:

```scala
def mvnDeps = super.mvnDeps() ++ Seq(mvn"forj:forj-json:0.1.0-SNAPSHOT")
```

A first round trip:

```java
import forj.json.Codec;
import forj.json.Json;

record Point(double x, double y) {
    given Codec<Point> codec = Codec.product(
            a -> new Point((double) a[0], (double) a[1]),
            Codec.<Point, Double>field("x", Point::x),
            Codec.<Point, Double>field("y", Point::y));
}

byte[] bytes = Json.encode(new Point(1.5, 2));     // {"x":1.5,"y":2.0}
Point back = Json.decode(bytes);
```

- **The codec is a given** declared inside the record, so it's found wherever a `Point` is
  encoded or decoded, with no import.
- **`Json.decode` finds the type from the variable:** `Point back = ...` asks for a
  `Codec<Point>`. There's no `Point.class`.
- **`encode` returns `byte[]`**; to write to a stream, see [section 8](#8-streams-and-effects).

With `import static forj.json.Json.*`, the same calls read as methods:

```java
byte[] bytes = point.toJson();
Point back = bytes.fromJson();
```

## 2. Records

A record's codec lists its fields in order: the JSON name, the accessor, and (implicitly) the
field type's codec. The constructor lambda receives the decoded values in the same order.

```java
record Address(String city, Optional<String> zip) {
    given Codec<Address> codec = Codec.product(
            a -> new Address((String) a[0], (Optional<String>) a[1]),
            Codec.<Address, String>field("city", Address::city),
            Codec.<Address, Optional<String>>field("zip", Address::zip));
}

record Customer(long id, String name, List<String> tags, Optional<Address> address) {
    given Codec<Customer> codec = Codec.product(
            a -> new Customer((long) a[0], (String) a[1], (List<String>) a[2], (Optional<Address>) a[3]),
            Codec.<Customer, Long>field("id", Customer::id),
            Codec.<Customer, String>field("name", Customer::name),
            Codec.<Customer, List<String>>field("tags", Customer::tags),
            Codec.<Customer, Optional<Address>>field("address", Customer::address));
}
```

`new Customer(7, "Ana", List.of("vip"), Optional.of(new Address("Porto", Optional.empty())))`
is written as:

```json
{"id":7,"name":"Ana","tags":["vip"],"address":{"city":"Porto"}}
```

**Type arguments:** write them out (`Codec.<Customer, Long>field(...)`). forj doesn't infer
them from a method reference yet. Primitive fields use the boxed type (`long` → `Long`).

**How fields are read and written:**

| Situation | What happens |
|---|---|
| Fields | written in the order listed |
| An unknown field in the input | skipped |
| A required field missing | `JsonException`: `missing field 'name' at $[1] (byte 33)` |
| A required field `null` | `JsonException`: `expected a string but was NULL at $.name` |
| A field repeated in the input | `JsonException` |
| An `Optional` field | `empty` is left out when writing; missing or `null` reads as `empty` |
| A plain field that may be `null` | wrap its codec in `Codec.nullable`, below |
| A `null` in a required field when encoding | `IllegalArgumentException` |

**Fields that may be `null`.** Prefer `Optional`. For a plain field that uses `null`, pass a
nullable codec explicitly. A missing field then reads as `null`, and `null` is written as
`null`:

```java
record Note(String text) {
    given Codec<Note> codec = Codec.product(
            a -> new Note((String) a[0]),
            Codec.<Note, String>field("text", Note::text)(using Codec.nullable(Codec.strings)));
}
```

**Recursive types.** A record that contains itself refers to its own codec through
`Codec.lazy`, which looks it up on first use:

```java
record Tree(String label, List<Tree> children) {
    given Codec<Tree> codec = Codec.product(
            a -> new Tree((String) a[0], (List<Tree>) a[1]),
            Codec.<Tree, String>field("label", Tree::label),
            Codec.<Tree, List<Tree>>field("children", Tree::children)(using Codec.list(Codec.lazy(() -> Tree.codec))));
}
```

## 3. Built-in types and collections

| Java type | JSON | Notes |
|---|---|---|
| `String` | string | |
| `boolean`, `Boolean` | `true` / `false` | |
| `int`, `long`, `short`, `byte` and boxes | integer | out of range is an error; `1.0` isn't an integer |
| `double`, `float` and boxes | number | NaN and infinity can't be written |
| `char`, `Character` | one-character string | |
| `BigDecimal` | number | exact, any size |
| `BigInteger` | integer | |
| `UUID` | string | canonical 36-character form only |
| `Instant` | string | ISO-8601 in UTC: `2026-10-06T12:00:00Z` |
| `List<A>` | array | decodes to an unmodifiable list |
| `Set<A>` | array | a repeated element is an error |
| `Map<String, A>` | object | keys are strings; a repeated key is an error |
| `Optional<A>` | the value, or `null` | as a field: left out when empty |
| `forj.data.Either<L, R>` | `{"left": ...}` or `{"right": ...}` | exactly one of the two keys |

Containers work with any element that has a codec, nested as deep as you like:

```java
Map<String, List<Instant>> history = Map.of("logins", List.of(Instant.parse("2026-10-06T12:00:00Z")));
byte[] bytes = Json.encode(history);       // {"logins":["2026-10-06T12:00:00Z"]}
Map<String, List<Instant>> back = Json.decode(bytes);
```

## 4. Sealed types

A sealed interface and its records become objects that name their case in a discriminator
field, written first. Each case keeps its own codec:

```java
sealed interface Payment permits Card, Cash {
    given Codec<Payment> codec = Codec.<Payment>sealed("type")
            .when("card", Card.codec, p -> p instanceof Card c ? c : null)
            .when("cash", Cash.codec, p -> p instanceof Cash c ? c : null)
            .build();
}

record Card(String number) implements Payment {
    given Codec<Card> codec = Codec.product(
            a -> new Card((String) a[0]),
            Codec.<Card, String>field("number", Card::number));
}

record Cash() implements Payment {
    given Codec<Cash> codec = Codec.product(a -> new Cash());
}
```

```json
{"type":"card","number":"4111"}
{"type":"cash"}
```

- **The third argument picks the case** from a `Payment`: it returns the value as that case,
  or `null` for any other case.
- **When decoding,** the discriminator must be the object's first field. An unknown tag is a
  `JsonException` (`unknown type 'crypto' at $.payment.type`).
- **List every case.** Nothing checks it: a case left out fails with
  `IllegalArgumentException` when a value of that case is encoded.
- **Keep default methods out of the sealed interface,** or wrap the case codecs in
  `Codec.lazy`. An interface with default methods is initialized together with its records,
  and the sealed codec could read a case's codec before it exists; the builder then fails with
  a message saying so.

## 5. Custom codecs

**From an existing codec,** with `imap` (convert both ways):

```java
record UserId(long value) {
    given Codec<UserId> codec = Codec.longs.imap(UserId::new, UserId::value);
}
// new UserId(42) is written as 42
```

**By hand,** for full control, implement `Codec` against the streaming reader and writer:

```java
record Range(int from, int to) {
    given Codec<Range> codec = new Codec<>() {
        public void encode(JsonWriter out, Range r) {
            out.beginArray().value(r.from()).value(r.to()).endArray();       // [1,5]
        }

        public Range decode(JsonReader in) {
            in.beginArray();
            int from = in.readInt();
            int to = in.readInt();
            in.endArray();
            if (from > to) throw in.invalidValue("from is after to");
            return new Range(from, to);
        }
    };
}
```

- Read exactly the shape you expect; the reader throws `JsonException` for anything else.
- `in.invalidValue(reason)` reports a value that parsed but isn't acceptable, with that
  value's JSON path and byte offset. Throw what it returns.
- For objects, keep the field names in a `FieldNames` (`FieldNames.of("from", "to")`):
  `in.nextField(names)` returns a field's index, or -1 for an unknown field (then call
  `in.skipValue()`), and `out.name(names, i)` writes a pre-encoded name. Neither builds a
  string.

## 6. Choosing codecs with givens

Codecs are found at compile time, by type. The closest one wins:

| Where the given is | Applies to |
|---|---|
| a local `given` | the rest of that block |
| the enclosing method's `using` parameters | that method |
| the enclosing class | everything in the class |
| an `import static` | that file |
| next to the type (`Codec` itself, or the record) | everywhere: the default |

Two candidates at the same level is a compile error, and so is a type with no codec:
`forj: no given forj.json.Codec<java.time.Instant>`.

**A component codec is chosen where the record's codec is built,** not where it's used. To
write `Instant`s as epoch milliseconds in some records, put the override next to those
records' codecs:

```java
record Event(String name, Instant at) {
    given Codec<Instant> millis = Codec.longs.imap(Instant::ofEpochMilli, Instant::toEpochMilli);

    given Codec<Event> codec = Codec.product(
            a -> new Event((String) a[0], (Instant) a[1]),
            Codec.<Event, String>field("name", Event::name),
            Codec.<Event, Instant>field("at", Event::at));
}
// {"name":"launch","at":1700000000123}
```

Any codec can also be passed explicitly, which skips the search:
`Json.encode(value)(using myCodec, JsonBackend.forj)`.

## 7. Errors

**Bad input** is always a `forj.json.spi.JsonException`. It says what was wrong, where in the
document, and at which byte:

```java
// bytes: [{"id":1,"name":"Ana","tags":[]},{"id":2,"tags":[]}]
try {
    List<Customer> customers = Json.decode(bytes);
} catch (JsonException e) {
    e.getMessage();    // missing field 'name' at $[1] (byte 33)
    e.path();          // $[1]
    e.offset();        // 33: where the second object starts
}
```

Choose how a failure comes back:

```java
Customer c = Json.decode(bytes);                                // throws JsonException
Either<JsonException, Customer> result = Json.decodeEither(bytes);   // Left on failure
IO<Customer> program = Json.decodeF(bytes);                     // a failed IO (section 8)
```

**Values that can't be written** are an `IllegalArgumentException`: a `null` in a required
field, NaN or infinity, a string with an unpaired surrogate, or a sealed case with no
`when(...)`.

**What the reader rejects:** anything that isn't strict RFC 8259 JSON (comments, trailing
commas, single quotes), malformed UTF-8, unpaired surrogates, a byte order mark, and anything
but whitespace after the document. Numbers of any size are accepted; the type you decode into
checks the range.

## 8. Streams and effects

**Streams.** `encode` and `decode` also take an `OutputStream` or `InputStream`. The stream
is read or written through one bounded buffer, so a large document never needs to fit in
memory, and it isn't closed:

```java
Json.encode(customer, outputStream);
Customer c = Json.decode(inputStream);     // or inputStream.fromJson()
```

**Effects.** With forj's `IO` (or any effect type with the matching instance), decoding and
encoding become values you run later:

```java
IO<Customer> fromBytes = Json.decodeF(bytes);           // bad input fails the IO
IO<Customer> fromStream = Json.decodeF(inputStream);    // nothing is read until it runs
IO<Void> write = Json.encodeF(customer, outputStream);  // nothing is written until it runs
```

- `decodeF(bytes)` works for any `F` with an `ApplicativeError<F, Throwable>`.
- The stream versions need `Sync<F>`, because reading and writing are side effects.

**JDK effect types** such as `Callable` aren't forj `Kind`s. Import forj's `liftResults` in
the file, and forj converts the result for you:

```java
import static forj.Language.liftResults;

Callable<Customer> task = Json.decodeF(bytes);
```

## 9. Backends and limits

The parser is a given too. forj's own is the default; give another one, or the same one
configured differently, in a closer scope:

```java
static final JsonBackend STRICT = ForjBackend.of(
        JsonLimits.DEFAULT.withMaxDepth(32).withMaxStringLength(10_000).withMaxDocumentSize(1_000_000),
        16_384,     // buffer size in bytes
        512);       // buffers kept in the shared pool

static Customer parseUpload(byte[] bytes) {
    given JsonBackend backend = STRICT;
    return Json.decode(bytes);
}
```

| Limit (`JsonLimits`) | Default | Exceeding it |
|---|---|---|
| `maxDepth` | 512 | `JsonException`: nesting deeper than the limit |
| `maxStringLength` (chars) | 20,000,000 | `JsonException` |
| `maxNumberLength` (bytes) | 1,000 | `JsonException` |
| `maxDocumentSize` (bytes) | unlimited | `JsonException` |

- **Buffers** come from a pool shared by all threads, with no per-thread caches, so memory
  stays bounded however many virtual threads decode at once. A backend made with
  `ForjBackend.of` has its own pool.
- **A whole application** can switch backends with a class-level given, or an
  `import static` of a class that declares one.
