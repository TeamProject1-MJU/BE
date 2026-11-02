"""Build the Flyway seed migration for subway lines, stations, station_lines and station_connections.

Usage (from the repository root):
    python3 data/build_subway_seed.py [output.sql]

The default output is src/main/resources/db/migration/V11__seed_subway_data.sql.
The output depends only on the input files: rows are sorted and no random values or timestamps are used.
"""
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
DEFAULT_OUTPUT = ROOT / "src/main/resources/db/migration/V11__seed_subway_data.sql"

COORDS = DATA / "raw/stations_coords_20250814_utf8.csv"
TRANSFERS = DATA / "raw/transfers_20260831_utf8.csv"
EDGES = DATA / "edges.csv"
OVERRIDES = DATA / "station_overrides.csv"

LINES = [
    ("1", "1호선", "#0052A4"), ("2", "2호선", "#00A84D"), ("3", "3호선", "#EF7C1C"), ("4", "4호선", "#00A5DE"),
    ("5", "5호선", "#996CAC"), ("6", "6호선", "#CD7C2F"), ("7", "7호선", "#747F00"), ("8", "8호선", "#E6186C"),
]
LINE_CODES = [code for code, _, _ in LINES]

# Coordinate file names -> names used in edges.csv (per line).
COORD_ALIASES = {("1", "서울"): "서울역", ("4", "서울"): "서울역", ("7", "뚝섬유원지"): "자양", ("7", "총신대입구"): "이수"}
# Node names (edges.csv) that are one station across lines -> stations.name.
MERGED_NAMES = {"총신대입구": "총신대입구(이수)", "이수": "총신대입구(이수)"}
# The first MAIN station of a loop line, where seq starts.
LOOP_START = {"2": "시청"}
FORWARD = {"DOWN", "INNER", "LOOP"}


def read_csv(path):
    with open(path, encoding="utf-8-sig", newline="") as file:
        return list(csv.DictReader(file))


def station_name(node_name):
    return MERGED_NAMES.get(node_name, node_name)


def sql_text(value):
    return "'" + value.replace("'", "''") + "'"


def load_coords(report):
    nodes = {}
    for row in read_csv(COORDS):
        line, raw_name = row["호선"].strip(), row["역명"].strip()
        name = COORD_ALIASES.get((line, raw_name), raw_name)
        if name != raw_name:
            report.append(f"alias: {line}호선 {raw_name} -> {name}")
        nodes[(line, name)] = {
            "code": row["고유역번호(외부역코드)"].strip(),
            "lat": row["위도"].strip(),
            "lng": row["경도"].strip(),
        }
    applied = 0
    for row in read_csv(OVERRIDES):
        key = (row["line"].strip(), row["station"].strip())
        node = nodes.get(key)
        action = "updated" if node else "added"
        if node is None:
            if not row["station_code"].strip():
                raise SystemExit(f"override for missing station {key} needs station_code")
            node = nodes[key] = {"code": row["station_code"].strip()}
        node["lat"], node["lng"] = row["latitude"].strip(), row["longitude"].strip()
        if row["station_code"].strip():
            node["code"] = row["station_code"].strip()
        applied += 1
        report.append(f"override {action}: {key[0]}호선 {key[1]} ({node['lat']}, {node['lng']}, code {node['code']}) - {row['reason']}")
    report.append(f"overrides applied: {applied}")
    return nodes


def chain(edges, start):
    """Follows forward edges from start until the path ends or returns to a visited node."""
    following = {edge["from_station"]: edge["to_station"] for edge in edges if edge["direction"] in FORWARD}
    order, current = [start], start
    while current in following and following[current] not in order:
        current = following[current]
        order.append(current)
    return order


def build_station_lines(edges, nodes):
    by_branch = defaultdict(list)
    for edge in edges:
        by_branch[(edge["line"], edge["branch_code"])].append(edge)
    station_lines = []
    main_nodes = set()
    for line in LINE_CODES:
        main = by_branch[(line, "MAIN")]
        if line in LOOP_START:
            start = LOOP_START[line]
        else:
            targets = {edge["to_station"] for edge in main if edge["direction"] in FORWARD}
            starts = sorted({edge["from_station"] for edge in main if edge["direction"] in FORWARD} - targets)
            if len(starts) != 1:
                raise SystemExit(f"{line}호선 MAIN has no single start: {starts}")
            start = starts[0]
        order = chain(main, start)
        for seq, name in enumerate(order, 1):
            station_lines.append((line, name, "MAIN", seq))
            main_nodes.add((line, name))
        for branch in sorted(code for (branch_line, code) in by_branch if branch_line == line and code != "MAIN"):
            branch_edges = by_branch[(line, branch)]
            junctions = sorted({edge["from_station"] for edge in branch_edges if edge["direction"] in FORWARD
                                and (line, edge["from_station"]) in main_nodes})
            if len(junctions) != 1:
                raise SystemExit(f"{line}호선 {branch} has no single junction: {junctions}")
            only_branch = [name for name in chain(branch_edges, junctions[0]) if (line, name) not in main_nodes]
            for seq, name in enumerate(only_branch, 1):
                station_lines.append((line, name, branch, seq))
    known = {(line, name) for line, name, _, _ in station_lines}
    edge_nodes = {(edge["line"], name) for edge in edges for name in (edge["from_station"], edge["to_station"])}
    if known != edge_nodes:
        raise SystemExit(f"station_lines do not cover edges.csv: {sorted(edge_nodes ^ known)}")
    missing = sorted(known - set(nodes))
    if missing:
        raise SystemExit(f"no coordinates for {missing}")
    unused = sorted(set(nodes) - known)
    if unused:
        raise SystemExit(f"coordinate rows without edges: {unused}")
    return station_lines


def build_transfers(station_lines, report):
    by_station = defaultdict(dict)
    for line, name, _, _ in station_lines:
        by_station[station_name(name)][line] = name
    transfers = {}
    for row in read_csv(TRANSFERS):
        line, name, target = row["호선"].strip(), row["환승역명"].strip(), row["환승노선"].strip()
        label = f"{line}호선 {name} -> {target}"
        match = re.fullmatch(r"([1-8])호선", target)
        if line not in LINE_CODES or match is None:
            report.append(f"transfer excluded (not lines 1-8): {label}")
            continue
        to_line = match.group(1)
        if to_line == line:
            report.append(f"transfer excluded (same line): {label}")
            continue
        lines = by_station.get(station_name(name), {})
        if lines.get(line) != name:
            report.append(f"transfer excluded (no {line}호선 {name} in our data): {label}")
            continue
        if to_line not in lines:
            report.append(f"transfer excluded ({to_line}호선 {name} not in our data): {label}")
            continue
        minutes, seconds = row["환승소요시간"].strip().split(":")
        transfers[(line, name, to_line, lines[to_line])] = (int(minutes) * 60 + int(seconds), int(row["환승거리"].strip()))
    for station, lines in sorted(by_station.items()):
        for from_line in sorted(lines):
            for to_line in sorted(lines):
                key = (from_line, lines[from_line], to_line, lines[to_line])
                if from_line == to_line or key in transfers:
                    continue
                reverse = (to_line, lines[to_line], from_line, lines[from_line])
                if reverse not in transfers:
                    raise SystemExit(f"no transfer data in either direction for {station} {from_line}<->{to_line}")
                transfers[key] = transfers[reverse]
                report.append(f"transfer filled from reverse direction: {from_line}호선 {lines[from_line]} -> "
                              f"{to_line}호선 {lines[to_line]} = {transfers[key][0]}s, {transfers[key][1]}m")
    return transfers


def render(station_lines, nodes, edges, transfers):
    codes = {(line, name): nodes[(line, name)]["code"] for line, name, _, _ in station_lines}
    stations = {}
    for line, name, _, _ in station_lines:
        # Lines are visited 1..8, so a merged station keeps the lowest line's coordinates.
        stations.setdefault(station_name(name), nodes[(line, name)])
    out = [
        "-- Generated by data/build_subway_seed.py. Do not edit by hand; rerun the script and add a new migration.",
        "-- Sources: Seoul Metro open data (KOGL Type 1). See docs/subway-seed-51.md.",
        "",
        "insert into subway_lines (line_code, name, color, sort_order) values",
        ",\n".join(f"    ({sql_text(code)}, {sql_text(name)}, {sql_text(color)}, {order})"
                   for order, (code, name, color) in enumerate(LINES, 1)) + ";",
        "",
        "-- Stations are new rows; station_lines are linked to exactly the rows inserted here.",
        "with new_stations as (",
        "    insert into stations (name, latitude, longitude, is_supported) values",
        ",\n".join(f"        ({sql_text(name)}, {coords['lat']}, {coords['lng']}, true)" for name, coords in stations.items()),
        "    returning id, name",
        ")",
        "insert into station_lines (station_id, line_id, station_code, seq, branch_code)",
        "select s.id, l.id, v.station_code, v.seq, v.branch_code",
        "from (values",
        ",\n".join(f"    ({sql_text(station_name(name))}, {sql_text(line)}, {sql_text(codes[(line, name)])}, {seq}, {sql_text(branch)})"
                   for line, name, branch, seq in station_lines),
        ") as v (station_name, line_code, station_code, seq, branch_code)",
        "join new_stations s on s.name = v.station_name",
        "join subway_lines l on l.line_code = v.line_code",
        "order by l.sort_order, v.branch_code <> 'MAIN', v.branch_code, v.seq;",
        "",
    ]
    rows = []
    for edge in sorted(edges, key=lambda e: (e["line"], e["branch_code"] != "MAIN", e["branch_code"],
                                             e["from_station"], e["to_station"])):
        rows.append((edge["line"], codes[(edge["line"], edge["from_station"])], edge["line"],
                     codes[(edge["line"], edge["to_station"])], "RIDE", sql_text(edge["direction"]),
                     int(edge["travel_seconds"]), int(edge["distance_m"]),
                     f"{edge['line']}호선 {edge['from_station']} -> {edge['to_station']}"))
    for (from_line, from_name, to_line, to_name), (seconds, meters) in sorted(transfers.items()):
        rows.append((from_line, codes[(from_line, from_name)], to_line, codes[(to_line, to_name)], "TRANSFER", "null",
                     seconds, meters, f"{from_line}호선 {from_name} -> {to_line}호선 {to_name}"))
    out += [
        "-- Nodes are found by their natural key (line_code, station_code).",
        "insert into station_connections (from_station_line_id, to_station_line_id, connection_type, direction, travel_seconds, distance_m)",
        "select f.id, t.id, v.connection_type, v.direction, v.travel_seconds, v.distance_m",
        "from (values",
        # RIDE rows come first, so the direction column is typed as text before the TRANSFER nulls.
        "\n".join(f"    ({sql_text(fl)}, {sql_text(fc)}, {sql_text(tl)}, {sql_text(tc)}, {sql_text(kind)}, {direction}, {seconds}, {meters})"
                  f"{',' if index < len(rows) - 1 else ''} -- {label}"
                  for index, (fl, fc, tl, tc, kind, direction, seconds, meters, label) in enumerate(rows)),
        ") as v (from_line, from_code, to_line, to_code, connection_type, direction, travel_seconds, distance_m)",
        "join subway_lines fl on fl.line_code = v.from_line",
        "join station_lines f on f.line_id = fl.id and f.station_code = v.from_code",
        "join subway_lines tl on tl.line_code = v.to_line",
        "join station_lines t on t.line_id = tl.id and t.station_code = v.to_code",
        "order by v.connection_type, fl.sort_order, tl.sort_order, f.id, t.id;",
        "",
    ]
    return "\n".join(out), len(stations), len(rows) - len(transfers)


def main():
    output = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUTPUT
    report = []
    nodes = load_coords(report)
    edges = read_csv(EDGES)
    station_lines = build_station_lines(edges, nodes)
    transfers = build_transfers(station_lines, report)
    sql, station_count, ride_count = render(station_lines, nodes, edges, transfers)
    output.write_text(sql, encoding="utf-8")
    for line in report:
        print(line)
    print(f"subway_lines {len(LINES)} / stations {station_count} / station_lines {len(station_lines)} / "
          f"RIDE {ride_count} / TRANSFER {len(transfers)}")
    print(f"wrote {output.relative_to(ROOT) if output.is_relative_to(ROOT) else output}")


if __name__ == "__main__":
    main()
