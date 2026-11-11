package com.mju.linkro.recommendation;

import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import com.mju.linkro.recommendation.service.Candidate.Member;
import com.mju.linkro.recommendation.service.RecommendationEngine;
import com.mju.linkro.recommendation.service.RecommendationEngine.Evaluation;
import com.mju.linkro.recommendation.service.RecommendationService;
import com.mju.linkro.recommendation.RecommendationProperties;
import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/**
 * Verification report of timetable routing (#96) on the V1~V15 seed: 10 station pairs at weekday 08:00 / 13:00 /
 * 22:00 and Saturday 14:00 (the appointment is the arrival deadline), checked train by train against the bundled file,
 * and the time one recommendation takes with and without the timetable. Writes build/reports/recommendation-96.md and
 * build/reports/recommendation-96-pairs.csv (station coordinates, for an out-of-repo Kakao comparison).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams=",
        "linkro.subway.module=graph", "linkro.ai.module=mock"})
@Timeout(600)
class RecommendationTimetableReportPostgresTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(SEOUL);
    static final LocalDate THURSDAY = LocalDate.of(2026, 11, 12);
    static final LocalDate SATURDAY = LocalDate.of(2026, 11, 14);
    static final String[][] PAIRS = {{"강남", "홍대입구"}, {"사당", "강남"}, {"서울역", "강남"}, {"잠실", "광화문"}, {"노원", "사당"},
            {"까치산", "성수"}, {"마천", "여의도"}, {"불암산", "신촌"}, {"응암", "이태원"}, {"장암", "모란"}};

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TimetableRouteFinder timetable;
    @Autowired SubwayRouteFinder finder;
    @Autowired FareCalculator fares;
    @Autowired RecommendationEngine engine;
    @Autowired RecommendationService service;
    @Autowired JdbcTemplate jdbc;

    long station(String name) {
        return jdbc.queryForObject("select id from stations where name = ?", Long.class, name);
    }

    static Instant at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(SEOUL).toInstant();
    }

    /** "daytype|line|code|station" -> {arrive, depart} rows of the bundled file (local trains). */
    static Map<String, List<int[]>> rawRows() throws IOException {
        Map<String, List<int[]>> rows = new HashMap<>();
        try (var in = RecommendationTimetableReportPostgresTest.class.getClassLoader().getResourceAsStream("train-timetable.csv.gz");
             var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(",");
                if (!"일반".equals(f[4])) continue;
                rows.computeIfAbsent(String.join("|", f[2], f[0], f[5], f[1]), k -> new ArrayList<>())
                        .add(new int[]{Integer.parseInt(f[6]), Integer.parseInt(f[7])});
            }
        }
        return rows;
    }

    static boolean matchesFile(TimedRoute route, LocalDate serviceDate, String day, Map<String, List<int[]>> raw) {
        long midnight = serviceDate.atStartOfDay(SEOUL).toEpochSecond();
        for (TimedLeg leg : route.legs()) {
            int board = (int) (leg.boardAt().getEpochSecond() - midnight);
            int alight = (int) (leg.alightAt().getEpochSecond() - midnight);
            List<int[]> from = raw.get(String.join("|", day, leg.lineName(), leg.trainCode(), leg.fromStationName()));
            List<int[]> to = raw.get(String.join("|", day, leg.lineName(), leg.trainCode(), leg.toStationName()));
            if (from == null || to == null || from.stream().noneMatch(row -> row[1] == board)
                    || to.stream().noneMatch(row -> (row[0] > 0 ? row[0] : row[1]) == alight)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void report() throws IOException {
        Map<String, List<int[]>> raw = rawRows();
        record Slot(String label, Instant at, LocalDate serviceDate, String day) {}
        List<Slot> slots = List.of(new Slot("평일 08:00", at(THURSDAY, 8), THURSDAY, "평일"),
                new Slot("평일 13:00", at(THURSDAY, 13), THURSDAY, "평일"), new Slot("평일 22:00", at(THURSDAY, 22), THURSDAY, "평일"),
                new Slot("토 14:00", at(SATURDAY, 14), SATURDAY, "토요일"));
        StringBuilder out = new StringBuilder("# Timetable routing report (#96)\n\n");
        out.append("Appointment = arrival deadline. minutes = ceil((arrival − leave) / 60), slack = appointment − arrival. ESTIMATE = fixed-cost graph ")
                .append("(travel_seconds + dwell 30 + transfer wait 120, V15).\n\n");
        out.append("| pair | slot | boarding train | legs (board → alight, wait) | transfers | minutes | slack s | ESTIMATE | file | ms |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        StringBuilder csv = new StringBuilder("pair,slot,from,to,from_lat,from_lon,to_lat,to_lon,minutes,transfers\n");
        int checked = 0;
        int matched = 0;
        for (String[] pair : PAIRS) {
            long from = station(pair[0]);
            long to = station(pair[1]);
            int estimate = (finder.shortestPath(from, to).totalSeconds() + 59) / 60;
            for (Slot slot : slots) {
                long started = System.nanoTime();
                TimedRoute route = timetable.latestDepartures(to, slot.at(), Set.of(from), 0).get(from);
                long micros = (System.nanoTime() - started) / 1000;
                assertThat(route).as("%s -> %s at %s", pair[0], pair[1], slot.label()).isNotNull();
                int minutes = (int) ((route.arriveAt().getEpochSecond() - route.leaveAt().getEpochSecond() + 59) / 60);
                long slack = slot.at().getEpochSecond() - route.arriveAt().getEpochSecond();
                boolean ok = matchesFile(route, slot.serviceDate(), slot.day(), raw);
                checked++;
                if (ok) matched++;
                TimedLeg first = route.legs().get(0);
                StringBuilder legs = new StringBuilder();
                for (TimedLeg leg : route.legs()) {
                    if (!legs.isEmpty()) legs.append("<br>");
                    legs.append(leg.lineName()).append(' ').append(leg.trainCode()).append(' ').append(leg.fromStationName())
                            .append(' ').append(HMS.format(leg.boardAt())).append(" → ").append(leg.toStationName()).append(' ')
                            .append(HMS.format(leg.alightAt()));
                    if (leg.walkSec() > 0 || leg.waitSec() > 0) {
                        legs.append(" (도보 ").append(leg.walkSec()).append("s, 대기 ").append(leg.waitSec()).append("s)");
                    }
                }
                out.append("| ").append(pair[0]).append("→").append(pair[1]).append(" | ").append(slot.label()).append(" | ")
                        .append(first.lineName()).append(' ').append(first.trainCode()).append(' ').append(HMS.format(first.boardAt()))
                        .append(' ').append(first.terminal()).append("행 | ").append(legs).append(" | ")
                        .append(route.path().transferCount()).append(" | ").append(minutes).append(" | ").append(slack).append(" | ").append(estimate)
                        .append(" | ").append(ok ? "일치" : "불일치").append(" | ").append(String.format(Locale.ROOT, "%.1f", micros / 1000.0))
                        .append(" |\n");
                var a = jdbc.queryForMap("select latitude, longitude from stations where id = ?", from);
                var b = jdbc.queryForMap("select latitude, longitude from stations where id = ?", to);
                csv.append(String.join(",", pair[0] + "-" + pair[1], slot.label(), pair[0], pair[1], a.get("latitude").toString(),
                        a.get("longitude").toString(), b.get("latitude").toString(), b.get("longitude").toString(),
                        String.valueOf(minutes), String.valueOf(route.path().transferCount()))).append('\n');
            }
        }
        out.append("\nTrains checked against the file: ").append(matched).append(" / ").append(checked).append(" routes\n");

        // ---- recommendation time ----
        RecommendationEngine fixed = new RecommendationEngine(finder, fares, new RecommendationProperties());
        String[][] groups = {{"강남", "홍대입구"}, {"강남", "사당", "노원"}, {"강남", "사당", "노원", "까치산"},
                {"강남", "사당", "노원", "까치산", "마천", "장암"}};
        out.append("\n## One recommendation (engine only, weekday 13:00, average of 5 after 2 warm-ups)\n\n");
        out.append("| members | candidates | timetable ms | fixed-cost ms | fallback members |\n| --- | --- | --- | --- | --- |\n");
        int fallbacks = 0;
        for (String[] group : groups) {
            List<Member> members = new ArrayList<>();
            for (String name : group) members.add(new Member(UUID.randomUUID(), name, station(name)));
            Instant time = at(THURSDAY, 13);
            for (int i = 0; i < 2; i++) {
                engine.evaluate(members, AppliedConstraints.none(), time, Instant.now());
                fixed.evaluate(members, AppliedConstraints.none(), time, Instant.now());
            }
            long timed = 0;
            long plain = 0;
            Evaluation evaluation = null;
            for (int i = 0; i < 5; i++) {
                long started = System.nanoTime();
                evaluation = engine.evaluate(members, AppliedConstraints.none(), time, Instant.now());
                timed += System.nanoTime() - started;
                started = System.nanoTime();
                fixed.evaluate(members, AppliedConstraints.none(), time, Instant.now());
                plain += System.nanoTime() - started;
            }
            fallbacks += evaluation.fallbackMembers();
            out.append("| ").append(group.length).append(" | ").append(evaluation.candidates().size() + evaluation.unreachableByTrain())
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", timed / 5 / 1e6)).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f", plain / 5 / 1e6)).append(" | ").append(evaluation.fallbackMembers()).append(" |\n");
        }

        // ---- whole POST (mock AI, mock congestion; no Kakao call is part of a recommendation) ----
        out.append("\n## Whole recommendation request (service.create, mock AI, weekday 13:00, average of 5 after 2 warm-ups)\n\n");
        out.append("| members | create ms |\n| --- | --- |\n");
        for (String[] group : groups) {
            long total = 0;
            for (int run = 0; run < 7; run++) {
                UUID room = UUID.randomUUID();
                List<UUID> users = new ArrayList<>();
                for (String ignored : group) {
                    UUID user = UUID.randomUUID();
                    jdbc.update("insert into users (id, kakao_id, nickname) values (?, ?, ?)", user, user.toString(), "u" + users.size());
                    users.add(user);
                }
                jdbc.update("insert into meeting_rooms (id, room_code, name, created_by, appointment_at) values (?, ?, '약속', ?, ?)",
                        room, room.toString().substring(0, 6).toUpperCase(), users.get(0), java.sql.Timestamp.from(at(THURSDAY, 13)));
                for (int i = 0; i < group.length; i++) {
                    jdbc.update("insert into meeting_members (id, room_id, user_id, role, departure_station_id) values (?, ?, ?, ?, ?)",
                            UUID.randomUUID(), room, users.get(i), i == 0 ? "OWNER" : "MEMBER", station(group[i]));
                }
                long started = System.nanoTime();
                service.create(users.get(0), room, null);
                if (run >= 2) total += System.nanoTime() - started;
            }
            out.append("| ").append(group.length).append(" | ").append(String.format(Locale.ROOT, "%.1f", total / 5 / 1e6)).append(" |\n");
        }

        Path file = Path.of("build", "reports", "recommendation-96.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out, StandardCharsets.UTF_8);
        Files.writeString(Path.of("build", "reports", "recommendation-96-pairs.csv"), csv, StandardCharsets.UTF_8);
        System.out.println(out);

        assertThat(matched).isEqualTo(checked);
        assertThat(fallbacks).isZero();
    }
}
