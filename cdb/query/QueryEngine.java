package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.SchemaManager;
import cdb.ddl.TableSchema;
import cdb.query.querytypes.*;
import cdb.storage.StorageEngine;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class QueryEngine {
    private SchemaManager schemaManager;
    private StorageEngine storageEngine;
    private BitmapIndexManager indexManager;

    public QueryEngine(SchemaManager schemaManager, StorageEngine storageEngine, BitmapIndexManager indexManager) {
        this.schemaManager = schemaManager;
        this.storageEngine = storageEngine;
        this.indexManager  = indexManager;
    }

    public String execute(Query query) {
        try {
            if (query instanceof CreateTableQuery) {
                return executeCreate((CreateTableQuery) query);
            } else if (query instanceof InsertQuery) {
                return executeInsert((InsertQuery) query);
            } else if (query instanceof SelectQuery) {
                return executeSelect((SelectQuery) query);
            } else if (query instanceof UpdateQuery) {
                return executeUpdate((UpdateQuery) query);
            } else if (query instanceof DeleteQuery) {
                return executeDelete((DeleteQuery) query);
            } else if (query instanceof DropTableQuery) {
                return executeDropTable((DropTableQuery) query);
            } else if (query instanceof ShowTablesQuery) {
                return executeShowTables();
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        return "Unknown query type";
    }

    private String executeCreate(CreateTableQuery q) throws IOException {
        schemaManager.createTable(q.getSchema());
        storageEngine.createTable(q.getSchema());
        return "Table " + q.getSchema().getTableName() + " created successfully.";
    }

    private String executeInsert(InsertQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());
        if (schema.getColumns().size() != q.getValues().size()) {
            throw new IllegalArgumentException("Column count doesn't match value count.");
        }

        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnSchema col = schema.getColumns().get(i);
            String val = q.getValues().get(i);

            if (col.hasConstraint("NOT_NULL") && (val == null || val.isEmpty() || val.equalsIgnoreCase("null"))) {
                throw new IllegalArgumentException("Column " + col.getName() + " cannot be null.");
            }

            validateType(val, col.getType());

            if (col.hasConstraint("PRIMARY_KEY") || col.hasConstraint("UNIQUE")) {
                List<String> existing = storageEngine.readColumn(q.getTableName(), col.getName());
                if (existing.contains(val)) {
                    throw new IllegalArgumentException(
                            "Constraint violation on " + col.getName() + " for value " + val);
                }
            }
        }

        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnSchema col = schema.getColumns().get(i);
            storageEngine.appendValue(q.getTableName(), col.getName(), q.getValues().get(i));
        }

        indexManager.insertRow(q.getTableName(), schema, q.getValues());
        return "1 row inserted.";
    }

    // -------------------------------------------------------------------------
    // SELECT
    // -------------------------------------------------------------------------
    private String executeSelect(SelectQuery q) throws IOException {
        if (q.hasJoin()) {
            return executeSelectJoin(q);
        }
        return executeSelectSingle(q);
    }

    private String executeSelectSingle(SelectQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        List<String> requestedCols = resolveColumns(q.getColumns(), schema);
        for (String colName : requestedCols) {
            if (schema.getColumn(colName) == null)
                throw new IllegalArgumentException("Column not found: " + colName);
        }
        validateWhereClause(q.getWhereClause(), schema);

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getWhereClause());

        // Aggregation without GROUP BY
        if (q.hasAggregation() && !q.hasGroupBy()) {
            return computeAggregation(q, q.getTableName(), schema, validRowIndexes);
        }

        // Aggregation with GROUP BY
        if (q.hasAggregation() && q.hasGroupBy()) {
            return computeGroupByAggregation(q, q.getTableName(), schema, validRowIndexes);
        }

        // Read requested columns
        List<List<String>> columnsData = new ArrayList<>();
        for (String colName : requestedCols) {
            columnsData.add(storageEngine.readColumn(q.getTableName(), colName));
        }

        // ORDER BY
        if (q.hasOrderBy()) {
            validRowIndexes = sortRowIndexes(validRowIndexes, columnsData, requestedCols,
                    q.getOrderByColumn(), q.isOrderByAsc());
        }

        // LIMIT / OFFSET
        if (q.hasLimit()) {
            int from = Math.min(q.getOffset(), validRowIndexes.size());
            int to = Math.min(from + q.getLimit(), validRowIndexes.size());
            validRowIndexes = validRowIndexes.subList(from, to);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", requestedCols)).append("\n");
        sb.append("-".repeat(Math.max(1, requestedCols.size() * 15))).append("\n");

        for (int idx : validRowIndexes) {
            List<String> rowValues = new ArrayList<>();
            for (List<String> colData : columnsData) {
                rowValues.add(idx < colData.size() ? colData.get(idx) : "null");
            }
            sb.append(String.join("\t", rowValues)).append("\n");
        }

        return sb.toString().trim() + "\n(" + validRowIndexes.size() + " rows)";
    }

    // -------------------------------------------------------------------------
    // JOIN SELECT
    // -------------------------------------------------------------------------
    private String executeSelectJoin(SelectQuery q) throws IOException {
        String table1 = q.getTableName();
        String table2 = q.getJoinTable();

        TableSchema schema1 = schemaManager.getTable(table1);
        TableSchema schema2 = schemaManager.getTable(table2);
        if (schema1 == null) throw new IllegalArgumentException("Table not found: " + table1);
        if (schema2 == null) throw new IllegalArgumentException("Table not found: " + table2);

        boolean isLeftJoin = "LEFT".equalsIgnoreCase(q.getJoinType());

        // Determine result columns
        List<String> requestedCols;
        if (q.getColumns().size() == 1 && q.getColumns().get(0).equals("*")) {
            requestedCols = new ArrayList<>();
            for (ColumnSchema c : schema1.getColumns())
                requestedCols.add(table1 + "." + c.getName());
            for (ColumnSchema c : schema2.getColumns())
                requestedCols.add(table2 + "." + c.getName());
        } else {
            requestedCols = new ArrayList<>(q.getColumns());
        }

        // Filter row indexes for table1 from WHERE (only conditions for table1)
        WhereClause t1Where = extractTableWhere(q.getWhereClause(), table1, schema1);
        List<Integer> validRowIndexes1 = getFilteredRowIndexes(q.getTableName(), schema1, t1Where);

        // Read join column data
        List<String> leftJoinData = storageEngine.readColumn(table1, q.getJoinLeftCol());
        List<String> rightJoinData = storageEngine.readColumn(table2, q.getJoinRightCol());

        // Read all column data from both tables
        Map<String, List<String>> colData1 = new HashMap<>();
        for (ColumnSchema c : schema1.getColumns()) {
            colData1.put(c.getName(), storageEngine.readColumn(table1, c.getName()));
        }
        Map<String, List<String>> colData2 = new HashMap<>();
        for (ColumnSchema c : schema2.getColumns()) {
            colData2.put(c.getName(), storageEngine.readColumn(table2, c.getName()));
        }

        // Where conditions for table2
        WhereClause t2Where = extractTableWhere(q.getWhereClause(), table2, schema2);
        List<Integer> validRowIndexes2 = null;
        if (t2Where != null) {
            validRowIndexes2 = getFilteredRowIndexes(table2, schema2, t2Where);
        }
        Set<Integer> t2ValidSet = validRowIndexes2 != null
                ? new HashSet<>(validRowIndexes2) : null;

        // Nested loop join
        List<String> allColNames = new ArrayList<>();
        for (String col : requestedCols) {
            String simpleName = col.contains(".") ? col.substring(col.indexOf('.') + 1) : col;
            allColNames.add(simpleName);
        }

        List<List<String>> resultRows = new ArrayList<>();

        for (int i : validRowIndexes1) {
            String leftVal = i < leftJoinData.size() ? leftJoinData.get(i) : null;
            boolean matched = false;

            for (int j = 0; j < rightJoinData.size(); j++) {
                if (t2ValidSet != null && !t2ValidSet.contains(j)) continue;

                String rightVal = rightJoinData.get(j);
                if (leftVal != null && leftVal.equals(rightVal)) {
                    matched = true;
                    List<String> row = new ArrayList<>();
                    for (String col : requestedCols) {
                        String tblPrefix = col.contains(".") ? col.substring(0, col.indexOf('.')) : table1;
                        String simpleName = col.contains(".") ? col.substring(col.indexOf('.') + 1) : col;
                        if (tblPrefix.equals(table1) || tblPrefix.equals(q.getTableName())) {
                            row.add(i < colData1.getOrDefault(simpleName, Collections.emptyList()).size()
                                    ? colData1.get(simpleName).get(i) : "null");
                        } else {
                            row.add(j < colData2.getOrDefault(simpleName, Collections.emptyList()).size()
                                    ? colData2.get(simpleName).get(j) : "null");
                        }
                    }
                    resultRows.add(row);
                }
            }

            // LEFT JOIN: if no match, add row with nulls for right table
            if (isLeftJoin && !matched) {
                List<String> row = new ArrayList<>();
                for (String col : requestedCols) {
                    String tblPrefix = col.contains(".") ? col.substring(0, col.indexOf('.')) : table1;
                    String simpleName = col.contains(".") ? col.substring(col.indexOf('.') + 1) : col;
                    if (tblPrefix.equals(table1) || tblPrefix.equals(q.getTableName())) {
                        row.add(i < colData1.getOrDefault(simpleName, Collections.emptyList()).size()
                                ? colData1.get(simpleName).get(i) : "null");
                    } else {
                        row.add("null");
                    }
                }
                resultRows.add(row);
            }
        }

        // ORDER BY on joined results
        if (q.hasOrderBy() && !resultRows.isEmpty()) {
            String orderCol = q.getOrderByColumn();
            int orderIdx = -1;
            for (int k = 0; k < allColNames.size(); k++) {
                if (allColNames.get(k).equals(orderCol)) { orderIdx = k; break; }
            }
            if (orderIdx >= 0) {
                final int oi = orderIdx;
                resultRows.sort((a, b) -> {
                    String va = a.get(oi), vb = b.get(oi);
                    try {
                        int cmp = Double.compare(Double.parseDouble(va), Double.parseDouble(vb));
                        return q.isOrderByAsc() ? cmp : -cmp;
                    } catch (NumberFormatException e) {
                        int cmp = va.compareToIgnoreCase(vb);
                        return q.isOrderByAsc() ? cmp : -cmp;
                    }
                });
            }
        }

        // LIMIT / OFFSET
        if (q.hasLimit()) {
            int from = Math.min(q.getOffset(), resultRows.size());
            int to = Math.min(from + q.getLimit(), resultRows.size());
            resultRows = resultRows.subList(from, to);
        }

        // Format output
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", requestedCols)).append("\n");
        sb.append("-".repeat(Math.max(1, requestedCols.size() * 15))).append("\n");
        for (List<String> row : resultRows) {
            sb.append(String.join("\t", row)).append("\n");
        }
        return sb.toString().trim() + "\n(" + resultRows.size() + " rows)";
    }

    private WhereClause extractTableWhere(WhereClause where, String tableName, TableSchema schema) {
        if (where == null) return null;
        List<List<WhereCondition>> resultGroups = new ArrayList<>();
        for (List<WhereCondition> group : where.getOrGroups()) {
            List<WhereCondition> filtered = new ArrayList<>();
            for (WhereCondition cond : group) {
                String col = cond.getColumn();
                // Check if this column belongs to the given table
                String simpleCol = col.contains(".") ? col.substring(col.indexOf('.') + 1) : col;
                if (schema.getColumn(simpleCol) != null) {
                    // Recreate with simple column name
                    filtered.add(new WhereCondition(simpleCol, cond.getOp(), cond.getValue(),
                            cond.getSecondValue(), cond.getValueList(), cond.isNegated()));
                }
            }
            if (!filtered.isEmpty()) resultGroups.add(filtered);
        }
        return resultGroups.isEmpty() ? null : new WhereClause(resultGroups);
    }

    // -------------------------------------------------------------------------
    // AGGREGATION
    // -------------------------------------------------------------------------
    private String computeAggregation(SelectQuery q, String tableName, TableSchema schema,
                                       List<Integer> rowIndexes) throws IOException {
        String func = q.getAggFunction();
        String col = q.getAggColumn();

        List<String> values;
        if (col == null || "*".equals(col)) {
            // COUNT(*) - just count rows
            long count = rowIndexes.size();
            return func + "(*)\n-----\n" + count + "\n(1 row)";
        }

        ColumnSchema colSchema = schema.getColumn(col);
        if (colSchema == null) throw new IllegalArgumentException("Column not found: " + col);

        List<String> allValues = storageEngine.readColumn(tableName, col);
        List<String> filtered = new ArrayList<>();
        for (int idx : rowIndexes) {
            if (idx < allValues.size()) filtered.add(allValues.get(idx));
        }

        return switch (func) {
            case "COUNT" -> {
                long count = filtered.stream().filter(v -> v != null && !v.isEmpty()).count();
                yield "COUNT(" + col + ")\n-----\n" + count + "\n(1 row)";
            }
            case "SUM" -> {
                double sum = filtered.stream().mapToDouble(this::parseDoubleSafe).sum();
                yield "SUM(" + col + ")\n-----\n" + sum + "\n(1 row)";
            }
            case "AVG" -> {
                double avg = filtered.stream().mapToDouble(this::parseDoubleSafe).average().orElse(0);
                yield "AVG(" + col + ")\n-----\n" + avg + "\n(1 row)";
            }
            case "MIN" -> {
                double min = filtered.stream().mapToDouble(this::parseDoubleSafe).min().orElse(0);
                yield "MIN(" + col + ")\n-----\n" + min + "\n(1 row)";
            }
            case "MAX" -> {
                double max = filtered.stream().mapToDouble(this::parseDoubleSafe).max().orElse(0);
                yield "MAX(" + col + ")\n-----\n" + max + "\n(1 row)";
            }
            default -> "Unknown aggregation: " + func;
        };
    }

    private String computeGroupByAggregation(SelectQuery q, String tableName, TableSchema schema,
                                              List<Integer> rowIndexes) throws IOException {
        String func = q.getAggFunction();
        String aggCol = q.getAggColumn();
        String groupCol = q.getGroupByColumn();

        ColumnSchema groupColSchema = schema.getColumn(groupCol);
        if (groupColSchema == null) throw new IllegalArgumentException("Group column not found: " + groupCol);

        List<String> groupAllValues = storageEngine.readColumn(tableName, groupCol);
        List<String> aggAllValues = (aggCol == null || "*".equals(aggCol))
                ? null : storageEngine.readColumn(tableName, aggCol);

        // Group the data
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        for (int idx : rowIndexes) {
            String groupVal = idx < groupAllValues.size() ? groupAllValues.get(idx) : "null";
            if (aggAllValues != null) {
                double val = idx < aggAllValues.size() ? parseDoubleSafe(aggAllValues.get(idx)) : 0;
                groups.computeIfAbsent(groupVal, k -> new ArrayList<>()).add(val);
            } else {
                groups.computeIfAbsent(groupVal, k -> new ArrayList<>()).add(0.0);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(groupCol).append("\t").append(func).append("(").append(aggCol == null ? "*" : aggCol).append(")\n");
        sb.append("-".repeat(30)).append("\n");

        for (Map.Entry<String, List<Double>> entry : groups.entrySet()) {
            String result = switch (func) {
                case "COUNT" -> String.valueOf(entry.getValue().size());
                case "SUM" -> String.valueOf(entry.getValue().stream().mapToDouble(d -> d).sum());
                case "AVG" -> String.valueOf(entry.getValue().stream().mapToDouble(d -> d).average().orElse(0));
                case "MIN" -> String.valueOf(entry.getValue().stream().mapToDouble(d -> d).min().orElse(0));
                case "MAX" -> String.valueOf(entry.getValue().stream().mapToDouble(d -> d).max().orElse(0));
                default -> "?";
            };
            sb.append(entry.getKey()).append("\t").append(result).append("\n");
        }
        return sb.toString().trim() + "\n(" + groups.size() + " groups)";
    }

    private double parseDoubleSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------
    private String executeUpdate(UpdateQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        if (schema.getColumn(q.getSetColumn()) == null) {
            throw new IllegalArgumentException("Column not found: " + q.getSetColumn());
        }

        validateWhereClause(q.getWhereClause(), schema);

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getWhereClause());

        List<String> currentValues = storageEngine.readColumn(q.getTableName(), q.getSetColumn());

        for (int idx : validRowIndexes) {
            String oldValue = currentValues.get(idx);
            storageEngine.updateValue(q.getTableName(), q.getSetColumn(), idx, q.getSetValue());
            indexManager.updateValue(q.getTableName(), q.getSetColumn(), idx, oldValue, q.getSetValue());
        }

        return validRowIndexes.size() + " rows updated.";
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------
    private String executeDelete(DeleteQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        validateWhereClause(q.getWhereClause(), schema);

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getWhereClause());

        for (int i = validRowIndexes.size() - 1; i >= 0; i--) {
            int idx = validRowIndexes.get(i);
            storageEngine.deleteRow(q.getTableName(), idx);
        }

        indexManager.buildIndex(q.getTableName());

        return validRowIndexes.size() + " rows deleted.";
    }

    // -------------------------------------------------------------------------
    // DROP TABLE
    // -------------------------------------------------------------------------
    private String executeDropTable(DropTableQuery q) throws IOException {
        String tableName = q.getTableName();
        TableSchema schema = schemaManager.getTable(tableName);
        if (schema == null) throw new IllegalArgumentException("Table not found: " + tableName);
        schemaManager.dropTable(tableName);
        storageEngine.dropTable(tableName);
        return "Table " + tableName + " dropped successfully.";
    }

    // -------------------------------------------------------------------------
    // SHOW TABLES
    // -------------------------------------------------------------------------
    private String executeShowTables() {
        Set<String> tables = schemaManager.listTables();
        if (tables.isEmpty()) return "No tables found.";
        StringBuilder sb = new StringBuilder("Tables:\n");
        for (String t : tables) sb.append("  ").append(t).append("\n");
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Core filtering
    // -------------------------------------------------------------------------
    private List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema,
                                                WhereClause whereClause) throws IOException {

        if (whereClause == null) {
            List<Integer> all = indexManager.getFilteredRowIndexes(tableName, schema, (WhereClause) null);
            return all;
        }

        List<Integer> indexed = indexManager.getFilteredRowIndexes(tableName, schema, whereClause);
        if (indexed != null) {
            return indexed;
        }

        List<WhereCondition> allConditions = whereClause.getAllConditions();

        String firstCol = allConditions.get(0).getColumn();
        List<String> firstColData = storageEngine.readColumn(tableName, firstCol);
        int rowCount = firstColData.size();

        Map<String, List<String>> colDataMap = new HashMap<>();
        for (WhereCondition cond : allConditions) {
            if (!colDataMap.containsKey(cond.getColumn())) {
                colDataMap.put(cond.getColumn(), storageEngine.readColumn(tableName, cond.getColumn()));
            }
        }

        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            boolean rowMatchesAnyOrGroup = false;

            for (List<WhereCondition> andGroup : whereClause.getOrGroups()) {
                boolean andGroupMatches = true;

                for (WhereCondition cond : andGroup) {
                    List<String> colData = colDataMap.get(cond.getColumn());
                    String cellVal = i < colData.size() ? colData.get(i) : "null";
                    boolean condMatch = evaluateCondition(cellVal, cond);

                    if (!condMatch) {
                        andGroupMatches = false;
                        break;
                    }
                }

                if (andGroupMatches) {
                    rowMatchesAnyOrGroup = true;
                    break;
                }
            }

            if (rowMatchesAnyOrGroup) {
                result.add(i);
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Condition evaluator (extended)
    // -------------------------------------------------------------------------
    private boolean evaluateCondition(String val1, WhereCondition cond) {
        String op = cond.getOp();
        boolean negated = cond.isNegated();

        boolean result = switch (op) {
            case "=", "!=", ">", "<", ">=", "<=" -> evaluateComparison(val1, op, cond.getValue());
            case "IN" -> evaluateIn(val1, cond.getValueList());
            case "NOT_IN" -> !evaluateIn(val1, cond.getValueList());
            case "BETWEEN" -> evaluateBetween(val1, cond.getValue(), cond.getSecondValue());
            case "NOT_BETWEEN" -> !evaluateBetween(val1, cond.getValue(), cond.getSecondValue());
            case "LIKE" -> evaluateLike(val1, cond.getValue());
            case "NOT_LIKE" -> !evaluateLike(val1, cond.getValue());
            case "IS_NULL" -> val1 == null || val1.trim().isEmpty() || val1.equalsIgnoreCase("null");
            case "IS_NOT_NULL" -> !(val1 == null || val1.trim().isEmpty() || val1.equalsIgnoreCase("null"));
            default -> false;
        };

        return negated ? !result : result;
    }

    private boolean evaluateComparison(String val1, String op, String val2) {
        try {
            double num1 = Double.parseDouble(normalizeBoolean(val1).trim());
            double num2 = Double.parseDouble(normalizeBoolean(val2).trim());
            return switch (op) {
                case "="  -> num1 == num2;
                case "!=" -> num1 != num2;
                case ">"  -> num1 > num2;
                case "<"  -> num1 < num2;
                case ">=" -> num1 >= num2;
                case "<=" -> num1 <= num2;
                default   -> false;
            };
        } catch (NumberFormatException e) {
            return switch (op) {
                case "="  -> val1.trim().equalsIgnoreCase(val2.trim());
                case "!=" -> !val1.trim().equalsIgnoreCase(val2.trim());
                default   -> false;
            };
        }
    }

    private boolean evaluateIn(String val, List<String> valueList) {
        if (valueList == null) return false;
        String trimmed = val.trim();
        for (String v : valueList) {
            if (trimmed.equalsIgnoreCase(v.trim())) return true;
            try {
                if (Double.parseDouble(trimmed) == Double.parseDouble(v.trim())) return true;
            } catch (NumberFormatException e) { /* string comparison */ }
        }
        return false;
    }

    private boolean evaluateBetween(String val, String low, String high) {
        try {
            double d = Double.parseDouble(normalizeBoolean(val).trim());
            double l = Double.parseDouble(normalizeBoolean(low).trim());
            double h = Double.parseDouble(normalizeBoolean(high).trim());
            return d >= l && d <= h;
        } catch (NumberFormatException e) {
            String v = val.trim().toLowerCase();
            return v.compareTo(low.trim().toLowerCase()) >= 0
                && v.compareTo(high.trim().toLowerCase()) <= 0;
        }
    }

    private boolean evaluateLike(String val, String pattern) {
        if (val == null || pattern == null) return false;
        String regex = Pattern.quote(pattern).replace("%", "\\E.*\\Q").replace("_", "\\E.\\Q");
        return val.matches("(?i)" + regex);
    }

    // -------------------------------------------------------------------------
    // ORDER BY helper
    // -------------------------------------------------------------------------
    private List<Integer> sortRowIndexes(List<Integer> indexes, List<List<String>> columnsData,
                                          List<String> colNames, String orderCol, boolean asc) {
        int colIdx = colNames.indexOf(orderCol);
        if (colIdx < 0 || colIdx >= columnsData.size()) return indexes;

        List<String> sortCol = columnsData.get(colIdx);
        List<Integer> sorted = new ArrayList<>(indexes);
        sorted.sort((a, b) -> {
            String va = a < sortCol.size() ? sortCol.get(a) : "null";
            String vb = b < sortCol.size() ? sortCol.get(b) : "null";
            try {
                int cmp = Double.compare(Double.parseDouble(va), Double.parseDouble(vb));
                return asc ? cmp : -cmp;
            } catch (NumberFormatException e) {
                int cmp = va.compareToIgnoreCase(vb);
                return asc ? cmp : -cmp;
            }
        });
        return sorted;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private List<String> resolveColumns(List<String> requested, TableSchema schema) {
        if (requested.size() == 1 && requested.get(0).equals("*")) {
            List<String> all = new ArrayList<>();
            for (ColumnSchema col : schema.getColumns()) {
                all.add(col.getName());
            }
            return all;
        }
        return requested;
    }

    private void validateWhereClause(WhereClause whereClause, TableSchema schema) {
        if (whereClause == null) return;
        for (WhereCondition cond : whereClause.getAllConditions()) {
            String col = cond.getColumn();
            String simpleCol = col.contains(".") ? col.substring(col.indexOf('.') + 1) : col;
            if (schema.getColumn(simpleCol) == null) {
                throw new IllegalArgumentException("Filter column not found: " + col);
            }
        }
    }

    private void validateType(String value, String type) {
        if (value == null || value.equalsIgnoreCase("null")) return;
        try {
            switch (type.toUpperCase()) {
                case "BYTE":       Byte.parseByte(value.trim()); break;
                case "SHORT":      Short.parseShort(value.trim()); break;
                case "INT":
                case "INTEGER":    Integer.parseInt(value.trim()); break;
                case "LONG":
                case "BIGINT":     Long.parseLong(value.trim()); break;
                case "FLOAT":
                case "REAL":       Float.parseFloat(value.trim()); break;
                case "DOUBLE":
                case "DECIMAL":    Double.parseDouble(value.trim()); break;
                case "BOOLEAN":
                case "BOOL":       break;
                case "BIGDECIMAL":
                case "NUMERIC":    new java.math.BigDecimal(value.trim()); break;
                default:           break;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid value for type " + type + ": \"" + value + "\". " + e.getMessage());
        }
    }

    private String normalizeBoolean(String val) {
        String s = val.trim().toLowerCase();
        if (s.equals("true"))  return "1";
        if (s.equals("false")) return "0";
        return s;
    }
}
