package com.mju.linkro.chat.service;

import com.mju.linkro.ai.api.ChatToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools of @링크로: eight read-only ones (#129) and run_recommendation (#168), the only one that writes. Each description says when to use the tool and when not, because the
 * AI has to choose between look-alikes (get_route, get_departure_plan, compare_members_travel). No tool takes a room
 * or user: they are fixed on the server.
 */
final class ChatToolSpecs {
    static final String GET_ROOM_INFO = "get_room_info";
    static final String LIST_MEMBERS = "list_members";
    static final String GET_LATEST_RECOMMENDATION = "get_latest_recommendation";
    static final String GET_DEPARTURE_PLAN = "get_departure_plan";
    static final String GET_ROUTE = "get_route";
    static final String GET_CONGESTION = "get_congestion";
    static final String SEARCH_STATIONS = "search_stations";
    static final String COMPARE_MEMBERS_TRAVEL = "compare_members_travel";
    static final String RUN_RECOMMENDATION = "run_recommendation";

    private static final String STATION_NAME = "역 이름. 끝의 '역'은 있어도 없어도 된다(예: 강남, 강남역).";
    private static final String FAILURES = " 실패하면 ok가 false이고 code에 이유가 온다. 그러면 추측하지 말고 앱의 경로 보기에서 확인하라고 안내한다.";

    private ChatToolSpecs() {}

    static List<ChatToolSpec> all() {
        return List.of(
                tool(GET_ROOM_INFO, "이 약속방의 이름, 약속 시각, 확정된 약속역, 멤버 수를 알려준다. 약속이 언제인지, 약속역이 정해졌는지 물을 때 쓴다."
                        + " 경로·시간 계산에는 쓰지 않는다.", Map.of(), List.of()),
                tool(LIST_MEMBERS, "멤버별 닉네임·역할(OWNER/MEMBER)·출발역과, 출발역을 아직 정하지 않은 멤버를 알려준다."
                        + " 누가 어디서 출발하는지 물을 때 쓴다. 이동 시간·요금은 알려주지 않는다.", Map.of(), List.of()),
                tool(GET_LATEST_RECOMMENDATION, "이 방에서 가장 최근에 받은 중간역 추천 결과(기준별 최적역, 멤버별 나서는 시각·첫 열차·도착·여유)를 알려준다."
                        + " '추천해줘', '추천 결과 알려줘'에 쓴다. 새 추천을 실행하지는 못한다. 추천이 없으면 code가 NO_RECOMMENDATION이니"
                        + " 앱의 추천받기 버튼으로 안내한다. 추천 이후 약속·멤버가 바뀌었으면 code가 STALE이고 시각은 오지 않으니"
                        + " 다시 추천받아 달라고 안내한다. 시간이 지나도 STALE이 되지는 않으니 멤버별 leaveAtPassed·boardAtPassed(또는 departuresPassed)가"
                        + " true인 시각은 이미 지난 것이다. 그 시각을 지금 쓸 수 있는 계획처럼 말하지 말고, 지금 출발 기준은 get_route로 안내한다." + FAILURES, Map.of(), List.of()),
                tool(GET_DEPARTURE_PLAN, "약속 시각에 맞추려면 언제 나서고 어느 열차를 타야 하는지 알려준다(나서는 시각, 첫 열차, 도착 시각, 환승 수, 소요 분, 여유)."
                        + " '약속 시간까지 몇 시 열차 타야 돼?', '몇 시에 나가야 해?'에 쓴다. 약속 시각이 있는 방에서만 되고,"
                        + " 약속이 없거나 지났으면 get_route를 쓴다. 여러 멤버를 비교하려면 compare_members_travel을 쓴다."
                        + " 결과의 leaveAtPassed가 true면 leaveAt은 이미 지난 시각이니 그 시각에 나서라고 하지 말고, get_route로 지금 출발 기준을 안내한다." + FAILURES,
                        Map.of("destinationStation", string("약속 장소 역 이름. " + STATION_NAME),
                                "member", string("나서는 시각을 알고 싶은 멤버 닉네임. 생략하면 질문한 사람.")),
                        List.of("destinationStation")),
                tool(GET_ROUTE, "출발역에서 도착역까지 가는 경로(소요 시간, 요금, 환승, 단계 요약)를 알려준다."
                        + " '어떻게 가?', '얼마나 걸려?', '요금이 얼마야?'에 쓴다. 약속이 있는 방은 약속에 맞춘 시각표 경로다."
                        + " 약속 시각까지 몇 시에 나서야 하는지는 get_departure_plan, 멤버 전원 비교는 compare_members_travel을 쓴다."
                        + " 결과의 arrivesAfterAppointment가 true면 지금 출발해도 약속에 늦는다는 뜻이고, appointmentPassed가 true면 약속 시각이 이미 지났고"
                        + " 지금 출발 기준 경로라는 뜻이다. 둘 다 약속에 맞는다고 말하지 않는다."
                        + " 시간은 셋으로 나뉜다. travelMinutes는 타고 가는 시간, waitMinutes는 출발 전 첫 열차를 기다리는 시간(없으면 필드가 오지 않음),"
                        + " totalWithWaitMinutes는 지금부터 도착까지 걸리는 시간(없으면 필드가 오지 않음)이다. '지금부터 얼마나 걸려?'는 totalWithWaitMinutes,"
                        + " '타고 가는 데 얼마나?'는 travelMinutes로 답한다." + FAILURES,
                        Map.of("originStation", string("출발역 이름. 생략하면 질문한 사람의 출발역. " + STATION_NAME),
                                "destinationStation", string("도착역 이름. " + STATION_NAME)),
                        List.of("destinationStation")),
                tool(GET_CONGESTION, "특정 역·노선·방향·시각의 혼잡도를 알려준다(통계 기반 예측값). '그 시간에 2호선 강남역 사람 많아?'에 쓴다."
                        + " 경로나 소요 시간을 알려주지는 않는다." + FAILURES,
                        Map.of("station", string(STATION_NAME), "line", string("노선 이름(예: 2호선)."),
                                "direction", enumeration("열차 방향.", "UP", "DOWN", "INNER", "OUTER", "LOOP"),
                                "time", string("통과 시각 HH:mm (한국 시간)."),
                                "date", string("통과 날짜 yyyy-MM-dd. 생략하면 오늘.")),
                        List.of("station", "line", "direction", "time")),
                tool(SEARCH_STATIONS, "역 이름 일부로 일치하는 역을 최대 5개 찾는다. 이름이 불확실하거나 같은 이름의 역이 여럿(code STATION_AMBIGUOUS)일 때"
                        + " 어떤 역인지 확인하려고 쓴다. 경로·혼잡도는 알려주지 않는다.",
                        Map.of("query", string("찾을 역 이름 또는 일부.")), List.of("query")),
                tool(COMPARE_MEMBERS_TRAVEL, "멤버 전원이 도착역까지 가는 데 걸리는 시간·요금·환승과 가장 오래 걸리는 멤버를 알려준다."
                        + " '누가 제일 오래 걸려?', '다 비슷하게 걸려?'에 쓴다. 한 사람의 경로는 get_route, 한 사람의 나서는 시각은"
                        + " get_departure_plan을 쓴다. 출발역이 없는 멤버는 따로 알려준다. totalMinutes는 타고 가는 시간(출발 전 대기 제외)이다." + FAILURES,
                        Map.of("destinationStation", string("도착역 이름. " + STATION_NAME)), List.of("destinationStation")),
                tool(RUN_RECOMMENDATION, "사용자가 조건을 말하며 중간역 추천을 해 달라고 할 때 추천을 새로 실행하고 이 방의 추천 기록에 저장한다(앱의 추천받기와 같다)."
                        + " '환승 1번 이하로 40분 안쪽인 역 추천해줘', '그냥 추천해줘'에 쓴다. 조건이 없으면 인자 없이 부른다."
                        + " 한 질문에 한 번만 실행되고, 두 번째 호출은 code가 ALREADY_RUN이다. 이미 받은 추천 결과를 알려 달라는 질문에는 쓰지 않고 get_latest_recommendation을 쓴다."
                        + " 인자가 범위를 벗어나면(code VALIDATION_ERROR) 실행하지 않으니 사용자에게 값을 다시 묻는다."
                        + " hasResults가 false면 조건에 맞는 역이 없다는 뜻이니 조건을 풀어 보자고 말로 안내한다."
                        + " 성공하면 bestStation(시간이 가장 짧은 역)과 appliedConstraints로 답하고, 앱의 [상세보기]에서 전체 결과를 볼 수 있다고 안내한다."
                        + " 실패하면 ok가 false이고 code에 이유가 온다(TOO_FEW_MEMBERS 멤버 2명 미만, DEPARTURE_MISSING 출발역 없는 멤버가 있음,"
                        + " APPOINTMENT_PASSED 약속 시각이 지남, NO_SERVICE 약속 시각 전에 도착하는 열차가 없음, RECOMMENDATION_IN_PROGRESS 이미 계산 중)."
                        + " 이유를 말로 알리고 추측하지 않는다.",
                        Map.of("maxMinutes", integer("모든 참여자의 이동 시간 상한(분), 1~300."),
                                "maxTransfers", integer("환승 횟수 상한, 0~10."),
                                "maxFareWon", integer("요금 상한(원), 1~100000."),
                                "excludeStations", Map.of("type", "array", "description", "추천에서 뺄 역 이름 목록. " + STATION_NAME, "items", Map.of("type", "string")),
                                "memberLimits", Map.of("type", "array", "description", "특정 멤버만의 이동 시간 상한 목록.",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of("nickname", string("멤버 닉네임."), "maxMinutes", integer("그 멤버의 이동 시간 상한(분), 1~300.")),
                                                "required", List.of("nickname", "maxMinutes"), "additionalProperties", false))),
                        List.of()));
    }

    private static ChatToolSpec tool(String name, String description, Map<String, Object> properties, List<String> required) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        parameters.put("additionalProperties", false);
        return new ChatToolSpec(name, description, parameters);
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> enumeration(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }
}
