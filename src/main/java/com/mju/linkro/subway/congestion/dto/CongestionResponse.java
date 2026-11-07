package com.mju.linkro.subway.congestion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mju.linkro.subway.api.CongestionSource;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** GET /congestion data (6.2). Without data {@code supported} is false and pct, level, levelLabel, source are null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = CongestionResponse.SCHEMA, requiredProperties = {"stationId", "lineId", "direction", "dayType", "slotStart",
        "slotDate", "firstTrain", "supported", "pct", "level", "levelLabel", "source"})
public record CongestionResponse(
        @Schema(example = "31") long stationId,
        @Schema(example = "2") long lineId,
        @Schema(example = "INNER") DirectionCode direction,
        @Schema(description = "실제로 조회한 영업일의 요일 유형(공휴일은 SUNDAY). date가 없으면 요청한 dayType", example = "WEEKDAY") DayType dayType,
        @Schema(description = "조회한 30분 슬롯(HH:mm). 05:30~23:30, 00:00, 00:30", example = "18:30") String slotStart,
        @Schema(description = "조회한 영업일(yyyy-MM-dd). 00:00~00:59는 전날, 막차 이후 첫차면 다음 날. date를 보내지 않으면 null",
                example = "2026-11-06", types = {"string", "null"}) String slotDate,
        @Schema(description = "첫차(05:30) 슬롯으로 바꿔 조회했으면 true. 01:00~05:29, 또는 막차 이후 다음 영업일 05:30", example = "false") boolean firstTrain,
        @Schema(description = "false면 데이터 없음(회색): pct, level, levelLabel, source가 null", example = "true") boolean supported,
        @Schema(description = "정원 대비 %, 소수 1자리 내림. 데이터 없으면 null", example = "92.5", types = {"number", "null"}) BigDecimal pct,
        @Schema(description = "저장된 단계 그대로(COMFORTABLE/RELAXED/NORMAL/CROWDED/VERY_CROWDED). 데이터 없으면 null",
                example = "NORMAL", types = {"string", "null"}) String level,
        @Schema(description = "저장된 화면 라벨 그대로. 데이터 없으면 null", example = "보통", types = {"string", "null"}) String levelLabel,
        @Schema(description = "같은 슬롯에 여러 출처가 있으면 PREDICTED > ESTIMATED > MOCK 순으로 하나. ESTIMATED는 앞뒤 역 평균 추정값. 데이터 없으면 null",
                example = "PREDICTED", types = {"string", "null"}) CongestionSource source) {
    public static final String SCHEMA = "CongestionData";
}
