# jdbc-capture-agent

A **passive** Java agent that observes JDBC activity — it never blocks, alters, mocks or replays a
query. It is attached to any application with:

```
java -javaagent:jdbc-capture-agent.jar=out=./capture,rows=sample:100 -jar your-app.jar
```

The **same jar** runs on **Java 8 and Java 25** (compiled to Java 8 bytecode). It hooks the return of
`java.sql.Driver#connect` and `javax.sql.DataSource#getConnection` (all non-JDK implementations),
wraps the returned `Connection` in a dynamic `java.lang.reflect.Proxy`, and transitively wraps
`Statement` / `PreparedStatement` / `CallableStatement` / `ResultSet`. It writes a **JSONL trace**
(one JSON object per line) plus a `summary.json`.

Its purpose here: capture the JDBC activity of a legacy batch and its Spring Batch rewrite, replay
both on identical databases, and diff the final state.

---

## 1. Build

Prerequisites: JDK 25 (Temurin) for the Maven build, Docker (Rancher Desktop) for the samples/tests.

```
make build          # builds agent, samples, tests (each module via mvn -f <module>/pom.xml)
```

The agent jar lands at `jdbc-capture-agent/target/jdbc-capture-agent.jar`. It is **self-contained**:
Byte Buddy and JSqlParser are shaded and relocated under `fr.capture.shaded.*`, so the agent cannot
clash with the application's own copies of those libraries.

## 2. Options

Passed as a single string after `=`, options separated by `,`:

| Option | Default | Meaning |
|--------|---------|---------|
| `out=<dir>` | `./capture` | Output directory (`trace.jsonl`, `summary.json`, `agent-errors.log`). |
| `rows=none\|sample:N\|all` | `sample:100` | How many row **values** to capture per `ResultSet`. Metadata + row count are always captured. |
| `mask=<c1\|c2\|...>` | *(none)* | Column names whose values are replaced by a **stable hash** (same input → same output). List separated by `\|`. |
| `clock=<ISO-8601>` | *(none)* | Freezes the timestamp of **agent events** to the given instant, advancing by real elapsed time. See the limitation below. |
| `exclude=<regex>` | *(none)* | Case-insensitive regex; matching SQL is not captured (e.g. Spring Batch `BATCH_*`). Single token, **must not contain a comma**. |
| `queue=<N>` | `65536` | Bounded async queue capacity. When full, events are dropped and counted (`dropped`), never blocking the app. |

Example:

```
-javaagent:jdbc-capture-agent.jar=out=./capture/legacy,rows=all,mask=email|ssn,clock=2020-01-01T00:00:00Z,exclude=BATCH_
```

## 3. Output files

- `trace.jsonl` — one event per line (see §4).
- `summary.json` — end-of-run rollup (see §5).
- `agent-errors.log` — internal agent errors only. Its presence does **not** mean the application
  failed; the agent is fail-safe and never propagates its own errors.

---

## 4. Trace format — field by field

Every event carries this **common envelope**:

| Field | Type | Meaning |
|-------|------|---------|
| `event` | string | Event type (see below). |
| `ts` | string | ISO-8601 UTC timestamp (subject to `clock=`, agent-side only). |
| `run` | string | Random UUID identifying this JVM run. |
| `thread` | string | Application thread name. |
| `conn` | number | Logical connection id (`0` for run-level events). |
| `tx` | number | Logical transaction id on that connection, bumped on each commit/rollback. |

### `run_start`
| Field | Meaning |
|-------|---------|
| `phase` | `premain` or `agentmain`. |
| `started` | Run start timestamp (same clock rules). |
| `java_version`, `java_vendor` | From system properties. |
| `command` | `sun.java.command` — main class + args, or the `-jar` path. Closest available to the real `argv` (see limitations). |
| `options` | The parsed agent options (as applied). |
| `jvm_args` | array — JVM input arguments (`RuntimeMXBean`). |
| `sys_props` | object — selected properties (`os.*`, `java.vm.*`, `user.timezone`, `file.encoding`, ...). |

### `conn_open`
| Field | Meaning |
|-------|---------|
| `source` | `connect` (from `Driver#connect`) or `getConnection` (from `DataSource#getConnection`). |

### `conn_close`
Envelope only; `tx` is the connection's final logical tx. A close with an open manual transaction
also emits a `tx` event of kind `rollback_implicit` first.

### `query`
| Field | Meaning |
|-------|---------|
| `sql` | The SQL text (for prepared statements, the template with `?`). |
| `kind` | `select` \| `insert` \| `update` \| `delete` \| `call` \| `ddl` \| `other`. |
| `duration_ms` | Wall-clock duration of the `execute*` call. |
| `params` | *(optional)* array of bound params: `{idx\|name, type, value}` (`type` = the `setXxx` suffix, e.g. `INT`, `STRING`, `NULL`). |
| `affected` | *(optional)* update count when known. |
| `batch` | *(optional)* `true` for `executeBatch`/`executeLargeBatch`. |
| `batch_size` | *(batch)* number of batched entries. |
| `update_counts` | *(batch)* array of per-entry update counts. |
| `params_batch` | *(batch, prepared)* array of parameter sets (one per batched entry). |
| `sql_batch` | *(batch, plain Statement)* array of batched SQL strings. |

Values follow the **value encoding** rules below.

### `rows_read` (and `generated_keys`)
| Field | Meaning |
|-------|---------|
| `sql` | The query that produced the result set. |
| `rows` | Total rows the application iterated via `next()`. |
| `values_mode` | `none` \| `sample` \| `all` (`generated_keys` is always `all`). |
| `values_captured` | Number of rows whose values were recorded. |
| `columns` | array of `{name, type}` (SQL type name). |
| `values` | array of rows; each row is an array of column values (value encoding below). |
| `pk_table` | *(optional)* the single read table, when its PK is present in the result set. |
| `pk_columns` | *(optional)* PK column names. |
| `primary_keys` | *(optional)* captured PK tuples (`col=value,...`), one per iterated row. |

`generated_keys` has the same shape with `event = "generated_keys"`.

### `tx`
| Field | Meaning |
|-------|---------|
| `kind` | `commit` \| `rollback` \| `rollback_implicit` (close with uncommitted work) \| `autocommit`. |
| `value` | *(autocommit only)* the new boolean auto-commit state. |

Writes are attributed to their `(conn, tx)`; a consumer can keep only writes whose `tx` later
appears in a `commit` event — i.e. **rolled-back writes are identifiable as not validated**.

### `touched`
| Field | Meaning |
|-------|---------|
| `kind` | Same classification as the query. |
| `read` | array of table names read (best-effort SQL parse, JSqlParser). |
| `write` | array of table names written. |
| `parsed` | `true` if the SQL parsed; when `false`, `unparsed: true` and `sql_raw` carries the original SQL. |

### `run_end`
| Field | Meaning |
|-------|---------|
| `queries` | Total captured `execute*` calls. |
| `rows_read` | Total rows iterated. |
| `writes` | Total write statements (insert/update/delete/ddl/call). |
| `excluded` | Statements skipped by `exclude=`. |
| `dropped` | Events dropped because the async queue was full (**0 = no loss**). |
| `trace_written` | Lines actually written to `trace.jsonl`. |

### Value encoding (params & row values)
- `null` → `null`.
- LOB (`byte[]`, `Blob`, `Clob`) → **content is never stored**; instead `{"lob":"bytes|blob|clob","len":N,"sha256":"..."}`.
- Masked column → a stable `masked:<hash>` token.
- Everything else → the value stringified and quoted (its type is carried separately by
  `params[].type` or `columns[].type`). Strings longer than 8192 chars are truncated with a marker.

---

## 5. `summary.json`

```jsonc
{
  "run": { "run": "...", "java_version": "...", "started": "...", "ended": "...",
           "queries": N, "rows_read": N, "writes": N, "excluded": N, "dropped": N },
  "tables_read":    { "person": 3 },        // table -> rows read
  "tables_written": { "person": 3 },        // table -> rows affected
  "primary_keys_read": { "person": ["id=1", "id=2", "id=3"] },
  "distinct_queries": [ "insert into person (id, name) values (?, ?)" ]  // normalised, params -> ?
}
```

---

## 6. Samples, capture and tests

Two functionally-equivalent samples writing to the **same PostgreSQL**, run in Docker with the
**same agent jar**:

- `sample-legacy` — raw JDBC (`DriverManager`, prepared statements, batch, a manual transaction with
  a **voluntary rollback**), run under `eclipse-temurin:8`.
- `sample-modern` — Spring Boot 3 / HikariCP, same functional work with **different SQL**, run under
  `eclipse-temurin:25`.

```
make pg-up                    # start PostgreSQL (docker compose)
make capture-legacy           # -> ./capture/legacy/{trace.jsonl,summary.json,...}
make capture-modern           # -> ./capture/modern/{...}
make capture-legacy SCENARIO=empty
make capture-legacy OPTS="clock=2020-01-01T00:00:00Z"
make overhead                 # measure agent runtime cost (with vs without)
make test                     # end-to-end integration tests
make pg-down
```

The integration tests (`integration-tests/`) assert: no double-counting on HikariCP; rollback writes
are attributable and uncommitted; frozen-clock agent timestamps align on both JVMs; the "empty" run
produces a trace without data writes; the application boots despite an invalid agent option (error
logged); and the measured overhead is reported. Any missing prerequisite (unbuilt jar, no Docker)
**skips** the corresponding test rather than failing or faking it.

> **Docker note (this machine):** Rancher Desktop refuses host bind-mounts of the repo
> (`operation not permitted`). The scripts therefore inject the jars into the containers via a
> **tar-pipe** over stdin and read the produced trace back over stdout. The sample containers join
> the compose network `capnet` and reach PostgreSQL at host `postgres`.

---

## 7. Known limitations

- **Frozen clock affects agent timestamps only.** `clock=` makes the timestamps *the agent writes*
  deterministic. It does **not** change the clock the application observes
  (`System.currentTimeMillis`, `new Date()`, `LocalDateTime.now()`, ...). Doing so would require
  instrumenting bootstrap JDK classes (`java.lang.System`, `java.time.*`), which is unsafe/unsupported
  and could destabilise the JVM. This is documented rather than worked around silently.
- **Touched-table analysis is best effort.** Parsing uses JSqlParser (Java-8-compatible, relocated);
  on a parse failure the event is marked `unparsed` and the raw SQL is kept.
- **Primary-key capture is best effort.** PK columns come from `DatabaseMetaData#getPrimaryKeys`
  (cached per table). PKs are recorded only when the query reads a single table and all PK columns
  are present in the result set.
- **`command` is not the true `argv`.** The real `main` arguments are not available in `premain`;
  `sun.java.command` (main class + args, or `-jar` path) is used as the closest approximation.
- **Bound parameters are not masked** (no reliable parameter→column mapping). Masking applies to
  result-set column values.
- **Overhead numbers are workload-dependent** and include Docker timing noise; treat them as
  indicative.
# java-jdbc-auditer
