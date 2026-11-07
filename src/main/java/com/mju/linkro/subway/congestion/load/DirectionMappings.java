package com.mju.linkro.subway.congestion.load;

import com.mju.linkro.subway.congestion.load.CongestionLoadPlan.Node;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AI direction -> seed direction rules, read from {@value #RESOURCE}. A rule matches a node by branch_code or by its
 * exact seed direction set; the first matching rule for (node, AI direction) applies.
 */
final class DirectionMappings {
    static final String RESOURCE = "/congestion-load/direction-mappings.csv";

    record Rule(String branch, Set<String> directions, String from, String to, String report) {
        boolean matches(Node node, String direction) {
            if (!from.equals(direction)) return false;
            return branch != null ? branch.equals(node.branchCode()) : directions.equals(node.directions());
        }
    }

    private final List<Rule> rules;

    private DirectionMappings(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    static DirectionMappings load() {
        try (InputStream in = DirectionMappings.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Header {@code match,from,to,report}; blank lines and lines starting with # are ignored. */
    static DirectionMappings parse(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean header = true;
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            String[] cells = text.split(",", -1);
            if (header) {
                if (!text.equals("match,from,to,report")) throw new IllegalArgumentException("unexpected header: " + text);
                header = false;
                continue;
            }
            if (cells.length != 4) throw new IllegalArgumentException("expected match,from,to,report: " + text);
            String match = cells[0].strip();
            String from = cells[1].strip();
            Rule rule;
            if (match.startsWith("branch=")) {
                rule = new Rule(match.substring("branch=".length()), null, from, cells[2].strip(), cells[3].strip());
            } else if (match.startsWith("directions=")) {
                rule = new Rule(null, Set.of(match.substring("directions=".length()).split("\\+")), from, cells[2].strip(), cells[3].strip());
            } else {
                throw new IllegalArgumentException("match must be branch=... or directions=...: " + text);
            }
            if (!seen.add(match + "|" + from)) throw new IllegalArgumentException("two rules for " + match + " " + from);
            rules.add(rule);
        }
        return new DirectionMappings(rules);
    }

    Optional<Rule> find(Node node, String direction) {
        return rules.stream().filter(rule -> rule.matches(node, direction)).findFirst();
    }

    /** Report labels in file order, each once. */
    List<String> reports() {
        return rules.stream().map(Rule::report).distinct().toList();
    }
}
