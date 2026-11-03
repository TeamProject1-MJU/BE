package com.mju.linkro.subway.congestion.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Minimal RFC 4180 reader (quoted fields, doubled quotes, UTF-8 BOM). Values stay strings; nothing is parsed as double. */
final class CongestionCsv {
    static final List<String> REQUIRED = List.of("line_code", "station_name", "direction", "day_type", "slot_start",
            "source", "congestion_pct", "congestion_level", "congestion_level_label", "model_version");

    private CongestionCsv() {}

    static List<Map<String, String>> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> records = parse(text);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("CSV is empty: " + file);
        }
        List<String> header = records.get(0);
        List<String> missing = REQUIRED.stream().filter(column -> !header.contains(column)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("CSV is missing columns " + missing);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() == 1 && record.get(0).isEmpty()) {
                continue;
            }
            if (record.size() != header.size()) {
                throw new IllegalArgumentException("CSV line " + (i + 1) + " has " + record.size() + " fields, expected " + header.size());
            }
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), record.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    static List<List<String>> parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV ends inside a quoted field");
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
