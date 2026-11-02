"""Calls the Kakao Map transit route API once per station pair and keeps raw responses in scratch/ only.

Usage (from the repository root):
    python3 scripts/kakao_probe/probe.py first   # pair 1 only (existence check)
    python3 scripts/kakao_probe/probe.py rest    # remaining pairs

The API key is read only from the KAKAO_REST_API_KEY environment variable and is never printed or written.
A pair whose raw file already exists is skipped, so no pair is called twice.
Raw responses must not be committed or kept (Kakao terms); scratch/ is git-ignored and should be deleted after analysis.
"""
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(__file__).resolve().parent / "scratch"
ENDPOINT = "https://dapi.kakao.com/v2/routing/publictraffic"
TIMEOUT_SECONDS = 10

# (id, origin, destination, purpose). Names are stations.name values from the V11 seed.
PAIRS = [
    (1, "강남", "홍대입구", "2호선 단일 노선"),
    (2, "홍대입구", "강남", "2호선 반대 방향(내선/외선)"),
    (3, "충정로", "시청", "2호선 순환 닫힘 INNER"),
    (4, "시청", "충정로", "2호선 순환 닫힘 OUTER"),
    (5, "건대입구", "용답", "성수지선"),
    (6, "신설동", "성수", "성수지선 반대 방향"),
    (7, "까치산", "영등포구청", "신정지선"),
    (8, "양천구청", "신도림", "신정지선 반대 방향"),
    (9, "마천", "강동", "5호선 마천지선"),
    (10, "둔촌동", "길동", "마천지선→하남 방면(강동 갈아타기)"),
    (11, "응암", "연신내", "6호선 응암순환"),
    (12, "구산", "새절", "응암순환→본선"),
    (13, "고속터미널", "총신대입구(이수)", "7호선 이수 표기"),
    (14, "총신대입구(이수)", "서울역", "4호선 총신대입구 표기"),
    (15, "서울역", "청량리", "1호선, 서울역 표기"),
    (16, "서울역", "사당", "4호선 서울역"),
    (17, "잠실", "고속터미널", "환승 1회"),
    (18, "남태령", "암사역사공원", "환승 2회 이상"),
    (19, "방화", "하남검단산", "장거리(5호선 끝~끝)"),
    (20, "장암", "온수", "장거리(7호선 끝~끝)"),
    (21, "지축", "오금", "장거리(3호선 끝~끝)"),
    (22, "시청", "을지로입구", "단거리(인접역)"),
    (23, "종각", "종로3가", "단거리(인접역)"),
    (24, "신내", "모란", "버스가 섞일 만한 구간"),
    (25, "당고개", "하남검단산", "버스가 섞일 만한 장거리"),
    (26, "암사역사공원", "모란", "8호선 끝~끝"),
]


def station_coords():
    """Coordinates exactly as the V11 seed builds them (aliases, overrides, lowest line wins)."""
    sys.path.insert(0, str(ROOT / "data"))
    import build_subway_seed as seed

    nodes = seed.load_coords([])
    station_lines = seed.build_station_lines(seed.read_csv(seed.EDGES), nodes)
    coords = {}
    for line, name, _, _ in station_lines:
        coords.setdefault(seed.station_name(name), nodes[(line, name)])
    return coords


def call(key, origin, destination, coords):
    o, d = coords[origin], coords[destination]
    query = urllib.parse.urlencode({
        "start_x": o["lng"], "start_y": o["lat"], "s_name": origin,
        "end_x": d["lng"], "end_y": d["lat"], "e_name": destination,
        "input_coord": "WGS84", "output_coord": "WGS84",
    })
    request = urllib.request.Request(f"{ENDPOINT}?{query}", headers={"Authorization": f"KakaoAK {key}"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            status, body = response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read().decode("utf-8", "replace")
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    return status, body, elapsed_ms


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode not in ("first", "rest"):
        raise SystemExit("usage: probe.py first|rest")
    key = os.environ.get("KAKAO_REST_API_KEY")
    if not key:
        raise SystemExit("KAKAO_REST_API_KEY is not set")
    coords = station_coords()
    SCRATCH.mkdir(exist_ok=True)
    targets = PAIRS[:1] if mode == "first" else PAIRS[1:]
    for pair_id, origin, destination, purpose in targets:
        raw = SCRATCH / f"{pair_id:02d}.json"
        if raw.exists():
            print(f"{pair_id:02d} skipped (already called)")
            continue
        status, body, elapsed_ms = call(key, origin, destination, coords)
        raw.write_text(json.dumps({"id": pair_id, "origin": origin, "destination": destination, "purpose": purpose,
                                   "http_status": status, "elapsed_ms": elapsed_ms, "body": body},
                                  ensure_ascii=False), encoding="utf-8")
        print(f"{pair_id:02d} {origin} -> {destination}: HTTP {status}, {elapsed_ms} ms")
        if status != 200:
            # Error bodies from Kakao do not contain the key; print only the first part for diagnosis.
            print(f"   body: {body[:300]}")
            if mode == "first":
                raise SystemExit(1)


if __name__ == "__main__":
    main()
