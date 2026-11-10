package com.mju.linkro.subway.arrival;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Query name for the arrival API: the exception in arrival/station-query-names.csv, else our name trimmed. */
@Component
public class ArrivalStationNames {
    static final String RESOURCE = "/arrival/station-query-names.csv";
    private static final String HEADER = "line_code,station_name,query_name";

    private final Map<String, String> names;

    public ArrivalStationNames() {
        this(load());
    }

    ArrivalStationNames(Map<String, String> names) {
        this.names = Map.copyOf(names);
    }

    public String queryName(String lineCode, String stationName) {
        String name = stationName.strip();
        return names.getOrDefault(lineCode + "|" + name, name);
    }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> names = new HashMap<>();
        boolean header = true;
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (header) {
                if (!text.equals(HEADER)) throw new IllegalArgumentException("unexpected header: " + text);
                header = false;
                continue;
            }
            String[] cells = text.split(",", -1);
            if (cells.length != 3) throw new IllegalArgumentException("expected " + HEADER + ": " + text);
            if (names.put(cells[0].strip() + "|" + cells[1].strip(), cells[2].strip()) != null) {
                throw new IllegalArgumentException("duplicate row: " + text);
            }
        }
        return names;
    }

    private static Map<String, String> load() {
        try (InputStream in = ArrivalStationNames.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
