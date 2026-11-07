package com.mju.linkro.subway.congestion.holiday;

/** Hand-written responses in the getRestDeInfo JSON shape (공공데이터포털 문서 기준); no real response is used. */
final class KasiStub {
    private KasiStub() {}

    static String ok(String items, int totalCount) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":" + items + ",\"numOfRows\":10,\"pageNo\":1,\"totalCount\":" + totalCount + "}}}";
    }

    static String item(String name, String locdate, String isHoliday) {
        return "{\"dateKind\":\"01\",\"dateName\":\"" + name + "\",\"isHoliday\":\"" + isHoliday + "\",\"locdate\":" + locdate + ",\"seq\":1}";
    }

    static String array(String... items) {
        return "{\"item\":[" + String.join(",", items) + "]}";
    }

    static String single(String item) {
        return "{\"item\":" + item + "}";
    }

    static final String EMPTY = ok("\"\"", 0);
}
