package iuh.fit.clubservice.infrastructure.external.client;

import iuh.fit.clubservice.infrastructure.config.PaymentClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Đẩy lệ phí giải mô phỏng sang payment-service để dựng dòng tiền demo ({@code DemoTournamentFeeSync}). Endpoint chỉ có
 * khi payment-service bật {@code app.demo-data.simulation.enabled}.
 */
@FeignClient(
        name = "GOATSPORTS-PAYMENT-SERVICE",
        contextId = "demoPaymentClient",
        url = "${payment.service.url}",
        path = "/internal/demo",
        configuration = PaymentClientConfig.class
)
public interface DemoPaymentClient {

    @PostMapping("/tournament-fees")
    Map<String, Object> tournamentFees(@RequestBody List<TournamentFee> rows);

    /** Khớp {@code DemoPaymentSeeder.TournamentFee} ở payment-service. */
    record TournamentFee(UUID paymentId, UUID registrationId, UUID payerId, UUID organizerId,
                         String organizerAccountName, double amount, LocalDateTime paidAt) { }
}
