package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.DirectionCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SubwayGraphTest {

    @Test
    void unsupportedStationsGetNoNodesAndTheirEdgesAreDropped() {
        SubwayGraph graph = GraphFixture.basic().graph();

        // 10 node rows minus the unsupported station's node; 12 RIDE rows minus the two touching it.
        assertThat(graph.nodeCount()).isEqualTo(9);
        assertThat(graph.rideCount()).isEqualTo(10);
        assertThat(graph.transferCount()).isEqualTo(2);
        assertThat(graph.stationCount()).isEqualTo(7);
        assertThat(graph.nodesOf(8)).isEmpty();
    }

    @Test
    void emptyGraphFailsWithAClearMessage() {
        assertThatThrownBy(() -> SubwayGraph.of(new SubwayGraphData(List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Subway graph is empty (0 nodes, 0 edges)");
        var nodesOnly = new GraphFixture().line(1, "1").station(1, "A").node(11, 1, 1, 1, "MAIN");
        assertThatThrownBy(nodesOnly::graph).hasMessageContaining("1 nodes, 0 edges");
    }

    @Test
    void inconsistentEdgesAreLoggedAsWarnings(CapturedOutput output) {
        new GraphFixture().line(1, "1").line(2, "2").station(1, "A").station(2, "B")
                .node(11, 1, 1, 1, "MAIN").node(12, 1, 2, 1, "MAIN").node(21, 2, 1, 2, "MAIN").node(22, 2, 2, 2, "MAIN")
                .ride(11, 22, DirectionCode.DOWN, 100, 1000)   // RIDE across lines
                .transfer(11, 21, 60, 50)                      // TRANSFER between stations
                .transfer(21, 21, 60, 50)                      // TRANSFER on one line
                .transfer(11, 12, 60, 50)                      // valid
                .graph();

        assertThat(output.getAll()).contains("Subway RIDE edge 1 joins different lines (1 -> 2)")
                .contains("Subway TRANSFER edge 2 must join one station on different lines")
                .contains("Subway TRANSFER edge 3 must join one station on different lines")
                .doesNotContain("Subway TRANSFER edge 4");
    }
}
