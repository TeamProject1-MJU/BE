package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.SubwayRouteFinder;
import com.mju.linkro.subway.api.SubwayRouteFinderContract;

/** #60 contract on the small hand-made graph. */
class DijkstraSubwayRouteFinderContractTest extends SubwayRouteFinderContract {
    private final SubwayRouteFinder finder = new DijkstraSubwayRouteFinder(GraphFixture.basic().graph(), GraphFixture.costs());

    @Override protected SubwayRouteFinder finder() { return finder; }
    @Override protected long origin() { return 1; }
    @Override protected long destination() { return 4; }
    @Override protected long missingStation() { return 999; }
    @Override protected long unreachableStation() { return 9; }
}
