package cdb.client;

import cdb.api.DatabaseAPI;
import cdb.ddl.SchemaManager;
import cdb.util.FileUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;

public class CLIClient {
    private static final String BASE_DIR = "databases";
    private DatabaseAPI currentDb;
    private String currentDbName;

    public CLIClient() {
        FileUtils.ensureDirectory(BASE_DIR);
    }

    public void start() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Column-Store Database System v2.0");
        System.out.println("Type 'HELP' for commands, 'EXIT' to quit.\n");

        while (true) {
            String prompt = "CDB";
            if (currentDbName != null) prompt += " [" + currentDbName + "]";
            System.out.print(prompt + " > ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String result = handleCommand(line);
            if (result.equals("__EXIT__")) break;
            if (result != null) System.out.println(result);
        }
        scanner.close();
    }

    private String handleCommand(String line) {
        String upper = line.toUpperCase().trim();

        if (upper.equals("EXIT") || upper.equals("QUIT")) return "__EXIT__";
        if (upper.equals("HELP")) return showHelp();

        if (upper.equals("SHOW DATABASES")) return showDatabases();
        if (upper.startsWith("CREATE DATABASE ")) return createDatabase(line.substring(16).trim());
        if (upper.startsWith("USE DATABASE ")) return useDatabase(line.substring(13).trim());
        if (upper.equals("SHOW TABLES")) return showTables();
        if (upper.startsWith("SHOW BITMAP INDEX ")) return showBitmapIndex(line.substring(18).trim());

        if (upper.startsWith("SOURCE ")) {
            return executeScript(line.substring(7).trim());
        }

        if (currentDb == null) {
            return "No database selected. Use 'USE DATABASE <name>' first.";
        }

        try {
            return currentDb.execute(line);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String showHelp() {
        return """
               Commands:
                 HELP                           Show this help
                 SHOW DATABASES                 List all databases
                 CREATE DATABASE <name>         Create a new database
                 USE DATABASE <name>            Switch to a database
                 SHOW TABLES                    List tables in current database
                 SHOW BITMAP INDEX <table>      Show bitmap index for a table
                 SOURCE <file>                  Execute SQL from a file
                 EXIT/QUIT                      Exit the program

               SQL Commands (when database is selected):
                 CREATE TABLE, INSERT, SELECT, UPDATE, DELETE, DROP TABLE
                 JOIN queries, aggregation (COUNT/SUM/AVG/MIN/MAX), GROUP BY
                 ORDER BY, LIMIT, WHERE (AND/OR/IN/BETWEEN/LIKE/NOT)
               """;
    }

    private String showDatabases() {
        File dir = new File(BASE_DIR);
        File[] dbs = dir.listFiles(File::isDirectory);
        if (dbs == null || dbs.length == 0) return "No databases found.";
        StringBuilder sb = new StringBuilder("Databases:\n");
        for (File db : dbs) sb.append("  ").append(db.getName()).append("\n");
        return sb.toString().trim();
    }

    private String createDatabase(String name) {
        if (name.isEmpty()) return "Error: Database name required.";
        String path = BASE_DIR + "/" + name;
        FileUtils.ensureDirectory(path);
        FileUtils.ensureDirectory(path + "/metadata");
        FileUtils.ensureDirectory(path + "/tables");
        return "Database '" + name + "' created successfully.";
    }

    private String useDatabase(String name) {
        if (name.isEmpty()) return "Error: Database name required.";
        String path = BASE_DIR + "/" + name;
        if (!new File(path).exists()) {
            return "Error: Database '" + name + "' does not exist. Use CREATE DATABASE first.";
        }
        this.currentDbName = name;
        this.currentDb = new DatabaseAPI(path);
        return "Switched to database '" + name + "'.";
    }

    private String showTables() {
        if (currentDb == null) return "No database selected.";
        try {
            return currentDb.execute("SHOW TABLES");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String showBitmapIndex(String table) {
        if (currentDb == null) return "No database selected.";
        if (table.isEmpty()) return "Error: Table name required.";
        return currentDb.dumpIndex(table);
    }

    private String executeScript(String filePath) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)));
            String[] statements = content.split(";");
            StringBuilder results = new StringBuilder();
            for (String stmt : statements) {
                stmt = stmt.trim();
                if (!stmt.isEmpty()) {
                    results.append("> ").append(stmt).append("\n");
                    results.append(handleCommand(stmt)).append("\n\n");
                }
            }
            return results.toString().trim();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    public static void main(String[] args) {
        new CLIClient().start();
    }
}
