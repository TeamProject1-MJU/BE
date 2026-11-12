package com.mju.linkro.subway.station.service;

import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.station.repository.StationReadRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JdbcLineLookup implements LineLookup {
    private final StationReadRepository reads;

    @Override
    public Optional<String> name(long lineId) {
        return reads.lineName(lineId);
    }
}
