package cdb.query.querytypes;

public class DropTableQuery implements Query {
    private final String tableName;

    public DropTableQuery(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() { return tableName; }
}
