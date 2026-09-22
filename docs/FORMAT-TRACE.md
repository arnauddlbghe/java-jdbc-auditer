# Trace format — field by field

The agent writes `trace.jsonl` (one JSON object per line) and a `summary.json`. This is the
exhaustive reference; see the main [README](../README.md) for the quick start.

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

## `summary.json`

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
