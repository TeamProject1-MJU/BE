"""Summarizes raw responses in scratch/ against the V11 seed graph. Prints a summary only; never writes raw data.

Usage (from the repository root):
    python3 scripts/kakao_probe/analyze.py
"""
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(__file__).resolve().parent / "scratch"
sys.path.insert(0, str(ROOT / "data"))
import build_subway_seed as seed  # noqa: E402

LINE_PATTERN = re.compile(r"^(?:수도권\s*)?([1-8])호선$")


def graph():
    edges = seed.read_csv(seed.EDGES)
    nodes = seed.load_coords([])
    station_lines = seed.build_station_lines(edges, nodes)
    names = defaultdict(set)
    for line, name, _, _ in station_lines:
        names[line].add(name)
    directions = {(e["line"], e["from_station"], e["to_station"]): e["direction"] for e in edges}
    return names, directions


def candidates(raw):
    """Name variants tried in order: as given, without parentheses, without a trailing 역, both."""
    no_paren = re.sub(r"\s*\(.*?\)\s*", "", raw).strip()
    variants = [raw.strip(), no_paren]
    for value in list(variants):
        if value.endswith("역") and len(value) > 2:
            variants.append(value[:-1])
    return list(dict.fromkeys(variants))


def match(line, raw, names):
    for index, value in enumerate(candidates(raw)):
        if value in names[line]:
            return value, ["exact", "no_paren", "no_suffix", "no_paren_suffix"][min(index, 3)]
    return None, None


def main():
    names, directions = graph()
    files = sorted(SCRATCH.glob("*.json"))
    elapsed, route_keys, step_keys, fare_keys, vehicle_types = [], Counter(), Counter(), Counter(), Counter()
    route_types, step_types, line_names, statuses = Counter(), Counter(), Counter(), Counter()
    walking = []
    subway_steps = 0
    unmatched_lines, unmatched_stops, match_kinds = Counter(), Counter(), Counter()
    total_stops = 0
    gaps, direction_mixed, direction_kinds = [], [], Counter()
    first_subway = []
    for path in files:
        record = json.loads(path.read_text(encoding="utf-8"))
        elapsed.append(record["elapsed_ms"])
        body = json.loads(record["body"])
        statuses[body.get("status")] += 1
        label = f"{record['id']:02d} {record['origin']}->{record['destination']}"
        for route_index, route in enumerate(body.get("routes", [])):
            properties = route["properties"]
            route_keys.update(properties.keys())
            fare_keys.update(properties.get("fare", {}).keys())
            route_types[properties["type"]] += 1
            for step in route["steps"]:
                step_properties = step["properties"]
                step_keys.update(f"{step_properties['type']}.{key}" for key in step_properties)
                step_keys.update(f"{step_properties['type']}.path.{key}" for key in step.get("path", {}))
                step_types[step_properties["type"]] += 1
                for vehicle in step_properties.get("vehicles", []):
                    vehicle_types[(step_properties["type"], vehicle.get("type"))] += 1
                if step_properties["type"] == "WALKING":
                    walking.append((step_properties.get("distance"), step_properties.get("time"),
                                    len(step.get("path", {}).get("points", []))))
                if step_properties["type"] != "SUBWAY":
                    continue
                subway_steps += 1
                vehicle_names = [v["name"] for v in step_properties.get("vehicles", [])]
                line_names.update(vehicle_names)
                stops = [s["name"] for s in step_properties.get("stops", [])]
                total_stops += len(stops)
                if route_index == 0:
                    first_subway.append((label, vehicle_names, len(stops), stops[0] if stops else None,
                                         stops[-1] if stops else None))
                # A step may list several vehicles (e.g. branches); use the first that is one of our lines.
                line = next((m.group(1) for m in map(LINE_PATTERN.match, vehicle_names) if m), None)
                if line is None:
                    for name in vehicle_names:
                        unmatched_lines[name] += 1
                    continue
                matched = []
                for stop in stops:
                    node, kind = match(line, stop, names)
                    match_kinds[kind or "unmatched"] += 1
                    if node is None:
                        unmatched_stops[(line, stop)] += 1
                    matched.append(node)
                found = set()
                for a, b in zip(matched, matched[1:]):
                    if a is None or b is None:
                        continue
                    direction = directions.get((line, a, b))
                    if direction is None:
                        gaps.append((label, line, a, b))
                    else:
                        found.add(direction)
                direction_kinds[tuple(sorted(found))] += 1
                if len(found) > 1:
                    direction_mixed.append((label, line, sorted(found)))
    print(f"calls: {len(files)}, status: {dict(statuses)}")
    print(f"latency ms: avg {round(statistics.mean(elapsed))}, median {round(statistics.median(elapsed))}, "
          f"min {min(elapsed)}, max {max(elapsed)}")
    print(f"route types: {dict(route_types)}")
    print(f"step types: {dict(step_types)}")
    print(f"route property keys: {dict(route_keys)}")
    print(f"fare keys: {dict(fare_keys)}")
    print(f"step property keys: {dict(step_keys)}")
    print(f"vehicle types: {dict(vehicle_types)}")
    print(f"subway line names: {dict(line_names)}")
    print(f"walking steps: {len(walking)}, with distance {sum(w[0] is not None for w in walking)}, "
          f"with time {sum(w[1] is not None for w in walking)}, with path {sum(w[2] > 0 for w in walking)}")
    print(f"subway steps: {subway_steps}, stops: {total_stops}, match kinds: {dict(match_kinds)}")
    print(f"unmatched line names: {dict(unmatched_lines)}")
    print(f"unmatched stops: {dict(unmatched_stops)}")
    print(f"non-adjacent consecutive stops (gaps): {len(gaps)} {gaps[:10]}")
    print(f"direction sets per subway step: {dict(direction_kinds)}")
    print(f"steps with mixed directions: {direction_mixed}")
    print("first route subway steps:")
    for row in first_subway:
        print("  ", row)


if __name__ == "__main__":
    main()
