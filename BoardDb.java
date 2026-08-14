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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter REG_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static volatile boolean schemaReady = false;

    private BoardDb() {
    }

    public static Connection open() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        DbConfig cfg = DbConfig.load();
        Connection conn = DriverManager.getConnection(cfg.jdbcUrl, cfg.username, cfg.password);
        ensureSchema(conn);
        return conn;
    }

    /** Post.category 컬럼이 없으면 추가 (내국인/외국인 분리) */
    private static void ensureSchema(Connection conn) {
        if (schemaReady) {
            return;
        }
        synchronized (BoardDb.class) {
            if (schemaReady) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                        "ALTER TABLE Post ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'DOMESTIC'");
            } catch (SQLException e) {
                // 이미 있으면 무시 (MySQL 1060 duplicate column)
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (!(e.getErrorCode() == 1060 || msg.contains("duplicate"))) {
                    System.err.println("Post.category 확인: " + e.getMessage());
                }
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE Member ADD COLUMN profile_image VARCHAR(500)");
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (!(e.getErrorCode() == 1060 || msg.contains("duplicate"))) {
                    System.err.println("Member.profile_image 확인: " + e.getMessage());
                }
            }
            schemaReady = true;
        }
    }

    public static Map<String, String> login(String memberId, String password) throws Exception {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT member_id, nickname, profile_image FROM Member WHERE member_id = ? AND password = ?")) {
            ps.setString(1, memberId);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return memberRow(rs);
            }
        }
    }

    /** 회원가입. 성공 시 memberId·nickname 반환 */
    public static Map<String, String> register(String memberId, String password, String nickname)
            throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("아이디를 입력하세요.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("비밀번호를 입력하세요.");
        }
        String id = memberId.trim();
        String nick = (nickname == null || nickname.isBlank()) ? id : nickname.trim();
        if (id.length() > 40) {
            throw new IllegalArgumentException("아이디는 40자 이내로 입력하세요.");
        }
        try (Connection conn = open()) {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM Member WHERE member_id = ? LIMIT 1")) {
                check.setString(1, id);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO Member (member_id, password, nickname) VALUES (?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, password);
                ps.setString(3, nick);
                ps.executeUpdate();
            }
            Map<String, String> out = new LinkedHashMap<>();
            out.put("memberId", id);
            out.put("nickname", nick);
            out.put("profileImage", "");
            return out;
        }
    }

    public static Map<String, String> getMember(String memberId) throws Exception {
        if (memberId == null || memberId.isBlank()) {
            return null;
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT member_id, nickname, profile_image FROM Member WHERE member_id = ?")) {
            ps.setString(1, memberId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? memberRow(rs) : null;
            }
        }
    }

    public static Map<String, String> updateProfileImage(String memberId, String profileImage)
            throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        String url = profileImage == null ? "" : profileImage.trim();
        if (!url.isEmpty() && !url.startsWith("/uploads/")) {
            throw new IllegalArgumentException("잘못된 이미지 경로입니다.");
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE Member SET profile_image = ? WHERE member_id = ?")) {
            ps.setString(1, url.isEmpty() ? null : url);
            ps.setString(2, memberId.trim());
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("회원을 찾을 수 없습니다.");
            }
        }
        return getMember(memberId);
    }

    private static Map<String, String> memberRow(ResultSet rs) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("memberId", rs.getString("member_id"));
        out.put("nickname", nullToEmpty(rs.getString("nickname")));
        out.put("profileImage", nullToEmpty(rs.getString("profile_image")));
        return out;
    }

    private static final String POST_SELECT = """
            SELECT p.post_id, p.member_id, p.content, p.reg_date, p.category,
                   m.nickname, m.profile_image,
                   l.location_id, l.title AS location_title, l.address, l.image_url,
                   (SELECT COUNT(*) FROM Recommendation r WHERE r.post_id = p.post_id) AS recommend_count,
                   (SELECT COUNT(*) FROM Reply rp WHERE rp.post_id = p.post_id) AS reply_count
            FROM Post p
            JOIN Member m ON m.member_id = p.member_id
            LEFT JOIN Location l ON l.location_id = p.location_id
            """;

    public static List<Map<String, Object>> listPosts(String viewerId, String category) throws Exception {
        return listPosts(viewerId, category, "", "");
    }

    /**
     * @param sido 도/시 필터 (address 컬럼에 저장된 시·도, 빈 문자열이면 전체)
     * @param q 장소명(location title) 부분 검색
     */
    public static List<Map<String, Object>> listPosts(
            String viewerId, String category, String sido, String q) throws Exception {
        String cat = normalizeCategory(category);
        String sidoFilter = sido == null ? "" : sido.trim();
        String query = q == null ? "" : q.trim();

        StringBuilder sql = new StringBuilder(POST_SELECT);
        sql.append(" WHERE p.category = ? ");
        if (!sidoFilter.isEmpty()) {
            sql.append(" AND (")
                    .append("COALESCE(l.address, '') = ? OR COALESCE(l.address, '') LIKE ? ")
                    .append("OR COALESCE(l.address, '') LIKE ?")
                    .append(") ");
        }
        if (!query.isEmpty()) {
            sql.append(" AND COALESCE(l.title, '') LIKE ? ");
        }
        sql.append(" ORDER BY COALESCE(l.address, ''), p.reg_date DESC, p.post_id DESC ");

        return queryPosts(sql.toString(), viewerId, ps -> {
            int i = 1;
            ps.setString(i++, cat);
            if (!sidoFilter.isEmpty()) {
                ps.setString(i++, sidoFilter);
                ps.setString(i++, sidoFilter + "%");
                ps.setString(i++, "%" + sidoFilter + "%");
            }
            if (!query.isEmpty()) {
                ps.setString(i, "%" + query + "%");
            }
        });
    }

    /** 특정 회원이 쓴 글 */
    public static List<Map<String, Object>> listPostsByAuthor(String viewerId, String authorId) throws Exception {
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("작성자 아이디가 필요합니다.");
        }
        String sql = POST_SELECT + """
                WHERE p.member_id = ?
                ORDER BY p.reg_date DESC, p.post_id DESC
                """;
        return queryPosts(sql, viewerId, ps -> ps.setString(1, authorId.trim()));
    }

    /** 특정 회원이 추천한 글 */
    public static List<Map<String, Object>> listRecommendedPosts(String viewerId, String likerId) throws Exception {
        if (likerId == null || likerId.isBlank()) {
            throw new IllegalArgumentException("회원 아이디가 필요합니다.");
        }
        String sql = POST_SELECT + """
                JOIN Recommendation rec ON rec.post_id = p.post_id
                WHERE rec.member_id = ?
                ORDER BY p.reg_date DESC, p.post_id DESC
                """;
        return queryPosts(sql, viewerId, ps -> ps.setString(1, likerId.trim()));
    }

    @FunctionalInterface
    private interface PsBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<Map<String, Object>> queryPosts(String sql, String viewerId, PsBinder binder)
            throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = postRow(rs);
                    long postId = ((Number) row.get("postId")).longValue();
                    row.put("recommended", viewerId != null && !viewerId.isBlank()
                            && hasRecommendation(conn, viewerId, postId));
                    list.add(row);
                }
            }
        }
        return list;
    }

    public static Map<String, Object> getPost(long postId, String viewerId) throws Exception {
        String sql = POST_SELECT + " WHERE p.post_id = ? ";
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

    public static long createPost(
            String memberId,
            String content,
            String locationTitle,
            String address,
            String category,
            String imageUrl)
            throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("내용을 입력하세요.");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("도/시를 선택하세요.");
        }
        if (locationTitle == null || locationTitle.isBlank()) {
            throw new IllegalArgumentException("장소명을 입력하세요.");
        }
        String cat = normalizeCategory(category);
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                ensureMember(conn, memberId);
                String title = locationTitle.trim();
                long locationId = insertLocation(
                        conn,
                        title,
                        address.trim(),
                        imageUrl == null ? "" : imageUrl.trim());
                long postId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO Post (member_id, location_id, content, category) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, memberId);
                    ps.setLong(2, locationId);
                    ps.setString(3, content.trim());
                    ps.setString(4, cat);
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

    /** 본인 글만 수정. imageUrl이 null이면 기존 사진 유지, 빈 문자열이면 사진 제거 */
    public static void updatePost(
            long postId,
            String memberId,
            String content,
            String locationTitle,
            String address,
            String category,
            String imageUrl)
            throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("내용을 입력하세요.");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("도/시를 선택하세요.");
        }
        if (locationTitle == null || locationTitle.isBlank()) {
            throw new IllegalArgumentException("장소명을 입력하세요.");
        }
        String cat = normalizeCategory(category);
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                ensureMember(conn, memberId);
                Long locationId = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT member_id, location_id FROM Post WHERE post_id = ?")) {
                    ps.setLong(1, postId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("게시글이 없습니다.");
                        }
                        if (!memberId.equals(rs.getString("member_id"))) {
                            throw new IllegalArgumentException("본인 글만 수정할 수 있습니다.");
                        }
                        long loc = rs.getLong("location_id");
                        if (!rs.wasNull()) {
                            locationId = loc;
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE Post SET content = ?, category = ? WHERE post_id = ?")) {
                    ps.setString(1, content.trim());
                    ps.setString(2, cat);
                    ps.setLong(3, postId);
                    ps.executeUpdate();
                }
                if (locationId != null) {
                    String title = locationTitle.trim();
                    String addr = address.trim();
                    if (imageUrl == null) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE Location SET title = ?, address = ? WHERE location_id = ?")) {
                            ps.setString(1, title);
                            ps.setString(2, addr);
                            ps.setLong(3, locationId);
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE Location SET title = ?, address = ?, image_url = ? WHERE location_id = ?")) {
                            ps.setString(1, title);
                            ps.setString(2, addr);
                            ps.setString(3, imageUrl.isBlank() ? null : imageUrl.trim());
                            ps.setLong(4, locationId);
                            ps.executeUpdate();
                        }
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 본인 글만 삭제 (댓글·추천 포함) */
    public static void deletePost(long postId, String memberId) throws Exception {
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT member_id FROM Post WHERE post_id = ?")) {
                    ps.setLong(1, postId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("게시글이 없습니다.");
                        }
                        if (!memberId.equals(rs.getString("member_id"))) {
                            throw new IllegalArgumentException("본인 글만 삭제할 수 있습니다.");
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM Recommendation WHERE post_id = ?")) {
                    ps.setLong(1, postId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM Reply WHERE post_id = ?")) {
                    ps.setLong(1, postId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM Post WHERE post_id = ? AND member_id = ?")) {
                    ps.setLong(1, postId);
                    ps.setString(2, memberId);
                    int n = ps.executeUpdate();
                    if (n == 0) {
                        throw new IllegalArgumentException("삭제에 실패했습니다.");
                    }
                }
                conn.commit();
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
                    row.put("regDate", formatRegDate(rs, "reg_date"));
                    row.put("regAt", readRegAtMillis(rs, "reg_date"));
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
        row.put("profileImage", nullToEmpty(rs.getString("profile_image")));
        row.put("content", nullToEmpty(rs.getString("content")));
        row.put("category", normalizeCategory(rs.getString("category")));
        row.put("regDate", formatRegDate(rs, "reg_date"));
        row.put("regAt", readRegAtMillis(rs, "reg_date"));
        long locId = rs.getLong("location_id");
        row.put("locationId", rs.wasNull() ? null : locId);
        row.put("locationTitle", nullToEmpty(rs.getString("location_title")));
        row.put("address", nullToEmpty(rs.getString("address")));
        row.put("imageUrl", nullToEmpty(rs.getString("image_url")));
        row.put("recommendCount", rs.getLong("recommend_count"));
        row.put("replyCount", rs.getLong("reply_count"));
        return row;
    }

    /**
     * RDS DATETIME은 UTC 벽시계로 저장됨(세션 time_zone=UTC).
     * getTimestamp()+Asia/Seoul 해석하면 9시간 밀리므로 문자열을 UTC Instant로 읽는다.
     */
    private static Instant readRegInstant(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.length() >= 19) {
            s = s.substring(0, 19);
        }
        LocalDateTime ldt = LocalDateTime.parse(s.replace(' ', 'T'));
        return ldt.toInstant(ZoneOffset.UTC);
    }

    private static Long readRegAtMillis(ResultSet rs, String column) throws SQLException {
        Instant instant = readRegInstant(rs, column);
        return instant == null ? null : instant.toEpochMilli();
    }

    /** 한국시간(+09:00) 표시용 */
    private static String formatRegDate(ResultSet rs, String column) throws SQLException {
        Instant instant = readRegInstant(rs, column);
        if (instant == null) {
            return "";
        }
        return instant.atZone(KST).format(REG_DATE_FMT);
    }

    private static String normalizeCategory(String category) {
        if (category != null && "FOREIGN".equalsIgnoreCase(category.trim())) {
            return "FOREIGN";
        }
        return "DOMESTIC";
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

    private static long insertLocation(Connection conn, String title, String address, String imageUrl)
            throws SQLException {
        long nextId;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(location_id), 0) + 1 AS next_id FROM Location")) {
            rs.next();
            nextId = rs.getLong("next_id");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO Location (location_id, title, address, tel, image_url) VALUES (?, ?, ?, NULL, ?)")) {
            ps.setLong(1, nextId);
            ps.setString(2, title);
            ps.setString(3, address.isBlank() ? null : address);
            ps.setString(4, imageUrl.isBlank() ? null : imageUrl);
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
