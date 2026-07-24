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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilderFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 웹 UI + REST API — 외국인 대비 내국인 방문 순위가 낮은 유료관광지(히든젬) 탐색.
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
    private static final String PAID_VISITOR_URL =
            "http://openapi.tour.go.kr/openapi/service/TourismResourceStatsService/getPchrgTrrsrtVisitorList";
    private static final String KOR_SERVICE_BASE = "https://apis.data.go.kr/B551011/KorService2";
    private static final Map<String, String> GEM_CACHE = new HashMap<>();
    private static final Map<String, List<Attraction>> YM_DATA_CACHE = new HashMap<>();
    private static final Map<String, String> IMAGE_CACHE = new ConcurrentHashMap<>();
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
        List<Attraction> attractions = filterAttractions(loadYmData(ym), sido, gungu);

        Map<String, Integer> foreignRank = rankMap(attractions, a -> a.foreign);
        Map<String, Integer> domesticRank = rankMap(attractions, a -> a.domestic);

        List<HiddenGem> gems = new ArrayList<>();
        for (Attraction a : attractions) {
            if (a.foreign <= 0 || a.domestic <= 0) {
                continue;
            }
            double share = a.foreign / (a.foreign + a.domestic) * 100.0;
            int fRank = foreignRank.getOrDefault(a.key(), attractions.size());
            int dRank = domesticRank.getOrDefault(a.key(), attractions.size());
            gems.add(new HiddenGem(a, share, dRank, fRank, dRank - fRank));
        }
        gems.sort(Comparator.comparingInt((HiddenGem g) -> g.gemScore).reversed()
                .thenComparingDouble(g -> g.foreignShare).reversed());
        gems.removeIf(g -> g.gemScore <= 0);
        if (gems.size() > limit) {
            gems = new ArrayList<>(gems.subList(0, limit));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ym\":").append(q(ym))
                .append(",\"sido\":").append(sido.isEmpty() ? "null" : q(sido))
                .append(",\"gungu\":").append(gungu.isEmpty() ? "null" : q(gungu))
                .append(",\"totalAttractions\":").append(attractions.size())
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
                    .append(",\"gemScore\":").append(g.gemScore)
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
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

    private static Map<String, String> lookupThumbnailsParallel(List<String[]> items) {
        Map<String, String> result = new ConcurrentHashMap<>();
        items.parallelStream().forEach(pair -> {
            String key = imageCacheKey(pair[0], pair[1]);
            String url = lookupThumbnail(pair[0], pair[1]);
            if (url != null) {
                result.put(key, url);
            }
        });
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
            IMAGE_CACHE.put(cacheKey, url == null ? "" : url);
            return url;
        } catch (Exception e) {
            IMAGE_CACHE.put(cacheKey, "");
            return null;
        }
    }

    private static String lookupThumbnailFromApi(String resNm, String sido) throws Exception {
        String xml = korSearchKeyword(resNm);
        Document doc = parseXml(xml);
        checkResult(doc);
        return pickImageFromSearch(doc, resNm, sido);
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

    private static String pickImageFromSearch(Document doc, String resNm, String sido) throws Exception {
        NodeList items = doc.getElementsByTagName("item");
        String titleMatch = null;
        String anyImage = null;
        String sidoShort = shortenSido(sido);

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
            boolean nameMatch = title.equals(resNm) || title.contains(resNm) || resNm.contains(title);
            boolean regionMatch = sido.isBlank()
                    || addr1.contains(sido)
                    || (!sidoShort.isBlank() && addr1.contains(sidoShort));
            if (nameMatch && regionMatch) {
                return img;
            }
            if (nameMatch && titleMatch == null) {
                titleMatch = img;
            }
        }
        return titleMatch != null ? titleMatch : anyImage;
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
        final int gemScore;

        HiddenGem(Attraction a, double foreignShare, int domesticRank, int foreignRank, int gemScore) {
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
