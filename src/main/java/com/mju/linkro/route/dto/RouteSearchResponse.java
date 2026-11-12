package com.mju.linkro.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mju.linkro.subway.api.CongestionSource;
import com.mju.linkro.subway.api.DirectionCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** POST /routes/search data (6.1). Null fields are always written. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "RouteSearchData", requiredProperties = {"routeSource", "routes", "appointmentPassed", "notice"})
public record RouteSearchResponse(
        @Schema(description = "KAKAO: 카카오 대중교통 길찾기(지하철만). INTERNAL: 카카오 키 없음·실패·지하철 경로 없음·역 매칭 실패로 내부 다익스트라 경로, 또는 약속방 모드(약속이 남은 방 + 열차 시각표)의 시각표 경로(추천과 같은 계산, 카카오 호출 없음)") RouteSource routeSource,
        List<Route> routes,
        @Schema(description = "roomId의 약속 시각 자체가 이미 지났으면 true(추천의 APPOINTMENT_PASSED와 같은 초 단위 기준). 이때 출발은 요청 시각이고 departAt은 무시된다", example = "false") boolean appointmentPassed,
        @Schema(description = "appointmentPassed일 때 안내 문구. 아니면 null", example = "약속 시각이 이미 지나 지금 출발 기준으로 안내합니다.", types = {"string", "null"}) String notice) {

    public enum RouteSource { KAKAO, INTERNAL }

    /** KAKAO: Kakao fare.value. ESTIMATED: our RIDE-distance fare ("예상 요금"). */
    public enum FareSource { KAKAO, ESTIMATED }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RouteData", requiredProperties = {"routeId", "routeType", "summary", "steps"})
    public record Route(
            @Schema(description = "응답 안에서 경로를 구분하는 식별자", example = "r1") String routeId,
            @Schema(description = "FASTEST(소요시간 최소), FEWER_TRANSFERS(환승 최소), CHEAPEST(요금 최소) 중 처음 해당하는 것. 어느 것도 아니면 null",
                    example = "FASTEST", types = {"string", "null"}) String routeType,
            Summary summary, List<Step> steps) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RouteSummaryData", requiredProperties = {"totalTimeSec", "arrivalAt", "fare", "fareSource", "transferCount", "walkingMinutes", "congestion", "departureAt", "noService", "serviceNotice", "arrivesAfterAppointment"})
    public record Summary(
            @Schema(description = "소요시간(초). 약속방 모드는 arrivalAt − departureAt(열차 대기 포함). 그 밖에는 카카오 totalTime 또는 고정 비용(열차 대기 미포함 예상 값)", example = "1680") int totalTimeSec,
            @Schema(description = "departureAt + totalTimeSec (Asia/Seoul 오프셋)", example = "2026-11-06T08:28:00+09:00") String arrivalAt,
            @Schema(description = "원", example = "1550") int fare,
            @Schema(example = "KAKAO") FareSource fareSource,
            @Schema(example = "1") int transferCount,
            @Schema(description = "도보 시간 합(분, 반올림). 약속방 모드는 열차별 환승 도보 합(승강장 대기 제외)", example = "4") int walkingMinutes,
            @Schema(description = "supported 정차역 중 원값이 가장 큰 정차역의 값. 모두 데이터 없음이면 supported=false") Congestion congestion,
            @Schema(description = "출발 예정 시각(Asia/Seoul 오프셋). 약속방 모드는 늦어도 이때 나서면 약속에 도착하는 시각(추천 departureAt과 같음). noService면 첫차 기준으로 바꾼 시각", example = "2026-11-06T08:00:00+09:00") String departureAt,
            @Schema(description = "요청한 출발 시각에 이 경로를 운행하는 열차가 없어(첫차 전·막차 후) 출발을 첫차 기준으로 바꿨으면 true. 열차 시각표가 없으면 항상 false", example = "false") boolean noService,
            @Schema(description = "noService일 때 안내 문구. 아니면 null", example = "요청한 시각에는 이 경로를 운행하는 열차가 없어 첫차(11월 6일 05:36 출발) 기준으로 안내합니다.", types = {"string", "null"}) String serviceNotice,
            @Schema(description = "roomId의 방에 약속 시각이 있고 appointmentPassed가 false인데, 최종 arrivalAt이 약속 시각보다 늦으면 true(둘 다 초 단위로 자른 뒤 비교, 약속과 같은 시각 도착은 false). "
                    + "비운행이라 첫차로 밀려 늦어진 경우와, 약속 − 소요시간이 이미 지나 지금 출발로 바뀐 경우가 여기에 해당한다. 방·약속이 없으면 항상 false", example = "false") boolean arrivesAfterAppointment) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RouteCongestionData", requiredProperties = {"supported", "kind", "pct", "level", "levelLabel"})
    public record Congestion(
            @Schema(example = "true") boolean supported,
            @Schema(description = "출처. PREDICTED > ESTIMATED > MOCK. 데이터 없으면 null", example = "PREDICTED", types = {"string", "null"}) CongestionSource kind,
            @Schema(description = "정수. 원값 반올림, 단계 상한을 넘으면 상한 바로 아래 정수. 데이터 없으면 null", example = "93", types = {"integer", "null"}) Integer pct,
            @Schema(example = "NORMAL", types = {"string", "null"}) String level,
            @Schema(example = "보통", types = {"string", "null"}) String levelLabel) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RouteStepData", requiredProperties = {"type", "guidance", "durationSec", "distanceM", "line", "direction", "directionCode", "stops", "path", "stations"})
    public record Step(
            @Schema(description = "WALKING 또는 SUBWAY", example = "SUBWAY") String type,
            @Schema(example = "2호선 탑승, 4개 역 이동 후 사당 하차") String guidance,
            @Schema(example = "840") int durationSec,
            @Schema(example = "9400") int distanceM,
            @Schema(description = "SUBWAY만. WALKING은 null", types = {"object", "null"}) Line line,
            @Schema(description = "방면 문자열. 카카오가 방면을 주지 않아 항상 null", types = {"string", "null"}) String direction,
            @Schema(description = "SUBWAY 승차 방향(간선으로 계산). WALKING은 null", example = "INNER", types = {"string", "null"}) DirectionCode directionCode,
            @Schema(description = "SUBWAY: 승차역부터 하차역까지. WALKING: 빈 배열") List<Stop> stops,
            @Schema(description = "[경도, 위도] 배열. 없으면 null", types = {"array", "null"}) List<List<Double>> path,
            @Schema(description = "SUBWAY 정차역별 혼잡도(stops와 같은 순서). WALKING: 빈 배열") List<Station> stations) {}

    @Schema(name = "RouteLineData", requiredProperties = {"id", "name", "color"})
    public record Line(@Schema(example = "2") long id, @Schema(example = "2호선") String name, @Schema(example = "#00A84D") String color) {}

    @Schema(name = "RouteStopData", requiredProperties = {"id", "name", "order"})
    public record Stop(@Schema(example = "31") long id, @Schema(example = "강남") String name, @Schema(example = "1") int order) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RouteStationData", requiredProperties = {"id", "name", "slotStart", "slotDate", "firstTrain", "congestion"})
    public record Station(
            @Schema(example = "31") long id,
            @Schema(example = "강남") String name,
            @Schema(description = "조회한 30분 슬롯(HH:mm)", example = "08:00") String slotStart,
            @Schema(description = "조회한 영업일(yyyy-MM-dd)", example = "2026-11-06") String slotDate,
            @Schema(example = "false") boolean firstTrain,
            Congestion congestion) {}
}
