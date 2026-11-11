package com.mju.linkro.room;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "linkro.room")
public class RoomProperties {
    @Min(1)
    private int maxStationProposals = 9;
}
