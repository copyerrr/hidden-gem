import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** DeepL 번역 (db.properties deepl.api.key). Free 키(:fx)는 api-free.deepl.com */
public final class DeepLClient {

    private static final Path PROPS = Path.of("db.properties");
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile String apiKey;
    private static volatile boolean keyLoaded;

    private DeepLClient() {
    }

    public static boolean isConfigured() {
        ensureKey();
        return apiKey != null && !apiKey.isBlank();
    }

    public static List<String> translate(List<String> texts, String targetLang) throws Exception {
        ensureKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("deepl.api.key 가 설정되지 않았습니다.");
        }
        String target = normalizeTarget(targetLang);
        List<String> out = new ArrayList<>(texts.size());
        List<Integer> missIdx = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i) == null ? "" : texts.get(i);
            if (t.isBlank()) {
                out.add(t);
                continue;
            }
            String cached = CACHE.get(cacheKey(target, t));
            if (cached != null) {
                out.add(cached);
            } else {
                out.add(null);
                missIdx.add(i);
                missTexts.add(t);
            }
        }

        if (!missTexts.isEmpty()) {
            List<String> translated = callDeepL(missTexts, target);
            for (int j = 0; j < missIdx.size(); j++) {
                String src = missTexts.get(j);
                String dst = j < translated.size() ? translated.get(j) : src;
                CACHE.put(cacheKey(target, src), dst);
                out.set(missIdx.get(j), dst);
            }
        }
        return out;
    }

    private static List<String> callDeepL(List<String> texts, String target) throws Exception {
        String host = apiKey.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
        StringBuilder json = new StringBuilder("{\"text\":[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) json.append(',');
            json.append(q(texts.get(i)));
        }
        json.append("],\"target_lang\":").append(q(target)).append('}');

        HttpURLConnection conn = (HttpURLConnection) URI.create(host + "/v2/translate").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(30_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "HiddenGem/1.0");
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("DeepL HTTP " + code + ": " + resp);
        }
        return parseTranslations(resp, texts.size());
    }

    private static List<String> parseTranslations(String resp, int expected) {
        List<String> out = new ArrayList<>();
        int idx = 0;
        while (out.size() < expected) {
            int key = resp.indexOf("\"text\"", idx);
            if (key < 0) break;
            int colon = resp.indexOf(':', key);
            int q1 = resp.indexOf('"', colon + 1);
            if (q1 < 0) break;
            StringBuilder sb = new StringBuilder();
            for (int i = q1 + 1; i < resp.length(); i++) {
                char c = resp.charAt(i);
                if (c == '\\' && i + 1 < resp.length()) {
                    char n = resp.charAt(++i);
                    if (n == 'n') sb.append('\n');
                    else if (n == 't') sb.append('\t');
                    else if (n == 'r') sb.append('\r');
                    else if (n == '"') sb.append('"');
                    else if (n == '\\') sb.append('\\');
                    else if (n == 'u' && i + 4 < resp.length()) {
                        sb.append((char) Integer.parseInt(resp.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else sb.append(n);
                } else if (c == '"') {
                    idx = i + 1;
                    break;
                } else {
                    sb.append(c);
                }
            }
            out.add(sb.toString());
        }
        while (out.size() < expected) {
            out.add("");
        }
        return out;
    }

    private static void ensureKey() {
        if (keyLoaded) return;
        synchronized (DeepLClient.class) {
            if (keyLoaded) return;
            try {
                Properties props = new Properties();
                if (Files.isRegularFile(PROPS)) {
                    try (InputStream in = Files.newInputStream(PROPS)) {
                        props.load(in);
                    }
                }
                String env = System.getenv("DEEPL_API_KEY");
                apiKey = (env != null && !env.isBlank())
                        ? env.trim()
                        : trim(props.getProperty("deepl.api.key"));
            } catch (Exception e) {
                apiKey = null;
            }
            keyLoaded = true;
        }
    }

    private static String normalizeTarget(String targetLang) {
        if (targetLang == null || targetLang.isBlank()) return "EN";
        String t = targetLang.trim().toUpperCase();
        if (t.startsWith("EN")) return "EN";
        if (t.startsWith("KO") || t.equals("KR")) return "KO";
        if (t.startsWith("JA")) return "JA";
        if (t.startsWith("ZH")) return "ZH";
        return t.length() > 5 ? t.substring(0, 5) : t;
    }

    private static String cacheKey(String target, String text) {
        return target + "|" + text;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String q(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }
}