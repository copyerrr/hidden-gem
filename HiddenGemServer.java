import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 웹 UI + REST API — 외국인에게 상대적으로 알려졌지만 내국인에게는 덜 알려진 유료관광지(히든젬) 탐색.
 * <p>
 * 점수: {@code ln(1+foreign) × (domesticRank − foreignRank)}<br>
 * 필터: 숙박·레저 키워드 제외, foreign≥100, domesticRank&gt;50, foreignRank&gt;15
 * <p>
 * 데이터: openapi.tour.go.kr 유료관광지방문객수조회
 * 실행: {@code start.bat} 또는 {@code start.sh}
 * 브라우저: {@code http://localhost:8080}
 * <p>
 * 게시판 DB: {@code /api/login}, {@code /api/register}, {@code /api/upload}, {@code /api/posts}
 */
public class HiddenGemServer {

    private static final int PORT = 8080;
    private static final Path WEB_ROOT = Path.of("web");
    /** 표본이 너무 적은 곳 제외 */
    private static final double MIN_FOREIGN_VISITORS = 100;
    /** 내국인에게 이미 유명한 곳 제외 (순위 1=최다) */
    private static final int MIN_DOMESTIC_RANK = 50;
    /** 외국인 메가 관광지 제외 */
    private static final int MIN_FOREIGN_RANK = 15;
    private static final Pattern BLOCKED_NAME = Pattern.compile(
            "리조트|호텔|워터파크|케리비안|스키장|골프|콘도|모텔|펜션|스파플러스|한솔오크|플레이도시|카지노");
    /**
     * TourAPI 검색용 별칭. key는 {@link #compactName(String)} 기준.
     * 통계 명칭 ↔ 관광정보 명칭이 다른 경우 (복수 후보 가능).
     */
    private static final Map<String, List<String>> THUMB_ALIASES = Map.ofEntries(
            Map.entry("우방타워랜드", List.of("이월드")),
            Map.entry("트릭아이미술관", List.of("트릭아트", "트릭아이")),
            Map.entry("트릭아이", List.of("트릭아트")),
            Map.entry("선비문화수련원", List.of("한국선비문화수련원", "선비촌")),
            Map.entry("해녀박물관", List.of("제주해녀박물관", "해녀박물관")),
            Map.entry("노보텔부산", List.of("노보텔 부산", "노보텔 앰배서더 부산")),
            Map.entry("헌릉인릉", List.of("헌릉", "인릉")),
            Map.entry("용인대장금파크", List.of("대장금파크", "용인대장금파크", "MBC드라마서커스용인대장금파크")),
            Map.entry("대장금파크", List.of("대장금파크", "용인대장금파크")),
            Map.entry("구리시고구려대장간마을", List.of("고구려대장간마을")),
            Map.entry("고구려대장간마을", List.of("고구려대장간마을")),
            Map.entry("태릉강릉조선왕릉전시관", List.of("태릉", "강릉", "조선왕릉")),
            // '정강원'만 쓰면 정강원 관광농원 등으로 오매칭됨 — 공식명 위주
            Map.entry("한국전통음식문화체험관", List.of("한국전통음식문화체험관")),
            Map.entry("골드힐카운티", List.of("골드힐")),
            Map.entry("설악파크", List.of("설악파크")),
            Map.entry("달곁에별", List.of("달곁에별")));
    private static final String PAID_VISITOR_URL =
            "http://openapi.tour.go.kr/openapi/service/TourismResourceStatsService/getPchrgTrrsrtVisitorList";
    private static final String KOR_SERVICE_BASE = "https://apis.data.go.kr/B551011/KorService2";
    private static final Map<String, String> GEM_CACHE = new HashMap<>();
    private static final Map<String, List<Attraction>> YM_DATA_CACHE = new HashMap<>();
    /** 썸네일/상세 검색 키워드 최대 개수 */
    private static final int MAX_THUMB_KEYWORDS = 12;
    private static final Map<String, String> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> PLACE_DETAIL_CACHE = new ConcurrentHashMap<>();
    /** 썸네일 검색 시 확보한 contentId — 상세에서 재검색 생략용 */
    private static final Map<String, SearchHit> CONTENT_HIT_CACHE = new ConcurrentHashMap<>();
    /** KorService2 호출 횟수 (벤치마크용) */
    private static final Map<String, java.util.concurrent.atomic.AtomicInteger> KOR_TRAFFIC =
            new ConcurrentHashMap<>();
    /** 일일 호출 한도 초과 시 true — 자정 리셋 전까지 사진/상세 enrichment 불가 */
    private static final java.util.concurrent.atomic.AtomicBoolean KOR_API_LIMITED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 한 번에 전체 1260건을 받으면 Read timed out 나므로 페이지 단위로 받음 */
    private static final int API_PAGE_SIZE = 100;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "traffic".equalsIgnoreCase(args[0])) {
            int n = args.length > 1 ? Integer.parseInt(args[1]) : 10;
            runTrafficCompare(n);
            return;
        }
        if (!Files.isDirectory(WEB_ROOT)) {
            System.err.println("web/ 폴더가 없습니다. 프로젝트 루트에서 실행하세요.");
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", HiddenGemServer::handleStatic);
        server.createContext("/api/hidden-gems", HiddenGemServer::handleHiddenGems);
        server.createContext("/api/thumbnails", HiddenGemServer::handleThumbnails);
        server.createContext("/api/place-detail", HiddenGemServer::handlePlaceDetail);
        server.createContext("/api/regions", HiddenGemServer::handleRegions);
        server.createContext("/api/config", HiddenGemServer::handlePublicConfig);
        server.createContext("/api/login", BoardApi::handleLogin);
        server.createContext("/api/register", BoardApi::handleRegister);
        server.createContext("/api/profile", BoardApi::handleProfile);
        server.createContext("/api/translate", BoardApi::handleTranslate);
        server.createContext("/api/upload", BoardApi::handleUpload);
        server.createContext("/uploads", BoardApi::handleUploads);
        server.createContext("/api/posts", BoardApi::handlePosts);
        server.createContext("/api/traffic", HiddenGemServer::handleTraffic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("히든젬 서버 시작 → http://localhost:" + PORT);
        System.out.println("KorService2 트래픽 조회 → http://localhost:" + PORT + "/api/traffic");
        try {
            try (var ignored = BoardDb.open()) {
                System.out.println("DB 연결 OK (게시판 API 활성)");
            }
        } catch (Exception e) {
            System.err.println("DB 연결 실패 — 게시판 API는 오류를 반환합니다: " + e.getMessage());
        }
        warmCacheAsync();
    }

    /** 서버 기동 직후 백그라운드에서 기본 데이터를 미리 받아 둡니다. */
    private static void warmCacheAsync() {
        ExecutorService bg = Executors.newSingleThreadExecutor();
        bg.execute(() -> {
            try {
                System.out.println("백그라운드: 관광지 데이터 미리 로딩…");
                String ym = defaultYm();
                loadYmData(ym);
                buildHiddenGemsJson(ym, "", "", 30);
                System.out.println("백그라운드: 데이터 준비 완료");
            } catch (Exception e) {
                System.err.println("백그라운드 로딩 실패: " + e.getMessage());
            }
        });
        bg.shutdown();
    }

    /** 브라우저용 공개 키만. GPS 좌표는 받지 않음. */
    private static void handlePublicConfig(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json; charset=utf-8", jsonError("GET only").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String kakaoJs = readProp("kakao.js.key", System.getenv("KAKAO_JS_KEY"));
        String odsay = readProp("odsay.api.key", System.getenv("ODSAY_API_KEY"));
        String body = "{\"kakaoJsKey\":" + q(kakaoJs == null ? "" : kakaoJs.trim())
                + ",\"odsayApiKey\":" + q(odsay == null ? "" : odsay.trim()) + "}";
        respond(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static String readProp(String key, String envFallback) {
        try {
            Path p = Path.of("db.properties");
            if (Files.isRegularFile(p)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                }
                String v = props.getProperty(key);
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (envFallback != null && !envFallback.isBlank()) {
            return envFallback.trim();
        }
        return "";
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        Path file = WEB_ROOT.resolve(path.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(WEB_ROOT) || !Files.isRegularFile(file)) {
            if (path.startsWith("/api/")) {
                respond(ex, 404, "application/json; charset=utf-8",
                        jsonError("Not Found").getBytes(StandardCharsets.UTF_8));
            } else {
                respond(ex, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        String ct = contentType(file.getFileName().toString());
        respond(ex, 200, ct, Files.readAllBytes(file));
    }

    private static void handleThumbnails(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json; charset=utf-8", jsonError("POST only").getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String json = buildThumbnailsJson(parseThumbnailLines(body));
            respond(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8", jsonError(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void handlePlaceDetail(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json; charset=utf-8", jsonError("GET only").getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            Map<String, String> qmap = query(ex.getRequestURI());
            String contentId = qmap.getOrDefault("contentId", "").trim();
            String resNm = qmap.getOrDefault("resNm", "").trim();
            String sido = qmap.getOrDefault("sido", "").trim();
            String gungu = qmap.getOrDefault("gungu", "").trim();
            if (contentId.isBlank() && resNm.isBlank()) {
                respond(ex, 400, "application/json; charset=utf-8",
                        jsonError("resNm or contentId required").getBytes(StandardCharsets.UTF_8));
                return;
            }
            String cacheKey = !contentId.isBlank()
                    ? "cid:" + contentId
                    : resNm + "|" + sido + "|" + gungu;
            String body = PLACE_DETAIL_CACHE.get(cacheKey);
            if (body == null) {
                body = !contentId.isBlank()
                        ? buildPlaceDetailByContentId(contentId, resNm)
                        : buildPlaceDetailJson(resNm, sido, gungu);
                // 한도 초과 실패는 캐시하지 않음 (한도 해제 후 재시도 가능)
                if (body == null || !body.contains("\"apiLimited\":true")) {
                    PLACE_DETAIL_CACHE.put(cacheKey, body);
                }
            }
            respond(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8",
                    jsonError(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void handleTraffic(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            Map<String, String> q = query(ex.getRequestURI());
            if ("1".equals(q.get("reset")) || "true".equalsIgnoreCase(q.get("reset"))) {
                resetKorTrafficCounterOnly();
            }
            respond(ex, 200, "application/json; charset=utf-8",
                    "{\"ok\":true,\"total\":0}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json; charset=utf-8", jsonError("GET or POST").getBytes(StandardCharsets.UTF_8));
            return;
        }
        StringBuilder sb = new StringBuilder("{\"total\":").append(korTotal())
                .append(",\"apiLimited\":").append(KOR_API_LIMITED.get())
                .append(",\"ops\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : snapshotTraffic().entrySet()) {
            if ("TOTAL".equals(e.getKey())) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(q(e.getKey())).append(':').append(e.getValue());
        }
        sb.append("}}");
        respond(ex, 200, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void handleRegions(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json; charset=utf-8", jsonError("GET only").getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            Map<String, String> q = query(ex.getRequestURI());
            String ym = q.getOrDefault("ym", defaultYm());
            String body = buildRegionsJson(ym);
            respond(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8", jsonError(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void handleHiddenGems(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json; charset=utf-8", jsonError("GET only").getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            Map<String, String> q = query(ex.getRequestURI());
            String ym = q.getOrDefault("ym", defaultYm());
            String sido = q.getOrDefault("sido", "").trim();
            String gungu = q.getOrDefault("gungu", "").trim();
            int limit = Math.min(Math.max(parseInt(q.get("limit"), 30), 1), 100);

            String cacheKey = ym + ":" + sido + ":" + gungu + ":" + limit;
            String body = GEM_CACHE.get(cacheKey);
            if (body == null) {
                body = buildHiddenGemsJson(ym, sido, gungu, limit);
                GEM_CACHE.put(cacheKey, body);
            }
            respond(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8", jsonError(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildRegionsJson(String ym) throws Exception {
        List<Attraction> all = loadYmData(ym);
        Map<String, Set<String>> gunguBySido = new LinkedHashMap<>();
        for (Attraction a : all) {
            if (a.sido.isBlank()) {
                continue;
            }
            gunguBySido.computeIfAbsent(a.sido, k -> new TreeSet<>());
            if (!a.gungu.isBlank()) {
                gunguBySido.get(a.sido).add(a.gungu);
            }
        }
        List<String> sidoList = new ArrayList<>(new TreeSet<>(gunguBySido.keySet()));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ym\":").append(q(ym))
                .append(",\"totalAttractions\":").append(all.size())
                .append(",\"sido\":[");
        for (int i = 0; i < sidoList.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(q(sidoList.get(i)));
        }
        sb.append("],\"gunguBySido\":{");
        boolean firstSido = true;
        for (String sido : sidoList) {
            if (!firstSido) {
                sb.append(',');
            }
            firstSido = false;
            sb.append(q(sido)).append(":[");
            List<String> gunguList = new ArrayList<>(gunguBySido.getOrDefault(sido, Set.of()));
            for (int i = 0; i < gunguList.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(q(gunguList.get(i)));
            }
            sb.append(']');
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String buildHiddenGemsJson(String ym, String sido, String gungu, int limit) throws Exception {
        List<Attraction> all = loadYmData(ym);
        // 순위·점수는 전국 기준으로 유지한 뒤, 지역 필터는 결과만 자른다.
        Map<String, Integer> foreignRank = rankMap(all, a -> a.foreign);
        Map<String, Integer> domesticRank = rankMap(all, a -> a.domestic);

        List<HiddenGem> gems = new ArrayList<>();
        for (Attraction a : all) {
            if (a.foreign < MIN_FOREIGN_VISITORS || a.domestic <= 0) {
                continue;
            }
            if (isBlockedName(a.resNm)) {
                continue;
            }
            int fRank = foreignRank.getOrDefault(a.key(), all.size());
            int dRank = domesticRank.getOrDefault(a.key(), all.size());
            if (dRank <= MIN_DOMESTIC_RANK || fRank <= MIN_FOREIGN_RANK) {
                continue;
            }
            int gap = dRank - fRank;
            if (gap <= 0) {
                continue;
            }
            double share = a.foreign / (a.foreign + a.domestic) * 100.0;
            double score = Math.log(1.0 + a.foreign) * gap;
            gems.add(new HiddenGem(a, share, dRank, fRank, score));
        }
        gems.sort(Comparator
                .comparingDouble((HiddenGem g) -> g.gemScore).reversed()
                .thenComparing(Comparator.comparingDouble((HiddenGem g) -> g.foreignShare).reversed()));

        if (!sido.isBlank() || !gungu.isBlank()) {
            gems.removeIf(g -> {
                if (!sido.isBlank() && !sido.equals(g.sido)) {
                    return true;
                }
                return !gungu.isBlank() && !gungu.equals(g.gungu);
            });
        }

        List<Attraction> scoped = filterAttractions(all, sido, gungu);
        if (gems.size() > limit) {
            gems = new ArrayList<>(gems.subList(0, limit));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ym\":").append(q(ym))
                .append(",\"sido\":").append(sido.isEmpty() ? "null" : q(sido))
                .append(",\"gungu\":").append(gungu.isEmpty() ? "null" : q(gungu))
                .append(",\"totalAttractions\":").append(scoped.size())
                .append(",\"gems\":[");
        for (int i = 0; i < gems.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            HiddenGem g = gems.get(i);
            sb.append('{')
                    .append("\"resNm\":").append(q(g.resNm))
                    .append(",\"sido\":").append(q(g.sido))
                    .append(",\"gungu\":").append(q(g.gungu))
                    .append(",\"addrCd\":").append(q(g.addrCd))
                    .append(",\"domesticVisitors\":").append(round(g.domestic))
                    .append(",\"foreignVisitors\":").append(round(g.foreign))
                    .append(",\"foreignShare\":").append(round(g.foreignShare))
                    .append(",\"domesticRank\":").append(g.domesticRank)
                    .append(",\"foreignRank\":").append(g.foreignRank)
                    .append(",\"gemScore\":").append(round(g.gemScore))
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static boolean isBlockedName(String resNm) {
        return resNm != null && BLOCKED_NAME.matcher(resNm).find();
    }

    private static List<Attraction> loadYmData(String ym) throws Exception {
        List<Attraction> cached = YM_DATA_CACHE.get(ym);
        if (cached != null) {
            return cached;
        }
        System.out.println("방문 통계 API 로딩 중… (ym=" + ym + ")");
        List<Attraction> list = fetchAllFromApi(ym);
        YM_DATA_CACHE.put(ym, list);
        System.out.println("방문 통계 로딩 완료: " + list.size() + "건 (RAM만 보관, 서버 종료 시 삭제)");
        return list;
    }

    private static List<Attraction> filterAttractions(List<Attraction> all, String sido, String gungu) {
        if (sido.isBlank() && gungu.isBlank()) {
            return all;
        }
        List<Attraction> filtered = new ArrayList<>();
        for (Attraction a : all) {
            if (!sido.isBlank() && !sido.equals(a.sido)) {
                continue;
            }
            if (!gungu.isBlank() && !gungu.equals(a.gungu)) {
                continue;
            }
            filtered.add(a);
        }
        return filtered;
    }

    private static List<Attraction> fetchAllFromApi(String ym) throws Exception {
        String xml = fetchPaidVisitorXml(ym, "", "", 1, 1);
        Document doc = parseXml(xml);
        checkResult(doc);
        int total = parseInt(text(doc, "totalCount"), 0);
        if (total <= 0) {
            return List.of();
        }
        if (total <= API_PAGE_SIZE) {
            if (total <= 1) {
                return parseAttractions(doc);
            }
            xml = fetchPaidVisitorXml(ym, "", "", 1, total);
            doc = parseXml(xml);
            checkResult(doc);
            return parseAttractions(doc);
        }

        List<Attraction> all = new ArrayList<>(total);
        int pages = (total + API_PAGE_SIZE - 1) / API_PAGE_SIZE;
        for (int pageNo = 1; pageNo <= pages; pageNo++) {
            xml = fetchPaidVisitorXml(ym, "", "", pageNo, API_PAGE_SIZE);
            doc = parseXml(xml);
            checkResult(doc);
            List<Attraction> page = parseAttractions(doc);
            all.addAll(page);
            System.out.println("API 로딩 " + pageNo + "/" + pages
                    + " (" + all.size() + "/" + total + "건)");
            if (page.isEmpty()) {
                break;
            }
        }
        return all;
    }

    private static List<Attraction> parseAttractions(Document doc) {
        List<Attraction> list = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            Attraction a = parseAttraction(item);
            if (a != null) {
                list.add(a);
            }
        }
        return list;
    }

    private static List<String[]> parseThumbnailLines(String body) {
        List<String[]> rows = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                rows.add(new String[] { line.trim(), "" });
            } else {
                rows.add(new String[] { line.substring(0, tab).trim(), line.substring(tab + 1).trim() });
            }
        }
        return rows;
    }

    private static String buildThumbnailsJson(List<String[]> items) {
        Map<String, String> found = lookupThumbnailsParallel(items);
        StringBuilder sb = new StringBuilder("{\"thumbnails\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : found.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(q(e.getKey())).append(':').append(q(e.getValue()));
        }
        sb.append("}");
        if (KOR_API_LIMITED.get()) {
            sb.append(",\"apiLimited\":true");
        }
        sb.append('}');
        return sb.toString();
    }

    private static String buildPlaceDetailJson(String resNm, String sido, String gungu) throws Exception {
        return buildPlaceDetailJson(resNm, sido, gungu, true);
    }

    /** contentId로 바로 상세 조회 (근처 장소 클릭용) */
    private static String buildPlaceDetailByContentId(String contentId, String fallbackName) throws Exception {
        SearchHit hit = new SearchHit(
                contentId,
                "",
                fallbackName == null ? "" : fallbackName,
                "",
                "",
                "",
                null);
        return buildPlaceDetailFromHit(hit, firstNonBlank(fallbackName, "장소"), "", "");
    }

    /** @param reuseContentId true면 썸네일에서 찾은 contentId 재사용 (운영 기본) */
    private static String buildPlaceDetailJson(String resNm, String sido, String gungu, boolean reuseContentId)
            throws Exception {
        SearchHit hit = null;
        if (reuseContentId) {
            hit = CONTENT_HIT_CACHE.get(imageCacheKey(resNm, sido));
        }
        if (hit == null || hit.contentId == null || hit.contentId.isBlank()) {
            hit = resolveSearchHit(resNm, sido, gungu);
            if (hit != null && !hit.contentId.isBlank()) {
                CONTENT_HIT_CACHE.put(imageCacheKey(resNm, sido), hit);
            }
        }
        if (hit == null || hit.contentId.isBlank()) {
            if (KOR_API_LIMITED.get()) {
                return "{\"found\":false,\"apiLimited\":true,\"resNm\":" + q(resNm) + ",\"sido\":" + q(sido)
                        + ",\"message\":" + q("관광공사 API 일일 호출 한도를 초과했습니다. 내일 다시 시도해 주세요.") + "}";
            }
            return "{\"found\":false,\"resNm\":" + q(resNm) + ",\"sido\":" + q(sido)
                    + ",\"message\":" + q("관광공사 DB에서 장소를 찾지 못했습니다.") + "}";
        }
        return buildPlaceDetailFromHit(hit, resNm, sido, gungu);
    }

    private static String buildPlaceDetailFromHit(SearchHit hit, String resNm, String sido, String gungu)
            throws Exception {
        Map<String, String> common = fetchDetailCommon(hit.contentId);
        String contentTypeId = firstNonBlank(common.get("contenttypeid"), hit.contentTypeId);
        Map<String, String> intro = fetchDetailIntro(hit.contentId, contentTypeId);

        String title = firstNonBlank(common.get("title"), hit.title, resNm);
        String addr = firstNonBlank(common.get("addr1"), hit.addr);
        String overview = nullToEmpty(common.get("overview"));
        String homepage = nullToEmpty(common.get("homepage"));
        String image = firstNonBlank(
                normalizeImageUrlOrNull(common.get("firstimage")),
                normalizeImageUrlOrNull(common.get("firstimage2")),
                hit.image);
        String mapx = firstNonBlank(common.get("mapx"), hit.mapx);
        String mapy = firstNonBlank(common.get("mapy"), hit.mapy);
        String tel = firstNonBlank(intro.get("infocenter"), intro.get("infocenterfood"), common.get("tel"));

        // 사진·기본 정보가 없으면 상세 실패로 처리 (목록/근처에서 걸러짐)
        if (image == null || image.isBlank() || title.isBlank()) {
            return "{\"found\":false,\"resNm\":" + q(resNm) + ",\"sido\":" + q(sido)
                    + ",\"contentId\":" + q(hit.contentId)
                    + ",\"message\":" + q("사진 또는 상세 정보가 없어 표시하지 않습니다.") + "}";
        }

        List<String[]> infoRows = new ArrayList<>();
        addInfoRow(infoRows, "이용시간", firstNonBlank(intro.get("usetime"), intro.get("opentimefood"), intro.get("opentime")));
        addInfoRow(infoRows, "휴무일", firstNonBlank(intro.get("restdate"), intro.get("restdatefood")));
        addInfoRow(infoRows, "주차", intro.get("parking"));
        addInfoRow(infoRows, "문의", tel);
        addInfoRow(infoRows, "체험안내", intro.get("expguide"));
        addInfoRow(infoRows, "입장료", firstNonBlank(intro.get("usefee"), intro.get("usagefee")));

        List<NearbyPlace> restaurants = List.of();
        List<NearbyPlace> attractions = List.of();
        if (!mapx.isBlank() && !mapy.isBlank()) {
            restaurants = fetchNearby(mapx, mapy, "39", hit.contentId, 8);
            attractions = fetchNearby(mapx, mapy, "12", hit.contentId, 8);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"found\":true")
                .append(",\"resNm\":").append(q(resNm))
                .append(",\"sido\":").append(q(sido))
                .append(",\"contentId\":").append(q(hit.contentId))
                .append(",\"contentTypeId\":").append(q(contentTypeId))
                .append(",\"title\":").append(q(title))
                .append(",\"addr\":").append(q(addr))
                .append(",\"overview\":").append(q(overview))
                .append(",\"homepage\":").append(q(extractHomepage(homepage)))
                .append(",\"image\":").append(image == null ? "null" : q(image))
                .append(",\"mapx\":").append(q(mapx))
                .append(",\"mapy\":").append(q(mapy))
                .append(",\"tel\":").append(q(nullToEmpty(tel)))
                .append(",\"info\":[");
        for (int i = 0; i < infoRows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"label\":").append(q(infoRows.get(i)[0]))
                    .append(",\"value\":").append(q(infoRows.get(i)[1])).append('}');
        }
        sb.append("],\"restaurants\":");
        appendNearbyJson(sb, restaurants);
        sb.append(",\"attractions\":");
        appendNearbyJson(sb, attractions);
        sb.append('}');
        return sb.toString();
    }

    private static void addInfoRow(List<String[]> rows, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String cleaned = stripHtml(value).trim();
        if (!cleaned.isBlank()) {
            rows.add(new String[] { label, cleaned });
        }
    }

    private static void appendNearbyJson(StringBuilder sb, List<NearbyPlace> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            NearbyPlace p = list.get(i);
            sb.append("{\"title\":").append(q(p.title))
                    .append(",\"addr\":").append(q(p.addr))
                    .append(",\"image\":").append(p.image == null ? "null" : q(p.image))
                    .append(",\"dist\":").append(round(p.dist))
                    .append(",\"contentId\":").append(q(p.contentId))
                    .append('}');
        }
        sb.append(']');
    }

    private static SearchHit resolveSearchHit(String resNm, String sido, String gungu) {
        if (KOR_API_LIMITED.get()) {
            return null;
        }
        for (String keyword : thumbnailSearchKeywords(resNm, sido, gungu)) {
            try {
                String xml = korSearchKeyword(keyword);
                Document doc = parseXml(xml);
                checkResult(doc);
                SearchHit hit = pickSearchHit(doc, resNm, keyword, sido, gungu);
                if (hit != null) {
                    return hit;
                }
            } catch (Exception ignored) {
                if (KOR_API_LIMITED.get()) {
                    return null;
                }
                // next keyword
            }
        }
        return null;
    }

    private static SearchHit pickSearchHit(
            Document doc, String originalName, String keyword, String sido, String gungu) {
        NodeList items = doc.getElementsByTagName("item");
        SearchHit nameAndRegion = null;
        SearchHit nameOnly = null;
        boolean requireRegion = !sido.isBlank() || !gungu.isBlank();

        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String contentId = childText(item, "contentid");
            if (contentId.isBlank()) {
                continue;
            }
            String title = childText(item, "title");
            String addr1 = childText(item, "addr1");
            SearchHit hit = new SearchHit(
                    contentId,
                    childText(item, "contenttypeid"),
                    title,
                    addr1,
                    childText(item, "mapx"),
                    childText(item, "mapy"),
                    imageFromItem(item));

            boolean regionOk = regionMatches(addr1, sido, gungu);
            boolean nameOk = titleMatchesQuery(title, originalName, keyword);

            if (nameOk && regionOk) {
                return hit;
            }
            if (nameOk && nameAndRegion == null && regionOk) {
                nameAndRegion = hit;
            }
            if (nameOk && nameOnly == null) {
                nameOnly = hit;
            }
        }
        if (requireRegion) {
            return nameAndRegion;
        }
        return nameOnly;
    }

    /** 주소가 요청한 시·도와 시군구에 속하는지 */
    private static boolean regionMatches(String addr1, String sido, String gungu) {
        if (addr1 == null || addr1.isBlank()) {
            return sido.isBlank() && gungu.isBlank();
        }
        if (!gungu.isBlank() && !addr1.contains(gungu)) {
            return false;
        }
        if (sido.isBlank()) {
            return true;
        }
        if (addr1.contains(sido)) {
            return true;
        }
        String sidoShort = shortenSido(sido);
        return !sidoShort.isBlank() && addr1.contains(sidoShort);
    }

    private static Map<String, String> fetchDetailCommon(String contentId) throws Exception {
        StringBuilder url = new StringBuilder(KOR_SERVICE_BASE).append("/detailCommon2?");
        url.append("serviceKey=").append(enc(tourServiceKeyRaw()));
        url.append("&MobileOS=ETC&MobileApp=HiddenGem&_type=xml");
        url.append("&contentId=").append(enc(contentId));
        Document doc = parseXml(httpGet(url.toString()));
        checkResult(doc);
        return firstItemFields(doc);
    }

    private static Map<String, String> fetchDetailIntro(String contentId, String contentTypeId) {
        if (contentId == null || contentId.isBlank() || contentTypeId == null || contentTypeId.isBlank()) {
            return Map.of();
        }
        try {
            StringBuilder url = new StringBuilder(KOR_SERVICE_BASE).append("/detailIntro2?");
            url.append("serviceKey=").append(enc(tourServiceKeyRaw()));
            url.append("&MobileOS=ETC&MobileApp=HiddenGem&_type=xml");
            url.append("&contentId=").append(enc(contentId));
            url.append("&contentTypeId=").append(enc(contentTypeId));
            Document doc = parseXml(httpGet(url.toString()));
            checkResult(doc);
            return firstItemFields(doc);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<NearbyPlace> fetchNearby(
            String mapx, String mapy, String contentTypeId, String excludeContentId, int limit) {
        List<NearbyPlace> out = new ArrayList<>();
        try {
            StringBuilder url = new StringBuilder(KOR_SERVICE_BASE).append("/locationBasedList2?");
            url.append("serviceKey=").append(enc(tourServiceKeyRaw()));
            // 사진 없는 항목을 걸러내므로 후보를 더 가져옴
            url.append("&numOfRows=").append(Math.max(limit * 4, 24));
            url.append("&pageNo=1&MobileOS=ETC&MobileApp=HiddenGem&_type=xml&arrange=E");
            url.append("&mapX=").append(enc(mapx));
            url.append("&mapY=").append(enc(mapy));
            url.append("&radius=3000");
            url.append("&contentTypeId=").append(enc(contentTypeId));
            Document doc = parseXml(httpGet(url.toString()));
            checkResult(doc);
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength() && out.size() < limit; i++) {
                Element item = (Element) items.item(i);
                String cid = childText(item, "contentid");
                if (cid.isBlank() || cid.equals(excludeContentId)) {
                    continue;
                }
                String title = childText(item, "title");
                if (title.isBlank()) {
                    continue;
                }
                String image = imageFromItem(item);
                if (image == null || image.isBlank()) {
                    continue; // 사진 없는 근처 장소는 제외
                }
                String addr = childText(item, "addr1");
                out.add(new NearbyPlace(
                        title,
                        addr,
                        image,
                        parseDouble(childText(item, "dist")),
                        cid));
            }
        } catch (Exception ignored) {
            // 근처 정보는 선택
        }
        return out;
    }

    private static Map<String, String> firstItemFields(Document doc) {
        Map<String, String> map = new LinkedHashMap<>();
        NodeList items = doc.getElementsByTagName("item");
        if (items.getLength() == 0) {
            return map;
        }
        Element item = (Element) items.item(0);
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = n.getNodeName();
            String val = n.getTextContent() == null ? "" : n.getTextContent().trim();
            map.put(name, val);
        }
        return map;
    }

    private static String extractHomepage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        java.util.regex.Matcher m = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                .matcher(raw);
        if (m.find()) {
            return m.group(1).trim();
        }
        String plain = stripHtml(raw).trim();
        if (plain.startsWith("http://") || plain.startsWith("https://")) {
            return plain.split("\\s+")[0];
        }
        return plain;
    }

    private static String stripHtml(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String normalizeImageUrlOrNull(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return normalizeImageUrl(url);
    }

    private static final class SearchHit {
        final String contentId;
        final String contentTypeId;
        final String title;
        final String addr;
        final String mapx;
        final String mapy;
        final String image;

        SearchHit(String contentId, String contentTypeId, String title, String addr,
                  String mapx, String mapy, String image) {
            this.contentId = contentId;
            this.contentTypeId = contentTypeId;
            this.title = title;
            this.addr = addr;
            this.mapx = mapx;
            this.mapy = mapy;
            this.image = image;
        }
    }

    private static final class NearbyPlace {
        final String title;
        final String addr;
        final String image;
        final double dist;
        final String contentId;

        NearbyPlace(String title, String addr, String image, double dist, String contentId) {
            this.title = title;
            this.addr = addr;
            this.image = image;
            this.dist = dist;
            this.contentId = contentId;
        }
    }

    private static Map<String, String> lookupThumbnailsParallel(List<String[]> items) {
        Map<String, String> result = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String[] pair : items) {
                futures.add(pool.submit(() -> {
                    String key = imageCacheKey(pair[0], pair[1]);
                    String url = lookupThumbnail(pair[0], pair[1]);
                    if (url != null) {
                        result.put(key, url);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // 개별 실패 무시
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    private static String imageCacheKey(String resNm, String sido) {
        return resNm + "|" + sido;
    }

    private static Attraction parseAttraction(Element item) {
        String resNm = childText(item, "resNm");
        if (resNm.isBlank()) {
            return null;
        }
        double foreign = parseDouble(childText(item, "csForCnt"));
        double domestic = parseDouble(childText(item, "csNatCnt"));
        if (foreign <= 0 && domestic <= 0) {
            return null;
        }
        return new Attraction(
                resNm,
                childText(item, "sido"),
                childText(item, "gungu"),
                childText(item, "addrCd"),
                childText(item, "ym"),
                domestic,
                foreign);
    }

    private static String lookupThumbnail(String resNm, String sido) {
        String cacheKey = imageCacheKey(resNm, sido);
        if (IMAGE_CACHE.containsKey(cacheKey)) {
            String cached = IMAGE_CACHE.get(cacheKey);
            return cached.isEmpty() ? null : cached;
        }
        try {
            SearchHit hit = lookupThumbnailHitFromApi(resNm, sido);
            if (hit != null && hit.image != null && !hit.image.isBlank()) {
                IMAGE_CACHE.put(cacheKey, hit.image);
                if (hit.contentId != null && !hit.contentId.isBlank()) {
                    CONTENT_HIT_CACHE.put(cacheKey, hit);
                }
                return hit.image;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static SearchHit lookupThumbnailHitFromApi(String resNm, String sido) throws Exception {
        if (KOR_API_LIMITED.get()) {
            return null;
        }
        for (String keyword : thumbnailSearchKeywords(resNm, sido, "")) {
            try {
                SearchHit found = searchThumbnailHit(keyword, resNm, sido);
                if (found != null) {
                    return found;
                }
            } catch (Exception ignored) {
                if (KOR_API_LIMITED.get()) {
                    return null;
                }
                // 다음 후보 키워드 시도
            }
        }
        return null;
    }

    /**
     * 검색 폴백: 원본 → 지역+이름 → 공백제거 → 핵심어 → 별칭.
     */
    private static List<String> thumbnailSearchKeywords(String resNm, String sido, String gungu) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (resNm == null || resNm.isBlank()) {
            return List.of();
        }
        String trimmed = resNm.trim();
        keys.add(trimmed);

        if (!gungu.isBlank()) {
            keys.add(gungu + " " + trimmed);
            keys.add(compactName(gungu + trimmed));
        }
        if (!sido.isBlank()) {
            keys.add(sido + " " + trimmed);
            String shortSido = shortenSido(sido);
            if (!shortSido.isBlank() && !shortSido.equals(sido)) {
                keys.add(shortSido + " " + trimmed);
            }
        }

        String compact = compactName(trimmed);
        if (!compact.isBlank()) {
            keys.add(compact);
        }

        String stripped = stripRegionPrefix(trimmed);
        if (!stripped.isBlank()) {
            keys.add(stripped);
            String strippedCompact = compactName(stripped);
            if (!strippedCompact.isBlank()) {
                keys.add(strippedCompact);
            }
            if (!gungu.isBlank()) {
                keys.add(gungu + " " + stripped);
            }
        }

        for (String token : splitNameTokens(trimmed)) {
            keys.add(token);
            if (!gungu.isBlank()) {
                keys.add(gungu + " " + token);
            }
        }

        List<String> snapshot = new ArrayList<>(keys);
        for (String key : snapshot) {
            List<String> aliases = THUMB_ALIASES.get(compactName(key));
            if (aliases != null) {
                keys.addAll(aliases);
            }
        }

        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (key != null && !key.isBlank() && out.size() < MAX_THUMB_KEYWORDS) {
                out.add(key);
            }
        }
        return out;
    }

    /** 시·군·구·도 등 지역 접두어 제거 */
    private static String stripRegionPrefix(String name) {
        String s = name.trim();
        s = s.replaceFirst("^[가-힣]+(특별자치시|특별시|광역시|특별자치도)\\s*", "");
        s = s.replaceFirst("^[가-힣]+(시|군|구)\\s+", "");
        s = s.replaceFirst("^(용인|파주|속초|수원|제주|부산|대구|서울|영주|평창|단양|양구|춘천|강릉|김해|곡성|천안|화성|구리)\\s+", "");
        return s.trim();
    }

    private static List<String> splitNameTokens(String name) {
        List<String> tokens = new ArrayList<>();
        for (String part : name.split("[·ㆍ,/\\s]+")) {
            String t = part.trim();
            if (t.length() < 2 || isWeakToken(t)) {
                continue;
            }
            tokens.add(t);
        }
        return tokens;
    }

    private static boolean isWeakToken(String token) {
        if (token.matches(".*[시군구]$") && token.length() <= 4) {
            return true;
        }
        return token.equals("관광지") || token.equals("전시관") || token.equals("박물관")
                || token.equals("공원") || token.equals("타워");
    }

    private static String searchThumbnail(String keyword, String originalName, String sido) throws Exception {
        SearchHit hit = searchThumbnailHit(keyword, originalName, sido);
        return hit == null ? null : hit.image;
    }

    private static SearchHit searchThumbnailHit(String keyword, String originalName, String sido) throws Exception {
        String xml = korSearchKeyword(keyword);
        Document doc = parseXml(xml);
        checkResult(doc);
        return pickSearchHitForThumbnail(doc, originalName, keyword, sido);
    }

    private static SearchHit pickSearchHitForThumbnail(
            Document doc, String originalName, String keyword, String sido) throws Exception {
        NodeList items = doc.getElementsByTagName("item");
        SearchHit titleAndRegion = null;
        SearchHit titleOnly = null;
        boolean requireRegion = !sido.isBlank();

        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String contentId = childText(item, "contentid");
            if (contentId.isBlank()) {
                continue;
            }
            String title = childText(item, "title");
            String addr1 = childText(item, "addr1");
            String img = imageFromItem(item);
            if (img == null) {
                img = fetchDetailImage(contentId);
            }
            if (img == null) {
                continue;
            }
            SearchHit hit = new SearchHit(
                    contentId,
                    childText(item, "contenttypeid"),
                    title,
                    addr1,
                    childText(item, "mapx"),
                    childText(item, "mapy"),
                    img);

            boolean nameMatch = titleMatchesQuery(title, originalName, keyword);
            boolean regionMatch = regionMatches(addr1, sido, "");
            if (nameMatch && regionMatch) {
                return hit;
            }
            if (nameMatch && titleAndRegion == null && regionMatch) {
                titleAndRegion = hit;
            }
            if (nameMatch && titleOnly == null) {
                titleOnly = hit;
            }
        }
        if (requireRegion) {
            return titleAndRegion;
        }
        return titleOnly;
    }

    private static String pickImageFromSearch(Document doc, String originalName, String keyword, String sido)
            throws Exception {
        SearchHit hit = pickSearchHitForThumbnail(doc, originalName, keyword, sido);
        return hit == null ? null : hit.image;
    }

    /**
     * 원본 통계명 또는 (원본과 다른) 별칭 키워드와 제목이 맞을 때만 매칭.
     * "정강원"→관광농원, "용인"→호텔 같은 짧은 부분일치 오매칭은 거부.
     */
    private static boolean titleMatchesQuery(String title, String originalName, String keyword) {
        if (namesMatchPlace(title, originalName)) {
            return true;
        }
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        String compactKw = compactName(keyword);
        String compactOrig = compactName(originalName);
        // 검색어가 원본과 동일하면 원본 매칭만 인정 (이미 실패)
        if (compactKw.isEmpty() || compactKw.equals(compactOrig)) {
            return false;
        }
        // 별칭이 원본을 포함/확장한 경우(예: "정강원 한국전통…") → 별칭 기준으로 매칭
        return namesMatchPlace(title, keyword);
    }

    /** 띄어쓰기 무시 이름 비교 (짧은 부분 문자열 오매칭 방지) */
    private static boolean namesMatch(String a, String b) {
        return namesMatchPlace(a, b);
    }

    private static boolean namesMatchStrict(String a, String b) {
        return namesMatchPlace(a, b);
    }

    private static boolean namesMatchPlace(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String ca = compactName(a);
        String cb = compactName(b);
        if (ca.isEmpty() || cb.isEmpty()) {
            return false;
        }
        if (ca.equals(cb)) {
            return true;
        }
        if (softContainsName(ca, cb)) {
            return true;
        }
        return significantTokensMatch(a, b);
    }

    /** 한쪽 이름이 다른 쪽에 충분히 길게 포함될 때만 */
    private static boolean softContainsName(String ca, String cb) {
        String shorter = ca.length() <= cb.length() ? ca : cb;
        String longer = ca.length() <= cb.length() ? cb : ca;
        // 3글자 이하 짧은 키워드가 긴 상호에만 들어가는 경우 차단 (정강원→관광농원 등)
        if (shorter.length() < 4) {
            return false;
        }
        if (!longer.contains(shorter)) {
            return false;
        }
        return (double) shorter.length() / (double) longer.length() >= 0.4;
    }

    /**
     * 공백으로 나뉜 핵심 토큰이 모두 상대 이름에 있으면 매칭.
     * 예: "용인 대장금 파크" → 대장금 필수 → 용인호텔은 탈락.
     */
    private static boolean significantTokensMatch(String title, String original) {
        List<String> tokens = significantNameTokens(original);
        if (tokens.isEmpty()) {
            return false;
        }
        String ct = compactName(title);
        if (ct.isEmpty()) {
            return false;
        }
        int hit = 0;
        for (String token : tokens) {
            String c = compactName(token);
            if (c.length() >= 2 && ct.contains(c)) {
                hit++;
            }
        }
        if (tokens.size() == 1) {
            return softContainsName(ct, compactName(tokens.get(0)));
        }
        // 토큰 2개 이상이면 전부 포함되어야 함 (약한 접미사는 이미 제거됨)
        return hit >= tokens.size();
    }

    private static final Set<String> WEAK_NAME_TOKENS = Set.of(
            "관광지", "공원", "파크", "랜드", "테마파크", "박물관", "미술관", "체험관", "체험장",
            "문화관", "문화원", "타워", "센터", "마을", "시장", "해변", "해수욕장", "폭포", "온천",
            "리조트", "호텔", "펜션", "농원", "관광농원", "세트장", "드라마", "테마", "월드",
            "휴양림", "수목원", "식물원", "동물원", "수족관", "기념관", "전시관");

    private static List<String> significantNameTokens(String name) {
        List<String> out = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return out;
        }
        for (String part : name.trim().split("[\\s·ㆍ]+")) {
            String t = part.replaceAll("[^0-9A-Za-z가-힣]", "");
            if (t.length() < 2) {
                continue;
            }
            if (WEAK_NAME_TOKENS.contains(t)) {
                continue;
            }
            out.add(t);
        }
        if (out.isEmpty()) {
            String compact = compactName(name);
            if (compact.length() >= 4) {
                out.add(compact);
            }
        }
        // 핵심 토큰이 2개 이상이면 짧은 지역명(2~3글자)은 보조로만 취급 → 필수에서 제외
        if (out.size() >= 2) {
            List<String> strong = new ArrayList<>();
            for (String t : out) {
                if (t.length() >= 4) {
                    strong.add(t);
                }
            }
            if (!strong.isEmpty()) {
                return strong;
            }
        }
        return out;
    }

    private static String compactName(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.replaceAll("[\\s·ㆍ.\\-_/()（）\\[\\]]+", "");
    }

    private static String shortenSido(String sido) {
        if (sido == null || sido.isBlank()) {
            return "";
        }
        return sido
                .replace("특별자치도", "")
                .replace("특별시", "")
                .replace("광역시", "")
                .trim();
    }

    private static String imageFromItem(Element item) {
        String img = childText(item, "firstimage");
        if (img.isBlank()) {
            img = childText(item, "firstimage2");
        }
        if (img.isBlank()) {
            return null;
        }
        return normalizeImageUrl(img);
    }

    private static String fetchDetailImage(String contentId) throws Exception {
        StringBuilder url = new StringBuilder(KOR_SERVICE_BASE).append("/detailImage2?");
        url.append("serviceKey=").append(enc(tourServiceKeyRaw()));
        url.append("&contentId=").append(enc(contentId));
        url.append("&MobileOS=ETC&MobileApp=HiddenGem");
        url.append("&imageYN=Y&subImageYN=Y");
        url.append("&_type=xml");
        String xml = httpGet(url.toString());
        Document doc = parseXml(xml);
        checkResult(doc);
        NodeList items = doc.getElementsByTagName("item");
        if (items.getLength() == 0) {
            return null;
        }
        Element item = (Element) items.item(0);
        String img = childText(item, "smallimageurl");
        if (img.isBlank()) {
            img = childText(item, "originimgurl");
        }
        return img.isBlank() ? null : normalizeImageUrl(img);
    }

    private static String normalizeImageUrl(String url) {
        if (url.startsWith("http://")) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    private static String fetchPaidVisitorXml(String ym, String sido, String gungu, int pageNo, int numOfRows) throws Exception {
        StringBuilder url = new StringBuilder(PAID_VISITOR_URL).append('?');
        appendParam(url, "serviceKey", tourServiceKeyRaw());
        appendParam(url, "YM", ym);
        if (!sido.isBlank()) {
            appendParam(url, "SIDO", sido);
        }
        if (!gungu.isBlank()) {
            appendParam(url, "GUNGU", gungu);
        }
        appendParam(url, "pageNo", String.valueOf(pageNo));
        appendParam(url, "numOfRows", String.valueOf(numOfRows));
        appendParam(url, "_type", "xml");
        return httpGet(url.toString());
    }

    private static void appendParam(StringBuilder url, String key, String value) {
        if (url.charAt(url.length() - 1) != '?') {
            url.append('&');
        }
        url.append(enc(key)).append('=').append(enc(value));
    }

    private static String korSearchKeyword(String keyword) throws Exception {
        StringBuilder url = new StringBuilder(KOR_SERVICE_BASE).append("/searchKeyword2?");
        url.append("serviceKey=").append(enc(tourServiceKeyRaw()));
        url.append("&numOfRows=10&pageNo=1");
        url.append("&MobileOS=ETC&MobileApp=HiddenGem");
        url.append("&_type=xml&arrange=A");
        url.append("&keyword=").append(enc(keyword));
        return httpGet(url.toString());
    }

    private static void checkResult(Document doc) throws Exception {
        String code = firstTagText(doc, "resultCode");
        if (!code.isBlank() && !"0000".equals(code)) {
            // OpenAPI_ServiceResponse (한도 초과 등)
            String err = firstTagText(doc, "errMsg");
            if (err.isBlank()) {
                err = firstTagText(doc, "returnAuthMsg");
            }
            if (err.isBlank()) {
                err = firstTagText(doc, "resultMsg");
            }
            markKorApiLimitedIfNeeded(code, firstTagText(doc, "returnReasonCode"), err);
            throw new IllegalStateException("API 오류 " + code + ": " + err);
        }
        String reason = firstTagText(doc, "returnReasonCode");
        if ("22".equals(reason) || (!reason.isBlank() && !"0".equals(reason) && !"00".equals(reason))) {
            String auth = firstTagText(doc, "returnAuthMsg");
            markKorApiLimitedIfNeeded(code, reason, auth + " " + firstTagText(doc, "errMsg"));
            throw new IllegalStateException("API 제한 " + reason + ": " + auth);
        }
    }

    private static void markKorApiLimitedIfNeeded(String code, String reason, String message) {
        String msg = message == null ? "" : message.toUpperCase();
        if ("22".equals(reason) || msg.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")) {
            KOR_API_LIMITED.set(true);
        }
    }

    private static void noteKorTraffic(String urlString) {
        if (urlString == null || !urlString.contains("/KorService2/")) {
            return;
        }
        String op = "other";
        int idx = urlString.indexOf("/KorService2/");
        if (idx >= 0) {
            String rest = urlString.substring(idx + "/KorService2/".length());
            int q = rest.indexOf('?');
            op = q >= 0 ? rest.substring(0, q) : rest;
        }
        KOR_TRAFFIC.computeIfAbsent(op, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
        KOR_TRAFFIC.computeIfAbsent("TOTAL", k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
    }

    private static void resetKorTraffic() {
        KOR_TRAFFIC.clear();
        IMAGE_CACHE.clear();
        PLACE_DETAIL_CACHE.clear();
        CONTENT_HIT_CACHE.clear();
    }

    private static void resetKorTrafficCounterOnly() {
        KOR_TRAFFIC.clear();
    }

    private static int korTotal() {
        java.util.concurrent.atomic.AtomicInteger t = KOR_TRAFFIC.get("TOTAL");
        return t == null ? 0 : t.get();
    }

    private static void printKorTraffic(String label) {
        System.out.println("--- " + label + " ---");
        System.out.println("TOTAL=" + korTotal());
        KOR_TRAFFIC.entrySet().stream()
                .filter(e -> !"TOTAL".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("  " + e.getKey() + "=" + e.getValue().get()));
    }

    /**
     * contentId 재사용 전/후 트래픽 비교.
     * 실행: {@code java HiddenGemServer traffic 10}
     */
    private static void runTrafficCompare(int n) throws Exception {
        System.out.println("KorService2 트래픽 비교 (썸네일+상세, 장소 " + n + "곳)");
        String ym = defaultYm();
        List<Attraction> all = loadYmData(ym);
        List<HiddenGem> gems = buildGemList(all, n);
        if (gems.isEmpty()) {
            System.out.println("비교할 장소가 없습니다. 방문 통계 API 응답을 확인하세요.");
            return;
        }
        System.out.println("샘플:");
        for (int i = 0; i < gems.size(); i++) {
            HiddenGem g = gems.get(i);
            System.out.println("  " + (i + 1) + ". " + g.resNm + " / " + g.sido);
        }

        resetKorTraffic();
        int beforeOkThumb = 0;
        int beforeOkDetail = 0;
        for (HiddenGem g : gems) {
            String thumb = lookupThumbnail(g.resNm, g.sido);
            if (thumb != null) {
                beforeOkThumb++;
            }
            String gungu = g.gungu == null ? "" : g.gungu;
            String detail = buildPlaceDetailJson(g.resNm, g.sido, gungu, false);
            if (detail.contains("\"found\":true")) {
                beforeOkDetail++;
            }
        }
        int beforeTotal = korTotal();
        Map<String, Integer> beforeMap = snapshotTraffic();
        printKorTraffic("BEFORE (재검색) thumbOk=" + beforeOkThumb + " detailOk=" + beforeOkDetail);

        resetKorTraffic();
        int afterOkThumb = 0;
        int afterOkDetail = 0;
        for (HiddenGem g : gems) {
            String thumb = lookupThumbnail(g.resNm, g.sido);
            if (thumb != null) {
                afterOkThumb++;
            }
            String gungu = g.gungu == null ? "" : g.gungu;
            String detail = buildPlaceDetailJson(g.resNm, g.sido, gungu, true);
            if (detail.contains("\"found\":true")) {
                afterOkDetail++;
            }
        }
        int afterTotal = korTotal();
        Map<String, Integer> afterMap = snapshotTraffic();
        printKorTraffic("AFTER (contentId 재사용) thumbOk=" + afterOkThumb + " detailOk=" + afterOkDetail);

        System.out.println("=== 비교 요약 ===");
        System.out.println("BEFORE TOTAL: " + beforeTotal);
        System.out.println("AFTER  TOTAL: " + afterTotal);
        System.out.println("절감:       " + (beforeTotal - afterTotal)
                + " (" + (beforeTotal == 0 ? 0 : Math.round((beforeTotal - afterTotal) * 1000.0 / beforeTotal) / 10.0) + "%)");
        System.out.println("searchKeyword2 BEFORE=" + beforeMap.getOrDefault("searchKeyword2", 0)
                + " AFTER=" + afterMap.getOrDefault("searchKeyword2", 0));
        System.out.println("locationBased  BEFORE=" + beforeMap.getOrDefault("locationBasedList2", 0)
                + " AFTER=" + afterMap.getOrDefault("locationBasedList2", 0));

        // --- 메모리 캐시(2차 조회): IMAGE_CACHE·CONTENT_HIT_CACHE RAM 유지, contentId 재사용 ON ---
        resetKorTraffic(); // 콜드 스타트
        int pass1Thumb = 0, pass1Detail = 0;
        for (HiddenGem g : gems) {
            if (lookupThumbnail(g.resNm, g.sido) != null) {
                pass1Thumb++;
            }
            if (buildPlaceDetailJson(g.resNm, g.sido, g.gungu == null ? "" : g.gungu).contains("\"found\":true")) {
                pass1Detail++;
            }
        }
        int pass1Total = korTotal();
        Map<String, Integer> pass1Map = snapshotTraffic();

        resetKorTrafficCounterOnly(); // 호출 카운터만 리셋, RAM 캐시 유지
        int pass2Thumb = 0, pass2Detail = 0;
        for (HiddenGem g : gems) {
            if (lookupThumbnail(g.resNm, g.sido) != null) {
                pass2Thumb++;
            }
            if (buildPlaceDetailJson(g.resNm, g.sido, g.gungu == null ? "" : g.gungu).contains("\"found\":true")) {
                pass2Detail++;
            }
        }
        int pass2Total = korTotal();
        Map<String, Integer> pass2Map = snapshotTraffic();

        System.out.println("=== 메모리 캐시 (2차 조회, RAM 유지) ===");
        System.out.println("1차(콜드) TOTAL=" + pass1Total
                + " search=" + pass1Map.getOrDefault("searchKeyword2", 0)
                + " thumbOk=" + pass1Thumb + " detailOk=" + pass1Detail);
        System.out.println("2차(웜)  TOTAL=" + pass2Total
                + " search=" + pass2Map.getOrDefault("searchKeyword2", 0)
                + " thumbOk=" + pass2Thumb + " detailOk=" + pass2Detail);
        System.out.println("2차 절감: " + (pass1Total - pass2Total)
                + " (" + (pass1Total == 0 ? 0 : Math.round((pass1Total - pass2Total) * 1000.0 / pass1Total) / 10.0) + "%)");
    }

    private static Map<String, Integer> snapshotTraffic() {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (Map.Entry<String, java.util.concurrent.atomic.AtomicInteger> e : KOR_TRAFFIC.entrySet()) {
            m.put(e.getKey(), e.getValue().get());
        }
        return m;
    }

    private static List<HiddenGem> buildGemList(List<Attraction> all, int limit) {
        Map<String, Integer> foreignRank = rankMap(all, a -> a.foreign);
        Map<String, Integer> domesticRank = rankMap(all, a -> a.domestic);
        List<HiddenGem> gems = new ArrayList<>();
        for (Attraction a : all) {
            if (a.foreign < MIN_FOREIGN_VISITORS || a.domestic <= 0) {
                continue;
            }
            if (isBlockedName(a.resNm)) {
                continue;
            }
            int fRank = foreignRank.getOrDefault(a.key(), all.size());
            int dRank = domesticRank.getOrDefault(a.key(), all.size());
            if (dRank <= MIN_DOMESTIC_RANK || fRank <= MIN_FOREIGN_RANK) {
                continue;
            }
            int gap = dRank - fRank;
            if (gap <= 0) {
                continue;
            }
            double share = a.foreign / (a.foreign + a.domestic) * 100.0;
            double score = Math.log(1.0 + a.foreign) * gap;
            gems.add(new HiddenGem(a, share, dRank, fRank, score));
        }
        gems.sort(Comparator
                .comparingDouble((HiddenGem g) -> g.gemScore).reversed()
                .thenComparing(Comparator.comparingDouble((HiddenGem g) -> g.foreignShare).reversed()));
        if (gems.size() > limit) {
            gems = new ArrayList<>(gems.subList(0, limit));
        }
        return gems;
    }

    private static String httpGet(String urlString) throws Exception {
        noteKorTraffic(urlString);
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(90_000);
        conn.setRequestProperty("Accept", "application/xml, application/json, */*");
        conn.setRequestProperty("User-Agent", "HiddenGem/1.0");

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code <= 299) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);
        conn.disconnect();
        if (code < 200 || code > 299) {
            markKorApiLimitedFromHttp(code, body);
            throw new IllegalStateException("HTTP " + code + (body.isBlank() ? "" : ": " + body.substring(0, Math.min(120, body.length()))));
        }
        return body;
    }

    /** HTTP 429 등 — XML resultCode를 보기 전에 끊기는 한도 초과 응답 처리 */
    private static void markKorApiLimitedFromHttp(int httpCode, String body) {
        if (httpCode == 429) {
            KOR_API_LIMITED.set(true);
            return;
        }
        String raw = body == null ? "" : body;
        String upper = raw.toUpperCase();
        if (upper.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")
                || raw.contains("<returnReasonCode>22</returnReasonCode>")
                || raw.contains("returnReasonCode>22<")) {
            KOR_API_LIMITED.set(true);
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Integer> rankMap(List<Attraction> items, java.util.function.ToDoubleFunction<Attraction> key) {
        List<Attraction> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(key).reversed());
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).key(), i + 1);
        }
        return ranks;
    }

    private static void respond(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
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

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static String jsonError(String msg) {
        return "{\"error\":" + q(msg == null ? "unknown" : msg) + "}";
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String childText(Element parent, String tag) {
        NodeList ch = parent.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n instanceof Element e && tag.equals(e.getTagName())) {
                return e.getTextContent() != null ? e.getTextContent().trim() : "";
            }
        }
        return "";
    }

    private static String text(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return "";
        }
        return list.item(0).getTextContent().trim();
    }

    private static String firstTagText(Document doc, String tag) {
        return text(doc, tag);
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String tourServiceKeyRaw() {
        String key = System.getenv("TOUR_GO_KR_SERVICE_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("DATA_GO_KR_SERVICE_KEY");
        }
        if (key == null || key.isBlank()) {
            key = "66a478965dedc52ede9955fb2313a277f384963cc354e8d1e4ea289fb0189d78";
        }
        return key;
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /** API에 데이터가 있는 기본 년월 */
    private static String defaultYm() {
        return "201201";
    }

    private static final class Attraction {
        final String resNm;
        final String sido;
        final String gungu;
        final String addrCd;
        final String ym;
        final double domestic;
        final double foreign;

        Attraction(String resNm, String sido, String gungu, String addrCd, String ym, double domestic, double foreign) {
            this.resNm = resNm;
            this.sido = sido;
            this.gungu = gungu;
            this.addrCd = addrCd;
            this.ym = ym;
            this.domestic = domestic;
            this.foreign = foreign;
        }

        String key() {
            return resNm + "|" + sido + "|" + gungu;
        }
    }

    private static final class HiddenGem {
        final String resNm;
        final String sido;
        final String gungu;
        final String addrCd;
        final double domestic;
        final double foreign;
        final double foreignShare;
        final int domesticRank;
        final int foreignRank;
        final double gemScore;

        HiddenGem(Attraction a, double foreignShare, int domesticRank, int foreignRank, double gemScore) {
            this.resNm = a.resNm;
            this.sido = a.sido;
            this.gungu = a.gungu;
            this.addrCd = a.addrCd;
            this.domestic = a.domestic;
            this.foreign = a.foreign;
            this.foreignShare = foreignShare;
            this.domesticRank = domesticRank;
            this.foreignRank = foreignRank;
            this.gemScore = gemScore;
        }
    }
}
