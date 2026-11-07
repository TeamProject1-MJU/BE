package com.mju.linkro.subway.congestion.load;

import java.util.Map;

/** Station name matching between AI files and stations.name. Both sides are normalized the same way. */
public final class StationNames {
    /** AI name -> our stations.name. 7호선 이수 is the merged station 총신대입구(이수). */
    static final Map<String, String> ALIASES = Map.of("이수", "총신대입구(이수)");

    private StationNames() {}

    /** Alias, then trim, drop parenthesised parts and a trailing "역" (서울역 -> 서울, 신촌(지하) -> 신촌). */
    public static String normalize(String raw) {
        String name = raw.strip();
        name = ALIASES.getOrDefault(name, name);
        name = name.replaceAll("\\s*\\([^)]*\\)", "").strip();
        if (name.endsWith("역") && name.length() > 1) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }
}
