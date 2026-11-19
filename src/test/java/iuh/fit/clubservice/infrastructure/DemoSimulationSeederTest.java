package iuh.fit.clubservice.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoSimulationSeederTest {

    @Test
    void clubsGatherLocalPlayersOfTheirSport() {
        List<DemoSimulationSeeder.Club> clubs = DemoSimulationSeeder.clubs();
        assertThat(clubs).hasSize(30);
        assertThat(clubs.stream().map(DemoSimulationSeeder.Club::id).distinct()).hasSize(30);
        assertThat(clubs).allSatisfy(club -> {
            assertThat(club.everyone()).hasSizeBetween(7, 25);
            assertThat(club.everyone()).allMatch(player -> player.city() == club.city()
                    && player.sports().contains(club.sport()));
            assertThat(club.everyone().stream().distinct()).hasSize(club.everyone().size());
        });
        // Tất định giữa các lần chạy JVM: id theo tên.
        assertThat(DemoSimulationSeeder.clubs().get(0).name()).isEqualTo(clubs.get(0).name());
        assertThat(DemoSimulationSeeder.slug("Khánh Hòa")).isEqualTo("khanh-hoa");
    }

    @Test
    void matchmakingClubsArePairsOfDisjointLocalTeams() {
        List<DemoSimulationSeeder.Club> clubs = DemoSimulationSeeder.matchmakingClubs();
        Set<UUID> everyone = new HashSet<>();
        assertThat(clubs).hasSize(12).allSatisfy(club -> {
            assertThat(club.city().name()).isEqualTo("Hồ Chí Minh");
            assertThat(club.owner().sports()).contains(club.sport());
            club.everyone().forEach(player -> assertThat(everyone.add(player.userId())).as(club.name()).isTrue());
        });
        // Mỗi bên đủ người cho hình thức đông nhất của môn.
        assertThat(clubs).allMatch(club -> club.everyone().size() == switch (club.sport()) {
            case "FOOTBALL" -> 11;
            case "VOLLEYBALL" -> 6;
            case "BASKETBALL" -> 5;
            default -> 2;
        });
        // Không trùng tên/ id với CLB mô phỏng.
        Set<UUID> simulated = new HashSet<>(DemoSimulationSeeder.clubs().stream().map(DemoSimulationSeeder.Club::id).toList());
        assertThat(clubs).noneMatch(club -> simulated.contains(club.id()));
    }

    @Test
    void nobodyPlaysForTwoTeamsInOneTournament() {
        List<DemoSimulationSeeder.Club> clubs = DemoSimulationSeeder.clubs();
        for (DemoTournaments.Tournament tournament : DemoTournaments.ALL) {
            List<DemoSimulationSeeder.Entrant> entrants = DemoSimulationSeeder.entrants(tournament, clubs);
            assertThat(entrants).as(tournament.name()).hasSize(tournament.teams());
            Set<UUID> players = new HashSet<>();
            entrants.forEach(entrant -> entrant.roster().forEach(player ->
                    assertThat(players.add(player.userId())).as(tournament.name()).isTrue()));
            assertThat(entrants).allMatch(entrant -> !entrant.registeredAt().toLocalDate()
                    .isAfter(tournament.registrationClose()));
        }
    }

    @Test
    void knockoutScoresAlwaysHaveAWinner() {
        DemoTournaments.Tournament cup = DemoTournaments.ALL.get(0);
        List<DemoSimulationSeeder.Entrant> entrants = DemoSimulationSeeder.entrants(cup, DemoSimulationSeeder.clubs());
        for (int match = 0; match < 50; match++) {
            int[] score = DemoSimulationSeeder.score(cup, entrants.get(0), entrants.get(1), false,
                    DemoSimulationSeeder.id("test", String.valueOf(match)));
            assertThat(score[0]).isNotEqualTo(score[1]);
        }
    }
}
