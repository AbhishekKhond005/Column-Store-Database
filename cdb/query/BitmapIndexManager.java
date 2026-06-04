package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.SchemaManager;
import cdb.ddl.TableSchema;
import cdb.query.querytypes.WhereClause;
import cdb.query.querytypes.WhereCondition;
import cdb.storage.StorageEngine;

import java.io.IOException;
import java.util.*;

/**
 * BitmapIndexManager — builds and maintains in-memory bitmap indexes
 * using a strict TWO-GATE policy:
 *
 * GATE 1 — TYPE GATE (ColumnSchema.isCategorical()):
 * Numeric types (INT, LONG, DOUBLE, FLOAT, etc.) are UNCONDITIONALLY excluded.
 * No cardinality check is ever run for numeric columns.
 *
 * GATE 2 — CARDINALITY GATE:
 * Even for categorical types (VARCHAR, STRING, BOOLEAN...), the index is
 * skipped or dropped if the number of distinct values is too high relative
 * to the row count. This prevents high-cardinality string columns (e.g., Name,
 * Email) from creating one BitSet vector per row — the same explosion problem
 * that affects unindexed numeric columns.
 *
 * Thresholds (tunable via constants below):
 * MAX_DISTINCT_VALUES — absolute cap on distinct values regardless of row count
 * MAX_CARDINALITY_RATIO — max ratio of distinct/total rows (above = too sparse)
 * MIN_ROWS_FOR_RATIO — ratio check only kicks in once the table is large enough
 */
public class BitmapIndexManager {
    private final StorageEngine storageEngine;
    private final SchemaManager schemaManager;

    // ── Cardinality gate thresholds ──────────────────────────────────────────
    /** Absolute maximum number of distinct values allowed in a bitmap index. */
    private static final int MAX_DISTINCT_VALUES = 100;
    /**
     * If the table has at least MIN_ROWS_FOR_RATIO rows, also reject the index
     * when distinctValues / totalRows > MAX_CARDINALITY_RATIO.
     * Example: 60 distinct names in 100 rows → ratio 0.60 > 0.50 → skip.
     */
    private static final double MAX_CARDINALITY_RATIO = 0.50;
    private static final int MIN_ROWS_FOR_RATIO = 10;

    // Table -> Column -> Value -> BitSet
    private final Map<String, Map<String, Map<String, BitSet>>> indexes = new HashMap<>();

    // Table -> Number of active (non-deleted) rows
    private final Map<String, Integer> tableRowCounts = new HashMap<>();

    /**
     * Columns explicitly evicted by Gate 2 (cardinality exceeded).
     * These must NEVER be re-added to the index, even if cardinality temporarily
     * drops due to deletes. Rebuild via buildIndex() is the only way to
     * re-evaluate.
     * Table -> Set<columnName>
     */
    private final Map<String, Set<String>> evictedColumns = new HashMap<>();

    public BitmapIndexManager(StorageEngine storageEngine, SchemaManager schemaManager) {
        this.storageEngine = storageEngine;
        this.schemaManager = schemaManager;
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    public void initializeAll() throws IOException {
        for (String tableName : schemaManager.listTables()) {
            buildIndex(tableName);
        }
    }

    /**
     * Builds (or rebuilds) the bitmap index for the given table.
     *
     * TWO-GATE POLICY applied per column:
     * Gate 1: col.isCategorical() — numerics are unconditionally excluded.
     * Gate 2: cardinality guard — categorical columns with too many distinct
     * values relative to row count are also excluded.
     */
    public void buildIndex(String tableName) throws IOException {
        TableSchema schema = schemaManager.getTable(tableName);
        if (schema == null)
            return;

        Map<String, Map<String, BitSet>> tableIndexes = new HashMap<>();
        int rowCount = 0;

        for (ColumnSchema col : schema.getColumns()) {

            // ── Gate 1: type gate ────────────────────────────────────────────
            if (!col.isCategorical()) {
                continue;
            }

            String colName = col.getName();
            List<String> values = storageEngine.readColumn(tableName, colName);
            rowCount = Math.max(rowCount, values.size());

            Set<String> distinctVals = new HashSet<>(values);
            int distinctCount = distinctVals.size();
            int totalRows = values.size();

            // ── Gate 2: cardinality guard ────────────────────────────────────
            if (exceedsCardinalityThreshold(distinctCount, totalRows)) {
                // Record the eviction so insertRow never recreates this index.
                evictedColumns.computeIfAbsent(tableName, k -> new HashSet<>()).add(colName);
                continue;
            }

            // Both gates passed — build the index.
            Map<String, BitSet> colIndex = new HashMap<>();
            for (int i = 0; i < values.size(); i++) {
                colIndex.computeIfAbsent(values.get(i), k -> new BitSet()).set(i);
            }
            tableIndexes.put(colName, colIndex);
        }

        // If no categorical columns, still record the row count via any column
        if (rowCount == 0 && !schema.getColumns().isEmpty()) {
            List<String> sample = storageEngine.readColumn(tableName,
                    schema.getColumns().get(0).getName());
            rowCount = sample.size();
        }

        // Also clear any stale evictions for this table so a rebuild
        // re-evaluates cardinality from scratch.
        evictedColumns.remove(tableName);
        indexes.put(tableName, tableIndexes);
        tableRowCounts.put(tableName, rowCount);
    }

    // -------------------------------------------------------------------------
    // DML maintenance
    // -------------------------------------------------------------------------

    /**
     * Called after every INSERT. Updates bitmap indexes for columns that passed
     * both gates. If inserting a new distinct value pushes a column over the
     * cardinality threshold, the index for that column is dropped so it won't
     * waste memory going forward. Subsequent queries on that column will use
     * the sequential scan fallback.
     */
    public void insertRow(String tableName, TableSchema schema, List<String> values) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.computeIfAbsent(tableName, k -> new HashMap<>());

        int newIdx = tableRowCounts.getOrDefault(tableName, 0);
        int totalRows = newIdx + 1; // after this insert

        Set<String> evicted = evictedColumns.getOrDefault(tableName, Collections.emptySet());

        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnSchema col = schema.getColumns().get(i);

            // Gate 1: skip non-categorical columns — they are never indexed.
            if (!col.isCategorical())
                continue;

            String colName = col.getName();

            // If Gate 2 previously evicted this column, never re-add it.
            if (evicted.contains(colName))
                continue;

            String val = values.get(i);
            // computeIfAbsent handles both:
            // (a) first insert into a newly created table (no prior buildIndex entry)
            // (b) subsequent inserts into an already-indexed column
            Map<String, BitSet> colIndex = tableIndexes.computeIfAbsent(colName, k -> new HashMap<>());
            colIndex.computeIfAbsent(val, k -> new BitSet()).set(newIdx);

            // Gate 2 (post-insert): if adding this value pushed us over the threshold,
            // evict the index for this column now to free memory.
            if (exceedsCardinalityThreshold(colIndex.size(), totalRows)) {
                tableIndexes.remove(colName);
                evictedColumns.computeIfAbsent(tableName, k -> new HashSet<>()).add(colName);
            }
        }

        tableRowCounts.put(tableName, totalRows);
    }

    /**
     * Called after every UPDATE on a single cell.
     * Moves the bit for rowIndex from oldValue's bitmap to newValue's bitmap.
     * No-op for non-categorical columns (they have no index entry).
     */
    public void updateValue(String tableName, String colName, int rowIndex,
            String oldValue, String newValue) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null)
            return;

        Map<String, BitSet> colIndex = tableIndexes.get(colName);
        if (colIndex == null)
            return; // column is not categorical — nothing to update

        // Remove old association
        BitSet oldBits = colIndex.get(oldValue);
        if (oldBits != null) {
            oldBits.clear(rowIndex);
        }

        // Add new association
        colIndex.computeIfAbsent(newValue, k -> new BitSet()).set(rowIndex);
    }

    // -------------------------------------------------------------------------
    // Query-time filtering
    // -------------------------------------------------------------------------

    public List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema,
            WhereClause whereClause) {
        if (whereClause == null || whereClause.isEmpty()) {
            return allRows(tableName);
        }

        int totalRows = tableRowCounts.getOrDefault(tableName, 0);
        BitSet finalResult = new BitSet(totalRows);

        for (List<WhereCondition> andGroup : whereClause.getOrGroups()) {
            BitSet groupResult = new BitSet(totalRows);
            groupResult.set(0, totalRows); // Start with all bits TRUE for AND logic

            for (WhereCondition cond : andGroup) {
                BitSet condBits = evaluateConditionWithIndex(tableName, cond, totalRows);
                if (condBits == null) {
                    // This condition's column has no bitmap index (numeric or evicted).
                    // Signal a full sequential scan fallback.
                    return null;
                }
                groupResult.and(condBits);
            }
            finalResult.or(groupResult);
        }

        List<Integer> result = toList(finalResult);

        return result;
    }

    /**
     * Legacy single-condition API kept for backward compatibility.
     */
    public List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema,
            String filterCol, String filterOp, String filterVal) {
        if (filterCol == null)
            return allRows(tableName);

        int totalRows = tableRowCounts.getOrDefault(tableName, 0);
        BitSet bits = evaluateConditionWithIndex(tableName,
                new WhereCondition(filterCol, filterOp, filterVal), totalRows);
        if (bits == null)
            return null;

        List<Integer> result = toList(bits);
        return result;
    }

    // -------------------------------------------------------------------------
    // Index diagnostics
    // -------------------------------------------------------------------------

    public String dumpIndex(String tableName) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null || tableIndexes.isEmpty()) {
            return "No bitmap index found for table '" + tableName + "'. "
                    + "(No categorical columns or table does not exist.)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Bitmap Index for table '").append(tableName).append("':\n");
        int maxRows = tableRowCounts.getOrDefault(tableName, 0);

        for (Map.Entry<String, Map<String, BitSet>> colEntry : tableIndexes.entrySet()) {
            String colName = colEntry.getKey();
            sb.append("  Column: ").append(colName).append("\n");
            for (Map.Entry<String, BitSet> valEntry : colEntry.getValue().entrySet()) {
                String val = valEntry.getKey();
                BitSet bs = valEntry.getValue();

                StringBuilder bits = new StringBuilder();
                for (int i = 0; i < maxRows; i++) {
                    bits.append(bs.get(i) ? "1" : "0");
                }
                sb.append("    Value '").append(val).append("': ")
                        .append(bits).append("  ").append(bs).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Evaluates a single WHERE condition using the bitmap index.
     *
     * Returns null if the column is not indexed (i.e., not categorical),
     * which tells the caller to fall back to a sequential scan.
     *
     * For categorical columns only '=' and '!=' operators make sense;
     * range operators (>, <, >=, <=) always return an empty BitSet for
     * categorical data since string ordering is not supported via bitmap.
     */
    private BitSet evaluateConditionWithIndex(String tableName, WhereCondition cond, int totalRows) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null || !tableIndexes.containsKey(cond.getColumn())) {
            return null; // column not indexed → caller must use sequential scan
        }

        Map<String, BitSet> colIndex = tableIndexes.get(cond.getColumn());
        String filterOp = cond.getOp();
        String filterVal = cond.getValue();
        BitSet resultBits = new BitSet();

        switch (filterOp) {
            case "=" -> {
                BitSet exactMatch = colIndex.get(filterVal);
                if (exactMatch != null)
                    resultBits.or(exactMatch);
            }
            case "!=" -> {
                for (int i = 0; i < totalRows; i++)
                    resultBits.set(i);
                BitSet exactMatch = colIndex.get(filterVal);
                if (exactMatch != null)
                    resultBits.andNot(exactMatch);
            }
            default -> {
                return null;
            }
        }

        return resultBits;
    }

    private List<Integer> allRows(String tableName) {
        int count = tableRowCounts.getOrDefault(tableName, 0);
        List<Integer> all = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            all.add(i);
        return all;
    }

    private List<Integer> toList(BitSet bs) {
        List<Integer> list = new ArrayList<>();
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
            list.add(i);
        }
        return list;
    }

    /**
     * Gate 2 — cardinality check.
     *
     * Returns true (= should NOT build/keep the index) if either:
     * a) distinctCount > MAX_DISTINCT_VALUES (absolute cap, always applied)
     * b) The table has >= MIN_ROWS_FOR_RATIO rows AND
     * (distinctCount / totalRows) > MAX_CARDINALITY_RATIO
     *
     * Example outcomes with defaults (MAX_DISTINCT=100, RATIO=0.50, MIN_ROWS=20):
     * Department: 3 distinct / 10,000 rows → ratio 0.0003 → INDEX BUILT
     * Status: 2 distinct / 5,000 rows → ratio 0.0004 → INDEX BUILT
     * Name: 9,800 distinct / 10,000 rows → exceeds absolute cap 100 → SKIPPED
     * Tag: 60 distinct / 80 rows → ratio 0.75 > 0.50 → SKIPPED
     * Tag: 60 distinct / 15 rows → ratio check skipped (< MIN_ROWS) → INDEX BUILT
     */
    private boolean exceedsCardinalityThreshold(int distinctCount, int totalRows) {
        // Absolute cap — always enforced
        if (distinctCount > MAX_DISTINCT_VALUES)
            return true;
        // Ratio cap — only enforced once table is large enough to be meaningful
        if (totalRows >= MIN_ROWS_FOR_RATIO) {
            double ratio = (double) distinctCount / totalRows;
            if (ratio > MAX_CARDINALITY_RATIO)
                return true;
        }
        return false;
    }
}
