import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

/**
 * 게시판 REST: /api/login, /api/posts/...
 */
public final class BoardApi {

    private static final Pattern JSON_FIELD = Pattern.compile(
            "\"(\\w+)\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|null|true|false|-?\\d+(?:\\.\\d+)?)");

    private BoardApi() {
    }

    public static void handleLogin(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, error("POST only"));
            return;
        }
        try {
            Map<String, String> body = parseJson(readBody(ex));
            String memberId = body.getOrDefault("memberId", "").trim();
            String password = body.getOrDefault("password", "");
            Map<String, String> user = BoardDb.login(memberId, password);
            if (user == null) {
                json(ex, 401, error("아이디 또는 비밀번호가 올바르지 않습니다."));
                return;
            }
            json(ex, 200, "{"
                    + "\"memberId\":" + q(user.get("memberId"))
                    + ",\"nickname\":" + q(user.get("nickname"))
                    + "}");
        } catch (Exception e) {
            json(ex, 500, error(e.getMessage()));
        }
    }

    public static void handlePosts(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if ("/api/posts".equals(path)) {
                if ("GET".equalsIgnoreCase(method)) {
                    listPosts(ex);
                } else if ("POST".equalsIgnoreCase(method)) {
                    createPost(ex);
                } else {
                    json(ex, 405, error("GET or POST only"));
                }
                return;
            }

            // /api/posts/{id}
            // /api/posts/{id}/replies
            // /api/posts/{id}/recommend
            String rest = path.substring("/api/posts/".length());
            String[] parts = rest.split("/");
            if (parts.length == 0 || parts[0].isBlank()) {
                json(ex, 404, error("Not Found"));
                return;
            }
            long postId = Long.parseLong(parts[0]);

            if (parts.length == 1) {
                if ("GET".equalsIgnoreCase(method)) {
                    getPost(ex, postId);
                } else {
                    json(ex, 405, error("GET only"));
                }
                return;
            }

            if (parts.length == 2 && "replies".equals(parts[1])) {
                if ("POST".equalsIgnoreCase(method)) {
                    addReply(ex, postId);
                } else {
                    json(ex, 405, error("POST only"));
                }
                return;
            }

            if (parts.length == 2 && "recommend".equals(parts[1])) {
                if ("POST".equalsIgnoreCase(method)) {
                    toggleRecommend(ex, postId);
                } else {
                    json(ex, 405, error("POST only"));
                }
                return;
            }

            json(ex, 404, error("Not Found"));
        } catch (NumberFormatException e) {
            json(ex, 400, error("잘못된 post id"));
        } catch (IllegalArgumentException e) {
            json(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            json(ex, 500, error(e.getMessage()));
        }
    }

    private static void listPosts(HttpExchange ex) throws Exception {
        String viewer = query(ex.getRequestURI()).getOrDefault("memberId", "");
        List<Map<String, Object>> posts = BoardDb.listPosts(viewer);
        StringBuilder sb = new StringBuilder("{\"posts\":[");
        for (int i = 0; i < posts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(postJson(posts.get(i), false));
        }
        sb.append("]}");
        json(ex, 200, sb.toString());
    }

    private static void getPost(HttpExchange ex, long postId) throws Exception {
        String viewer = query(ex.getRequestURI()).getOrDefault("memberId", "");
        Map<String, Object> post = BoardDb.getPost(postId, viewer);
        if (post == null) {
            json(ex, 404, error("게시글이 없습니다."));
            return;
        }
        json(ex, 200, postJson(post, true));
    }

    private static void createPost(HttpExchange ex) throws Exception {
        Map<String, String> body = parseJson(readBody(ex));
        long id = BoardDb.createPost(
                body.get("memberId"),
                body.get("content"),
                body.get("locationTitle"),
                body.get("address"));
        json(ex, 201, "{\"postId\":" + id + "}");
    }

    private static void addReply(HttpExchange ex, long postId) throws Exception {
        Map<String, String> body = parseJson(readBody(ex));
        long id = BoardDb.addReply(body.get("memberId"), postId, body.get("content"));
        json(ex, 201, "{\"replyId\":" + id + "}");
    }

    private static void toggleRecommend(HttpExchange ex, long postId) throws Exception {
        Map<String, String> body = parseJson(readBody(ex));
        boolean on = BoardDb.toggleRecommend(body.get("memberId"), postId);
        json(ex, 200, "{\"recommended\":" + on + "}");
    }

    @SuppressWarnings("unchecked")
    private static String postJson(Map<String, Object> post, boolean withReplies) {
        StringBuilder sb = new StringBuilder();
        sb.append('{')
                .append("\"postId\":").append(post.get("postId"))
                .append(",\"memberId\":").append(q(str(post.get("memberId"))))
                .append(",\"nickname\":").append(q(str(post.get("nickname"))))
                .append(",\"content\":").append(q(str(post.get("content"))))
                .append(",\"regDate\":").append(q(str(post.get("regDate"))))
                .append(",\"locationId\":").append(post.get("locationId") == null ? "null" : post.get("locationId"))
                .append(",\"locationTitle\":").append(q(str(post.get("locationTitle"))))
                .append(",\"address\":").append(q(str(post.get("address"))))
                .append(",\"imageUrl\":").append(q(str(post.get("imageUrl"))))
                .append(",\"recommendCount\":").append(post.get("recommendCount"))
                .append(",\"replyCount\":").append(post.get("replyCount"))
                .append(",\"recommended\":").append(Boolean.TRUE.equals(post.get("recommended")));
        if (withReplies && post.get("replies") instanceof List<?> replies) {
            sb.append(",\"replies\":[");
            for (int i = 0; i < replies.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Map<String, Object> r = (Map<String, Object>) replies.get(i);
                sb.append('{')
                        .append("\"replyId\":").append(r.get("replyId"))
                        .append(",\"memberId\":").append(q(str(r.get("memberId"))))
                        .append(",\"nickname\":").append(q(str(r.get("nickname"))))
                        .append(",\"content\":").append(q(str(r.get("content"))))
                        .append(",\"regDate\":").append(q(str(r.get("regDate"))))
                        .append('}');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseJson(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        Matcher m = JSON_FIELD.matcher(body);
        while (m.find()) {
            String key = m.group(1);
            String raw = m.group(2);
            if (raw.startsWith("\"")) {
                String v = raw.substring(1, raw.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\");
                map.put(key, v);
            } else if ("null".equals(raw)) {
                map.put(key, null);
            } else {
                map.put(key, raw);
            }
        }
        return map;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> map = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return map;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1)));
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String error(String msg) {
        return "{\"error\":" + q(msg == null ? "unknown" : msg) + "}";
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
