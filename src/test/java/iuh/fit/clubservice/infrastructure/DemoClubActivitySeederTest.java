package iuh.fit.clubservice.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Lịch giao hữu mô phỏng: tất định, theo thứ tự thời gian, không đá trước khi CLB thành lập, tỷ số đúng luật từng môn. */
class DemoClubActivitySeederTest {
    private static final LocalDateTime FOUNDED = LocalDateTime.of(2026, 7, 1, 9, 0);

    private static DemoClubActivitySeeder.ClubRow club(String name, String sport, String city, LocalDateTime createdAt) {
        return new DemoClubActivitySeeder.ClubRow(UUID.nameUUIDFromBytes(name.getBytes()), name, sport, city,
                UUID.nameUUIDFromBytes(("owner-" + name).getBytes()), createdAt);
    }

    @Test
    void scheduleIsDeterministicOrderedAndRespectsFoundingDates() {
        LocalDateTime late = LocalDateTime.of(2026, 10, 1, 9, 0);
        List<DemoClubActivitySeeder.ClubRow> clubs = List.of(
                club("A FC", "FOOTBALL", "Hồ Chí Minh", FOUNDED), club("B FC", "FOOTBALL", "Hồ Chí Minh", FOUNDED),
                club("C FC", "FOOTBALL", "Hồ Chí Minh", late), club("D FC", "FOOTBALL", "Hồ Chí Minh", FOUNDED),
                club("Lẻ Volley", "VOLLEYBALL", "Hà Nội", FOUNDED), club("Cầu lông", "BADMINTON", "Hồ Chí Minh", FOUNDED));

        List<DemoClubActivitySeeder.Friendly> first = DemoClubActivitySeeder.schedule(clubs, LocalDate.of(2026, 12, 31));
        List<DemoClubActivitySeeder.Friendly> again = DemoClubActivitySeeder.schedule(clubs, LocalDate.of(2026, 12, 31));

        assertThat(first).isNotEmpty();
        assertThat(first).extracting(DemoClubActivitySeeder.Friendly::sessionId)
                .containsExactlyElementsOf(again.stream().map(DemoClubActivitySeeder.Friendly::sessionId).toList());
        assertThat(first).isSortedAccordingTo((a, b) -> a.playedAt().compareTo(b.playedAt()));
        assertThat(first).allSatisfy(match -> {
            assertThat(match.home().sport()).isEqualTo("FOOTBALL");
            assertThat(match.playedAt()).isAfterOrEqualTo(match.home().createdAt()).isAfterOrEqualTo(match.away().createdAt());
            assertThat(match.home().id()).isNotEqualTo(match.away().id());
        });
    }

    @Test
    void scoresFollowEachSportsRules() {
        Random random = new Random(7);
        for (int index = 0; index < 300; index++) {
            int[] volley = DemoClubActivitySeeder.score("VOLLEYBALL", 1200, 1300, random);
            assertThat(Math.max(volley[0], volley[1])).isEqualTo(3);
            assertThat(Math.min(volley[0], volley[1])).isBetween(0, 2);
            int[] hoops = DemoClubActivitySeeder.score("BASKETBALL", 1300, 1200, random);
            assertThat(hoops[0]).isNotEqualTo(hoops[1]);
            int[] football = DemoClubActivitySeeder.score("FOOTBALL", 1250, 1250, random);
            assertThat(football[0]).isBetween(0, 4);
            assertThat(football[1]).isBetween(0, 4);
        }
    }
}
