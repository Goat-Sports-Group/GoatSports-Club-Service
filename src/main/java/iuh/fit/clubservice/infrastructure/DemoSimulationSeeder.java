package iuh.fit.clubservice.infrastructure;

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
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Câu lạc bộ và giải đấu mô phỏng (dev / demo) cho cộng đồng {@link DemoPopulation}:
 * <ul>
 *   <li>30 CLB ở 4 tỉnh/thành, mỗi CLB 7–25 thành viên cùng tỉnh, cùng môn, vài đơn xin vào đang chờ, và buổi sinh hoạt
 *       hằng tuần tới {@link DemoPopulation#COVER_UNTIL};</li>
 *   <li>16 giải theo {@link DemoTournaments} (tháng 7 – hết tháng 12): đội đăng ký dần trong thời gian mở đăng ký,
 *       lịch thi đấu dựng khi giải bắt đầu, trận đã qua giờ có tỷ số (đội mạnh hơn theo ELO dễ thắng hơn). Trận có tỷ
 *       số để {@code rating_synced=false} nên TournamentRatingJob tính ELO cho người chơi như trận thật.</li>
 * </ul>
 * Đội hình chỉ gồm người chơi mô phỏng có tài khoản thật (không có cầu thủ "khách" không id).
 *
 * <p>Tất định: ngày tuyệt đối, id theo tên. Chạy lúc khởi động và mỗi đêm: thêm đội đăng ký mới, dựng lịch giải vừa
 * bắt đầu, điền tỷ số trận vừa đá. Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@Order(22)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoSimulationSeeder implements CommandLineRunner {
    static final String CREATED_BY = "demo-sim";
    private static final int[] CLUBS_PER_CITY = {12, 8, 5, 5};
    /** Mốc tuyệt đối cho ngày lập CLB và ngày bắt đầu sinh hoạt: chạy lại hôm nào cũng ra cùng giá trị. */
    private static final LocalDate FOUNDED_FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate ACTIVITIES_FROM = LocalDate.of(2026, 9, 1);
    private static final int[] CLUB_SPORT_WEIGHTS = {40, 30, 12, 8, 5, 5};
    private static final String[] TAGS = {"Giao lưu", "Thi đấu", "Cộng đồng", "Người mới", "Kỹ thuật", "Cuối tuần"};

    private final JdbcTemplate jdbc;

    record Club(UUID id, String name, String sport, DemoPopulation.City city, String district,
                DemoPopulation.Player owner, List<DemoPopulation.Player> admins, List<DemoPopulation.Player> members,
                List<DemoPopulation.Player> applicants, LocalDateTime createdAt, boolean manualApproval,
                boolean privateClub, DayOfWeek sessionDay, int sessionHour) {
        List<DemoPopulation.Player> everyone() {
            List<DemoPopulation.Player> all = new ArrayList<>();
            all.add(owner);
            all.addAll(admins);
            all.addAll(members);
            return all;
        }
    }

    // ---- CLB ----

    static List<Club> clubs() {
        List<DemoPopulation.Player> players = DemoPopulation.players();
        List<Club> clubs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<DemoPopulation.Player> owners = new HashSet<>();
        for (int cityIndex = 0; cityIndex < CLUBS_PER_CITY.length; cityIndex++) {
            DemoPopulation.City city = DemoPopulation.CITIES.get(cityIndex);
            List<DemoPopulation.Player> local = players.stream().filter(player -> player.city() == city).toList();
            for (int index = 0; index < CLUBS_PER_CITY[cityIndex]; index++) {
                Random random = DemoPopulation.random(0xC1B, cityIndex, index);
                String chosen = DemoPopulation.SPORTS.get(DemoPopulation.weighted(random, CLUB_SPORT_WEIGHTS));
                String sport = local.stream().filter(player -> player.sports().contains(chosen)).count() >= 7
                        ? chosen : "FOOTBALL";
                List<DemoPopulation.Player> fans = new ArrayList<>(local.stream()
                        .filter(player -> player.sports().contains(sport)).toList());
                Collections.shuffle(fans, random);
                String district = city.districts().get(random.nextInt(city.districts().size()));
                String name = clubName(sport, district, random);
                while (!names.add(name)) name = name + " II";
                DemoPopulation.Player owner = fans.stream().filter(player -> !owners.contains(player)).findFirst()
                        .orElse(fans.get(0));
                owners.add(owner);
                List<DemoPopulation.Player> rest = fans.stream().filter(player -> player != owner).toList();
                int size = Math.min(rest.size(), 6 + random.nextInt(19));
                List<DemoPopulation.Player> crew = rest.subList(0, size);
                int adminCount = Math.min(crew.size(), 1 + random.nextInt(2));
                List<DemoPopulation.Player> applicants = new ArrayList<>(rest.subList(size, Math.min(rest.size(), size + random.nextInt(4))));
                LocalDateTime createdAt = FOUNDED_FROM.plusDays(random.nextInt(150)).atTime(9 + random.nextInt(12), 0);
                boolean weekend = random.nextBoolean();
                DayOfWeek day = weekend ? (random.nextBoolean() ? DayOfWeek.SATURDAY : DayOfWeek.SUNDAY)
                        : DayOfWeek.of(1 + random.nextInt(5));
                clubs.add(new Club(id("sim-club", name), name, sport, city, district, owner,
                        List.copyOf(crew.subList(0, adminCount)), List.copyOf(crew.subList(adminCount, crew.size())),
                        List.copyOf(applicants), createdAt, random.nextInt(100) < 35, random.nextInt(100) < 15, day,
                        weekend ? 7 : 18 + random.nextInt(2)));
            }
        }
        return clubs;
    }

    /**
     * Cặp CLB để demo AI ghép trận ở TP.HCM, mỗi môn một cặp "Rồng Xanh" / "Hổ Vàng" (chỉ số người chơi
     * {@link DemoPopulation}, người đầu là chủ CLB = đội trưởng). Người của các CLB này không trùng nhau nên hai đội
     * trưởng chọn đồng đội ở tab "Thành viên CLB" là ghép được mọi hình thức: bóng đá 5/7/11, bóng rổ 3x3/5x5, bóng
     * chuyền 6, đơn/đôi. Ai-service bỏ họ khỏi kèo mô phỏng từ 19/11/2026 (matchmaking_simulation.DEMO_RESERVED) để
     * trang Ghép trận của họ luôn trống. Bóng rổ có 2 người không chơi môn này (3, 6): HCM chỉ có 8 người chơi bóng rổ.
     */
    static final List<MatchmakingTeam> MATCHMAKING_TEAMS = List.of(
            new MatchmakingTeam("FOOTBALL", "FC Rồng Xanh", List.of(25, 261, 34, 204, 296, 289, 288, 182, 39, 175, 221)),
            new MatchmakingTeam("FOOTBALL", "FC Hổ Vàng", List.of(183, 20, 37, 132, 51, 194, 17, 209, 92, 113, 222)),
            new MatchmakingTeam("BASKETBALL", "Bóng Rổ Rồng Xanh", List.of(282, 237, 254, 156, 3)),
            new MatchmakingTeam("BASKETBALL", "Bóng Rổ Hổ Vàng", List.of(13, 85, 143, 299, 6)),
            new MatchmakingTeam("VOLLEYBALL", "Bóng Chuyền Rồng Xanh", List.of(84, 190, 14, 108, 115, 33)),
            new MatchmakingTeam("VOLLEYBALL", "Bóng Chuyền Hổ Vàng", List.of(170, 219, 198, 98, 138, 273)),
            new MatchmakingTeam("TENNIS", "CLB Tennis Rồng Xanh", List.of(262, 159)),
            new MatchmakingTeam("TENNIS", "CLB Tennis Hổ Vàng", List.of(57, 255)),
            new MatchmakingTeam("PICKLEBALL", "Pickleball Rồng Xanh", List.of(230, 235)),
            new MatchmakingTeam("PICKLEBALL", "Pickleball Hổ Vàng", List.of(117, 290)),
            new MatchmakingTeam("BADMINTON", "CLB Cầu Lông Rồng Xanh", List.of(72, 242)),
            new MatchmakingTeam("BADMINTON", "CLB Cầu Lông Hổ Vàng", List.of(56, 270)));

    record MatchmakingTeam(String sport, String name, List<Integer> players) { }

    static List<Club> matchmakingClubs() {
        List<DemoPopulation.Player> players = DemoPopulation.players();
        List<Club> clubs = new ArrayList<>();
        for (MatchmakingTeam team : MATCHMAKING_TEAMS) {
            List<DemoPopulation.Player> crew = team.players().stream().map(players::get).toList();
            DemoPopulation.Player owner = crew.get(0);
            clubs.add(new Club(id("sim-club", team.name()), team.name(), team.sport(), owner.city(), owner.district(),
                    owner, List.of(), crew.subList(1, crew.size()), List.of(), LocalDateTime.of(2026, 10, 1, 9, 0),
                    true, false, DayOfWeek.SATURDAY, 7));
        }
        return clubs;
    }

    private static String clubName(String sport, String district, Random random) {
        return switch (sport) {
            case "FOOTBALL" -> "FC " + district + " " + pick(random, "United", "Warriors", "Stars", "Lions", "Tigers",
                    "Phoenix", "Eagles", "Dragons");
            case "BADMINTON" -> "CLB Cầu Lông " + district + " " + pick(random, "Smash", "Lông Vũ", "Tia Chớp", "Đôi Cánh");
            case "PICKLEBALL" -> "Pickleball " + district + " " + pick(random, "Club", "Dink Team", "Kitchen Crew");
            case "TENNIS" -> "CLB Tennis " + district + " " + pick(random, "Ace", "Topspin", "Baseline");
            case "BASKETBALL" -> "Bóng Rổ " + district + " " + pick(random, "Ballers", "Hoopers", "Dunkers");
            default -> "Bóng Chuyền " + district + " " + pick(random, "Spikers", "Blockers", "Setters");
        };
    }

    // ---- Chạy ----

    @Override
    @Transactional
    public void run(String... args) {
        seed(LocalDateTime.now());
    }

    /** Mỗi đêm: đội đăng ký mới, lịch giải vừa bắt đầu, tỷ số các trận vừa đá. */
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void daily() {
        seed(LocalDateTime.now());
    }

    void seed(LocalDateTime now) {
        List<Club> clubs = clubs();
        int newClubs = seedClubs(clubs) + seedClubs(matchmakingClubs());
        int activities = seedActivities(clubs);
        int[] totals = new int[3];
        for (DemoTournaments.Tournament tournament : DemoTournaments.ALL) {
            int[] counts = seedTournament(tournament, clubs, now);
            for (int index = 0; index < totals.length; index++) totals[index] += counts[index];
        }
        log.info("Dữ liệu mô phỏng CLB: {} CLB mới, {} buổi sinh hoạt, {} giải mới, {} đội đăng ký mới, {} trận mới/cập nhật",
                newClubs, activities, totals[0], totals[1], totals[2]);
    }

    private int seedClubs(List<Club> clubs) {
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> members = new ArrayList<>();
        List<Object[]> tags = new ArrayList<>();
        for (Club club : clubs) {
            Random random = DemoPopulation.random(0xC1C, club.id().getMostSignificantBits());
            List<DemoPopulation.Player> everyone = club.everyone();
            int elo = (int) Math.round(everyone.stream().mapToInt(DemoPopulation.Player::elo).average().orElse(1200));
            int matches = 6 + random.nextInt(35);
            double strength = Math.max(0.2, Math.min(0.75, 0.3 + (elo - 1100) / 1500.0));
            int wins = (int) Math.round(matches * strength);
            int draws = "FOOTBALL".equals(club.sport()) ? matches / 7 : 0;
            int losses = Math.max(0, matches - wins - draws);
            Timestamp createdAt = Timestamp.valueOf(club.createdAt());
            rows.add(new Object[]{club.id(), club.name(), description(club), club.sport(), club.owner().userId(),
                    slug(club.city().name()), club.district(), club.manualApproval() ? "MANUAL" : "AUTO",
                    club.privateClub() ? "PRIVATE" : "PUBLIC", wins, losses, draws, matches, (double) wins / matches,
                    round(club.city().latitude() + (random.nextDouble() - 0.5) * 0.06),
                    round(club.city().longitude() + (random.nextDouble() - 0.5) * 0.06), elo, createdAt, createdAt});
            members.add(member(club, club.owner(), "OWNER", club.createdAt()));
            for (DemoPopulation.Player admin : club.admins()) {
                members.add(member(club, admin, "ADMIN", club.createdAt().plusDays(1 + random.nextInt(10))));
            }
            for (DemoPopulation.Player player : club.members()) {
                members.add(member(club, player, "MEMBER", club.createdAt().plusDays(3 + random.nextInt(120))));
            }
            for (DemoPopulation.Player applicant : club.applicants()) {
                Timestamp appliedAt = Timestamp.valueOf(LocalDate.of(2026, 11, 1).plusDays(random.nextInt(10)).atTime(20, 30));
                members.add(new Object[]{id("sim-membership", club.id() + "|" + applicant.email()), club.id(),
                        applicant.userId(), "MEMBER", "PENDING", null, null, null, appliedAt,
                        "Mình chơi " + sportLabel(club.sport()).toLowerCase(Locale.ROOT) + " ở " + applicant.district()
                                + ", muốn tham gia sinh hoạt cùng CLB.", appliedAt, appliedAt});
            }
            List<String> picked = new ArrayList<>(List.of(TAGS));
            Collections.shuffle(picked, random);
            for (int order = 0; order < 2 + random.nextInt(2); order++) {
                tags.add(new Object[]{club.id(), order, picked.get(order)});
            }
        }
        int inserted = sum(jdbc.batchUpdate("""
                        insert into clubs (club_id, name, description, logo_url, banner_url, sport_type, owner_id, city,
                                           location, approval_mode, privacy, conversation_id, active, win_count, loss_count,
                                           draw_count, match_count, win_rate, latitude, longitude, elo_rating,
                                           created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, '/assets/images/goat.png', '/assets/images/default-banner-light.png', ?, ?, ?,
                                ?, ?, ?, null, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim')
                        on conflict do nothing
                        """, rows, 200, DemoSimulationSeeder::bind));
        jdbc.batchUpdate("""
                        insert into club_members (membership_id, club_id, user_id, role, status, joined_at, responded_by,
                                                  responded_at, applied_at, intro_message,
                                                  created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                        """, members, 500, DemoSimulationSeeder::bind);
        jdbc.batchUpdate("insert into club_tags (club_id, display_order, tag) values (?, ?, ?) on conflict do nothing",
                tags, 500, DemoSimulationSeeder::bind);
        return inserted;
    }

    private static Object[] member(Club club, DemoPopulation.Player player, String role, LocalDateTime joinedAt) {
        Timestamp at = Timestamp.valueOf(joinedAt);
        return new Object[]{id("sim-membership", club.id() + "|" + player.email()), club.id(), player.userId(), role,
                "ACTIVE", at, club.owner().userId(), at, null, null, at, at};
    }

    /** Buổi sinh hoạt hằng tuần tới hết {@link DemoPopulation#COVER_UNTIL}, ở sân demo cùng tỉnh có môn của CLB. */
    private int seedActivities(List<Club> clubs) {
        List<Object[]> rows = new ArrayList<>();
        for (Club club : clubs) {
            UUID venueId = homeVenue(club);
            LocalDate start = club.createdAt().toLocalDate().isAfter(ACTIVITIES_FROM)
                    ? club.createdAt().toLocalDate() : ACTIVITIES_FROM;
            LocalDate date = start.plusDays(Math.floorMod(club.sessionDay().getValue() - start.getDayOfWeek().getValue(), 7));
            int week = 0;
            for (; !date.isAfter(DemoPopulation.COVER_UNTIL); date = date.plusWeeks(1), week++) {
                String[] plan = ACTIVITIES[week % ACTIVITIES.length];
                LocalDateTime startAt = date.atTime(club.sessionHour(), 0);
                rows.add(new Object[]{id("sim-activity", club.id() + "|" + date), club.id(), plan[0],
                        plan[1].replace("{môn}", sportLabel(club.sport()).toLowerCase(Locale.ROOT)), venueId,
                        Timestamp.valueOf(startAt), Timestamp.valueOf(startAt.plusHours(2)), club.owner().userId(),
                        Timestamp.valueOf(startAt.minusDays(6)), Timestamp.valueOf(startAt.minusDays(6))});
            }
        }
        return sum(jdbc.batchUpdate("""
                        insert into club_activities (activity_id, club_id, title, description, venue_id, start_at, end_at,
                                                     created_by_user_id, created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                        """, rows, 500, DemoSimulationSeeder::bind));
    }

    private static final String[][] ACTIVITIES = {
            {"Sinh hoạt định kỳ", "Buổi {môn} hằng tuần của CLB, chia đội theo trình độ, ai mới tới cũng được xếp cặp."},
            {"Tập kỹ thuật", "Một giờ tập kỹ thuật cơ bản, một giờ đấu tập. Mang nước và giày phù hợp mặt sân."},
            {"Giao hữu nội bộ", "Đấu giao hữu nội bộ, ghi kết quả để cập nhật bảng xếp hạng CLB."},
            {"Tập thể lực", "Khởi động, chạy biến tốc và bài tập phản xạ trước mùa giải."}};

    private static UUID homeVenue(Club club) {
        for (DemoTournaments.DemoVenue venue : List.of(DemoTournaments.BINH_THANH, DemoTournaments.PHU_NHUAN,
                DemoTournaments.MY_DINH, DemoTournaments.SON_TRA, DemoTournaments.TAN_BINH)) {
            boolean sameCity = venue.city().equals(club.city().name());
            boolean offers = DemoTournaments.ALL.stream()
                    .anyMatch(tournament -> tournament.venue() == venue && tournament.sport().equals(club.sport()));
            if (sameCity && offers) return venue.id();
        }
        return null;
    }

    // ---- Giải đấu ----

    record Entrant(UUID registrationId, String teamName, UUID clubId, List<DemoPopulation.Player> roster,
                   LocalDateTime registeredAt) {
        DemoPopulation.Player captain() {
            return roster.get(0);
        }

        double strength() {
            return roster.stream().mapToInt(DemoPopulation.Player::elo).average().orElse(1200);
        }
    }

    /** Số người trong đội hình và số người ra sân theo thể thức. */
    private static int[] roster(String playFormat) {
        return switch (playFormat) {
            case "FOOTBALL_7" -> new int[]{10, 7};
            case "FOOTBALL_5" -> new int[]{7, 5};
            case "BASKETBALL_3X3" -> new int[]{4, 3};
            case "BASKETBALL_5X5" -> new int[]{8, 5};
            case "VOLLEYBALL_6" -> new int[]{8, 6};
            case "BADMINTON_DOUBLES", "TENNIS_DOUBLES", "PICKLEBALL_DOUBLES" -> new int[]{2, 2};
            default -> new int[]{1, 1};
        };
    }

    /**
     * Đội tham dự, tất định theo giải: ưu tiên người cùng tỉnh chơi môn đó, rồi người cùng tỉnh, rồi người tỉnh khác;
     * không ai đá cho hai đội. Môn đồng đội lấy CLB cùng tỉnh, cùng môn làm đội trước. Đăng ký rải đều trong thời gian
     * mở đăng ký.
     */
    static List<Entrant> entrants(DemoTournaments.Tournament tournament, List<Club> clubs) {
        Random random = DemoPopulation.random(0x70E, tournament.id().getMostSignificantBits(),
                tournament.id().getLeastSignificantBits());
        List<DemoPopulation.Player> players = DemoPopulation.players();
        String city = tournament.venue().city();
        LinkedHashSet<DemoPopulation.Player> pool = new LinkedHashSet<>();
        pool.addAll(shuffled(players.stream().filter(p -> p.city().name().equals(city) && p.sports().contains(tournament.sport())).toList(), random));
        pool.addAll(shuffled(players.stream().filter(p -> p.city().name().equals(city)).toList(), random));
        pool.addAll(shuffled(players.stream().filter(p -> p.sports().contains(tournament.sport())).toList(), random));
        pool.addAll(shuffled(players, random));
        Set<DemoPopulation.Player> used = new HashSet<>();
        int size = roster(tournament.playFormat())[0];
        List<Club> teamClubs = size >= 4 ? clubs.stream()
                .filter(club -> club.city().name().equals(city) && club.sport().equals(tournament.sport()))
                .limit(tournament.teams() / 2).toList() : List.of();
        long openHours = ChronoUnit.HOURS.between(tournament.registrationOpen().atTime(20, 0),
                tournament.registrationClose().atTime(20, 0));
        List<Entrant> entrants = new ArrayList<>();
        for (int index = 0; index < tournament.teams(); index++) {
            List<DemoPopulation.Player> roster = new ArrayList<>();
            Club club = index < teamClubs.size() ? teamClubs.get(index) : null;
            if (club != null) {
                for (DemoPopulation.Player member : club.everyone()) {
                    if (roster.size() < size && used.add(member)) roster.add(member);
                }
            }
            for (DemoPopulation.Player player : pool) {
                if (roster.size() >= size) break;
                if (used.add(player)) roster.add(player);
            }
            String teamName = club != null ? club.name() : size == 1 ? null : teamName(tournament, roster, random);
            UUID registrationId = id("sim-registration", tournament.name() + "|" + index);
            LocalDateTime registeredAt = tournament.registrationOpen().atTime(20, 0)
                    .plusHours(openHours * index / tournament.teams() + random.nextInt(6));
            entrants.add(new Entrant(registrationId, teamName, club == null ? null : club.id(), List.copyOf(roster),
                    registeredAt));
        }
        return entrants;
    }

    private static String teamName(DemoTournaments.Tournament tournament, List<DemoPopulation.Player> roster, Random random) {
        if (roster.size() == 2) return given(roster.get(0)) + " & " + given(roster.get(1));
        String district = roster.get(0).district();
        return switch (tournament.sport()) {
            case "FOOTBALL" -> district + " " + pick(random, "FC", "United", "Rangers", "Sharks", "Wolves", "Legends");
            case "BASKETBALL" -> district + " " + pick(random, "Hoops", "Ballers", "Kings", "Rockets");
            default -> district + " " + pick(random, "Spikers", "Volley", "Blockers", "Waves");
        };
    }

    /** Một giải: bản ghi giải, đội đã đăng ký tới {@code now}, và (khi giải đã bắt đầu) lịch + tỷ số tới {@code now}. */
    private int[] seedTournament(DemoTournaments.Tournament tournament, List<Club> clubs, LocalDateTime now) {
        List<Entrant> entrants = entrants(tournament, clubs);
        List<Entrant> registered = entrants.stream().filter(entrant -> !entrant.registeredAt().isAfter(now)).toList();
        List<Fixture> fixtures = now.isBefore(tournament.start().atStartOfDay()) ? List.of()
                : tournament.knockout() ? knockout(tournament, entrants, now) : roundRobin(tournament, entrants, now);
        boolean started = !fixtures.isEmpty();
        boolean finished = started && fixtures.stream().allMatch(fixture -> fixture.score() != null);
        LocalDateTime completedAt = finished
                ? fixtures.stream().map(Fixture::endsAt).max(LocalDateTime::compareTo).orElse(null) : null;
        String status = !started ? "PUBLISHED" : finished ? "COMPLETED" : "IN_PROGRESS";
        Timestamp createdAt = Timestamp.valueOf(tournament.registrationOpen().minusDays(3).atTime(10, 0));
        int created = jdbc.update("""
                        insert into tournaments (tournament_id, name, description, sport_type, format, status, organizer_id,
                                                 max_participants, entry_fee, prize_pool, registration_open_date,
                                                 registration_close_date, start_date, end_date, rules, venue_id, court_ids,
                                                 daily_start_time, daily_end_time, match_duration_minutes, play_format,
                                                 completed_at, created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, cast(? as jsonb), ?, ?, ?, ?,
                                ?, ?, 'demo-sim', now(), 'demo-sim')
                        on conflict (tournament_id) do update set status = excluded.status,
                            completed_at = excluded.completed_at, updated_at = now()
                        where tournaments.created_by = 'demo-sim' and tournaments.status <> excluded.status
                        """, tournament.id(), tournament.name(), tournament.description(), tournament.sport(),
                tournament.format(), status, DemoPopulation.userId(tournament.venue().ownerEmail()), tournament.teams(),
                tournament.fee(), tournament.prize(), Date.valueOf(tournament.registrationOpen()),
                Date.valueOf(tournament.registrationClose()), Date.valueOf(tournament.start()),
                Date.valueOf(tournament.end()), jsonArray(rules(tournament)), tournament.venue().id(),
                jsonArray(tournament.courts().stream().map(court -> tournament.venue().courtId(court).toString()).toList()),
                Time.valueOf(tournament.dailyStart()), Time.valueOf(tournament.dailyEnd()), tournament.matchMinutes(),
                tournament.playFormat(), completedAt == null ? null : Timestamp.valueOf(completedAt), createdAt);
        int teams = seedEntrants(tournament, registered);
        int matches = seedFixtures(tournament, fixtures);
        return new int[]{created > 0 && !started ? 1 : 0, teams, matches};
    }

    private int seedEntrants(DemoTournaments.Tournament tournament, List<Entrant> entrants) {
        int onField = roster(tournament.playFormat())[1];
        boolean individual = roster(tournament.playFormat())[0] == 1;
        List<Object[]> registrations = new ArrayList<>();
        List<Object[]> lineups = new ArrayList<>();
        List<Object[]> payments = new ArrayList<>();
        for (Entrant entrant : entrants) {
            Timestamp at = Timestamp.valueOf(entrant.registeredAt());
            Timestamp confirmed = Timestamp.valueOf(entrant.registeredAt().plusHours(2));
            registrations.add(new Object[]{entrant.registrationId(), tournament.id(),
                    individual ? entrant.captain().userId() : null, entrant.clubId(), entrant.teamName(),
                    individual ? "INDIVIDUAL" : "TEAM", entrant.captain().userId(), at, confirmed, at, at});
            if (!individual) {
                for (int slot = 0; slot < entrant.roster().size(); slot++) {
                    DemoPopulation.Player player = entrant.roster().get(slot);
                    lineups.add(new Object[]{id("sim-lineup", entrant.registrationId() + "|" + slot),
                            entrant.registrationId(), player.userId(), player.fullName(),
                            slot == 0 ? "CAPTAIN" : slot >= onField ? "SUBSTITUTE" : "PLAYER", slot + 1,
                            Timestamp.valueOf(entrant.registeredAt().plusMinutes(30L + slot))});
                }
            }
            if (tournament.fee() > 0) {
                payments.add(new Object[]{id("sim-tournament-payment", entrant.registrationId().toString()),
                        entrant.registrationId(), tournament.fee(), id("sim-payment", entrant.registrationId().toString()),
                        confirmed});
            }
        }
        int inserted = sum(jdbc.batchUpdate("""
                        insert into tournament_registrations (registration_id, tournament_id, player_id, club_id, team_name,
                                                              type, status, registered_by, registered_at, confirmed_at,
                                                              created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                        """, registrations, 200, DemoSimulationSeeder::bind));
        jdbc.batchUpdate("""
                        insert into tournament_lineups (lineup_id, registration_id, player_id, player_name, lineup_role,
                                                        shirt_number, member_status, responded_at,
                                                        created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, now(), 'demo-sim', now(), 'demo-sim') on conflict do nothing
                        """, lineups, 500, DemoSimulationSeeder::bind);
        jdbc.batchUpdate("""
                        insert into tournament_payments (tournament_payment_id, registration_id, amount, payment_id, status,
                                                         paid_at, created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, 'SUCCEEDED', ?, now(), 'demo-sim', now(), 'demo-sim') on conflict do nothing
                        """, payments, 200, DemoSimulationSeeder::bind);
        return inserted;
    }

    record Fixture(UUID id, int round, String roundName, int match, UUID home, UUID away, int[] score, UUID winner,
                   UUID next, LocalDate date, String court, LocalTime start, LocalTime end) {
        LocalDateTime endsAt() {
            return date.atTime(end);
        }
    }

    /** Loại trực tiếp: cặp 1-2, 3-4...; đội vào vòng sau chỉ hiện khi cả hai trận nhánh trước đã đá. */
    private static List<Fixture> knockout(DemoTournaments.Tournament tournament, List<Entrant> entrants, LocalDateTime now) {
        List<Fixture> fixtures = new ArrayList<>();
        List<Entrant> side = new ArrayList<>(entrants);
        for (int round = 1; round <= tournament.rounds(); round++) {
            List<Entrant> winners = new ArrayList<>();
            for (int match = 1; match <= side.size() / 2; match++) {
                Entrant home = side.get(match * 2 - 2);
                Entrant away = side.get(match * 2 - 1);
                UUID next = round < tournament.rounds() ? fixtureId(tournament, round + 1, (match + 1) / 2) : null;
                Fixture fixture = fixture(tournament, round, roundName(round, tournament.rounds()), match, home, away,
                        next, false, now);
                fixtures.add(fixture);
                winners.add(fixture.winner() == null ? null : fixture.winner().equals(home.registrationId()) ? home : away);
            }
            side = winners;
        }
        return fixtures;
    }

    /** Vòng tròn theo phương pháp xoay vòng: mỗi lượt mọi đội đá một trận. */
    private static List<Fixture> roundRobin(DemoTournaments.Tournament tournament, List<Entrant> entrants, LocalDateTime now) {
        List<Fixture> fixtures = new ArrayList<>();
        List<Entrant> circle = new ArrayList<>(entrants);
        int size = circle.size();
        for (int round = 1; round < size; round++) {
            for (int match = 1; match <= size / 2; match++) {
                fixtures.add(fixture(tournament, round, "Lượt " + round, match, circle.get(match - 1),
                        circle.get(size - match), null, true, now));
            }
            circle.add(1, circle.remove(size - 1));
        }
        return fixtures;
    }

    private static Fixture fixture(DemoTournaments.Tournament tournament, int round, String roundName, int match,
                                   Entrant home, Entrant away, UUID next, boolean drawAllowed, LocalDateTime now) {
        int slot = match - 1;
        LocalDate date = tournament.roundDate(round);
        LocalTime start = tournament.matchStart(slot);
        LocalTime end = start.plusMinutes(tournament.matchMinutes());
        UUID fixtureId = fixtureId(tournament, round, match);
        boolean played = home != null && away != null && !date.atTime(end).isAfter(now);
        int[] score = played ? score(tournament, home, away, drawAllowed, fixtureId) : null;
        UUID winner = score == null || score[0] == score[1] ? null
                : score[0] > score[1] ? home.registrationId() : away.registrationId();
        return new Fixture(fixtureId, round, roundName, match, home == null ? null : home.registrationId(),
                away == null ? null : away.registrationId(), score, winner, next, date, tournament.court(slot), start, end);
    }

    /** Tỷ số theo môn; đội mạnh hơn (ELO trung bình) có xác suất thắng theo công thức ELO. */
    static int[] score(DemoTournaments.Tournament tournament, Entrant home, Entrant away, boolean drawAllowed, UUID fixtureId) {
        Random random = DemoPopulation.random(0x5C0E, fixtureId.getMostSignificantBits(), fixtureId.getLeastSignificantBits());
        double homeWins = 1 / (1 + Math.pow(10, (away.strength() - home.strength()) / 400));
        if (drawAllowed && "FOOTBALL".equals(tournament.sport()) && random.nextInt(100) < 18) {
            int goals = random.nextInt(4);
            return new int[]{goals, goals};
        }
        boolean first = random.nextDouble() < homeWins;
        int[] result = switch (tournament.playFormat()) {
            case "FOOTBALL_7", "FOOTBALL_5" -> {
                int win = 1 + random.nextInt("FOOTBALL_5".equals(tournament.playFormat()) ? 7 : 5);
                yield new int[]{win, random.nextInt(win)};
            }
            case "BASKETBALL_3X3" -> {
                int win = 15 + random.nextInt(7);
                yield new int[]{win, Math.max(5, win - 1 - random.nextInt(9))};
            }
            case "BASKETBALL_5X5" -> {
                int win = 58 + random.nextInt(35);
                yield new int[]{win, win - 1 - random.nextInt(18)};
            }
            case "VOLLEYBALL_6", "TENNIS_SINGLES", "TENNIS_DOUBLES" -> new int[]{2, random.nextInt(2)};
            case "PICKLEBALL_DOUBLES", "PICKLEBALL_SINGLES" -> random.nextInt(100) < 15
                    ? new int[]{12, 10} : new int[]{11, 3 + random.nextInt(7)};
            default -> random.nextInt(100) < 15 ? new int[]{22 + random.nextInt(4), 0} : new int[]{21, 8 + random.nextInt(12)};
        };
        if (result[1] == 0 && result[0] > 21) result[1] = result[0] - 2;
        return first ? result : new int[]{result[1], result[0]};
    }

    /** Ghi từ trận cuối ngược về trận đầu (next_fixture_id có khóa ngoại); trận vừa đá thì cập nhật tỷ số. */
    private int seedFixtures(DemoTournaments.Tournament tournament, List<Fixture> fixtures) {
        if (fixtures.isEmpty()) return 0;
        List<Object[]> reservations = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int index = fixtures.size() - 1; index >= 0; index--) {
            Fixture fixture = fixtures.get(index);
            UUID reservationId = id("tournament-reservation", fixture.id().toString());
            reservations.add(new Object[]{reservationId, tournament.id(), tournament.venue().id(),
                    tournament.venue().courtId(fixture.court()), Date.valueOf(fixture.date()),
                    Time.valueOf(fixture.start()), Time.valueOf(fixture.end())});
            boolean completed = fixture.score() != null;
            Timestamp updatedAt = Timestamp.valueOf(completed ? fixture.endsAt() : tournament.start().atTime(6, 0));
            rows.add(new Object[]{fixture.id(), tournament.id(), fixture.roundName(), fixture.round(), fixture.match(),
                    fixture.home(), fixture.away(), completed ? fixture.score()[0] : null,
                    completed ? fixture.score()[1] : null, fixture.winner(), fixture.next(), reservationId,
                    completed ? "COMPLETED" : "SCHEDULED", !completed,
                    Timestamp.valueOf(tournament.start().minusDays(1).atTime(21, 0)), updatedAt});
        }
        jdbc.batchUpdate("""
                        insert into tournament_venue_reservations (reservation_id, tournament_id, venue_id, court_id,
                                                                   play_date, start_time, end_time, status,
                                                                   created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', now(), 'demo-sim', now(), 'demo-sim')
                        on conflict do nothing
                        """, reservations, 200, DemoSimulationSeeder::bind);
        return sum(jdbc.batchUpdate("""
                        insert into tournament_fixtures (fixture_id, tournament_id, round_name, round_number, match_number,
                                                         registration1_id, registration2_id, score1, score2,
                                                         winner_registration_id, next_fixture_id, reservation_id, status,
                                                         rating_synced, created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim')
                        on conflict (fixture_id) do update set registration1_id = excluded.registration1_id,
                            registration2_id = excluded.registration2_id, score1 = excluded.score1, score2 = excluded.score2,
                            winner_registration_id = excluded.winner_registration_id, status = excluded.status,
                            rating_synced = excluded.rating_synced, updated_at = excluded.updated_at
                        where tournament_fixtures.created_by = 'demo-sim' and tournament_fixtures.status <> 'COMPLETED'
                          and (excluded.status = 'COMPLETED'
                               or tournament_fixtures.registration1_id is distinct from excluded.registration1_id
                               or tournament_fixtures.registration2_id is distinct from excluded.registration2_id)
                        """, rows, 200, DemoSimulationSeeder::bind));
    }

    private static List<String> rules(DemoTournaments.Tournament tournament) {
        return switch (tournament.sport()) {
            case "FOOTBALL" -> List.of("Có mặt trước giờ thi đấu 20 phút để kiểm tra danh sách.",
                    "Thẻ đỏ bị treo giò trận kế tiếp.", "Loại trực tiếp hòa thì đá luân lưu (ghi tỷ số sau luân lưu).");
            case "BADMINTON" -> List.of("Giao cầu theo luật BWF.", "Vận động viên có mặt trước 15 phút.");
            case "TENNIS" -> List.of("3 set thắng 2, set quyết định là tie-break 10 điểm.", "Tự mang bóng mới cho trận chung kết.");
            case "PICKLEBALL" -> List.of("Đánh tới 11 điểm, cách biệt 2.", "Không vô lê trong khu kitchen.");
            case "BASKETBALL" -> List.of("Luật FIBA rút gọn.", "Mỗi đội có tối đa 2 lần hội ý.");
            default -> List.of("Đấu 3 set thắng 2, set quyết định tính đến 15 điểm.");
        };
    }

    // ---- Tiện ích ----

    private static String roundName(int round, int totalRounds) {
        int fromFinal = totalRounds - round;
        if (fromFinal == 0) return "Chung kết";
        if (fromFinal == 1) return "Bán kết";
        if (fromFinal == 2) return "Tứ kết";
        return "Vòng " + round;
    }

    private static UUID fixtureId(DemoTournaments.Tournament tournament, int round, int match) {
        return id("fixture", tournament.name() + "|" + round + "|" + match);
    }

    private static String description(Club club) {
        return "CLB " + sportLabel(club.sport()).toLowerCase(Locale.ROOT) + " phong trào ở " + club.district() + ", "
                + club.city().name() + ". Sinh hoạt mỗi " + dayLabel(club.sessionDay()) + " lúc " + club.sessionHour()
                + "h, chào đón cả người mới chơi.";
    }

    private static String dayLabel(DayOfWeek day) {
        return day == DayOfWeek.SUNDAY ? "Chủ nhật" : "thứ " + (day.getValue() + 1);
    }

    private static String sportLabel(String sport) {
        return switch (sport) {
            case "FOOTBALL" -> "Bóng đá";
            case "BADMINTON" -> "Cầu lông";
            case "PICKLEBALL" -> "Pickleball";
            case "TENNIS" -> "Tennis";
            case "BASKETBALL" -> "Bóng rổ";
            default -> "Bóng chuyền";
        };
    }

    private static String given(DemoPopulation.Player player) {
        String[] parts = player.fullName().split(" ");
        return parts[parts.length - 1];
    }

    /** "Hồ Chí Minh" -> "ho-chi-minh", cùng dạng tỉnh/thành CLB đang lưu. */
    static String slug(String city) {
        return Normalizer.normalize(city, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace('đ', 'd')
                .replace('Đ', 'D').toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-");
    }

    private static <T> List<T> shuffled(List<T> items, Random random) {
        List<T> copy = new ArrayList<>(items);
        Collections.shuffle(copy, random);
        return copy;
    }

    private static String pick(Random random, String... values) {
        return values[random.nextInt(values.length)];
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    private static void bind(PreparedStatement statement, Object[] row) throws SQLException {
        for (int column = 0; column < row.length; column++) statement.setObject(column + 1, row[column]);
    }

    private static int sum(int[][] batches) {
        int total = 0;
        for (int[] batch : batches) for (int count : batch) total += Math.max(count, 0);
        return total;
    }

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
