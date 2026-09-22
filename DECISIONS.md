# Technical decisions & honest caveats

## Instrumentation

- **Byte Buddy `1.18.14`** (main artifact, *not* the `-jdk5` classifier). Its `ClassFileVersion`
  enum recognises class formats up to **`JAVA_V27`**, so Java 25's format is officially covered
  (verified by inspecting the enum in the resolved jar; confirmed at runtime by the spike running
  green on Temurin 25.0.4). We only ever instrument application/driver/pool classes (Java 8-era
  bytecode), never JDK classes.
- **Interception point:** advice on the *return* of `java.sql.Driver#connect` and
  `javax.sql.DataSource#getConnection`, matched by `hasSuperType(...)` on non-JDK implementations
  (`java.`, `javax.`, `jdk.`, `sun.`, `com.sun.` are ignored). The advice body is a single guarded
  static call (`Hooks.onConnection`) with `suppress = Throwable.class`.
- **Wrapping via `java.lang.reflect.Proxy`**, not subclassing: the returned `Connection` (and then
  its `Statement`/`ResultSet`) is a JDK dynamic proxy implementing every interface of the real
  object, so vendor-specific casts still work. **The JDK is never instrumented.**
- **Anti double-counting (HikariCP delegates to the driver):** before wrapping we check
  (1) `Proxy.isProxyClass` + our own `InvocationHandler` → already ours, and
  (2) `isWrapperFor(Connection)`/`unwrap(Connection)` resolving to one of our proxies → a pool
  wrapper already sitting on top of our physical proxy. Either way we return as-is. A per-thread
  re-entrancy guard additionally (a) prevents the agent's own JDBC calls (PK metadata lookups) from
  being captured, and (b) stops nested `connect` inside `getConnection` from double-wrapping.

## Packaging

- **`maven-shade-plugin` with relocation to `fr.capture.shaded.*`** (Byte Buddy + JSqlParser), so
  the agent cannot conflict with the application's own dependencies.
- **`--release 8`** for the agent (and samples' legacy code): one jar, identical on JVM 8 and 25.
- **`META-INF/versions/**` excluded from the shaded jar.** Byte Buddy is a multi-release jar carrying
  Java-24 (major 68) classes that the shade plugin's ASM cannot parse. Those MRJAR classes are a
  JDK-classfile-API bridge (an optimisation); Byte Buddy's own bundled ASM (up to `JAVA_V27`) covers
  our needs, since we only instrument low-bytecode app/driver classes. Dropping them lets the build
  succeed and changes nothing at runtime for this use case.
- **Manifest:** `Premain-Class` + `Agent-Class` = `fr.capture.agent.AgentMain`, with
  `Can-Redefine-Classes` / `Can-Retransform-Classes` = true.

## Runtime safety & I/O

- **Absolute fail-safe:** every hook/proxy path is wrapped in `try/catch(Throwable)`; internal errors
  go to `agent-errors.log`; if the agent cannot install, the application starts normally without it.
- **Asynchronous, bounded trace:** an `ArrayBlockingQueue` drained by one daemon thread; producers do
  a non-blocking `offer` (full queue → increment `dropped`, never block). A shutdown hook flushes and
  reports final counters + `dropped` in `run_end` and `summary.json`.

## Validation harness

- **tar-pipe instead of bind-mounts.** On this machine Rancher Desktop rejects host bind-mounts of
  the repo (`operation not permitted`). The scripts inject the agent + app jars into the containers
  over stdin and read the produced trace back over stdout — the exact same agent jar feeds both the
  JVM 8 and JVM 25 containers. PostgreSQL runs via docker compose on a named network `capnet`; the
  sample containers attach to it and resolve the DB at host `postgres`.

---

## Points NOT guaranteed / best effort

- **Application clock is not frozen.** `clock=` only affects the timestamps the agent writes. The
  application's own time sources are left untouched **by design** (instrumenting bootstrap
  `java.lang.System` / `java.time.*` is unsafe and out of scope). This is the honest choice the brief
  asked for: state the limitation rather than hack around it. Determinism of the two runs must be
  achieved at the application/database level, not by the agent lying to the app about time.
- **Touched-table parsing is best effort** (JSqlParser); parse failures are marked `unparsed` with
  the raw SQL preserved.
- **Primary-key capture is best effort:** only when a query reads a single table whose PK columns are
  all present in the result set; PK names via cached `DatabaseMetaData#getPrimaryKeys`.
- **`argv` of `main` is not available in `premain`;** `sun.java.command` is used as the closest
  approximation and documented as such.
- **Bound parameters are not masked** (no reliable parameter→column mapping); masking applies to
  result-set column values only.
- **`exclude` regex cannot contain a comma** (comma is the top-level option separator).

---

## To confirm (owned by sibling work streams, reported separately)

- Full agent build green after the complete feature set (params/batch/generated-keys/touched/PK/
  summary/clock/mask/exclude) and **spike re-validated on JVM 8 + 25**.
- `sample-legacy` and `sample-modern` implemented (modern must print `EXPECTED_QUERIES=<n>` on
  stdout for the exact Hikari no-double-count assertion).
- `integration-tests` executed end-to-end against both samples (currently: compiles; each test
  skips cleanly when its prerequisites are absent).
- Measured overhead figure from `make overhead` on real samples.
