package com.mju.linkro.subway.fare;

import com.mju.linkro.subway.api.StationFareLookup;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Seoul Metro fare table (fare-table.csv.gz, #109) by station pair. The file is by external station code
 * ({@code from_code,to_code,distance_km,fare_won}, the station_lines.station_code of our nodes); codes are mapped to
 * stations, and when a pair of stations has several code combinations (transfer stations) the lowest fare is kept.
 * Adult transit-card base fare. Immutable.
 */
public final class FareTable implements StationFareLookup {
    static final String HEADER = "from_code,to_code,distance_km,fare_won";

    /** What loading kept: file rows, rows whose codes are not ours, station pairs, pairs with several code combinations. */
    public record LoadReport(int rows, int unmappedRows, Set<String> unmappedCodes, int stationPairs, int multiCodePairs,
                             int multiCodePairsWithDifferentFares, long approxBytes) {}

    private final Map<Long, Integer> index;
    private final int[] fares;
    private final LoadReport report;

    private FareTable(Map<Long, Integer> index, int[] fares, LoadReport report) {
        this.index = index;
        this.fares = fares;
        this.report = report;
    }

    /** A table without pairs: every lookup is empty, so callers use their own estimate. */
    public static FareTable empty() {
        return new FareTable(Map.of(), new int[0], new LoadReport(0, 0, Set.of(), 0, 0, 0, 0));
    }

    /**
     * @param stationByCode station_lines.station_code -> station id
     * @throws IllegalStateException on a wrong header or malformed row
     */
    public static FareTable load(InputStream gzip, Map<String, Long> stationByCode) throws IOException {
        Map<Long, Integer> index = new HashMap<>();
        new TreeSet<>(stationByCode.values()).forEach(id -> index.put(id, index.size()));
        int size = index.size();
        int[] fares = new int[size * size];
        int[] combinations = new int[size * size];
        boolean[] differing = new boolean[size * size];
        Arrays.fill(fares, -1);
        int rows = 0;
        int unmapped = 0;
        Set<String> unmappedCodes = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(gzip), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !HEADER.equals(header.replace("\uFEFF", "").strip())) {
                throw new IllegalStateException("Fare table header must be: " + HEADER);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rows++;
                String[] f = line.split(",", -1);
                if (f.length != 4) throw new IllegalStateException("Fare table row " + rows + " has " + f.length + " columns");
                Long from = stationByCode.get(f[0].strip());
                Long to = stationByCode.get(f[1].strip());
                if (from == null || to == null) {
                    unmapped++;
                    if (from == null) unmappedCodes.add(f[0].strip());
                    if (to == null) unmappedCodes.add(f[1].strip());
                    continue;
                }
                int fare;
                try {
                    fare = Integer.parseInt(f[3].strip());
                } catch (NumberFormatException exception) {
                    throw new IllegalStateException("Fare table row " + rows + ": fare_won must be an integer");
                }
                if (from.equals(to)) continue;
                int cell = index.get(from) * size + index.get(to);
                if (fares[cell] >= 0 && fares[cell] != fare) differing[cell] = true;
                fares[cell] = fares[cell] < 0 ? fare : Math.min(fares[cell], fare);
                combinations[cell]++;
            }
        }
        int pairs = 0;
        int multi = 0;
        int multiDiffering = 0;
        for (int cell = 0; cell < fares.length; cell++) {
            if (fares[cell] < 0) continue;
            pairs++;
            if (combinations[cell] > 1) multi++;
            if (differing[cell]) multiDiffering++;
        }
        long bytes = 4L * fares.length + 48L * index.size();
        return new FareTable(Map.copyOf(index), fares,
                new LoadReport(rows, unmapped, Collections.unmodifiableSet(unmappedCodes), pairs, multi, multiDiffering, bytes));
    }

    @Override
    public OptionalInt fareBetween(long fromStationId, long toStationId) {
        Integer from = index.get(fromStationId);
        Integer to = index.get(toStationId);
        if (from == null || to == null) return OptionalInt.empty();
        int fare = fares[from * index.size() + to];
        return fare < 0 ? OptionalInt.empty() : OptionalInt.of(fare);
    }

    @Override
    public boolean loaded() {
        return report.stationPairs() > 0;
    }

    public LoadReport report() {
        return report;
    }
}
