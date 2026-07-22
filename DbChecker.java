import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * MySQL DB 연결 및 테이블 상태 점검.
 * <p>
 * 실행:
 * <pre>
 *   check-db.bat           # 연결 + 테이블 검사
 *   check-db.bat init      # schema.sql 로 테이블 생성 후 검사
 *   check-db.bat show      # 테이블 데이터 조회 (USERS, POSTS)
 *   check-db.bat show USERS # 특정 테이블만 조회
 *   check-db.bat tables    # DB에 있는 모든 테이블 목록·구조
 * </pre>
 * 설정: {@code db.properties} 또는 환경변수 {@code MYSQL_URL}, {@code MYSQL_USER}, {@code MYSQL_PASSWORD}
 */
public class DbChecker {

    private static final Path DB_PROPERTIES = Path.of("db.properties");
    private static final Path SCHEMA_SQL = Path.of("schema.sql");

    /** 검사 대상 테이블과 필수 컬럼 */
    private static final Map<String, List<String>> EXPECTED_TABLES = linkedMap(
            "USERS", List.of("USER_ID", "USERNAME", "PASSWORD", "NICKNAME", "CREATED_AT"),
            "POSTS", List.of(
                    "POST_ID", "CATEGORY", "TITLE", "CONTENT", "THUMBNAIL_URL",
                    "SIDO", "GUNGU", "GEM_SCORE", "FOREIGN_VISITORS", "DOMESTIC_VISITORS", "CREATED_AT"));

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : "";
        boolean init = "init".equalsIgnoreCase(mode);
        boolean show = "show".equalsIgnoreCase(mode);
        boolean tables = "tables".equalsIgnoreCase(mode);
        DbConfig config = DbConfig.load();

        System.out.println(tables ? "=== MySQL DB 테이블 목록 ==="
                : show ? "=== MySQL DB 데이터 조회 ===" : "=== MySQL DB 점검 ===");
        System.out.println("URL    : " + config.jdbcUrlForDisplay());
        System.out.println("사용자 : " + config.username);
        System.out.println();

        Class.forName("com.mysql.cj.jdbc.Driver");

        try (Connection conn = open(config)) {
            if (!show && !tables) {
                printDbInfo(conn);
            }

            if (init) {
                System.out.println("[init] schema.sql 적용 중…");
                runSchema(conn);
                System.out.println();
            }

            if (tables) {
                listAllTables(conn);
                return;
            }

            if (show) {
                if (args.length > 1) {
                    String table = args[1];
                    if (!tableExists(conn, table)) {
                        System.err.println("테이블 없음: " + table);
                        System.exit(1);
                    }
                    printTableData(conn, table, loadColumnNames(conn, table));
                } else {
                    for (String table : listTableNames(conn)) {
                        printTableData(conn, table, loadColumnNames(conn, table));
                        System.out.println();
                    }
                }
                return;
            }

            boolean ok = checkTables(conn);
            System.out.println();
            if (ok) {
                System.out.println("결과: 모든 테이블이 정상입니다.");
            } else {
                System.out.println("결과: 일부 테이블이 없거나 컬럼이 다릅니다.");
                System.out.println("      테이블 생성: check-db.bat init");
                System.exit(1);
            }
        }
    }

    private static Connection open(DbConfig config) throws SQLException {
        System.out.println("연결 시도…");
        Connection conn = DriverManager.getConnection(config.jdbcUrl(), config.username, config.password);
        System.out.println("연결 성공");
        System.out.println();
        return conn;
    }

    private static void printDbInfo(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        System.out.println("DB 제품 : " + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
        System.out.println("드라이버 : " + meta.getDriverName());
        System.out.println();
    }

    private static void runSchema(Connection conn) throws Exception {
        if (!Files.isRegularFile(SCHEMA_SQL)) {
            throw new IOException("schema.sql 파일이 없습니다.");
        }
        String sql = Files.readString(SCHEMA_SQL, StandardCharsets.UTF_8);
        List<String> statements = splitStatements(sql);
        try (Statement st = conn.createStatement()) {
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                try {
                    st.execute(trimmed);
                    System.out.println("  OK  " + firstLine(trimmed));
                } catch (SQLException e) {
                    if (isDuplicateObject(e)) {
                        System.out.println("  SKIP (이미 존재) " + firstLine(trimmed));
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    private static boolean isDuplicateObject(SQLException e) {
        if (e.getErrorCode() == 1050 || e.getErrorCode() == 1061) {
            return true;
        }
        String state = e.getSQLState();
        return "42S01".equals(state) || "42000".equals(state) && e.getMessage() != null
                && e.getMessage().contains("Duplicate");
    }

    private static boolean checkTables(Connection conn) throws SQLException {
        boolean allOk = true;
        Set<String> existing = loadExistingTables(conn);

        System.out.println("--- 테이블 검사 ---");
        for (Map.Entry<String, List<String>> entry : EXPECTED_TABLES.entrySet()) {
            String table = entry.getKey();
            List<String> requiredCols = entry.getValue();

            if (!existing.contains(table)) {
                System.out.println("[FAIL] " + table + " — 테이블 없음");
                allOk = false;
                continue;
            }

            Set<String> actualCols = loadColumns(conn, table);
            List<String> missing = new ArrayList<>();
            for (String col : requiredCols) {
                if (!actualCols.contains(col)) {
                    missing.add(col);
                }
            }

            long rows = countRows(conn, table);
            if (missing.isEmpty()) {
                System.out.println("[OK]   " + table + " — 컬럼 " + actualCols.size()
                        + "개, 데이터 " + rows + "건");
            } else {
                System.out.println("[FAIL] " + table + " — 누락 컬럼: " + String.join(", ", missing));
                allOk = false;
            }
        }

        Set<String> extra = new LinkedHashSet<>(existing);
        extra.removeAll(EXPECTED_TABLES.keySet());
        if (!extra.isEmpty()) {
            System.out.println();
            System.out.println("기타 사용자 테이블: " + String.join(", ", extra));
        }

        return allOk;
    }

    private static Set<String> loadExistingTables(Connection conn) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        String sql = """
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME IN ('USERS', 'POSTS')
                ORDER BY TABLE_NAME
                """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1).toUpperCase());
            }
        }
        return tables;
    }

    private static Set<String> loadColumns(Connection conn, String table) throws SQLException {
        Set<String> cols = new LinkedHashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                cols.add(rs.getString("COLUMN_NAME").toUpperCase());
            }
        }
        return cols;
    }

    private static long countRows(Connection conn, String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM `" + table + "`";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void printTableData(Connection conn, String table, List<String> cols) throws SQLException {
        long total = countRows(conn, table);
        System.out.println("--- " + table + " (" + total + "건) ---");
        if (total == 0) {
            System.out.println("(데이터 없음)");
            return;
        }

        String colList = String.join(", ", cols);
        String sql = "SELECT " + colList + " FROM `" + table + "` ORDER BY 1 LIMIT 50";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int rowNum = 0;
            while (rs.next()) {
                rowNum++;
                System.out.println("[" + rowNum + "]");
                for (String col : cols) {
                    String val = rs.getString(col);
                    if (col.equalsIgnoreCase("password") && val != null) {
                        val = "****";
                    }
                    if (val != null && val.length() > 80) {
                        val = val.substring(0, 77) + "...";
                    }
                    System.out.println("  " + col + " = " + (val == null ? "(null)" : val));
                }
                System.out.println();
            }
            if (total > 50) {
                System.out.println("… 외 " + (total - 50) + "건 더 있음 (최대 50건만 표시)");
            }
        }
    }

    private static void listAllTables(Connection conn) throws SQLException {
        List<String> names = listTableNames(conn);
        System.out.println("총 " + names.size() + "개 테이블\n");
        for (String table : names) {
            long rows = countRows(conn, table);
            List<String> cols = loadColumnNames(conn, table);
            System.out.println("■ " + table + " — " + rows + "건");
            for (String col : cols) {
                System.out.println("    · " + col);
            }
            System.out.println();
        }
    }

    private static List<String> listTableNames(Connection conn) throws SQLException {
        List<String> names = new ArrayList<>();
        String sql = """
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME
                """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static List<String> loadColumnNames(Connection conn, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        String sql = """
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        return cols;
    }

    private static List<String> splitStatements(String sql) {
        List<String> list = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                list.add(current.toString().replace(";", ""));
                current.setLength(0);
            }
        }
        if (current.length() > 0 && !current.toString().isBlank()) {
            list.add(current.toString());
        }
        return list;
    }

    private static String firstLine(String sql) {
        int nl = sql.indexOf('\n');
        String line = nl >= 0 ? sql.substring(0, nl) : sql;
        return line.trim();
    }

    @SafeVarargs
    private static <K, V> Map<K, V> linkedMap(Object... kv) {
        Map<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) kv[i];
            @SuppressWarnings("unchecked")
            V val = (V) kv[i + 1];
            map.put(key, val);
        }
        return map;
    }

    private static final class DbConfig {
        final String jdbcUrl;
        final String username;
        final String password;

        DbConfig(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        String jdbcUrl() {
            return jdbcUrl;
        }

        String jdbcUrlForDisplay() {
            return jdbcUrl.replaceAll("password=[^&]*", "password=***");
        }

        static DbConfig load() throws IOException {
            Properties props = new Properties();
            if (Files.isRegularFile(DB_PROPERTIES)) {
                try (InputStream in = Files.newInputStream(DB_PROPERTIES)) {
                    props.load(in);
                }
            }

            String url = firstNonBlank(
                    System.getenv("MYSQL_URL"),
                    props.getProperty("db.url"),
                    buildUrlFromParts(props));
            String username = firstNonBlank(
                    System.getenv("MYSQL_USER"),
                    props.getProperty("db.username"),
                    "min");
            String password = firstNonBlank(
                    System.getenv("MYSQL_PASSWORD"),
                    props.getProperty("db.password"),
                    "");
            if (password.isBlank()) {
                throw new IOException("비밀번호가 없습니다. db.properties 또는 MYSQL_PASSWORD 를 설정하세요.");
            }
            return new DbConfig(url, username, password);
        }

        private static String buildUrlFromParts(Properties props) {
            String host = firstNonBlank(props.getProperty("db.host"),
                    "database-1.cdy4w44w0vhw.ap-northeast-2.rds.amazonaws.com");
            String port = firstNonBlank(props.getProperty("db.port"), "3306");
            String database = firstNonBlank(props.getProperty("db.database"), "my_project_db");
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=true&serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true";
        }

        private static String firstNonBlank(String... values) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
            return "";
        }
    }
}
