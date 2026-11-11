package com.mju.linkro.room;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class RoomPropertiesTest {
    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(RoomConfig.class);
    @Test void defaultsAndOverridesAreBound() {
        context.run(c->assertThat(c.getBean(RoomProperties.class).getMaxStationProposals()).isEqualTo(9));
        context.withPropertyValues("linkro.room.max-station-proposals=2")
                .run(c->assertThat(c.getBean(RoomProperties.class).getMaxStationProposals()).isEqualTo(2));
    }
    @Test void rejectsZeroAndNegativeLimits() {
        for(int limit:new int[]{0,-1}) context.withPropertyValues("linkro.room.max-station-proposals="+limit)
                .run(c->assertThat(c).hasFailed());
    }
}
