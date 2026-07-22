import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 유료관광지방문객수조회 — openapi.tour.go.kr
 * 실행: javac ApiChecker.java && java ApiChecker [YM] [시도] [시군구] [관광지]
 */
public class ApiChecker {

    public static void main(String[] args) throws Exception {
        String serviceKey = System.getenv("TOUR_GO_KR_SERVICE_KEY");
        if (serviceKey == null || serviceKey.isBlank()) {
            serviceKey = System.getenv("DATA_GO_KR_SERVICE_KEY");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            serviceKey = "66a478965dedc52ede9955fb2313a277f384963cc354e8d1e4ea289fb0189d78";
        }

        String ym = args.length > 0 ? args[0] : "201201";
        String sido = args.length > 1 ? args[1] : "부산광역시";
        String gungu = args.length > 2 ? args[2] : "해운대구";
        String resNm = args.length > 3 ? args[3] : "부산시립미술관";

        String url = buildUrl(serviceKey, ym, sido, gungu, resNm);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);

        int httpCode = conn.getResponseCode();
        InputStream stream = httpCode >= 200 && httpCode <= 299
                ? conn.getInputStream()
                : conn.getErrorStream();
        String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        System.out.println("[유료관광지방문객수조회]");
        System.out.println("  YM=" + ym + "  시도=" + sido + "  시군구=" + gungu + "  관광지=" + resNm);
        System.out.println("  HTTP " + httpCode);
        System.out.println();

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

        String resultCode = tagText(doc, "resultCode");
        String resultMsg = tagText(doc, "resultMsg");
        System.out.println("  resultCode: " + resultCode);
        System.out.println("  resultMsg:  " + resultMsg);

        if (!"0000".equals(resultCode)) {
            return;
        }

        String totalCount = tagText(doc, "totalCount");
        NodeList items = doc.getElementsByTagName("item");
        System.out.println("  totalCount: " + (totalCount.isEmpty() ? items.getLength() : totalCount));
        System.out.println();

        if (items.getLength() == 0) {
            System.out.println("  (조회 결과 없음)");
            return;
        }

        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            System.out.println("--- #" + (i + 1) + " ---");
            printIfPresent(item, "ym", "년월");
            printIfPresent(item, "sido", "시도");
            printIfPresent(item, "gungu", "시군구");
            printIfPresent(item, "resNm", "관광지");
            printIfPresent(item, "addrCd", "지역코드");
            printIfPresent(item, "csForCnt", "외국인 방문객");
            printIfPresent(item, "csNatCnt", "내국인 방문객");
            printIfPresent(item, "csMvCnt", "이동 방문객");
            System.out.println();
        }
    }

    private static String buildUrl(String serviceKey, String ym, String sido, String gungu, String resNm)
            throws IOException {
        StringBuilder sb = new StringBuilder(
                "http://openapi.tour.go.kr/openapi/service/TourismResourceStatsService/getPchrgTrrsrtVisitorList?");
        append(sb, "serviceKey", serviceKey);
        append(sb, "YM", ym);
        append(sb, "SIDO", sido);
        append(sb, "GUNGU", gungu);
        append(sb, "RES_NM", resNm);
        append(sb, "pageNo", "1");
        append(sb, "numOfRows", "10");
        append(sb, "_type", "xml");
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, String value) throws IOException {
        if (sb.charAt(sb.length() - 1) != '?') {
            sb.append('&');
        }
        sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static void printIfPresent(Element item, String tag, String label) {
        String value = tagText(item, tag);
        if (!value.isEmpty()) {
            System.out.println("  " + label + ": " + value);
        }
    }

    private static String tagText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return "";
        }
        Node node = list.item(0);
        return node.getTextContent() != null ? node.getTextContent().trim() : "";
    }

    private static String tagText(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return "";
        }
        Node node = list.item(0);
        return node.getTextContent() != null ? node.getTextContent().trim() : "";
    }
}
