package com.mju.linkro.subway.api;

import java.util.Optional;

/** Line names for other domains (read-only, subway_lines table only). */
public interface LineLookup {

    /** The line's name as shown to users (e.g. "2호선"), or empty when there is no such line. */
    Optional<String> name(long lineId);
}
