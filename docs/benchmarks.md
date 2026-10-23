# Benchmarks

For anyone choosing a JSON library: how fast forj-codec is compared with Jackson databind on
the same records and the same bytes, how much memory each uses, and where forj-codec's
limits are.

## Current results

Data: 1 user is 225 B of JSON, 1,000 users 285 KB, 10,000 points 510 KB. "Jackson" is
Jackson databind; "forj on Jackson parser" is forj's codecs on jackson-core, through the
`json-jackson` adapter. In each row, the best value is in bold when it leads by more than
5% (smaller differences are within run-to-run noise).

**Speed** (higher is better):

| Data | Operation | forj (MB/s) | forj on Jackson parser (MB/s) | Jackson (MB/s) | vs Jackson |
|---|---|---:|---:|---:|---:|
| 1 user | decode | 201 | 182 | 196 | 1.03× |
| 1 user | encode | **470** | 322 | 436 | 1.08× |
| 1,000 users | decode | 224 | 203 | 223 | 1.00× |
| 1,000 users | encode | 424 | 332 | 436 | 0.97× |
| 10,000 points | decode | **190** | 149 | 163 | 1.17× |
| 10,000 points | encode | **449** | 297 | 336 | 1.33× |

**Memory allocated per call** (lower is better):

| Data | Operation | forj (KB) | forj on Jackson parser (KB) | Jackson (KB) |
|---|---|---:|---:|---:|
| 1 user | decode | 2.8 | 3.0 | 2.9 |
| 1 user | encode | **0.8** | 1.3 | 1.0 |
| 1,000 users | decode | 2,339 | 2,363 | 2,376 |
| 1,000 users | encode | 1,141 | 1,716 | **938** |
| 10,000 points | decode | **2,312** | 3,291 | 3,135 |
| 10,000 points | encode | **1,315** | 4,202 | 3,102 |

**In short:**
- forj is level with Jackson databind on string-heavy records and 17–33% faster on
  number-heavy data, where it also allocates less memory (under half when encoding).
- On the same codecs, forj's own parser and writer are 10–51% faster than Jackson's through
  the `json-jackson` adapter. The adapter itself runs 7–26% behind Jackson databind.

Measured 2026-10-22 on an Apple M2 Pro (10 cores, 32 GB, macOS 27.0.1) on mains power,
JDK 28-ea+18, 1 fork × 5 iterations. Error bars were 0.3–3.8%; differences under about 5% are
within run-to-run noise.

## Known limits

- **These records use hand-written codecs,** which encode through a generic field loop.
  `derives Codec` generates a straight-line encoder for each record instead; it isn't
  measured here yet.
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
  `ObjectMapper.writeValueAsBytes`/`readValue` for Jackson. The middle column is the same
  `Json.encode`/`Json.decode` calls with `JacksonBackend` as the backend.
- **Fairness:** a test (`BenchEquivalenceTest`) checks that both libraries write
  byte-identical JSON for every data set and read each other's output.
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
