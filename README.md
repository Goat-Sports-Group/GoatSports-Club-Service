# GoatSports Club Service

Quản lý câu lạc bộ và giải đấu: thành viên, buổi sinh hoạt, đăng ký giải, lịch thi đấu, bảng xếp hạng.

## Chức năng chính

- **Câu lạc bộ**: tạo, sửa, giải tán (đổi trạng thái, không xóa), chuyển quyền sở hữu. Vai trò OWNER / ADMIN / MEMBER;
  quy tắc "ai là người quản lý" nằm duy nhất ở `ClubAccessPolicy`. Duyệt thành viên tự động hoặc thủ công, mời, cấm.
- **Buổi sinh hoạt, ảnh, thống kê trận** của CLB; scouting tìm người chơi quanh CLB.
- **CLB nổi bật**: xếp hạng theo sinh hoạt đều, thành tích, tăng trưởng và quy mô (`ClubFeaturedPolicy`).
- **Giải đấu** (chủ sân tạo): bản nháp → công bố (chỉ đọc) → diễn ra → kết thúc / hủy (tự hoàn tiền, trả sân).
  - Đăng ký đội theo mời + chấp nhận, phí thanh toán trong 24 giờ (`TournamentRegistrationExpiryJob`).
  - Lịch đấu: loại trực tiếp (hạt giống tất định, `BracketPolicy`) hoặc vòng tròn; bảng xếp hạng tính từ kết quả trận
    (`StandingsCalculator`), không lưu bảng riêng.
  - Giữ sân qua venue-service; điểm ELO đồng bộ sang auth-service sau mỗi trận (`TournamentRatingJob` thử lại khi lỗi).
- **Trận giao hữu CLB** từ ghép trận (`/internal/clubs`), cập nhật ELO của CLB.

## API chính

| Đường dẫn | Mô tả |
|---|---|
| `/api/v1/clubs`, `/api/v1/clubs/{clubId}/photos` | CLB, thành viên, sinh hoạt, ảnh, CLB nổi bật |
| `/api/v1/tournaments` | Giải đấu, đăng ký, lịch đấu, tỷ số, bảng xếp hạng |
| `/api/v1/admin`, `/api/v1/admin/stats` | Quản trị CLB và giải |
| `/internal/clubs`, `/internal/users` | API nội bộ (cần `X-Gateway-Secret`) |

## Cấu trúc

```
src/main/java/iuh/fit/clubservice/
├── domain/          # entity, enum, quy tắc thuần: ClubAccessPolicy, BracketPolicy, StandingsCalculator, …
├── application/     # service theo use case, port, DTO
├── infrastructure/  # JPA, Kafka, Feign, job định kỳ, demo seeder
└── presentation/    # REST controller
shared-kernel/       # @AuditLog, Feign interceptor dùng chung
```

## Tích hợp

- **PostgreSQL** (Liquibase).
- **Kafka nhận**: `payment.events` (phí giải), `user-deleted`. **Kafka phát**: `club.events` (thông báo), `user-log`.
- **Feign**: auth (hồ sơ, ELO), payment (phí giải, hoàn tiền), venue (giữ sân), social (nhóm chat CLB), storage (ảnh).

## Chạy và kiểm thử

```bash
mvn -f shared-kernel/pom.xml clean install -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev     # cần application-dev.properties (bị .gitignore)
mvn test
```

Bật `DEMO_DATA_ENABLED=true` / `DEMO_SIMULATION_ENABLED=true` để có CLB và giải mẫu.

## CI/CD

`.gitlab-ci.yml`: test → `jib:build` lên Docker Hub → gọi Jenkins job `GoatSports-CD`.
