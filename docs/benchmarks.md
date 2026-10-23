# Benchmarks

For anyone choosing a JSON library: how fast forj-codec is compared with Jackson databind on
the same records and the same bytes, how much memory each uses, and where forj-codec's
limits are.

## Current results

Data: 1 user is 225 B of JSON, 1,000 users 285 KB, 10,000 points 510 KB. "forj" uses codecs
from `derives Codec`; "forj hand-written" uses the same codecs written with `Codec.product`
(the same decoder, a generic encoder). "forj on Jackson parser" is the derived codecs on
jackson-core, through the `json-jackson` adapter. "Jackson" is Jackson databind. Bold marks
where forj and Jackson differ by more than 5%, on whichever is better; smaller differences are
within run-to-run noise.

**Speed** (higher is better):

| Data | Operation | forj (MB/s) | forj hand-written (MB/s) | forj on Jackson parser (MB/s) | Jackson (MB/s) | vs Jackson |
|---|---|---:|---:|---:|---:|---:|
| 1 user | decode | 204 | 203 | 180 | 196 | 1.04× |
| 1 user | encode | **496** | 461 | 345 | 435 | 1.14× |
| 1,000 users | decode | 221 | 219 | 206 | 225 | 0.98× |
| 1,000 users | encode | 466 | 427 | 346 | 444 | 1.05× |
| 10,000 points | decode | **187** | 186 | 147 | 152 | 1.23× |
| 10,000 points | encode | **473** | 410 | 272 | 334 | 1.42× |

**Memory allocated per call** (lower is better):

| Data | Operation | forj (KB) | forj hand-written (KB) | forj on Jackson parser (KB) | Jackson (KB) |
|---|---|---:|---:|---:|---:|
| 1 user | decode | 2.8 | 2.8 | 3.0 | 2.9 |
| 1 user | encode | **0.8** | 0.8 | 1.4 | 1.1 |
| 1,000 users | decode | 2,403 | 2,399 | 2,411 | 2,433 |
| 1,000 users | encode | 1,180 | 1,168 | 1,769 | **961** |
| 10,000 points | decode | **2,367** | 2,367 | 3,370 | 3,770 |
| 10,000 points | encode | **1,267** | 1,347 | 4,223 | 3,177 |

**In short:**
- forj is level with Jackson databind when decoding string-heavy records and 5–14% faster
  encoding them. On number-heavy data it's 23% faster decoding and 42% faster encoding, and
  allocates less memory (under half when encoding).
- `derives Codec` encodes 5–15% faster than the same codec written by hand. Decoding is the
  same code either way.
- On the same codecs, forj's own parser and writer are 7–74% faster than Jackson's through
  the `json-jackson` adapter. The adapter itself runs 3–22% behind Jackson databind.

Measured 2026-10-23 on an Apple M2 Pro (10 cores, 32 GB, macOS 27.0.1) on mains power
throughout, JDK 28-ea+18, 2 forks × 8 iterations. Error bars were 0.2–3.3%.

## Known limits

- **A derived encoder allocates a small object (24 B) for each record it writes,** to carry
  the writer and field names. It costs about as much as the boxing it saves: 1,000 users
  allocate 1% more than hand-written codecs, 10,000 points 6% less.
- **Decoding is the same for derived and hand-written codecs:** fields are boxed into an
  array for the constructor.
- **`Instant` and `UUID` values go through `String` formatting and parsing** in the JDK.
  Jackson does the same, so this limits how far ahead forj can get.
- **Encoding a large document into memory allocates more than it needs:** the writer's
  buffers grow by doubling up to 256 KB. This costs memory, not speed, in these benchmarks.
- **The Jackson adapter is slower than Jackson databind,** mostly when encoding: it writes
  into a `ByteArrayOutputStream` rather than Jackson's recycled buffers, and adds a layer of
  checks to make Jackson behave like forj's backend.

## What's measured

- **Operations:** encode (record → `byte[]`) and decode (`byte[]` → record), each through the
  library's usual API: `Json.encode`/`Json.decode` for forj,
  `ObjectMapper.writeValueAsBytes`/`readValue` for Jackson. "forj hand-written" passes the
  `Codec.product` codecs explicitly; "forj on Jackson parser" is the same calls with
  `JacksonBackend` as the backend.
- **Fairness:** a test (`BenchEquivalenceTest`) checks that every column writes
  byte-identical JSON for every data set and reads the others' output.
- **Jackson:** 2.22.3 databind, set up the way most applications use it: the `java.time` and
  `Optional` modules, ISO-8601 instants, empty `Optional`s left out, unknown fields ignored.
- **Data** (`bench/.../Corpus.java`, generated from fixed seeds):

  | Data | What it is | Size |
  |---|---|---|
  | 1 user | one record: strings (some non-ASCII or escaped), numbers, an `Instant`, a `UUID`, a list, a map, an optional nested record | 225 B |
  | 1,000 users | a list of those records | 285 KB |
  | 10,000 points | a name and 10,000 `{lat, lon}` pairs | 510 KB |

- **JMH settings:** 1.37, throughput mode, 1 fork, 3 × 2 s warmup, 5 × 2 s measurement,
  `-Xms2g -Xmx2g`, with the GC profiler for memory per call. MB/s is 10⁶ bytes of JSON per
  second.

## Running them

```sh
bin/bench                           # everything; JSON results in out/bench-results/
bin/bench users_encode              # benchmarks matching a pattern
bin/bench users_encode -f 2 -i 8    # more forks and iterations, for tighter error bars
```

Run on mains power for numbers worth comparing.

## Not covered yet

- **jsoniter-scala,** the fastest JVM JSON library in most benchmarks.
- **Jackson 3** (3.2.3), which Spring Boot 4 uses.
- **Streams:** decoding from an `InputStream` and encoding to an `OutputStream`, the way an
  HTTP server uses them.

---

How these numbers were reached, the experiments behind the known limits, and notes for
continuing the work: [benchmarks-for-agents.md](benchmarks-for-agents.md).
