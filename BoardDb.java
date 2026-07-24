import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Member / Post / Reply / Recommendation / Location JDBC 접근.
 */
public final class BoardDb {

    private static final Path DB_PROPERTIES = Path.of("db.properties");

    private BoardDb() {
    }

    public static Connection open() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        DbConfig cfg = DbConfig.load();
        return DriverManager.getConnection(cfg.jdbcUrl, cfg.username, cfg.password);
    }

    public static Map<String, String> login(String memberId, String password) throws Exception {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT member_id, nickname FROM Member WHERE member_id = ? AND password = ?")) {
            ps.setString(1, memberId);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> out = new LinkedHashMap<>();
                out.put("memberId", rs.getString("member_id"));
                out.put("nickname", nullToEmpty(rs.getString("nickname")));
                return out;
            }
        }
    }

    public static List<Map<String, Object>> listPosts(String viewerId) throws Exception {
        String sql = """
                SELECT p.post_id, p.member_id, p.content, p.reg_date,
                       m.nickname,
                       l.location_id, l.title AS location_title, l.address, l.image_url,
                       (SELECT COUNT(*) FROM Recommendation r WHERE r.post_id = p.post_id) AS recommend_count,
                       (SELECT COUNT(*) FROM Reply rp WHERE rp.post_id = p.post_id) AS reply_count
                FROM Post p
                JOIN Member m ON m.member_id = p.member_id
                LEFT JOIN Location l ON l.location_id = p.location_id
                ORDER BY p.reg_date DESC, p.post_id DESC
                """;
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = postRow(rs);
                long postId = ((Number) row.get("postId")).longValue();
                row.put("recommended", viewerId != null && !viewerId.isBlank()
                        && hasRecommendation(conn, viewerId, postId));
                list.add(row);
            }
        }
        return list;
    }

    public static Map<String, Object> getPost(long postId, String viewerId) throws Exception {
        String sql = """
                SELECT p.post_id, p.member_id, p.content, p.reg_date,
                       m.nickname,
                       l.location_id, l.title AS location_title, l.address, l.image_url,
                       (SELECT COUNT(*) FROM Recommendation r WHERE r.post_id = p.post_id) AS recommend_count,
                       (SELECT COUNT(*) FROM Reply rp WHERE rp.post_id = p.post_id) AS reply_count
                FROM Post p
                JOIN Member m ON m.member_id = p.member_id
                LEFT JOIN Location l ON l.location_id = p.location_id
                WHERE p.post_id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = postRow(rs);
                row.put("recommended", viewerId != null && !viewerId.isBlank()
                        && hasRecommendation(conn, viewerId, postId));
                row.put("replies", listReplies(conn, postId));
                return row;
            }
        }
    }

    public static long createPost(String memberId, String content, String locationTitle, String address)
            throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("memberId가 필요합니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("내용을 입력하세요.");
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                ensureMember(conn, memberId);
                String title = (locationTitle == null || locationTitle.isBlank())
                        ? "장소 미정" : locationTitle.trim();
                long locationId = insertLocation(conn, title,
                        address == null ? "" : address.trim());
                long postId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO Post (member_id, location_id, content) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, memberId);
                    ps.setLong(2, locationId);
                    ps.setString(3, content.trim());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("post_id 생성 실패");
                        }
                        postId = keys.getLong(1);
                    }
                }
                conn.commit();
                return postId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public static boolean toggleRecommend(String memberId, long postId) throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        try (Connection conn = open()) {
            ensureMember(conn, memberId);
            ensurePost(conn, postId);
            if (hasRecommendation(conn, memberId, postId)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM Recommendation WHERE member_id = ? AND post_id = ?")) {
                    ps.setString(1, memberId);
                    ps.setLong(2, postId);
                    ps.executeUpdate();
                }
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO Recommendation (member_id, post_id) VALUES (?, ?)")) {
                ps.setString(1, memberId);
                ps.setLong(2, postId);
                ps.executeUpdate();
            }
            return true;
        }
    }

    public static long addReply(String memberId, long postId, String content) throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("댓글을 입력하세요.");
        }
        try (Connection conn = open()) {
            ensureMember(conn, memberId);
            ensurePost(conn, postId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO Reply (post_id, member_id, reply_content) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, postId);
                ps.setString(2, memberId);
                ps.setString(3, content.trim());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("reply_id 생성 실패");
                    }
                    return keys.getLong(1);
                }
            }
        }
    }

    private static List<Map<String, Object>> listReplies(Connection conn, long postId) throws SQLException {
        String sql = """
                SELECT r.reply_id, r.post_id, r.member_id, r.reply_content, r.reg_date, m.nickname
                FROM Reply r
                JOIN Member m ON m.member_id = r.member_id
                WHERE r.post_id = ?
                ORDER BY r.reg_date ASC, r.reply_id ASC
                """;
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("replyId", rs.getLong("reply_id"));
                    row.put("postId", rs.getLong("post_id"));
                    row.put("memberId", rs.getString("member_id"));
                    row.put("nickname", nullToEmpty(rs.getString("nickname")));
                    row.put("content", nullToEmpty(rs.getString("reply_content")));
                    row.put("regDate", String.valueOf(rs.getTimestamp("reg_date")));
                    list.add(row);
                }
            }
        }
        return list;
    }

    private static Map<String, Object> postRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("postId", rs.getLong("post_id"));
        row.put("memberId", rs.getString("member_id"));
        row.put("nickname", nullToEmpty(rs.getString("nickname")));
        row.put("content", nullToEmpty(rs.getString("content")));
        row.put("regDate", String.valueOf(rs.getTimestamp("reg_date")));
        long locId = rs.getLong("location_id");
        row.put("locationId", rs.wasNull() ? null : locId);
        row.put("locationTitle", nullToEmpty(rs.getString("location_title")));
        row.put("address", nullToEmpty(rs.getString("address")));
        row.put("imageUrl", nullToEmpty(rs.getString("image_url")));
        row.put("recommendCount", rs.getLong("recommend_count"));
        row.put("replyCount", rs.getLong("reply_count"));
        return row;
    }

    private static boolean hasRecommendation(Connection conn, String memberId, long postId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM Recommendation WHERE member_id = ? AND post_id = ? LIMIT 1")) {
            ps.setString(1, memberId);
            ps.setLong(2, postId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureMember(Connection conn, String memberId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM Member WHERE member_id = ? LIMIT 1")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("존재하지 않는 회원입니다: " + memberId);
                }
            }
        }
    }

    private static void ensurePost(Connection conn, long postId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM Post WHERE post_id = ? LIMIT 1")) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("게시글이 없습니다: " + postId);
                }
            }
        }
    }

    private static long insertLocation(Connection conn, String title, String address) throws SQLException {
        long nextId;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(location_id), 0) + 1 AS next_id FROM Location")) {
            rs.next();
            nextId = rs.getLong("next_id");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO Location (location_id, title, address, tel, image_url) VALUES (?, ?, ?, NULL, NULL)")) {
            ps.setLong(1, nextId);
            ps.setString(2, title);
            ps.setString(3, address.isBlank() ? null : address);
            ps.executeUpdate();
            return nextId;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
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

        static DbConfig load() throws IOException {
            Properties props = new Properties();
            if (Files.isRegularFile(DB_PROPERTIES)) {
                try (InputStream in = Files.newInputStream(DB_PROPERTIES)) {
                    props.load(in);
                }
            }
            String url = firstNonBlank(System.getenv("MYSQL_URL"), props.getProperty("db.url"), "");
            String username = firstNonBlank(System.getenv("MYSQL_USER"), props.getProperty("db.username"), "min");
            String password = firstNonBlank(System.getenv("MYSQL_PASSWORD"), props.getProperty("db.password"), "");
            if (url.isBlank() || password.isBlank()) {
                throw new IOException("db.properties 또는 MYSQL_* 환경변수를 설정하세요.");
            }
            return new DbConfig(url, username, password);
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
