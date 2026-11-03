package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Real routes and fares when {@code linkro.subway.module=graph}. The graph is loaded once at startup. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.subway.module", havingValue = "graph")
@EnableConfigurationProperties({SubwayGraphProperties.class, SubwayFareProperties.class})
@PropertySource(value = "classpath:subway-graph-defaults.properties", encoding = "UTF-8")
public class SubwayGraphModuleConfig {

    @Bean
    SubwayGraph subwayGraph(NamedParameterJdbcTemplate jdbc) {
        long started = System.nanoTime();
        SubwayGraph graph = SubwayGraph.of(new SubwayGraphLoader(jdbc).load());
        log.info("Subway graph loaded: {} nodes, {} RIDE and {} TRANSFER edges, {} stations in {} ms",
                graph.nodeCount(), graph.rideCount(), graph.transferCount(), graph.stationCount(),
                (System.nanoTime() - started) / 1_000_000);
        return graph;
    }

    @Bean
    SubwayRouteFinder subwayRouteFinder(SubwayGraph graph, SubwayGraphProperties costs) {
        return new DijkstraSubwayRouteFinder(graph, costs);
    }

    @Bean
    FareCalculator fareCalculator(SubwayFareProperties fare) {
        return new GraphFareCalculator(fare);
    }
}
