package cdb.ddl;

import cdb.util.FileUtils;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class SchemaManager {
    private String metadataDir;
    private Map<String, TableSchema> schemas;

    public SchemaManager(String dataDir) {
        this.metadataDir = dataDir + "/metadata";
        this.schemas = new HashMap<>();
        FileUtils.ensureDirectory(this.metadataDir);
        loadSchemas();
    }

    private void loadSchemas() {
        File dir = new File(metadataDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".schema.bin")) {
                    try {
                        TableSchema schema = loadSchemaBin(file);
                        if (schema != null) schemas.put(schema.getTableName(), schema);
                    } catch (IOException e) {
                        System.err.println("Failed to load binary schema: " + name);
                    }
                } else if (name.endsWith(".schema") && !name.endsWith(".schema.bin")) {
                    try {
                        String content = new String(Files.readAllBytes(file.toPath())).trim();
                        TableSchema schema = parseSchemaString(content);
                        if (schema != null) schemas.put(schema.getTableName(), schema);
                    } catch (IOException e) {
                        System.err.println("Failed to load schema: " + name);
                    }
                }
            }
        }
    }

    private TableSchema loadSchemaBin(File file) throws IOException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            String tableName = dis.readUTF();
            int colCount = dis.readInt();
            TableSchema table = new TableSchema(tableName);
            for (int i = 0; i < colCount; i++) {
                String colName = dis.readUTF();
                String colType = dis.readUTF();
                ColumnSchema col = new ColumnSchema(colName, colType);
                int constraintCount = dis.readInt();
                for (int j = 0; j < constraintCount; j++) {
                    col.addConstraint(dis.readUTF());
                }
                table.addColumn(col);
            }
            return table;
        }
    }

    private void saveSchemaBin(TableSchema schema) throws IOException {
        String filePath = metadataDir + "/" + schema.getTableName() + ".schema.bin";
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(filePath))) {
            dos.writeUTF(schema.getTableName());
            dos.writeInt(schema.getColumns().size());
            for (ColumnSchema col : schema.getColumns()) {
                dos.writeUTF(col.getName());
                dos.writeUTF(col.getType());
                dos.writeInt(col.getConstraints().size());
                for (String constraint : col.getConstraints()) {
                    dos.writeUTF(constraint);
                }
            }
        }
    }

    public TableSchema parseSchemaString(String content) {
        String[] tokens = content.split("\\s+");
        if (tokens.length < 2 || !tokens[0].equals("TABLE"))
            return null;

        TableSchema table = new TableSchema(tokens[1]);
        ColumnSchema currentColumn = null;

        for (int i = 2; i < tokens.length; i++) {
            if (tokens[i].equals("COLUMN")) {
                if (i + 2 < tokens.length) {
                    currentColumn = new ColumnSchema(tokens[i + 1], tokens[i + 2]);
                    table.addColumn(currentColumn);
                    i += 2;
                }
            } else if (currentColumn != null) {
                currentColumn.addConstraint(tokens[i]);
            }
        }
        return table;
    }

    public void createTable(TableSchema schema) throws IOException {
        String filePath = metadataDir + "/" + schema.getTableName() + ".schema";
        FileUtils.ensureFile(filePath);
        String schemaStr = schema.toString();
        Files.write(Paths.get(filePath), schemaStr.getBytes());
        saveSchemaBin(schema);
        schemas.put(schema.getTableName(), schema);
    }

    public void dropTable(String tableName) {
        String filePath = metadataDir + "/" + tableName + ".schema";
        new File(filePath).delete();
        String binPath = metadataDir + "/" + tableName + ".schema.bin";
        new File(binPath).delete();
        schemas.remove(tableName);
    }

    public TableSchema getTable(String tableName) {
        return schemas.get(tableName);
    }

    public java.util.Set<String> listTables() {
        return schemas.keySet();
    }
}
