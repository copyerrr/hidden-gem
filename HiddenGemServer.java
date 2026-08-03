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
 * 게시판 DB: {@code /api/login}, {@code /api/posts} (Post / Reply / Recommendation / Location)
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
            Map.entry("용인대장금파크", List.of("대장금파크", "용인 대장금파크")),
            Map.entry("대장금파크", List.of("대장금파크", "용인 대장금파크")),
            Map.entry("구리시고구려대장간마을", List.of("고구려대장간마을")),
            Map.entry("고구려대장간마을", List.of("고구려대장간마을")),
            Map.entry("태릉강릉조선왕릉전시관", List.of("태릉", "강릉", "조선왕릉")),
            Map.entry("한국전통음식문화체험관", List.of("정강원", "한국전통음식문화체험관")),
            Map.entry("골드힐카운티", List.of("골드힐")),
            Map.entry("설악파크", List.of("설악파크")),
            Map.entry("달곁에별", List.of("달곁에별")));
    private static final String PAID_VISITOR_URL =
            "http://openapi.tour.go.kr/openapi/service/TourismResourceStatsService/getPchrgTrrsrtVisitorList";
    private static final String KOR_SERVICE_BASE = "https://apis.data.go.kr/B551011/KorService2";
    private static final Map<String, String> GEM_CACHE = new HashMap<>();
    private static final Map<String, List<Attraction>> YM_DATA_CACHE = new HashMap<>();
    private static final Map<String, String> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> PLACE_DETAIL_CACHE = new ConcurrentHashMap<>();
    /** 한 번에 전체 1260건을 받으면 Read timed out 나므로 페이지 단위로 받음 */
    private static final int API_PAGE_SIZE = 100;
    private static final Path CACHE_DIR = Path.of("out");

    public static void main(String[] args) throws Exception {
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
        server.createContext("/api/login", BoardApi::handleLogin);
        server.createContext("/api/posts", BoardApi::handlePosts);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("히든젬 서버 시작 → http://localhost:" + PORT);
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

    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        Path file = WEB_ROOT.resolve(path.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(WEB_ROOT) || !Files.isRegularFile(file)) {
            respond(ex, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8));
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
            String resNm = qmap.getOrDefault("resNm", "").trim();
            String sido = qmap.getOrDefault("sido", "").trim();
            String gungu = qmap.getOrDefault("gungu", "").trim();
            if (resNm.isBlank()) {
                respond(ex, 400, "application/json; charset=utf-8",
                        jsonError("resNm required").getBytes(StandardCharsets.UTF_8));
                return;
            }
            String cacheKey = resNm + "|" + sido + "|" + gungu;
            String body = PLACE_DETAIL_CACHE.get(cacheKey);
            if (body == null) {
                body = buildPlaceDetailJson(resNm, sido, gungu);
                PLACE_DETAIL_CACHE.put(cacheKey, body);
            }
            respond(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8",
                    jsonError(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
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
        List<Attraction> fromDisk = readYmDiskCache(ym);
        if (fromDisk != null && !fromDisk.isEmpty()) {
            System.out.println("캐시 사용: " + ymDiskCachePath(ym) + " (" + fromDisk.size() + "건)");
            YM_DATA_CACHE.put(ym, fromDisk);
            return fromDisk;
        }
        List<Attraction> list = fetchAllFromApi(ym);
        YM_DATA_CACHE.put(ym, list);
        writeYmDiskCache(ym, list);
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

    private static Path ymDiskCachePath(String ym) {
        return CACHE_DIR.resolve("ym-" + ym + ".tsv");
    }

    private static List<Attraction> readYmDiskCache(String ym) {
        Path path = ymDiskCachePath(ym);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<Attraction> list = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                if (p.length < 7) {
                    continue;
                }
                list.add(new Attraction(
                        p[0], p[1], p[2], p[3], p[4],
                        parseDouble(p[5]), parseDouble(p[6])));
            }
            return list;
        } catch (Exception e) {
            System.err.println("캐시 읽기 실패(무시): " + e.getMessage());
            return null;
        }
    }

    private static void writeYmDiskCache(String ym, List<Attraction> list) {
        try {
            Files.createDirectories(CACHE_DIR);
            Path path = ymDiskCachePath(ym);
            StringBuilder sb = new StringBuilder();
            sb.append("# ym=").append(ym).append(" count=").append(list.size()).append('\n');
            for (Attraction a : list) {
                sb.append(escTab(a.resNm)).append('\t')
                        .append(escTab(a.sido)).append('\t')
                        .append(escTab(a.gungu)).append('\t')
                        .append(escTab(a.addrCd)).append('\t')
                        .append(escTab(a.ym)).append('\t')
                        .append(a.domestic).append('\t')
                        .append(a.foreign).append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("캐시 저장: " + path + " (" + list.size() + "건)");
        } catch (Exception e) {
            System.err.println("캐시 저장 실패(무시): " + e.getMessage());
        }
    }

    private static String escTab(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
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
        sb.append("}}");
        return sb.toString();
    }

    private static String buildPlaceDetailJson(String resNm, String sido, String gungu) throws Exception {
        SearchHit hit = resolveSearchHit(resNm, sido, gungu);
        if (hit == null || hit.contentId.isBlank()) {
            return "{\"found\":false,\"resNm\":" + q(resNm) + ",\"sido\":" + q(sido)
                    + ",\"message\":" + q("관광공사 DB에서 장소를 찾지 못했습니다.") + "}";
        }

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
                // next keyword
            }
        }
        return null;
    }

    private static SearchHit pickSearchHit(
            Document doc, String originalName, String keyword, String sido, String gungu) {
        NodeList items = doc.getElementsByTagName("item");
        SearchHit nameAndRegion = null;
        SearchHit anyInRegion = null;
        SearchHit nameOnly = null;
        SearchHit any = null;
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
            boolean nameOk = namesMatch(title, originalName) || namesMatch(title, keyword);

            if (any == null) {
                any = hit;
            }
            if (regionOk && anyInRegion == null) {
                anyInRegion = hit;
            }
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
            // 시도/시군구가 있으면 다른 지역 결과로 대체하지 않음 (오매칭 방지)
            return nameAndRegion != null ? nameAndRegion : null;
        }
        return nameOnly != null ? nameOnly : any;
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
            url.append("&numOfRows=").append(Math.max(limit * 2, 12));
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
                if (cid.equals(excludeContentId)) {
                    continue;
                }
                String title = childText(item, "title");
                if (title.isBlank()) {
                    continue;
                }
                out.add(new NearbyPlace(
                        title,
                        childText(item, "addr1"),
                        imageFromItem(item),
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
            String url = lookupThumbnailFromApi(resNm, sido);
            // 성공만 캐시 — 일시적 API 실패를 빈 값으로 고정하지 않음
            if (url != null) {
                IMAGE_CACHE.put(cacheKey, url);
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    private static String lookupThumbnailFromApi(String resNm, String sido) throws Exception {
        for (String keyword : thumbnailSearchKeywords(resNm, sido, "")) {
            try {
                String found = searchThumbnail(keyword, resNm, sido);
                if (found != null) {
                    return found;
                }
            } catch (Exception ignored) {
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
            if (key != null && !key.isBlank() && out.size() < 12) {
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
        String xml = korSearchKeyword(keyword);
        Document doc = parseXml(xml);
        checkResult(doc);
        return pickImageFromSearch(doc, originalName, keyword, sido);
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

    private static String pickImageFromSearch(Document doc, String originalName, String keyword, String sido)
            throws Exception {
        NodeList items = doc.getElementsByTagName("item");
        String titleAndRegion = null;
        String anyInRegion = null;
        String titleOnly = null;
        String anyImage = null;
        boolean requireRegion = !sido.isBlank();

        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = childText(item, "title");
            String addr1 = childText(item, "addr1");
            String contentId = childText(item, "contentid");
            String img = imageFromItem(item);
            if (img == null && !contentId.isBlank()) {
                img = fetchDetailImage(contentId);
            }
            if (img == null) {
                continue;
            }
            if (anyImage == null) {
                anyImage = img;
            }
            boolean nameMatch = namesMatch(title, originalName) || namesMatch(title, keyword);
            boolean regionMatch = regionMatches(addr1, sido, "");
            if (regionMatch && anyInRegion == null) {
                anyInRegion = img;
            }
            if (nameMatch && regionMatch) {
                return img;
            }
            if (nameMatch && titleAndRegion == null && regionMatch) {
                titleAndRegion = img;
            }
            if (nameMatch && titleOnly == null) {
                titleOnly = img;
            }
        }
        if (requireRegion) {
            return titleAndRegion;
        }
        return titleOnly != null ? titleOnly : anyImage;
    }

    /** 띄어쓰기 무시 이름 비교 */
    private static boolean namesMatch(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        String ca = compactName(a);
        String cb = compactName(b);
        return !ca.isEmpty() && !cb.isEmpty()
                && (ca.equals(cb) || ca.contains(cb) || cb.contains(ca));
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

    private static void checkResult(Document doc) throws Exception {
        String code = firstTagText(doc, "resultCode");
        if (!code.isBlank() && !"0000".equals(code)) {
            throw new IllegalStateException("API 오류 " + code + ": " + firstTagText(doc, "resultMsg"));
        }
    }

    private static String httpGet(String urlString) throws Exception {
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
            throw new IllegalStateException("HTTP " + code + (body.isBlank() ? "" : ": " + body.substring(0, Math.min(120, body.length()))));
        }
        return body;
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
