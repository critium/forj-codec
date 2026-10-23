# AGENTS.md: working on forj-codec

forj-codec is JSON for forj: codecs as type classes (`Codec<A>`, resolved as givens) over a
streaming SPI (`JsonReader`/`JsonWriter`/`JsonBackend`), with its own strict RFC 8259 parser
as the default backend. It is written in forj, a javac plugin that adds Scala features to
`.java` files. **Code using forj is not valid plain Java**, so an editor, a linter, or your
own knowledge of Java will flag it as broken. It isn't. The Mill build is the source of truth:
if `./mill __.test` passes, the code is correct.

- **Part 1** is how to read and write forj code. It is adapted from forj's own `AGENTS.md`
  (Part 1, as of forj `7ab78e3` plus the docs-style recipe). When forj changes, resync it from
  `../../forj/AGENTS.md`.
- **Part 2** is this repo's rules.

For more on forj, see `../../forj/docs/java-developers.md` (full guide) and
`../../forj/TODO.md` (known limits). For this repo: `README.md` (design goals, anti-goals),
`docs/guide.md` (how to use the codecs), `docs/benchmarks.md` and
`docs/benchmarks-for-agents.md`, `TODO.md` (next steps, in order).

## Part 1: forj code

### Never "fix" these: they are forj syntax

| You see | It is | Compiles to |
|---|---|---|
| `forj { x <- fa; y <- fb; } yield x + y` | comprehension (Scala `for`/`yield`) | `forjFlatMap(fa, x -> forjMap(fb, y -> x + y))` with the `Monad<F>` passed in |
| `x <- e;` / `_ <- e;` | generator / generator whose value is discarded | a lambda parameter |
| `guard(cond);` | filter, directly after a generator | `forjFilter` (needs `FunctorFilter<F>`) |
| `f(A a)(using Codec<A> c)` | context parameters (Scala 3 `using`) | `f(A a, @forj.Using Codec<A> c)` |
| `f(x)(using custom)` at a call | passing an instance explicitly | `f(x, custom)` |
| `f(x)` where `f` has a `using` clause | **instances filled in by the compiler** | `f(x, Codec.longs)` |
| `given Codec<Long> longs = ...;` | an instance (a member: `public static` by default) | `@forj.Given public static final Codec<Long> longs = ...;` |
| `given <A> Codec<List<A>> list(using Codec<A> e) {...}` | an instance built from others | a `@forj.Given` static method |
| `extension <A> byte[] toJson(A a)(using Codec<A> c)` | extension method | `@forj.Extension public static` |
| `user.toJson()` with no `toJson` on `User` | extension call | `Json.toJson(user, User.codec)` |
| `interface Functor<F<_>>`, `<F<_>> F<A> pure(A a)` | higher-kinded type parameter | `F` is a plain type parameter |
| `F<A>` where `F` is declared `F<_>` | a type applied to a type constructor | `forj.Kind<F, A>` |
| `record User(long id, String name) derives Codec {}` | derived instance (Scala 3 `derives`) | adds `given Codec<User> codec = Codec.deriveProduct(...)` to the record, with a generated encoder |
| `s"Hi $name, ${a + b}"` / `f"$x%.2f"` / `raw"C:\$p"` | string interpolation | `+` concatenation / `String.format` / raw text |
| `import static forj.Language.lifting;` | opt-in automatic lifting for this file | nothing; it changes how calls in the file resolve |

The keywords are contextual: `forj`, `given`, `using`, `extension` and `derives` remain
usable as ordinary names elsewhere. Each keyword form has a plain-Java spelling (`@Given`,
`@Using`, `@Extension`, `@Derives(X.class)`) with identical behaviour. Write that spelling
only where a file must stay parseable by plain tools.

### Invisible behaviour to account for when reading code

- **Calls can have arguments you don't see.** Any call to a method with a `using` clause gets
  its instances inserted (`Json.encode(user)` passes `User.codec`). To know what runs, find
  the given. The search goes nearest first, and the first level with a match wins; two
  matches at one level is a compile error:
  1. local `given`s (how the benchmarks swap in `given JsonBackend backend = JacksonBackend.DEFAULT;`);
  2. the enclosing method's `using` parameters;
  3. the enclosing classes (and their supertypes);
  4. `import static`;
  5. the companions of the required type and its type arguments (`Codec`'s own givens,
     a record's `codec`, `JsonBackend.forj`);
  6. `forj.Instances` (JDK types).
- **`x.m(...)` may not be a method of `x`.** It's an extension call when `x`'s type has no
  `m`. Lookup goes nearest first:
  1. the enclosing classes;
  2. `import static`;
  3. the type classes of givens in scope;
  4. the receiver's class and its type arguments' classes;
  5. `forj.Syntax`, which provides `map`, `flatMap`, `handleErrorWith`, `attempt`,
     `combine`, `sequence` and more on any `F<A>`.

  A real method always wins: `optional.map(f)` is `Optional.map`.
- **`F` is inferred from context,** not only from arguments:
  - a typed local: `IO<User> u = Json.decodeF(bytes);` gives `F = IO`;
  - a `return`, or an assignment;
  - the parameter the call is passed to.

  It is *not* inferred through a generic outer call. There, use a typed local or explicit
  type arguments.
- **A `using` clause after varargs moves in front of them** in the compiled signature:
  `of(Route<F>... rs)(using Applicative<F> ap)` is `of(Applicative<F> ap, Route<F>... rs)` to
  plain Java callers.
- **Lifting**, only in files importing `forj.Language.*`:
  - `liftArguments` wraps a JDK `List`/`Optional`/`Callable` passed where a `Kind` is
    expected;
  - `liftReceivers` lets `list.map(f)` reach `Syntax.map`;
  - `liftResults` unwraps a `Kind` assigned to a JDK type
    (`Callable<User> c = Json.decodeF(bytes);`).

### Rules when writing forj code

- Every generator ends with `;`. Generators are top-level statements of the block, never
  inside `if`/loops.
- `guard(...)` must directly follow a generator or another guard.
- No `return` and no `yield` inside a `forj { }` block; the result goes after it,
  `} yield expr`. (`yield` inside a `switch` expression is fine.)
- All generators in one block use the same `F`. Nested blocks may differ.
- Binder names are lambda parameters: they can't reuse an enclosing local's name and can't be
  reassigned.
- Put `using` parameters in their own clause, after the ordinary ones:
  `f(A a)(using Monad<F> m, Codec<A> c)`.
- Ask for the weakest capability that works: `Sync<F>` to suspend I/O,
  `ApplicativeError<F, Throwable>` to raise a decoding error, `Monad<F>` only for
  comprehensions.
- Two `using` overloads must differ in their ordinary parameter types
  (`decode(byte[])` next to `decode(InputStream)` is fine). forj doesn't rank "more specific"
  overloads.
- **forj doesn't infer type arguments from method references:** hand-written fields spell
  them out, `Codec.<User, Long>field("id", User::id)`. `derives` generates this form.
- For `derives X`, the type class `X` must declare static `field(String, Function<T, F>)(using
  X<F>)` and `product(Function<Object[], T>, Field...)`; optionally `deriveProduct`/
  `deriveField`, for a generated straight-line encoder (forj-codec's `Codec` declares both).
  Every component needs an `X` given in scope.

### Reading errors

- `forj: ...` errors come from the plugin and point at your code. Read them first. javac
  often adds a second "cannot be applied to given types" error for the same call, which is
  noise once the `forj:` error is fixed.
- `forj: no given X<T> for m` means no instance of that type is in scope: import or declare
  one (a record without a `codec`, say), or the effect lacks that capability.
- `forj: cannot infer the type of using parameter ...` means `F` has no context to come from.
  Add a typed local, or explicit type arguments.
- `forj: ambiguous givens for ...` lists the clashing instances. Remove one, or pass one
  explicitly with `(using x)`.
- `plug-in not found: Forj` in a build with annotation processors means the plugin is missing
  from the processor path (forj's README, `ForjModule`).
- Editors (IntelliJ, VS Code, Metals) show red on forj syntax and can't navigate into it.
  Ignore them; build instead.
- To see what forj generated: `-Dforj.trace=true` on the compiler JVM prints every resolver
  decision.

## Part 2: this repo

### Layout

| Module | Artifact | What |
|---|---|---|
| `json` | `forj-json` | `Codec`, `Json`, the SPI (`forj.json.spi`) and forj's backend (`forj.json.backend`) |
| `json-tck` | `forj-json-tck` | `JsonBackendTck`: what any backend must do, including the JSONTestSuite cases |
| `json-jackson` | `forj-json-jackson` | `JacksonBackend`, the SPI on jackson-core (no databind) |
| `bench` | (not published) | JMH benchmarks against Jackson databind; `bin/bench` runs them |

forj itself comes from `~/.m2` (`forj:forj-core` and `forj:forj-plugin`, `0.1.0-SNAPSHOT`),
published from `../../forj` with `./mill __.publishM2Local`. Never depend on forj another
way.

### Anti-goals: never do these

- **No reflection, anywhere** in published modules: no `java.lang.reflect`, no
  `MethodHandles` lookups by name, no `ServiceLoader`, no annotation scanning, no runtime
  bytecode generation. Instances are givens, resolved at compile time. Reflection-based
  libraries (Jackson databind) appear only as reference rows in `bench`.
- **forj's own parser is the default.** Any other parser sits behind the SPI as an optional
  backend module (as `json-jackson` does); `json` never depends on one.
- **No `ThreadLocal`,** and no per-thread buffers: the server runs on virtual threads, so
  shared state is a bounded lock-free pool (`BufferPool`).
- **No leniency:** strict RFC 8259. Implementation-defined inputs are listed in the README and
  pinned by tests.

### Behaviour rules

- Every backend passes the TCK (`JsonBackendTck`). A behaviour change goes into the TCK first,
  so all backends get it.
- `json-jackson` must write byte-identical output to forj's backend and fail with the same
  path and offset (`SameBehaviourTest`); where Jackson differs, the adapter corrects it.
- Errors: malformed or unacceptable input is a `JsonException` with the value's path and byte
  offset (`in.invalidValue(...)` from a codec); a value that can't be written is an
  `IllegalArgumentException`; writer misuse is an `IllegalStateException`.
- **No default methods on an interface that declares a `given` reading an implementing
  class's static field** (`JsonBackend`): the interface is initialized with the class, and the
  given can read `null`.

### Docs

- **Human docs (`README.md`, `docs/guide.md`, `docs/benchmarks.md`) describe the current
  state only.** Reasoning, history, experiments and dead ends go in a `-for-agents` doc
  (`docs/benchmarks-for-agents.md`).
- Every example in `docs/guide.md` is a test in `GuideExamplesTest` (and
  `GuideJacksonExampleTest`). Change both together.
- Next steps live in `TODO.md`, in priority order. Plans and cross-repo status live in
  `../planning/`.

### Benchmarks

- Run on mains power, never on battery: `bin/bench [pattern] [JMH args]`. Use `-f 2 -i 8` for
  numbers worth recording. Results land in `out/bench-results/`.
- Every library in a row must write the same bytes and read each other's output
  (`BenchEquivalenceTest`); a new reference library gets added there first.
- Method names are `shape_operation_library`.
- `bench` compiles with `-proc:none`, and `bench.harness` runs JMH's bytecode generator,
  because annotation processors used to break forj syntax (fixed in forj `986f3fa`; switching
  back is in `TODO.md`).
- Record each run, and any experiment, in `docs/benchmarks-for-agents.md`; update the table in
  `docs/benchmarks.md` only with current numbers.

### Build and verify

- `bin/fetch-jdk`: run once. It fetches the pinned JDK 28 EA into `.jdk/`; everything uses
  preview features and `.jdk/preview`.
- `./mill __.test` runs all tests; `./mill json.test`, `./mill json-jackson.test` and
  `./mill bench.test` run one module's.
- After forj changes: `./mill __.publishM2Local` in `../../forj`, then rebuild here.
- javadoc runs on sources rewritten by forj's `ExportSources` (`forjExportedSources`,
  `docSources` and `javadocOptions` in `ForjModule`), because the `javadoc` tool can't load
  the plugin. Pages show declarations as plain Java callers see them: `F<A>` as
  `forj.Kind<F, A>`, and `using` instances as ordinary parameters. Write `{@link}`s the way
  the source declares the method (`{@link #encode(Object, OutputStream)}`); the export adds
  the using parameters. Check with
  `./mill json.docJar + json-tck.docJar + json-jackson.docJar` (the published
  modules; without the `+`s, Mill builds only the first and passes the rest to it as
  arguments).
- **The docs' look** comes from critium.github.io (`javadoc.css` and `palette.css`), through
  `docStyle` in `build.mill`: javadoc jars get a copy baked in; if the site can't be reached,
  javadoc's default look and a warning. `FORJ_DOCS_STYLE=<checkout of critium.github.io>`
  uses a local copy (read through `Task.env`, so it works with the Mill daemon).
- **Hosted docs:** `bin/publish-docs` builds the `gh-pages` tree in a temp dir and prints
  where: `README.md` as the front page (rendered by Pages' Jekyll, with a tab bar), `docs/`
  next to it, and the javadoc of `json` under `api/`, `json-jackson` under `jackson/` and
  `json-tck` under `tck/` (the README links there by absolute URL). `--push` force-pushes it,
  for https://critium.github.io/forj-codec/, which must deploy from the `gh-pages` branch, `/`
  (root). The critium.github.io hub links `/forj-codec/`, `api/`, `jackson/` and `tck/`:
  keep those paths stable. **Never push, or change Pages settings, without the user's OK.**

### Downstream users

These build against this repo's `~/.m2` snapshot (`./mill __.publishM2Local` here):

- **forj-http** (`../server`), module `http-json` (`forj:forj-http-json`), uses
  `Json.encode(A)` (with `Codec<A>` and `JsonBackend` givens) to `byte[]`,
  `Json.decode(byte[])`, `Json.decode(InputStream)`, `record X(...) derives Codec` and
  `Codec<List<A>>`. It puts `JsonException.getMessage()` in its 400 responses' problem
  detail and a test checks for the path (`$.name`): keep the path in the message.
- **forj-plugins** (`../plugins`): its users example declares `derives Codec`.

Before changing those APIs, or republishing with behaviour changes, tell the forj-http and
forj-plugins sessions. Downstream builds need `./mill clean` to see a new snapshot.

### Working rules

- **Never commit or push,** in any repo (`../planning/` too), and never run
  `bin/publish-docs --push`, even when a request seems to ask for it: the user commits and
  pushes to GitHub by hand. Leave changes uncommitted and say what's waiting.
- Don't modify forj from this repo's work. Record forj bugs and limits in
  `../../forj/TODO.md` for the user to pick up.
- **Match the surrounding code:** 4 spaces for now (2 spaces once Mill can format); javadoc on
  non-trivial methods, saying *why*.
- **Time-box hard debugging** and record findings in `TODO.md` rather than going deep
  silently.
- **Write a failing test first,** and confirm it fails for the reported reason.
