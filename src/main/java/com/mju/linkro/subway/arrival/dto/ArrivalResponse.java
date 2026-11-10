package com.mju.linkro.subway.arrival.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mju.linkro.subway.api.DirectionCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** GET /stations/{id}/arrivals data. Unavailable answers are still 200 with {@code available=false} and a reason. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "ArrivalData", requiredProperties = {"available", "reason", "receivedAt", "trains"})
public record ArrivalResponse(
        @Schema(description = "도착 정보를 받았으면 true. 열차가 0건이어도 true일 수 있다", example = "true") boolean available,
        @Schema(description = "available=false일 때 이유. 정상이면 null", types = {"string", "null"}) Reason reason,
        @Schema(description = "서버가 외부 응답을 받은 시각(Asia/Seoul 오프셋). 외부 응답이 없으면 null",
                example = "2026-11-10T08:00:05+09:00", types = {"string", "null"}) String receivedAt,
        @Schema(description = "secondsUntil 오름차순. secondsUntil이 없으면 stopsAway×120초로 대신 정렬하고, 둘 다 없으면 뒤. 최대 limit건") List<Train> trains) {

    public enum Reason {
        /** 1~8호선이 아니거나 그 역에 없는 노선, 또는 미지원 역 */
        UNSUPPORTED_STATION,
        /** 외부 응답 INFO-200(해당 데이터 없음) */
        NO_DATA,
        /** 외부 호출 실패: 다른 결과 코드, 네트워크 오류·타임아웃, 읽을 수 없는 본문 */
        UPSTREAM_ERROR,
        /** SEOUL_ARRIVAL_API_KEY 없음 */
        DISABLED
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "ArrivalTrainData", requiredProperties = {"destinationName", "directionCode", "secondsUntil", "stopsAway", "message", "lastTrain", "express"})
    public record Train(
            @Schema(description = "종착역(bstatnNm)", example = "장암") String destinationName,
            @Schema(description = "서울시 updnLine 기준(상행 UP, 하행 DOWN, 내선 INNER, 외선 OUTER). 그 밖이면 null. 1호선은 시드·경로의 UP/DOWN과 반대(임시 보정 중, #86)", example = "UP", types = {"string", "null"}) DirectionCode directionCode,
            @Schema(description = "도착까지 남은 초(외부 수신 시각 기준 보정). 알 수 없으면 null(예: barvlDt 0인 \"[N]번째 전역\")", example = "170", types = {"integer", "null"}) Integer secondsUntil,
            @Schema(description = "arvlMsg2가 \"[N]번째 전역\"이면 N, 아니면 null. N이 10을 넘는 열차는 응답에서 뺀다", example = "2", types = {"integer", "null"}) Integer stopsAway,
            @Schema(description = "외부 안내 문구(arvlMsg2) 그대로", example = "2분 50초 후") String message,
            @Schema(description = "lstcarAt == \"1\"", example = "false") boolean lastTrain,
            @Schema(description = "급행 열차(btrainSttus가 \"급행\"이거나 trainLineNm에 \"(급행)\")", example = "false") boolean express) {}

    public static ArrivalResponse unavailable(Reason reason, String receivedAt) {
        return new ArrivalResponse(false, reason, receivedAt, List.of());
    }
}
