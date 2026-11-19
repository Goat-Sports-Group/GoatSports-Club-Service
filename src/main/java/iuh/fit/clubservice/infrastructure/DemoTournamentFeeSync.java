package iuh.fit.clubservice.infrastructure;

import iuh.fit.clubservice.infrastructure.external.client.DemoPaymentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Lệ phí giải mà seeder ghi thẳng vào {@code tournament_payments} (trạng thái SUCCEEDED, có {@code payment_id}) chưa có
 * khoản thu tương ứng ở payment-service: đẩy sang để màn tài chính của người tổ chức và admin khớp với đăng ký giải.
 * Lần đầu thử mỗi 30 phút tới khi thành công, sau đó mỗi đêm sau seeder mô phỏng. Đẩy lại là an toàn (id tất định).
 * Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoTournamentFeeSync {
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final DemoPaymentClient client;
    private volatile boolean synced;

    @Scheduled(initialDelay = 5, fixedDelay = 30, timeUnit = TimeUnit.MINUTES)
    public void syncUntilDone() {
        if (!synced) sync();
    }

    @Scheduled(cron = "0 55 0 * * *", zone = "Asia/Ho_Chi_Minh")
    public void nightly() {
        sync();
    }

    void sync() {
        Map<UUID, String> ownerNames = new HashMap<>();
        DemoPopulation.owners().forEach(owner -> ownerNames.put(owner.userId(), owner.fullName()));
        List<DemoPaymentClient.TournamentFee> rows = jdbc.query("""
                select tp.payment_id, tp.registration_id, r.registered_by, t.organizer_id, t.name, tp.amount, tp.paid_at
                from tournament_payments tp
                join tournament_registrations r on r.registration_id = tp.registration_id
                join tournaments t on t.tournament_id = r.tournament_id
                where tp.status = 'SUCCEEDED' and tp.payment_id is not null and tp.paid_at is not null
                  and tp.created_by in ('demo-sim', 'demo-seed')
                """, (rs, index) -> {
            UUID organizerId = rs.getObject(4, UUID.class);
            Timestamp paidAt = rs.getTimestamp(7);
            return new DemoPaymentClient.TournamentFee(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), organizerId,
                    ownerNames.getOrDefault(organizerId, "BTC " + rs.getString(5)), rs.getDouble(6),
                    paidAt.toLocalDateTime());
        });
        try {
            int payments = 0;
            for (int from = 0; from < rows.size(); from += BATCH) {
                Map<String, Object> result = client.tournamentFees(rows.subList(from, Math.min(rows.size(), from + BATCH)));
                Object inserted = result == null ? null : result.get("payments");
                if (inserted instanceof Number number) payments += number.intValue();
            }
            synced = true;
            log.info("Đã đẩy {} lệ phí giải mô phỏng sang payment-service ({} khoản thu mới)", rows.size(), payments);
        } catch (RuntimeException exception) {
            log.warn("Chưa đẩy được lệ phí giải mô phỏng sang payment-service (thử lại sau): {}", exception.getMessage());
        }
    }
}
