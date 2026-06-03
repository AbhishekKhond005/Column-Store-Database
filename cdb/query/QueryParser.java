package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.TableSchema;
import cdb.query.querytypes.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryParser {

    private static final Pattern CONDITION_PAT =
            Pattern.compile("(\\w+(?:\\.\\w+)?)\\s*(>=|<=|!=|[=><])\\s*([^\\s]+(?:\\s+[^\\s]+)*)");

    public Query parse(String query) {
        query = query.trim();
        String upper = query.toUpperCase();

        if (upper.equals("SHOW TABLES")) return new ShowTablesQuery();
        if (upper.startsWith("CREATE TABLE")) return parseCreateTable(query);
        if (upper.startsWith("INSERT INTO")) return parseInsert(query);
        if (upper.startsWith("SELECT")) return parseSelect(query);
        if (upper.startsWith("UPDATE")) return parseUpdate(query);
        if (upper.startsWith("DELETE FROM")) return parseDelete(query);
        if (upper.startsWith("DROP TABLE")) return parseDropTable(query);
        if (upper.startsWith("SHOW BITMAP INDEX") || upper.startsWith("SHOW INDEX")) return parseShowIndex(query);

        throw new IllegalArgumentException("Unsupported query: " + query);
    }

    private String[] splitWhereClause(String query) {
        String upper = query.toUpperCase();
        int whereIdx = findWhereKeyword(upper);
        if (whereIdx == -1) {
            return new String[]{ query, null };
        }
        String mainPart = query.substring(0, whereIdx).trim();
        String whereClause = query.substring(whereIdx + 6).trim();
        return new String[]{ mainPart, whereClause };
    }

    private int findWhereKeyword(String upper) {
        int idx = upper.lastIndexOf(" WHERE ");
        if (idx == -1) idx = upper.lastIndexOf("\nWHERE ");
        int orderIdx = findOrderInGroupBy(upper);
        if (idx > 0 && (orderIdx < 0 || idx < orderIdx)) return idx;
        return idx;
    }

    private int findOrderInGroupBy(String upper) {
        int o = upper.lastIndexOf(" ORDER BY ");
        int g = upper.lastIndexOf(" GROUP BY ");
        int h = upper.lastIndexOf(" HAVING ");
        int min = Integer.MAX_VALUE;
        if (o >= 0 && o < min) min = o;
        if (g >= 0 && g < min) min = g;
        if (h >= 0 && h < min) min = h;
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private WhereClause parseWhereClause(String whereClauseStr) {
        if (whereClauseStr == null) return null;

        // Strip trailing ORDER BY, GROUP BY, LIMIT
        String upper = whereClauseStr.toUpperCase();
        int trimIdx = whereClauseStr.length();
        for (String kw : new String[]{" ORDER BY ", " GROUP BY ", " HAVING ", " LIMIT "}) {
            int idx = upper.lastIndexOf(kw);
            if (idx >= 0 && idx < trimIdx) trimIdx = idx;
        }
        whereClauseStr = whereClauseStr.substring(0, trimIdx).trim();

        List<List<WhereCondition>> orGroups = new ArrayList<>();
        String[] orParts = splitByOrPreservingParens(whereClauseStr);

        for (String orPart : orParts) {
            List<WhereCondition> andGroup = new ArrayList<>();
            String[] andParts = splitByAndPreservingParens(orPart);

            for (String raw : andParts) {
                String trimmed = raw.trim();
                WhereCondition cond = parseSingleCondition(trimmed);
                if (cond != null) andGroup.add(cond);
            }
            if (!andGroup.isEmpty()) orGroups.add(andGroup);
        }

        return orGroups.isEmpty() ? null : new WhereClause(orGroups);
    }

    private WhereCondition parseSingleCondition(String expr) {
        if (expr.isEmpty()) return null;

        boolean negated = false;
        String working = expr;

        // Handle NOT prefix
        if (working.toUpperCase().startsWith("NOT ")) {
            negated = true;
            working = working.substring(4).trim();
            // Remove parentheses if present: NOT (cond)
            if (working.startsWith("(") && working.endsWith(")")) {
                working = working.substring(1, working.length() - 1).trim();
            }
        }

        // IN (...) 
        Matcher inMatcher = Pattern.compile("(\\w+(?:\\.\\w+)?)\\s+(NOT\\s+)?IN\\s*\\((.+)\\)", Pattern.CASE_INSENSITIVE).matcher(working);
        if (inMatcher.find()) {
            String col = inMatcher.group(1);
            boolean notIn = inMatcher.group(2) != null;
            List<String> vals = splitValues(inMatcher.group(3));
            return new WhereCondition(col, notIn ? "NOT_IN" : "IN", vals.isEmpty() ? "" : vals.get(0),
                                       null, vals, negated);
        }

        // BETWEEN x AND y
        Matcher btMatcher = Pattern.compile("(\\w+(?:\\.\\w+)?)\\s+(NOT\\s+)?BETWEEN\\s+(.+)\\s+AND\\s+(.+)", Pattern.CASE_INSENSITIVE).matcher(working);
        if (btMatcher.find()) {
            String col = btMatcher.group(1);
            boolean notBt = btMatcher.group(2) != null;
            String val1 = stripQuotes(btMatcher.group(3).trim());
            String val2 = stripQuotes(btMatcher.group(4).trim());
            String op = notBt ? "NOT_BETWEEN" : "BETWEEN";
            return new WhereCondition(col, op, val1, val2, null, negated);
        }

        // LIKE
        Matcher likeMatcher = Pattern.compile("(\\w+(?:\\.\\w+)?)\\s+(NOT\\s+)?LIKE\\s+(.+)", Pattern.CASE_INSENSITIVE).matcher(working);
        if (likeMatcher.find()) {
            String col = likeMatcher.group(1);
            boolean notLike = likeMatcher.group(2) != null;
            String pattern = stripQuotes(likeMatcher.group(3).trim());
            return new WhereCondition(col, notLike ? "NOT_LIKE" : "LIKE", pattern, null, null, negated);
        }

        // Standard comparison: col op val
        Matcher m = CONDITION_PAT.matcher(working);
        if (m.find()) {
            String col = m.group(1);
            String op = m.group(2);
            String val = stripQuotes(m.group(3).trim());
            return new WhereCondition(col, op, val, null, null, negated);
        }

        // IS NULL / IS NOT NULL
        Matcher nullMatcher = Pattern.compile("(\\w+(?:\\.\\w+)?)\\s+IS\\s+(NOT\\s+)?NULL", Pattern.CASE_INSENSITIVE).matcher(working);
        if (nullMatcher.find()) {
            String col = nullMatcher.group(1);
            boolean isNotNull = nullMatcher.group(2) != null;
            return new WhereCondition(col, isNotNull ? "IS_NOT_NULL" : "IS_NULL", "", null, null, negated);
        }

        throw new IllegalArgumentException("Invalid WHERE condition: '" + expr + "'");
    }

    private String stripQuotes(String s) {
        return s.replace("\"", "").replace("'", "")
                .replace("\u201c", "").replace("\u201d", "");
    }

    private List<String> splitValues(String s) {
        List<String> result = new ArrayList<>();
        for (String v : s.split(",")) {
            result.add(stripQuotes(v.trim()));
        }
        return result;
    }

    private String[] splitByOrPreservingParens(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        String upper = s.toUpperCase();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; buf.append(c); }
            else if (c == ')') { depth--; buf.append(c); }
            else if (depth == 0) {
                if (i + 3 <= s.length() && upper.substring(i, i + 3).equals("OR ")
                        && (i == 0 || Character.isWhitespace(s.charAt(i - 1)) || s.charAt(i - 1) == ')')) {
                    String candidate = buf.toString().trim();
                    if (!candidate.isEmpty()) parts.add(candidate);
                    buf = new StringBuilder();
                    i += 2; // skip "OR"
                    continue;
                }
                buf.append(c);
            } else {
                buf.append(c);
            }
        }
        String remaining = buf.toString().trim();
        if (!remaining.isEmpty()) parts.add(remaining);
        return parts.toArray(new String[0]);
    }

    private String[] splitByAndPreservingParens(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        String upper = s.toUpperCase();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; buf.append(c); }
            else if (c == ')') { depth--; buf.append(c); }
            else if (depth == 0) {
                if (i + 4 <= s.length() && upper.substring(i, i + 4).equals("AND ")
                        && (i == 0 || Character.isWhitespace(s.charAt(i - 1)) || s.charAt(i - 1) == ')')) {
                    String candidate = buf.toString().trim();
                    if (!candidate.isEmpty()) parts.add(candidate);
                    buf = new StringBuilder();
                    i += 3;
                    continue;
                }
                buf.append(c);
            } else {
                buf.append(c);
            }
        }
        String remaining = buf.toString().trim();
        if (!remaining.isEmpty()) parts.add(remaining);
        return parts.toArray(new String[0]);
    }

    // -------------------------------------------------------------------------
    // CREATE TABLE
    // -------------------------------------------------------------------------
    private CreateTableQuery parseCreateTable(String query) {
        Pattern pattern = Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            String columnsPart = matcher.group(2);
            TableSchema schema = new TableSchema(tableName);
            String[] colDefs = columnsPart.split(",");
            for (String colDef : colDefs) {
                String[] parts = colDef.trim().split("\\s+");
                if (parts.length >= 2) {
                    ColumnSchema col = new ColumnSchema(parts[0], parts[1]);
                    for (int i = 2; i < parts.length; i++) {
                        col.addConstraint(parts[i]);
                    }
                    schema.addColumn(col);
                }
            }
            return new CreateTableQuery(schema);
        }
        throw new IllegalArgumentException("Invalid CREATE TABLE syntax");
    }

    // -------------------------------------------------------------------------
    // INSERT
    // -------------------------------------------------------------------------
    private InsertQuery parseInsert(String query) {
        Pattern pattern = Pattern.compile("INSERT\\s+INTO\\s+(\\w+)\\s+VALUES\\s*\\((.*)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            String valuesPart = matcher.group(2);
            List<String> values = new ArrayList<>();
            for (String v : valuesPart.split(",")) {
                values.add(stripQuotes(v.trim()));
            }
            return new InsertQuery(tableName, values);
        }
        throw new IllegalArgumentException("Invalid INSERT syntax");
    }

    // -------------------------------------------------------------------------
    // SELECT (with JOIN, Aggregation, ORDER BY, LIMIT)
    // -------------------------------------------------------------------------
    private SelectQuery parseSelect(String query) {
        String upper = query.toUpperCase();

        // Extract ORDER BY
        String orderByColumn = null;
        boolean orderByAsc = true;
        int orderIdx = findLastKeyword(upper, " ORDER BY ");
        if (orderIdx >= 0) {
            String afterOrder = query.substring(orderIdx + 10).trim();
            int limitIdx = afterOrder.toUpperCase().indexOf(" LIMIT ");
            String orderPart = limitIdx >= 0 ? afterOrder.substring(0, limitIdx) : afterOrder;
            String[] orderParts = orderPart.split("\\s+");
            orderByColumn = orderParts[0].trim();
            if (orderParts.length > 1 && orderParts[1].toUpperCase().equals("DESC")) orderByAsc = false;
            query = query.substring(0, orderIdx).trim();
            upper = query.toUpperCase();
        }

        // Extract GROUP BY (before ORDER BY was already stripped)
        String groupByColumn = null;
        int groupIdx = findLastKeyword(upper, " GROUP BY ");
        if (groupIdx >= 0) {
            String afterGroup = query.substring(groupIdx + 10).trim();
            // Strip possible HAVING
            int havingIdx = afterGroup.toUpperCase().indexOf(" HAVING ");
            if (havingIdx >= 0) afterGroup = afterGroup.substring(0, havingIdx);
            groupByColumn = afterGroup.split("\\s+")[0].trim();
            query = query.substring(0, groupIdx).trim();
            upper = query.toUpperCase();
        }

        // Extract LIMIT / OFFSET
        int limit = -1;
        int offset = 0;
        int limitIdx = findLastKeyword(upper, " LIMIT ");
        if (limitIdx >= 0) {
            String afterLimit = query.substring(limitIdx + 7).trim();
            String[] parts = afterLimit.split("\\s+");
            try {
                limit = Integer.parseInt(parts[0]);
                if (parts.length > 2 && parts[1].toUpperCase().equals("OFFSET")) {
                    offset = Integer.parseInt(parts[2]);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid LIMIT value");
            }
            query = query.substring(0, limitIdx).trim();
            upper = query.toUpperCase();
        }

        // Split WHERE clause
        String[] whereParts = splitWhereClause(query);
        String mainPart = whereParts[0];
        WhereClause whereClause = parseWhereClause(whereParts[1]);

        // Detect aggregation in SELECT
        String aggFunction = null;
        String aggColumn = null;
        Matcher aggMatcher = Pattern.compile(
            "SELECT\\s+(COUNT|SUM|AVG|MIN|MAX)\\s*\\(\\s*(\\*|\\w+)\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(mainPart);
        if (aggMatcher.find()) {
            aggFunction = aggMatcher.group(1).toUpperCase();
            aggColumn = aggMatcher.group(2);
            if ("*".equals(aggColumn) && "COUNT".equals(aggFunction)) aggColumn = null;
        }

        // Detect JOIN
        Pattern joinPattern = Pattern.compile(
            "SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)\\s+(INNER\\s+JOIN|LEFT\\s+JOIN|JOIN)\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*=\\s*(\\w+)",
            Pattern.CASE_INSENSITIVE);
        Matcher joinMatcher = joinPattern.matcher(mainPart);

        if (joinMatcher.find()) {
            String colsPart = joinMatcher.group(1);
            String table1 = joinMatcher.group(2);
            String joinType = joinMatcher.group(3).toUpperCase().contains("LEFT") ? "LEFT" : "INNER";
            String table2 = joinMatcher.group(4);
            String leftCol = joinMatcher.group(5);
            String rightCol = joinMatcher.group(6);

            // Resolve column with table prefix
            List<String> cols = parseColumns(colsPart);
            String leftTable = table1;
            String rightTable = table2;

            return new SelectQuery.Builder()
                .tableName(table1)
                .columns(cols)
                .whereClause(whereClause)
                .join(table2, joinType, leftCol, rightCol)
                .aggregation(aggFunction, aggColumn)
                .groupBy(groupByColumn)
                .orderBy(orderByColumn, orderByAsc)
                .limit(limit)
                .offset(offset)
                .build();
        }

        // Regular SELECT (no JOIN)
        Pattern selectPattern = Pattern.compile("SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher selectMatcher = selectPattern.matcher(mainPart);
        if (selectMatcher.find()) {
            String colsPart = selectMatcher.group(1);
            String tableName = selectMatcher.group(2);
            List<String> cols = parseColumns(colsPart);

            return new SelectQuery.Builder()
                .tableName(tableName)
                .columns(cols)
                .whereClause(whereClause)
                .aggregation(aggFunction, aggColumn)
                .groupBy(groupByColumn)
                .orderBy(orderByColumn, orderByAsc)
                .limit(limit)
                .offset(offset)
                .build();
        }

        throw new IllegalArgumentException("Invalid SELECT syntax");
    }

    private int findLastKeyword(String upper, String keyword) {
        int idx = upper.lastIndexOf(keyword);
        // Make sure we find WHERE before these keywords when splitting
        if (idx >= 0) {
            int whereIdx = upper.lastIndexOf(" WHERE ");
            if (whereIdx >= 0 && whereIdx < idx) return idx;
            if (whereIdx < 0) return idx;
        }
        return -1;
    }

    private List<String> parseColumns(String colsPart) {
        List<String> cols = new ArrayList<>();
        if (colsPart.trim().equals("*")) {
            cols.add("*");
        } else {
            for (String c : colsPart.split(",")) {
                cols.add(c.trim());
            }
        }
        return cols;
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------
    private UpdateQuery parseUpdate(String query) {
        String[] parts = splitWhereClause(query);
        String mainPart = parts[0];
        WhereClause whereClause = parseWhereClause(parts[1]);

        Pattern pattern = Pattern.compile("UPDATE\\s+(\\w+)\\s+SET\\s+(\\w+)\\s*=\\s*(.*)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(mainPart);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            String setCol = matcher.group(2);
            String setVal = stripQuotes(matcher.group(3).trim());
            return new UpdateQuery(tableName, setCol, setVal, whereClause);
        }
        throw new IllegalArgumentException("Invalid UPDATE syntax");
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------
    private DeleteQuery parseDelete(String query) {
        String[] parts = splitWhereClause(query);
        String mainPart = parts[0];
        WhereClause whereClause = parseWhereClause(parts[1]);

        Pattern pattern = Pattern.compile("DELETE\\s+FROM\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(mainPart);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            return new DeleteQuery(tableName, whereClause);
        }
        throw new IllegalArgumentException("Invalid DELETE syntax");
    }

    // -------------------------------------------------------------------------
    // DROP TABLE
    // -------------------------------------------------------------------------
    private DropTableQuery parseDropTable(String query) {
        Pattern pattern = Pattern.compile("DROP\\s+TABLE\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            return new DropTableQuery(matcher.group(1));
        }
        throw new IllegalArgumentException("Invalid DROP TABLE syntax");
    }

    // -------------------------------------------------------------------------
    // SHOW INDEX
    // -------------------------------------------------------------------------
    private ShowIndexQuery parseShowIndex(String query) {
        Pattern pattern = Pattern.compile("SHOW\\s+(BITMAP\\s+)?INDEX\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            return new ShowIndexQuery(matcher.group(2), null);
        }
        throw new IllegalArgumentException("Invalid SHOW INDEX syntax");
    }
}
