# Column-Store Data Storage System

A column-oriented database engine built in pure Java (no external libraries). Implements binary column storage, dictionary encoding, bitmap indexing, SQL parsing, and a full query engine with JOINs and aggregation.

## Quick Start

```powershell
# Compile
javac -encoding UTF-8 -d bin cdb/ddl/*.java cdb/util/*.java cdb/storage/*.java cdb/storage/persistence/*.java cdb/query/querytypes/*.java cdb/query/*.java cdb/api/*.java cdb/client/*.java

# Run
java -cp bin cdb.client.CLIClient
```

```
CDB > CREATE DATABASE company
CDB > USE DATABASE company
CDB [company] > CREATE TABLE emp (id INT PRIMARY_KEY, name STRING, dept STRING, salary DOUBLE)
CDB [company] > INSERT INTO emp VALUES (1, 'Alice', 'Engineering', 80000.0)
CDB [company] > SELECT * FROM emp WHERE salary > 50000 ORDER BY salary DESC
```

## Supported SQL

| Category | Commands |
|----------|----------|
| **DDL** | `CREATE TABLE`, `DROP TABLE` |
| **DML** | `INSERT INTO ... VALUES`, `UPDATE ... SET ... WHERE`, `DELETE FROM ... WHERE` |
| **SELECT** | Single-table, `INNER JOIN`, `LEFT JOIN` |
| **Projection** | `SELECT col1, col2` or `SELECT *` |
| **Filtering** | `WHERE` with `AND`/`OR`, `IN`, `NOT IN`, `BETWEEN`, `LIKE`, `IS NULL`, `NOT`, `= != > < >= <=` |
| **Aggregation** | `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` with `GROUP BY` |
| **Sort/Pagination** | `ORDER BY col ASC/DESC`, `LIMIT n`, `OFFSET n` |
| **Indexing** | Automatic bitmap index on low-cardinality columns; `SHOW BITMAP INDEX <table>` |
| **CLI** | `CREATE DATABASE`, `USE DATABASE`, `SHOW DATABASES`, `SHOW TABLES`, `SOURCE <file>` |

## Architecture

```
CLIClient → DatabaseAPI → QueryParser → QueryEngine → BinaryStorageEngine
                                           ↕              ↕
                                     SchemaManager   BitmapIndexManager
```
- **CLIClient**: REPL that routes SQL to `DatabaseAPI`, handles `SHOW`/`USE`/`SOURCE` commands
- **QueryParser**: Regex-based SQL parser producing typed `Query` objects
- **QueryEngine**: Dispatches query execution, applies WHERE filtering (bitmap or sequential scan), joins (nested loop), aggregation, sorting
- **BinaryStorageEngine**: Manages per-column `.bin` files with tombstone deletion
- **BitmapIndexManager**: In-memory bitmap indexes for low-cardinality categorical columns; fallback to sequential scan for high-cardinality or unindexed columns
- **SchemaManager**: Reads/writes both text (`.schema`) and binary (`.schema.bin`) schema files

### Storage Format

Each column is stored in its own `.bin` file:
```
[Type Tag (4B)] [Record Count (4B)] [Flag (1B) + Value (N B)]...
```
- **Numerical types** (INT, DOUBLE, etc.): raw fixed-width binary bytes
- **String types** (STRING, VARCHAR, etc.): dictionary-encoded — `.dict` file maps strings to 4-byte IDs stored in `.bin`
- **Deletion**: Tombstone flag (`0xFF`) marks rows as deleted without physical removal
- **Schema**: Dual persistence — text `.schema` for readability, binary `.schema.bin` for fast startup

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Column-oriented storage** | Only reads requested columns — ideal for analytical queries |
| **Binary encoding** | Fixed-width records enable O(1) random access; no parsing overhead |
| **Dictionary encoding for strings** | Saves space for repeated values; keeps columns fixed-width |
| **Bitmap indexing** | Fast bitwise AND/OR for multi-condition queries on low-cardinality columns |
| **Tombstone deletion** | O(1) per-row delete; no file rewrite needed |
| **Nested loop join** | Simple, correct for any dataset size; hash join optimization possible |
| **Strategy Pattern** (StorageEngine) | Swap storage backends without changing query logic |
| **Command Pattern** (Query types) | Each SQL statement becomes an object dispatched by the engine |
| **DNF WHERE evaluation** | AND of OR groups supports arbitrary boolean logic |

## Design Patterns

- **Strategy**: `StorageEngine` interface with `BinaryStorageEngine` implementation
- **Template Method**: `BasePersister` defines header/delete/find logic; subclasses implement encoding
- **Command**: Each `Query` subtype encapsulates parsed SQL data
- **Builder**: `SelectQuery.Builder` constructs complex queries with JOIN/aggregation/ORDER BY

## Testing

```powershell
# Stress test (8 edge-case tests)
java -cp bin cdb.util.StressTester

# Integration test (CRUD with string columns)
java -cp bin cdb.util.TestCategoricalData
```
