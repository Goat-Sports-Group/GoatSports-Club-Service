package iuh.fit.clubservice.infrastructure;

import iuh.fit.clubservice.domain.service.ClubEloPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Hoạt động CLB mô phỏng (dev / demo) cho các CLB mô phỏng và CLB demo:
 * <ul>
 *   <li>giao hữu CLB với CLB (môn đồng đội, cùng tỉnh) mỗi cuối tuần từ tháng 8 tới hết tháng 12: mỗi lần chạy chỉ "đá"
 *       các trận đã tới giờ, theo thứ tự thời gian, tính ELO và thành tích bằng đúng {@link ClubEloPolicy} và quy tắc cộng
 *       dồn của ClubRecordPolicy như trận thật;</li>
 *   <li>lời mời vào CLB đang chờ / đã bị từ chối, kể cả lời mời gửi tới tài khoản demo để trình bày luồng nhận lời;</li>
 *   <li>danh sách theo dõi (Tìm người chơi) của ban quản trị, có ghi chú;</li>
 *   <li>điều kiện dự giải (tuổi, ELO) cho vài giải năm 2027 đang mở đăng ký.</li>
 * </ul>
 * Id tất định, mọi lệnh ghi bỏ qua dòng đã có. Chạy sau DemoSimulationSeeder và mỗi đêm.
 * Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@Order(23)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoClubActivitySeeder implements CommandLineRunner {
    private static final Set<String> TEAM_SPORTS = Set.of("FOOTBALL", "BASKETBALL", "VOLLEYBALL");
    private static final LocalDate FRIENDLY_FROM = LocalDate.of(2026, 8, 3);
    /** Tài khoản demo nhận lời mời vào CLB mô phỏng: email → môn của CLB mời. */
    private static final Map<String, String> DEMO_INVITEES = Map.of(
            "tung.vuthanh@goatsports.vn", "FOOTBALL",
            "minh.hoangnhat@goatsports.vn", "PICKLEBALL");

    private final JdbcTemplate jdbc;

    record ClubRow(UUID id, String name, String sport, String city, UUID ownerId, LocalDateTime createdAt) { }

    /** Trạng thái thi đấu của CLB, cập nhật dần khi "đá" các trận giao hữu. */
    static final class Standing {
        int elo;
        int wins;
        int losses;
        int draws;
        boolean changed;

        Standing(int elo, int wins, int losses, int draws) {
            this.elo = elo;
            this.wins = wins;
            this.losses = losses;
            this.draws = draws;
        }

        int matches() {
            return wins + losses + draws;
        }

        void record(int comparison) {
            if (comparison > 0) wins++;
            else if (comparison < 0) losses++;
            else draws++;
            changed = true;
        }
    }

    @Override
    @Transactional
    public void run(String... args) {
        seed(LocalDateTime.now());
    }

    @Scheduled(cron = "0 40 0 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void daily() {
        seed(LocalDateTime.now());
    }

    void seed(LocalDateTime now) {
        List<ClubRow> clubs = jdbc.query("""
                select club_id, name, sport_type, city, owner_id, created_at from clubs
                where active = true and created_by in ('demo-sim', 'demo-seed')
                """, (rs, index) -> new ClubRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, UUID.class), rs.getTimestamp(6).toLocalDateTime()));
        clubs.sort(Comparator.comparing(club -> club.id().toString()));
        Map<UUID, Set<UUID>> members = new HashMap<>();
        jdbc.query("select club_id, user_id from club_members where status = 'ACTIVE'", row -> {
            members.computeIfAbsent(row.getObject(1, UUID.class), key -> new HashSet<>()).add(row.getObject(2, UUID.class));
        });
        clubs.forEach(club -> members.computeIfAbsent(club.id(), key -> new HashSet<>()).add(club.ownerId()));
        int friendlies = friendlies(clubs, now);
        int invitations = invitations(clubs, members, now);
        int shortlist = shortlist(clubs, members, now);
        int rules = eligibilityRules();
        log.info("Hoạt động CLB mô phỏng: {} trận giao hữu mới, {} lời mời, {} người trong danh sách theo dõi, "
                + "{} điều kiện dự giải", friendlies, invitations, shortlist, rules);
    }

    // ---- Giao hữu CLB với CLB ----

    record Friendly(UUID sessionId, ClubRow home, ClubRow away, LocalDateTime playedAt, Random random) { }

    /** Lịch tất định: mỗi nhóm (môn đồng đội + tỉnh) có từ 2 CLB, mỗi tuần 1–2 trận vào thứ Bảy / Chủ nhật. */
    static List<Friendly> schedule(List<ClubRow> clubs, LocalDate until) {
        Map<String, List<ClubRow>> groups = new LinkedHashMap<>();
        for (ClubRow club : clubs) {
            if (!TEAM_SPORTS.contains(club.sport()) || club.city() == null) continue;
            groups.computeIfAbsent(club.sport() + "|" + club.city(), key -> new ArrayList<>()).add(club);
        }
        List<Friendly> matches = new ArrayList<>();
        for (Map.Entry<String, List<ClubRow>> group : groups.entrySet()) {
            if (group.getValue().size() < 2) continue;
            for (LocalDate week = FRIENDLY_FROM; !week.isAfter(until); week = week.plusWeeks(1)) {
                Random random = DemoPopulation.random(0xF1E, group.getKey().hashCode(), week.toEpochDay());
                List<ClubRow> shuffled = new ArrayList<>(group.getValue());
                Collections.shuffle(shuffled, random);
                int pairs = Math.min(2, shuffled.size() / 2);
                for (int pair = 0; pair < pairs; pair++) {
                    if (random.nextInt(100) >= 70) continue;
                    ClubRow home = shuffled.get(pair * 2);
                    ClubRow away = shuffled.get(pair * 2 + 1);
                    LocalDate day = week.with(random.nextBoolean() ? DayOfWeek.SATURDAY : DayOfWeek.SUNDAY);
                    LocalDateTime playedAt = day.atTime(random.nextBoolean() ? 16 : 18, 0);
                    if (playedAt.isBefore(home.createdAt()) || playedAt.isBefore(away.createdAt())) continue;
                    UUID sessionId = id("sim-friendly", group.getKey() + "|" + week + "|" + pair);
                    matches.add(new Friendly(sessionId, home, away, playedAt,
                            DemoPopulation.random(0xF1F, sessionId.getMostSignificantBits(), sessionId.getLeastSignificantBits())));
                }
            }
        }
        matches.sort(Comparator.comparing(Friendly::playedAt).thenComparing(match -> match.sessionId().toString()));
        return matches;
    }

    private int friendlies(List<ClubRow> clubs, LocalDateTime now) {
        Set<UUID> played = new HashSet<>(jdbc.queryForList("select distinct session_id from club_friendly_matches", UUID.class));
        Map<UUID, Standing> standings = new HashMap<>();
        jdbc.query("select club_id, elo_rating, win_count, loss_count, draw_count from clubs", row -> {
            int elo = row.getObject(2) == null ? ClubEloPolicy.INITIAL_RATING : row.getInt(2);
            standings.put(row.getObject(1, UUID.class), new Standing(elo, row.getInt(3), row.getInt(4), row.getInt(5)));
        });
        List<Object[]> rows = new ArrayList<>();
        int inserted = 0;
        for (Friendly match : schedule(clubs, DemoPopulation.COVER_UNTIL)) {
            if (match.playedAt().isAfter(now) || played.contains(match.sessionId())) continue;
            Standing home = standings.get(match.home().id());
            Standing away = standings.get(match.away().id());
            if (home == null || away == null) continue;
            int[] score = score(match.home().sport(), home.elo, away.elo, match.random());
            int comparison = Integer.compare(score[0], score[1]);
            int homeBefore = home.elo;
            int awayBefore = away.elo;
            int homeAfter = ClubEloPolicy.newRating(homeBefore, awayBefore, home.matches(), comparison);
            int awayAfter = ClubEloPolicy.newRating(awayBefore, homeBefore, away.matches(), -comparison);
            home.elo = homeAfter;
            away.elo = awayAfter;
            home.record(comparison);
            away.record(-comparison);
            String format = format(match.home().sport(), match.random());
            Timestamp playedAt = Timestamp.valueOf(match.playedAt());
            Timestamp recordedAt = Timestamp.valueOf(match.playedAt().plusHours(2));
            rows.add(friendlyRow(match, match.home(), match.away(), format, score[0], score[1], comparison, homeBefore,
                    homeAfter, playedAt, recordedAt));
            rows.add(friendlyRow(match, match.away(), match.home(), format, score[1], score[0], -comparison, awayBefore,
                    awayAfter, playedAt, recordedAt));
            inserted++;
        }
        jdbc.batchUpdate("""
                insert into club_friendly_matches (friendly_match_id, session_id, club_id, opponent_club_id, opponent_name,
                                                   sport_type, play_format, club_score, opponent_score, result,
                                                   elo_before, elo_after, played_at, created_at, created_by, updated_at,
                                                   updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                """, rows);
        List<Object[]> updates = new ArrayList<>();
        standings.forEach((clubId, standing) -> {
            if (!standing.changed) return;
            int matches = standing.matches();
            updates.add(new Object[]{standing.elo, standing.wins, standing.losses, standing.draws, matches,
                    matches == 0 ? 0D : (double) standing.wins / matches, clubId});
        });
        jdbc.batchUpdate("""
                update clubs set elo_rating = ?, win_count = ?, loss_count = ?, draw_count = ?, match_count = ?,
                                 win_rate = ?, updated_at = now(), updated_by = 'demo-sim'
                where club_id = ?
                """, updates);
        return inserted;
    }

    private static Object[] friendlyRow(Friendly match, ClubRow club, ClubRow opponent, String format, int clubScore,
                                        int opponentScore, int comparison, int before, int after, Timestamp playedAt,
                                        Timestamp recordedAt) {
        return new Object[]{id("sim-friendly-row", match.sessionId() + "|" + club.id()), match.sessionId(), club.id(),
                opponent.id(), opponent.name(), club.sport(), format, clubScore, opponentScore,
                comparison > 0 ? "WIN" : comparison < 0 ? "LOSS" : "DRAW", before, after, playedAt, recordedAt,
                recordedAt};
    }

    /** Tỷ số theo môn; đội ELO cao hơn dễ thắng hơn. Bóng đá có thể hòa, bóng rổ / bóng chuyền thì không. */
    static int[] score(String sport, int homeElo, int awayElo, Random random) {
        double expected = 1.0 / (1.0 + Math.pow(10, (awayElo - homeElo) / 400.0));
        double roll = random.nextDouble();
        switch (sport) {
            case "BASKETBALL" -> {
                int winner = 55 + random.nextInt(30);
                int loser = winner - 1 - random.nextInt(20);
                return roll < expected ? new int[]{winner, loser} : new int[]{loser, winner};
            }
            case "VOLLEYBALL" -> {
                int loser = random.nextInt(3);
                return roll < expected ? new int[]{3, loser} : new int[]{loser, 3};
            }
            default -> {
                if (roll >= expected * 0.8 && roll < expected * 0.8 + 0.2) {
                    int goals = random.nextInt(4);
                    return new int[]{goals, goals};
                }
                int winner = 1 + random.nextInt(4);
                int loser = random.nextInt(winner);
                return roll < expected * 0.8 ? new int[]{winner, loser} : new int[]{loser, winner};
            }
        }
    }

    private static String format(String sport, Random random) {
        return switch (sport) {
            case "BASKETBALL" -> "BASKETBALL_5X5";
            case "VOLLEYBALL" -> "VOLLEYBALL_6";
            default -> random.nextBoolean() ? "FOOTBALL_7" : "FOOTBALL_5";
        };
    }

    // ---- Lời mời + danh sách theo dõi ----

    /** Người chơi mô phỏng cùng tỉnh, chơi môn của CLB, chưa là thành viên — xếp theo thứ tự tất định của CLB. */
    private static List<DemoPopulation.Player> candidates(ClubRow club, Set<UUID> members) {
        List<DemoPopulation.Player> pool = new ArrayList<>(DemoPopulation.players().stream()
                .filter(player -> player.city().name().equals(club.city()) && player.sports().contains(club.sport())
                        && !members.contains(player.userId()))
                .toList());
        Collections.shuffle(pool, DemoPopulation.random(0x1A7, club.id().getMostSignificantBits(),
                club.id().getLeastSignificantBits()));
        return pool;
    }

    private int invitations(List<ClubRow> clubs, Map<UUID, Set<UUID>> members, LocalDateTime now) {
        List<Object[]> rows = new ArrayList<>();
        for (ClubRow club : clubs) {
            List<DemoPopulation.Player> pool = candidates(club, members.get(club.id()));
            Random random = DemoPopulation.random(0x1A8, club.id().getMostSignificantBits(), club.id().getLeastSignificantBits());
            for (int index = 0; index < Math.min(3, pool.size()); index++) {
                DemoPopulation.Player player = pool.get(index);
                boolean pending = index < 2;
                LocalDateTime createdAt = now.minusDays(pending ? 1 + random.nextInt(6) : 12 + random.nextInt(20))
                        .withHour(9 + random.nextInt(11)).withMinute(0).withSecond(0).withNano(0);
                rows.add(invitation(club, player.userId(), pending ? "PENDING" : "DECLINED",
                        "Chào " + given(player.fullName()) + ", " + club.name() + " đang tìm thêm người chơi "
                                + sportLabel(club.sport()) + " ở " + player.district() + ". Tham gia cùng tụi mình nhé!",
                        createdAt, pending ? null : createdAt.plusDays(1 + random.nextInt(3))));
            }
        }
        // Tài khoản demo: một lời mời đang chờ từ CLB mô phỏng cùng môn ở Hồ Chí Minh.
        for (Map.Entry<String, String> demo : DEMO_INVITEES.entrySet()) {
            UUID inviteeId = DemoPopulation.userId(demo.getKey());
            clubs.stream()
                    .filter(club -> club.sport().equals(demo.getValue()) && "Hồ Chí Minh".equals(club.city())
                            && !members.get(club.id()).contains(inviteeId))
                    .findFirst()
                    .ifPresent(club -> rows.add(invitation(club, inviteeId, "PENDING",
                            club.name() + " muốn mời bạn vào đội. Sinh hoạt hằng tuần, có đá giao hữu và dự giải!",
                            now.minusDays(1).withHour(20).withMinute(0).withSecond(0).withNano(0), null)));
        }
        return sum(jdbc.batchUpdate("""
                insert into club_invitations (invitation_id, club_id, invitee_id, invited_by, status, message, responded_at,
                                              created_at, created_by, updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                """, rows));
    }

    private static Object[] invitation(ClubRow club, UUID inviteeId, String status, String message,
                                       LocalDateTime createdAt, LocalDateTime respondedAt) {
        Timestamp created = Timestamp.valueOf(createdAt);
        return new Object[]{id("sim-invitation", club.id() + "|" + inviteeId), club.id(), inviteeId, club.ownerId(),
                status, message, respondedAt == null ? null : Timestamp.valueOf(respondedAt), created,
                respondedAt == null ? created : Timestamp.valueOf(respondedAt)};
    }

    private int shortlist(List<ClubRow> clubs, Map<UUID, Set<UUID>> members, LocalDateTime now) {
        String[] notes = {"Chơi đều cuối tuần, thái độ tốt", "Từng đá giao hữu với đội, kỹ thuật ổn",
                "Rảnh buổi tối, ở gần sân sinh hoạt", "Trình độ khá, hợp lối chơi của đội", "Bạn của thành viên giới thiệu"};
        List<Object[]> rows = new ArrayList<>();
        for (ClubRow club : clubs) {
            List<DemoPopulation.Player> pool = candidates(club, members.get(club.id()));
            for (int index = 3; index < Math.min(6, pool.size()); index++) {
                DemoPopulation.Player player = pool.get(index);
                Timestamp createdAt = Timestamp.valueOf(now.minusDays(2L + index * 3L).withHour(21).withMinute(0)
                        .withSecond(0).withNano(0));
                rows.add(new Object[]{id("sim-shortlist", club.id() + "|" + player.userId()), club.id(), player.userId(),
                        notes[Math.floorMod(player.index() + index, notes.length)], club.ownerId(), createdAt, createdAt});
            }
        }
        return sum(jdbc.batchUpdate("""
                insert into club_scouting_shortlist (entry_id, club_id, user_id, note, added_by, created_at, created_by,
                                                     updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                """, rows));
    }

    // ---- Điều kiện dự giải ----

    private int eligibilityRules() {
        String[][] rules = {
                {"Cúp Futsal Tết Dương Lịch 2027", "AGE", "GREATER_THAN_OR_EQUAL", "16"},
                {"Giải Bóng Chuyền Mừng Xuân 2027", "AGE", "GREATER_THAN_OR_EQUAL", "16"},
                {"Giải Tennis Đơn Mỹ Đình Đầu Năm 2027", "ELO_RATING", "GREATER_THAN_OR_EQUAL", "900"}};
        List<Object[]> rows = new ArrayList<>();
        for (String[] rule : rules) {
            UUID tournamentId = DemoTournaments.uuid("tournament", rule[0]);
            rows.add(new Object[]{id("sim-eligibility", tournamentId + "|" + rule[1]), tournamentId, rule[1], rule[2],
                    rule[3], tournamentId});
        }
        return sum(jdbc.batchUpdate("""
                insert into tournament_eligibility_rules (rule_id, tournament_id, rule_type, operator, expected_value,
                                                          created_at, created_by, updated_at, updated_by)
                select ?, ?, ?, ?, ?, now(), 'demo-sim', now(), 'demo-sim'
                where exists (select 1 from tournaments where tournament_id = ?)
                on conflict do nothing
                """, rows));
    }

    // ---- Tiện ích ----

    private static String given(String fullName) {
        String[] parts = fullName.trim().split(" ");
        return parts[parts.length - 1];
    }

    private static String sportLabel(String sport) {
        return switch (sport) {
            case "FOOTBALL" -> "bóng đá";
            case "BADMINTON" -> "cầu lông";
            case "TENNIS" -> "tennis";
            case "BASKETBALL" -> "bóng rổ";
            case "VOLLEYBALL" -> "bóng chuyền";
            default -> "pickleball";
        };
    }

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int count : counts) total += Math.max(count, 0);
        return total;
    }
}
