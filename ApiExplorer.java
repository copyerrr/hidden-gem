import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 한국관광공사 공공데이터포털 — 지역별 관광 수요 강도 (AreaTarDemDsService)
 * <p>
 * VS Code / Cursor 에서 실행할 때 <b>반드시 소스 저장 후 Java 프로젝트가 새 .class 를 만들었는지</b> 확인하세요.
 * 오래된 {@code jdt_ws/.../bin/ApiExplorer.class} 만 실행되면 URL 에 여전히 {@code baseYm=202401} 등이 찍힙니다.
 * <ul>
 *   <li>체류 강도: {@code areaTarSjrnDsList} (기본)</li>
 *   <li>소비 강도: 첫 인자 {@code exp}</li>
 * </ul>
 * 사용법: {@code java ApiExplorer [exp] [baseYm] [areaCd|all] [signguCd|-] [지표코드]}
 * <pre>
 *   java ApiExplorer
 *   java ApiExplorer 202509 all - 2101
 *   java ApiExplorer 202509 11 - 2101
 *   java ApiExplorer exp 202509 26 11530 2201
 * </pre>
 * {@code areaCd} 에 {@code all}(기본) 이면 시·도 17곳을 순회해 전국 시군구를 수집합니다.
 * {@code signguCd} 에 {@code -} 를 주면 해당 시·도의 시군구 전체를 조회합니다(기본값).
 * {@code numOfRows} 는 페이지 크기이며, {@code totalCount} 가 더 크면 다음 페이지를 자동 요청합니다.
 * 서비스키: 환경변수 {@code DATA_GO_KR_SERVICE_KEY} 우선.
 */
public class ApiExplorer {

    /** 콘솔에 찍히면 최신 소스로 컴파일된 것입니다. */
    private static final String COMPILE_STAMP = "20260517-v2";

    private static final String BASE_URL =
            "https://apis.data.go.kr/B551011/AreaTarDemDsService";

    /** 한 번에 요청할 최대 행 수(API 상한에 맞춤). totalCount 가 더 크면 pageNo 를 올려 모두 수집합니다. */
    private static final int PAGE_SIZE = 1000;

    /**
     * 행정구역 개편 반영 시·도 코드(202509 기준 API 동작 확인).
     * 구 코드 42(강원)·45(전북)는 0건 → 51·52 사용.
     */
    private static final String[] NATIONWIDE_AREA_CODES = {
            "11", "26", "27", "28", "29", "30", "31", "36", "41",
            "43", "44", "46", "47", "48", "50", "51", "52",
    };

    public static void main(String[] args) throws Exception {
        int i = 0;
        boolean expenditure = i < args.length && "exp".equalsIgnoreCase(args[i]);
        if (expenditure) {
            i++;
        }

        String baseYm = argOr(args, i++, "202509");
        String areaCd = argOr(args, i++, "all");
        String signguRaw = argOr(args, i++, "-");
        boolean omitSigngu = "-".equals(signguRaw);
        String signguCd = omitSigngu ? null : signguRaw;

        String defaultIx = expenditure ? "2201" : "2101";
        String ixCd = argOr(args, i, defaultIx);

        String serviceKeyRaw = System.getenv("DATA_GO_KR_SERVICE_KEY");
        if (serviceKeyRaw == null || serviceKeyRaw.isBlank()) {
            serviceKeyRaw = "66a478965dedc52ede9955fb2313a277f384963cc354e8d1e4ea289fb0189d78";
        }
        String serviceKey = URLEncoder.encode(serviceKeyRaw, StandardCharsets.UTF_8);
        String mobileApp = enc("AppTest");

        String endpoint = expenditure ? "/areaTarExpDsList" : "/areaTarSjrnDsList";

        System.out.println("--- Debug Info ---");
        System.out.println("[컴파일 스탬프] " + COMPILE_STAMP + "  (이 줄이 없거나 예전 값이면 IDE 가 오래된 .class 를 실행 중입니다.)");
        System.out.println("operation: " + (expenditure ? "areaTarExpDsList" : "areaTarSjrnDsList"));
        List<String> areaCodes = resolveAreaCodes(areaCd);
        boolean nationwide = areaCodes.size() > 1;

        System.out.println("baseYm=" + baseYm + " areaCd=" + (nationwide ? "all(" + areaCodes.size() + "개 시·도)" : areaCodes.get(0))
                + (signguCd != null ? " signguCd=" + signguCd : " signguCd=(시·도별 전체 구)")
                + " ixCd=" + ixCd + " pageSize=" + PAGE_SIZE);

        List<String> pageBodies = new ArrayList<>();
        int rowCount = 0;
        int lastResponseCode = 0;
        int httpCalls = 0;

        for (int a = 0; a < areaCodes.size(); a++) {
            String area = areaCodes.get(a);
            AreaFetchResult areaResult = fetchArea(
                    endpoint, serviceKey, mobileApp, baseYm, area, signguCd, ixCd, expenditure);
            httpCalls += areaResult.httpCalls;
            lastResponseCode = areaResult.lastResponseCode;
            pageBodies.addAll(areaResult.pageBodies);
            rowCount += areaResult.rowCount;

            if (nationwide) {
                System.out.println("[시·도] areaCd=" + area + " → " + areaResult.rowCount + "건"
                        + (areaResult.apiTotalCount >= 0 ? " (API totalCount=" + areaResult.apiTotalCount + ")" : ""));
            } else if (a == 0 && !areaResult.sampleUrl.isEmpty()) {
                System.out.println("URL (1페이지): " + areaResult.sampleUrl);
                System.out.println("Response Code: " + areaResult.lastResponseCode);
                System.out.println("------------------");
            }
        }

        String body = pageBodies.isEmpty() ? "" : pageBodies.get(0);
        String csv = xmlBodiesToCsv(pageBodies);

        System.out.println("[수집] HTTP " + httpCalls + "회, XML 페이지 " + pageBodies.size()
                + "개, item " + rowCount + "건"
                + (nationwide ? " (전국 " + areaCodes.size() + "개 시·도)" : ""));
        if (lastResponseCode < 200 || lastResponseCode > 300) {
            System.out.println("(마지막 HTTP " + lastResponseCode + ")");
        }
        Path out = Path.of(System.getProperty("user.home"), "area_tar_api_last_response.csv");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] outBytes = new byte[bom.length + csvBytes.length];
        System.arraycopy(bom, 0, outBytes, 0, bom.length);
        System.arraycopy(csvBytes, 0, outBytes, bom.length, csvBytes.length);
        Files.write(out, outBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        int preview = Math.min(body.length(), 2000);
        System.out.println("Result (XML 앞 " + preview + "자): " + body.substring(0, preview)
                + (body.length() > preview ? " ..." : ""));
        System.out.println();
        System.out.println("[CSV 저장 위치] " + out.toAbsolutePath());
        System.out.println("[CSV 미리보기]\n" + previewCsv(csv, 15));

        if (rowCount == 0 || (body != null && body.contains("<totalCount>0</totalCount>"))) {
            System.out.println();
            System.out.println("[참고] 조회 결과가 0건입니다.");
            System.out.println("  · areaCd(시·도)와 지표코드(2101 등)는 API 에서 사실상 필수입니다. 지표 없이는 0건입니다.");
            System.out.println("  · 전국: areaCd 자리에 all (기본). 한 시·도만: 11, 26 등. 구 1개: signguCd 에 코드 지정.");
            System.out.println("  · 구 강원(42)·구 전북(45) 코드는 API 0건 → 각각 51, 52 로 조회됩니다.");
            System.out.println("  · baseYm 은 공표 월(명세: 매월 16일 전후 갱신)에 맞는 YYYYMM 인지 확인하세요.");
        }
    }

    private static boolean isNationwideArea(String areaCd) {
        return areaCd == null
                || areaCd.isBlank()
                || "all".equalsIgnoreCase(areaCd)
                || "전국".equals(areaCd)
                || "*".equals(areaCd);
    }

    private static List<String> resolveAreaCodes(String areaCd) {
        if (isNationwideArea(areaCd)) {
            List<String> codes = new ArrayList<>(NATIONWIDE_AREA_CODES.length);
            for (String code : NATIONWIDE_AREA_CODES) {
                codes.add(code);
            }
            return codes;
        }
        if ("42".equals(areaCd)) {
            System.out.println("[참고] areaCd 42(구 강원) → 51 로 조회합니다.");
            areaCd = "51";
        } else if ("45".equals(areaCd)) {
            System.out.println("[참고] areaCd 45(구 전북) → 52 로 조회합니다.");
            areaCd = "52";
        }
        List<String> single = new ArrayList<>(1);
        single.add(areaCd);
        return single;
    }

    private static AreaFetchResult fetchArea(
            String endpoint,
            String serviceKey,
            String mobileApp,
            String baseYm,
            String areaCd,
            String signguCd,
            String ixCd,
            boolean expenditure) throws Exception {
        List<String> pageBodies = new ArrayList<>();
        int rowCount = 0;
        int apiTotalCount = -1;
        int httpCalls = 0;
        int lastResponseCode = 0; 
        String sampleUrl = "";
        int pageNo = 1;

        while (true) {
            String pageUrl = buildPageUrl(
                    endpoint, serviceKey, mobileApp, pageNo, baseYm, areaCd, signguCd, ixCd, expenditure);
            if (pageNo == 1) {
                sampleUrl = pageUrl;
            }
            HttpResult page = httpGet(pageUrl);
            httpCalls++;
            lastResponseCode = page.responseCode;

            if (page.body == null || page.body.isBlank()) {
                break;
            }

            int pageItems = countXmlItems(page.body);
            if (pageNo == 1) {
                apiTotalCount = parseTotalCount(page.body);
            }

            if (pageItems > 0) {
                pageBodies.add(page.body);
                rowCount += pageItems;
            }

            if (pageItems == 0) {
                break;
            }
            if (apiTotalCount >= 0 && rowCount >= apiTotalCount) {
                break;
            }
            if (pageItems < PAGE_SIZE) {
                break;
            }
            pageNo++;
        }

        return new AreaFetchResult(pageBodies, rowCount, apiTotalCount, httpCalls, lastResponseCode, sampleUrl);
    }

    private static final class AreaFetchResult {
        final List<String> pageBodies;
        final int rowCount;
        final int apiTotalCount;
        final int httpCalls;
        final int lastResponseCode;
        final String sampleUrl;

        AreaFetchResult(
                List<String> pageBodies,
                int rowCount,
                int apiTotalCount,
                int httpCalls,
                int lastResponseCode,
                String sampleUrl) {
            this.pageBodies = pageBodies;
            this.rowCount = rowCount;
            this.apiTotalCount = apiTotalCount;
            this.httpCalls = httpCalls;
            this.lastResponseCode = lastResponseCode;
            this.sampleUrl = sampleUrl;
        }
    }

    private static String buildPageUrl(
            String endpoint,
            String serviceKey,
            String mobileApp,
            int pageNo,
            String baseYm,
            String areaCd,
            String signguCd,
            String ixCd,
            boolean expenditure) {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL).append(endpoint).append('?');
        appendParam(urlBuilder, "serviceKey", serviceKey);
        appendParam(urlBuilder, "MobileApp", mobileApp);
        appendParam(urlBuilder, "MobileOS", enc("ETC"));
        appendParam(urlBuilder, "pageNo", enc(String.valueOf(pageNo)));
        appendParam(urlBuilder, "numOfRows", enc(String.valueOf(PAGE_SIZE)));
        appendParam(urlBuilder, "baseYm", enc(baseYm));
        appendParam(urlBuilder, "areaCd", enc(areaCd));
        if (signguCd != null) {
            appendParam(urlBuilder, "signguCd", enc(signguCd));
        }
        if (expenditure) {
            appendParam(urlBuilder, "tarExpDsIxCd", enc(ixCd));
        } else {
            appendParam(urlBuilder, "tarSjrnDsIxCd", enc(ixCd));
        }
        appendParam(urlBuilder, "_type", enc("xml"));
        return urlBuilder.toString();
    }

    private static HttpResult httpGet(String urlString) throws Exception {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        InputStream is = (responseCode >= 200 && responseCode <= 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
        String body = "";
        if (is != null) {
            try (BufferedReader rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }
        }
        conn.disconnect();
        return new HttpResult(responseCode, body);
    }

    private static int parseTotalCount(String xml) {
        if (xml == null) {
            return -1;
        }
        int start = xml.indexOf("<totalCount>");
        if (start < 0) {
            return -1;
        }
        start += "<totalCount>".length();
        int end = xml.indexOf("</totalCount>", start);
        if (end < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(xml.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int countXmlItems(String xml) throws Exception {
        if (xml == null || xml.isBlank()) {
            return 0;
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName("item");
        if (items.getLength() == 0) {
            items = doc.getElementsByTagNameNS("*", "item");
        }
        return items.getLength();
    }

    private static final class HttpResult {
        final int responseCode;
        final String body;

        HttpResult(int responseCode, String body) {
            this.responseCode = responseCode;
            this.body = body;
        }
    }

    private static String argOr(String[] args, int idx, String defaultVal) {
        if (idx < 0 || idx >= args.length || args[idx] == null || args[idx].isBlank()) {
            return defaultVal;
        }
        return args[idx];
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static void appendParam(StringBuilder sb, String name, String encodedValue) {
        if (sb.charAt(sb.length() - 1) != '?') {
            sb.append('&');
        }
        sb.append(name).append('=').append(encodedValue);
    }

    private static String xmlBodiesToCsv(List<String> xmlPages) {
        if (xmlPages == null || xmlPages.isEmpty()) {
            return escapeCsv("message") + "\n" + escapeCsv("empty response");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            List<String> columns = null;
            StringBuilder out = new StringBuilder();
            int totalRows = 0;

            for (String xml : xmlPages) {
                if (xml == null || xml.isBlank()) {
                    continue;
                }
                Document doc = f.newDocumentBuilder()
                        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                NodeList items = doc.getElementsByTagName("item");
                if (items.getLength() == 0) {
                    items = doc.getElementsByTagNameNS("*", "item");
                }
                if (items.getLength() == 0) {
                    continue;
                }

                if (columns == null) {
                    Element first = (Element) items.item(0);
                    columns = new ArrayList<>();
                    NodeList ch = first.getChildNodes();
                    for (int i = 0; i < ch.getLength(); i++) {
                        Node n = ch.item(i);
                        if (n instanceof Element) {
                            columns.add(((Element) n).getTagName());
                        }
                    }
                    if (columns.isEmpty()) {
                        return escapeCsv("message") + "\n" + escapeCsv("item has no child elements");
                    }
                    for (int c = 0; c < columns.size(); c++) {
                        if (c > 0) {
                            out.append(',');
                        }
                        out.append(escapeCsv(columns.get(c)));
                    }
                    out.append('\n');
                }

                for (int r = 0; r < items.getLength(); r++) {
                    Element row = (Element) items.item(r);
                    for (int c = 0; c < columns.size(); c++) {
                        if (c > 0) {
                            out.append(',');
                        }
                        out.append(escapeCsv(directChildText(row, columns.get(c))));
                    }
                    out.append('\n');
                    totalRows++;
                }
            }

            if (columns == null || totalRows == 0) {
                return escapeCsv("message") + "\n"
                        + escapeCsv("no <item> in XML (totalCount 0 이거나 오류 응답일 수 있음)");
            }
            return out.toString();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return escapeCsv("parse_error") + "\n" + escapeCsv(msg);
        }
    }

    private static String directChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if (tagName.equals(e.getTagName()) || tagName.equals(e.getLocalName())) {
                    return e.getTextContent() != null ? e.getTextContent().trim() : "";
                }
            }
        }
        return "";
    }

    private static String escapeCsv(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r\n", "\n").replace('\r', '\n');
        boolean needQuote = t.indexOf(',') >= 0 || t.indexOf('"') >= 0 || t.indexOf('\n') >= 0;
        String escaped = t.replace("\"", "\"\"");
        if (needQuote) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private static String previewCsv(String csv, int maxLines) {
        String[] lines = csv.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();
        int n = Math.min(maxLines, lines.length);
        for (int i = 0; i < n; i++) {
            sb.append(lines[i]).append('\n');
        }
        if (lines.length > maxLines) {
            sb.append("... (총 ").append(lines.length).append(" 줄)\n");
        }
        return sb.toString();
    }
}
