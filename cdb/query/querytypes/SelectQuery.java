package cdb.query.querytypes;

import java.util.List;

public class SelectQuery implements Query {
    private final String tableName;
    private final List<String> columns;
    private final WhereClause whereClause;

    // JOIN support
    private final String joinTable;
    private final String joinType; // "INNER", "LEFT", or null
    private final String joinLeftCol;
    private final String joinRightCol;

    // Aggregation
    private final String aggFunction;
    private final String aggColumn;
    private final String groupByColumn;

    // ORDER BY / LIMIT
    private final String orderByColumn;
    private final boolean orderByAsc;
    private final int limit;
    private final int offset;

    private SelectQuery(String tableName, List<String> columns, WhereClause whereClause,
                        String joinTable, String joinType, String joinLeftCol, String joinRightCol,
                        String aggFunction, String aggColumn, String groupByColumn,
                        String orderByColumn, boolean orderByAsc, int limit, int offset) {
        this.tableName = tableName;
        this.columns = columns;
        this.whereClause = whereClause;
        this.joinTable = joinTable;
        this.joinType = joinType;
        this.joinLeftCol = joinLeftCol;
        this.joinRightCol = joinRightCol;
        this.aggFunction = aggFunction;
        this.aggColumn = aggColumn;
        this.groupByColumn = groupByColumn;
        this.orderByColumn = orderByColumn;
        this.orderByAsc = orderByAsc;
        this.limit = limit;
        this.offset = offset;
    }

    // Simple select constructor
    public SelectQuery(String tableName, List<String> columns, WhereClause whereClause) {
        this(tableName, columns, whereClause, null, null, null, null,
             null, null, null, null, true, -1, 0);
    }

    // Legacy constructor
    public SelectQuery(String tableName, List<String> columns,
                       String filterColumn, String filterOp, String filterValue) {
        this(tableName, columns,
             filterColumn != null ? new WhereClause(new WhereCondition(filterColumn, filterOp, filterValue)) : null,
             null, null, null, null, null, null, null, null, true, -1, 0);
    }

    // Builder for complex queries
    public static class Builder {
        private String tableName;
        private List<String> columns;
        private WhereClause whereClause;
        private String joinTable, joinType, joinLeftCol, joinRightCol;
        private String aggFunction, aggColumn, groupByColumn;
        private String orderByColumn;
        private boolean orderByAsc = true;
        private int limit = -1;
        private int offset = 0;

        public Builder tableName(String v) { this.tableName = v; return this; }
        public Builder columns(List<String> v) { this.columns = v; return this; }
        public Builder whereClause(WhereClause v) { this.whereClause = v; return this; }
        public Builder join(String table, String type, String leftCol, String rightCol) {
            this.joinTable = table; this.joinType = type;
            this.joinLeftCol = leftCol; this.joinRightCol = rightCol;
            return this;
        }
        public Builder aggregation(String func, String col) {
            this.aggFunction = func; this.aggColumn = col; return this;
        }
        public Builder groupBy(String col) { this.groupByColumn = col; return this; }
        public Builder orderBy(String col, boolean asc) {
            this.orderByColumn = col; this.orderByAsc = asc; return this;
        }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder offset(int v) { this.offset = v; return this; }

        public SelectQuery build() {
            return new SelectQuery(tableName, columns, whereClause,
                joinTable, joinType, joinLeftCol, joinRightCol,
                aggFunction, aggColumn, groupByColumn,
                orderByColumn, orderByAsc, limit, offset);
        }
    }

    public String getTableName()       { return tableName; }
    public List<String> getColumns()   { return columns; }
    public WhereClause getWhereClause(){ return whereClause; }

    public String getJoinTable()    { return joinTable; }
    public String getJoinType()     { return joinType; }
    public String getJoinLeftCol()  { return joinLeftCol; }
    public String getJoinRightCol() { return joinRightCol; }
    public boolean hasJoin()        { return joinTable != null; }

    public String getAggFunction()  { return aggFunction; }
    public String getAggColumn()    { return aggColumn; }
    public String getGroupByColumn(){ return groupByColumn; }
    public boolean hasAggregation() { return aggFunction != null; }
    public boolean hasGroupBy()     { return groupByColumn != null; }

    public String getOrderByColumn() { return orderByColumn; }
    public boolean isOrderByAsc()    { return orderByAsc; }
    public boolean hasOrderBy()      { return orderByColumn != null; }
    public int getLimit()  { return limit; }
    public int getOffset() { return offset; }
    public boolean hasLimit() { return limit >= 0; }

    public String getFilterColumn() {
        return (whereClause != null && !whereClause.getAllConditions().isEmpty())
                ? whereClause.getAllConditions().get(0).getColumn() : null;
    }
    public String getFilterOp() {
        return (whereClause != null && !whereClause.getAllConditions().isEmpty())
                ? whereClause.getAllConditions().get(0).getOp() : null;
    }
    public String getFilterValue() {
        return (whereClause != null && !whereClause.getAllConditions().isEmpty())
                ? whereClause.getAllConditions().get(0).getValue() : null;
    }
}
