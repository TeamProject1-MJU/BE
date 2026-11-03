package com.mju.linkro.subway.graph;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Route cost settings ({@code linkro.subway.graph.*}). Defaults live in {@code subway-graph-defaults.properties};
 * application settings or environment variables override them.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "linkro.subway.graph")
public class SubwayGraphProperties {
    /** Added to every RIDE edge. API 14: no dwell time, so the default is 0. */
    private int dwellSeconds;
    /** Added to every TRANSFER edge on top of its walking time. */
    private int transferWaitSeconds;
    /** Train wait added to a branch transfer on top of its walking time. */
    private int branchTransferWaitSeconds;
    /** Walking time for a branch transfer at a junction missing from {@link #branchTransfers}. */
    private int defaultBranchTransferSeconds;
    /** Same-line transfer rows of public data 15044419 (excluded from V11 by ck_conn_not_self). */
    private List<BranchTransfer> branchTransfers = new ArrayList<>();

    @Getter
    @Setter
    public static class BranchTransfer {
        private String station;
        private String line;
        private int seconds;
        private int meters;

        public BranchTransfer() {}

        public BranchTransfer(String station, String line, int seconds, int meters) {
            this.station = station;
            this.line = line;
            this.seconds = seconds;
            this.meters = meters;
        }
    }

    void validate() {
        requireNonNegative("dwell-seconds", dwellSeconds);
        requireNonNegative("transfer-wait-seconds", transferWaitSeconds);
        requireNonNegative("branch-transfer-wait-seconds", branchTransferWaitSeconds);
        requireNonNegative("default-branch-transfer-seconds", defaultBranchTransferSeconds);
        for (BranchTransfer transfer : branchTransfers) {
            if (transfer.station == null || transfer.station.isBlank() || transfer.line == null || transfer.line.isBlank()) {
                throw new IllegalArgumentException("linkro.subway.graph.branch-transfers entries need station and line");
            }
            requireNonNegative("branch-transfers.seconds", transfer.seconds);
            requireNonNegative("branch-transfers.meters", transfer.meters);
        }
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("linkro.subway.graph." + name + " must not be negative: " + value);
        }
    }
}
