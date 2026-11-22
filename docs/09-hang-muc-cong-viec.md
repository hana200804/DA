# 09 · Hạng mục công việc

Kế hoạch 16 tuần cho nhóm 3 người. Mỗi chủ đề là một **milestone** trên GitHub; mỗi đầu ra tương ứng một hoặc nhiều **issue**. Phân kỳ tổng thể: [Đặc tả §12](02-dac-ta-yeu-cau.md#12-thời-gian-và-phân-kỳ).

## Sơ đồ phụ thuộc

```mermaid
flowchart LR
    M1[M1 · Thiết kế và nền tảng<br/>tuần 1–3]
    M2[M2 · Dữ liệu<br/>tuần 1–6]
    M3[M3 · Ba mô hình<br/>tuần 4–10]
    M4[M4 · Bản đồ và phê duyệt<br/>tuần 4–10]
    M5[M5 · Ứng dụng di động<br/>tuần 4–11]
    M6[M6 · Tích hợp và đánh giá<br/>tuần 11–16]

    M1 --> M4
    M1 --> M5
    M2 --> M3
    M3 -->|mô hình server| M4
    M3 -->|mô hình TFLite| M5
    M4 --> M5
    M3 --> M6
    M4 --> M6
    M5 --> M6
```

**Đường găng:** M2 → M3 → M6. Dữ liệu chậm thì mọi thứ phía sau chậm theo, nên tuần 1–6 thành viên 1 chỉ làm dữ liệu và mô hình; mô hình tạm (YOLOv8n huấn luyện trên bộ dữ liệu công khai) được giao cho thành viên 2 và 3 từ tuần 4 để không ai phải chờ.

## Phân công theo tuần

| Tuần | Thành viên 1 · AI và dữ liệu | Thành viên 2 · Backend, web | Thành viên 3 · Di động |
| --- | --- | --- | --- |
| 1–2 | Danh mục nhãn nháp; khảo sát và kiểm tra giấy phép dữ liệu | Rà soát tài liệu; hợp đồng API; khung backend một khối | Rà soát tài liệu; khung ứng dụng; màn hình mẫu |
| 3 | Gán nhãn dữ liệu công khai; quay video đợt 1 | Tài khoản, phân quyền, danh mục | Tài khoản, tra cứu offline |
| 4 | Gán nhãn; mô hình tạm YOLOv8n **đã huấn luyện** ([hồ sơ](../ai/experiments/yolov8n-640-v1.1-temp-s0/)) | Dịch vụ nhận diện (mô hình tạm), API nhận diện | Nhận diện ảnh qua server |
| 5 | **Dữ liệu v1.0**; bắt đầu huấn luyện DETR | API biển theo vùng, bản đồ trên web | Màn hình bản đồ, chi tiết biển |
| 6 | Huấn luyện YOLOv5, YOLOv8 | Đề xuất, worker gom cụm, điểm tin cậy | Tích hợp TFLite, FrameAnalyzer |
| 7 | Bộ đánh giá chung; **baseline cả 3 mô hình** | Hàng đợi phê duyệt, lịch sử xử lý | Tracker, đề xuất từ camera |
| 8 | Video đợt 2 (đêm, mưa); tách video val và tập thực địa theo tuyến | Trang phê duyệt trên web | Gửi đề xuất, hàng đợi offline; **đo FPS lần đầu trên video val** |
| 9 | Thí nghiệm AI-07; xuất TFLite các ứng viên | Đánh giá, báo cáo, xử lý báo cáo | Đánh giá, báo cáo trên ứng dụng |
| 10 | Chọn mô hình triển khai (AI-04); thẻ mô hình | Danh mục, người dùng trên web; đưa mô hình cuối vào dịch vụ | Đưa mô hình cuối vào ứng dụng; đo trên 2 thiết bị |
| 11 | Đánh giá cấu hình đã chốt trên tập test | **Luồng đầu–cuối**; P1: tự duyệt, so sánh mô hình, thống kê | **Luồng đầu–cuối**; P1: đề xuất của tôi, Wi-Fi |
| 12 | Thử nghiệm thực địa | Kiểm thử hiệu năng, bảo mật; triển khai demo | Sửa lỗi; P2 nếu kịp |
| 13–14 | Đánh giá thực địa, phê duyệt có trợ giúp (§5–6) | Mô phỏng §6; sửa lỗi | Đánh giá trên thiết bị §4; sửa lỗi |
| 15–16 | Báo cáo chương AI | Báo cáo chương hệ thống | Báo cáo chương ứng dụng; slide; video demo |

Cuối tuần 12 **đóng băng tính năng**.

## M1 · Thiết kế và nền tảng

**Mục tiêu:** cả nhóm thống nhất thiết kế; mọi thành phần có khung chạy được, kiểm thử được, triển khai được.

- [ ] Bộ tài liệu `docs/` được cả nhóm và GVHD duyệt; ADR chuyển `Accepted`
- [ ] Trả lời Q1–Q4 trong [Đặc tả §11](02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ)
- [ ] Backend một khối với các module `identity`, `catalog`, `sign`, `recognition`, `audit`; test ArchUnit cho ranh giới
- [ ] Migration Flyway cho toàn bộ lược đồ [CSDL](06-co-so-du-lieu.md)
- [ ] Tài khoản, phiên, phân quyền (FR-01, FR-30)
- [ ] Docker Compose: MySQL, Redis, MinIO, nginx, backend, dịch vụ nhận diện, web
- [ ] CI build, lint, test cho mọi thành phần

**Hoàn thành khi:** `docker compose up` chạy đủ hệ thống; đăng ký, đăng nhập được từ ứng dụng và web; CI xanh.

## M2 · Dữ liệu

**Mục tiêu:** dữ liệu đủ và đúng để huấn luyện và so sánh công bằng. Thiết kế: [Dữ liệu và gán nhãn](05-du-lieu.md).

- [x] [`labels.yaml` v1 đối chiếu QCVN 41](../ai/data/labels.yaml) (AI-01)
- [ ] Dữ liệu công khai đã kiểm tra giấy phép, gán nhãn lại
- [ ] Video thực địa ngày, đêm, mưa kèm GPS và nhãn chuẩn của tuyến
- [ ] Tập thực địa đã gán nhãn
- [ ] Dữ liệu tổng hợp cho lớp hiếm
- [x] [Script kiểm tra dữ liệu, chuyển YOLO ↔ COCO, sinh manifest có hash](../ai/data/README.md)

**Hoàn thành khi:** dữ liệu v1.0 có manifest và chia tập cố định; kiểm soát chất lượng gán nhãn đạt; thống kê dữ liệu đã lập.

## M3 · Ba mô hình

**Mục tiêu:** YOLOv5, YOLOv8, DETR được huấn luyện và so sánh theo cùng giao thức; chọn được mô hình triển khai. Thiết kế: [Thiết kế AI §2–4, §8](04-thiet-ke-ai.md#2-ba-mô-hình).

- [ ] Huấn luyện YOLOv5 n/s, YOLOv8 n/s và DETR-R50; RT-DETR-L bổ sung (AI-02)
- [x] Bộ đánh giá chung bằng `pycocotools` (AI-03)
- [ ] Bảng so sánh và phân tích lỗi ([Kiểm thử §3](08-kiem-thu.md#3-đánh-giá-và-so-sánh-mô-hình))
- [x] Xuất [bản TFLite INT8/FP16 của các ứng viên điện thoại](../ai/README.md#so-sánh-ứng-viên-điện-thoại-ai-04) (AI-05)
- [ ] Đo TFLite trên thiết bị Android tham chiếu (AI-05). Thành viên 3 phụ trách sau khi chốt Q7.
- [ ] Thí nghiệm 960 px, dữ liệu tổng hợp (AI-07)
- [ ] Chọn mô hình triển khai kèm lý do (AI-04); thẻ mô hình (AI-06)

**Hoàn thành khi:** đạt NFR-05, NFR-06; bảng kết quả đủ cả ba mô hình.

## M4 · Bản đồ và phê duyệt

**Mục tiêu:** đề xuất từ người dùng trở thành biển trên bản đồ qua phê duyệt có trợ giúp, mọi thay đổi có lịch sử. Thiết kế: [Kiến trúc §6](03-kien-truc.md#6-luồng-xử-lý-chính), [Thiết kế AI §6–7](04-thiet-ke-ai.md#6-gom-cụm-và-điểm-tin-cậy).

- [ ] Dịch vụ nhận diện phục vụ ba mô hình; API nhận diện có giới hạn tần suất (FR-02, FR-24)
- [ ] API danh mục, biển theo vùng có điểm gộp (FR-05, FR-06, FR-08)
- [ ] API đề xuất, worker gom cụm qua Redis Stream, điểm tin cậy, gợi ý (FR-04, FR-20, FR-21)
- [ ] Đánh giá, rà soát lại, báo cáo (FR-09, FR-10, FR-22)
- [ ] Lịch sử xử lý bất biến (FR-23)
- [ ] Web: hàng đợi phê duyệt, chi tiết biển, báo cáo, sửa biển, lịch sử (FR-31 → FR-34)
- [ ] Web: danh mục, người dùng (FR-35, FR-36)
- [ ] P1: tự duyệt, so sánh mô hình, thống kê (FR-25, FR-37, FR-38)

**Hoàn thành khi:** các ca kiểm thử P0 của backend và web trong [kế hoạch kiểm thử](08-kiem-thu.md) đạt. Nếu triển khai FR-25, chạy thêm UT-B09 và UT-B10.

## M5 · Ứng dụng di động

**Mục tiêu:** người dùng nhận diện, tra cứu, xem bản đồ và đóng góp từ điện thoại. Thiết kế: [Thiết kế AI §5](04-thiet-ke-ai.md#5-nhận-diện-trên-thiết-bị).

- [ ] Tài khoản (FR-01)
- [ ] Tra cứu danh mục offline (FR-08)
- [ ] Nhận diện ảnh qua server, chọn mô hình (FR-02)
- [ ] Bản đồ, lọc, chi tiết biển (FR-05 → FR-07)
- [ ] Nhận diện qua camera trên máy, tracker (FR-03)
- [ ] Gửi đề xuất từ camera và từ ảnh chụp (FR-04)
- [ ] Đánh giá, báo cáo (FR-09, FR-10)
- [ ] Chế độ phát lại video để đo hiệu năng
- [ ] P1: đề xuất của tôi, hàng đợi offline và chỉ Wi-Fi, quên mật khẩu, tìm địa điểm (FR-11 → FR-14)
- [ ] P2: tải trước vùng, cảnh báo tốc độ (FR-15, FR-16)

**Hoàn thành khi:** đạt NFR-01, NFR-07, NFR-08 trên thiết bị tham chiếu; ca UT-M01 → UT-M07, IT-M01 → IT-M03, UI-M01, UI-M02 đạt.

## M6 · Tích hợp và đánh giá

**Mục tiêu:** có số liệu chứng minh hệ thống đạt mục tiêu G1–G5 và một bản demo hoàn chỉnh. Phương pháp: [Kiểm thử](08-kiem-thu.md).

- [ ] Luồng đầu–cuối: ảnh → đề xuất → phê duyệt → bản đồ → đánh giá → rà soát
- [ ] Đánh giá trên thiết bị (§4) và thực địa (§5)
- [ ] Đánh giá phê duyệt có trợ giúp so với phê duyệt từng đề xuất (§6)
- [ ] Kiểm thử bảo mật và hiệu năng server (§7, §8)
- [ ] Triển khai bản demo lên máy chủ bằng Docker Compose, có HTTPS
- [ ] Báo cáo đồ án, slide, video demo dự phòng

**Hoàn thành khi:** tiêu chí ở [Kiểm thử §10](08-kiem-thu.md#10-tiêu-chí-hoàn-thành-và-phân-loại-lỗi) đạt; bản `v1.0.0` được gắn tag trên `main`.

## Rủi ro tiến độ

| Rủi ro | Dấu hiệu sớm | Cách xử lý |
| --- | --- | --- |
| Dữ liệu chưa đủ ở tuần 5 | Số box theo lớp dưới ngưỡng ở tuần 4 | Thu hẹp danh mục, gộp lớp hiếm; thành viên 2, 3 hỗ trợ gán nhãn 2 ngày |
| DETR chưa hội tụ ở tuần 8 | mAP val tăng chậm sau 50 epoch | Ưu tiên thời gian GPU cho biến thể đã duyệt và báo cáo đường học. Trao đổi với GVHD nếu cần đổi biến thể |
| Không đạt 15 FPS | Lần đo tuần 8 dưới 12 FPS | Ảnh 480 px, INT8, xử lý cách khung |
| Tích hợp muộn | Tuần 10 chưa gọi được API thật từ ứng dụng | Kiểm thử hợp đồng API từ tuần 4; demo nội bộ cuối mỗi tuần |
| Trễ chung | Hai tuần liền không xong đầu ra | Cắt theo thứ tự ở [Đặc tả §12](02-dac-ta-yeu-cau.md#12-thời-gian-và-phân-kỳ) |
