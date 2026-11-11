package com.mju.linkro.subway.fare;

import com.mju.linkro.subway.api.StationFareLookup;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Loads the fare table once at startup when {@code linkro.subway.module=graph} (#109). A missing or broken file does
 * not stop the application: the table is then empty and recommendation fares fall back to the ridden distance.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.subway.module", havingValue = "graph")
public class FareTableConfig {
    static final String RESOURCE = "fare-table.csv.gz";

    @Bean
    StationFareLookup stationFareLookup(NamedParameterJdbcTemplate jdbc) {
        long started = System.nanoTime();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Long> stationByCode = new HashMap<>();
            jdbc.query("select sl.station_code, sl.station_id from station_lines sl join stations s on s.id = sl.station_id "
                    + "where s.is_supported", Map.of(), (java.sql.ResultSet rs) -> {
                stationByCode.put(rs.getString("station_code"), rs.getLong("station_id"));
            });
            FareTable table = FareTable.load(in, stationByCode);
            FareTable.LoadReport r = table.report();
            log.info("Fare table loaded: {} rows, {} station pairs ({} with several code combinations, {} of them with different "
                            + "fares, lowest kept) in {} ms (~{} KB)", r.rows(), r.stationPairs(), r.multiCodePairs(),
                    r.multiCodePairsWithDifferentFares(), (System.nanoTime() - started) / 1_000_000,
                    String.format(Locale.ROOT, "%.0f", r.approxBytes() / 1024.0));
            if (!table.loaded()) {
                log.warn("Fare table {} has no station pairs; recommendation and route fares use the ridden distance", RESOURCE);
            }
            if (r.unmappedRows() > 0) {
                log.warn("Fare table: {} rows with station codes not in our graph were skipped: {}", r.unmappedRows(), r.unmappedCodes());
            }
            return table;
        } catch (Exception exception) {
            log.warn("Fare table {} could not be loaded ({}); recommendation and route fares use the ridden distance", RESOURCE,
                    exception.getClass().getSimpleName());
            return FareTable.empty();
        }
    }
}
