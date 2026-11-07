package com.mju.linkro.subway.congestion.load;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Neighbour-average estimation rules, read from {@value #RESOURCE}. */
final class Estimations {
    static final String RESOURCE = "/congestion-load/estimations.csv";
    private static final String HEADER = "line_code,station,direction,previous,next";

    /** Estimate (line, station, direction) from the same line's previous and next station. */
    record Rule(String lineCode, String station, String direction, String previous, String next) {}

    private final List<Rule> rules;

    private Estimations(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    static Estimations load() {
        try (InputStream in = Estimations.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Header {@value #HEADER}; blank lines and lines starting with # are ignored. */
    static Estimations parse(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        boolean header = true;
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (header) {
                if (!text.equals(HEADER)) throw new IllegalArgumentException("unexpected header: " + text);
                header = false;
                continue;
            }
            String[] cells = text.split(",", -1);
            if (cells.length != 5) throw new IllegalArgumentException("expected " + HEADER + ": " + text);
            Rule rule = new Rule(cells[0].strip(), cells[1].strip(), cells[2].strip(), cells[3].strip(), cells[4].strip());
            if (!targets.add(rule.lineCode() + "|" + rule.station() + "|" + rule.direction())) {
                throw new IllegalArgumentException("two rules for " + rule.lineCode() + "호선 " + rule.station() + " " + rule.direction());
            }
            rules.add(rule);
        }
        return new Estimations(rules);
    }

    List<Rule> rules() {
        return rules;
    }
}
