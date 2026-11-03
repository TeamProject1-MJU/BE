package com.mju.linkro.subway.station.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public final class StationResponses {
    private StationResponses() {}
    public static final String LINE_SCHEMA = "SubwayLineData";
    public static final String STATION_LINE_SCHEMA = "StationLineData";
    public static final String STATION_SCHEMA = "StationData";
    public static final String PAGE_SCHEMA = "StationPageData";

    @Schema(name = LINE_SCHEMA, requiredProperties = {"id", "lineCode", "name", "color"})
    public record Line(@Schema(example = "2") long id, @Schema(example = "2") String lineCode,
                       @Schema(example = "2호선") String name, @Schema(example = "#00A84D") String color) {}

    @Schema(name = STATION_LINE_SCHEMA, requiredProperties = {"lineId", "lineName", "color", "stationCode"})
    public record StationLine(@Schema(example = "2") long lineId, @Schema(example = "2호선") String lineName,
                              @Schema(example = "#00A84D") String color,
                              @Schema(description = "station_lines.station_code", example = "222") String stationCode) {}

    @Schema(name = STATION_SCHEMA, requiredProperties = {"id", "name", "latitude", "longitude", "isSupported", "lines"})
    public record Station(@Schema(example = "105") long id, @Schema(example = "강남") String name,
                          @Schema(example = "37.4979") double latitude, @Schema(example = "127.0276") double longitude,
                          @Schema(description = "false면 혼잡도·추천·경로 출발/도착 미제공(\"미제공\" 배지)") boolean isSupported,
                          @Schema(description = "역이 속한 모든 노선. sort_order, id 순") List<StationLine> lines) {}

    @Schema(name = PAGE_SCHEMA, requiredProperties = {"items", "page", "size", "totalElements"})
    public record StationPage(List<Station> items, @Schema(example = "0") int page, @Schema(example = "20") int size,
                              @Schema(example = "1") long totalElements) {}
}
