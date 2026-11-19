# Signa — bối cảnh dự án

Đồ án tốt nghiệp **"Xây dựng hệ thống hỗ trợ nhận diện và hiển thị biển báo giao thông trên bản đồ số"** (GVHD: TS. Đào Thị Thúy Quỳnh). Nhóm 3 người, 16 tuần.

Repo này bắt đầu lại từ [bộ thiết kế Signa](docs/README.md), chuyển từ nhánh `redesign/thiet-ke-lai` của dự án cũ. Việc triển khai nằm trong [kế hoạch 16 tuần](docs/09-hang-muc-cong-viec.md).

## Đề tài bắt buộc

- Dùng **YOLOv5, YOLOv8 và DETR** để nhận diện biển báo từ hình ảnh.
- Ứng dụng di động, backend, web quản trị: **nhận diện, tra cứu, hiển thị biển trên bản đồ, quản lý và phê duyệt** dữ liệu biển.
- MySQL (người dùng/tài khoản, phân quyền, loại biển, thông tin biển, biển báo thời gian thực, đánh giá, báo cáo, lịch sử cập nhật/xử lý), Redis (dữ liệu tạm), MinIO (ảnh biển báo).

Mọi quyết định phải truy được về các ý trên; phần ngoài đề tài đánh dấu là mở rộng.

## Phương án đang đề xuất

- So sánh công bằng 3 mô hình: phát hiện một tầng trực tiếp trên mã biển, cùng dữ liệu, một bộ đánh giá `pycocotools` (ADR-0003).
- Server chạy cả 3 mô hình cho ảnh chụp; điện thoại chạy một YOLO nhỏ (TFLite) cho camera, không cần mạng (ADR-0002).
- Gom cụm đề xuất + điểm tin cậy Beta (tiên nghiệm Jeffreys) chỉ **gợi ý**; kiểm duyệt viên quyết định (ADR-0004).
- Backend Spring Boot một khối chia module (`identity`, `catalog`, `sign`, `recognition`, `audit`); dịch vụ nhận diện Python FastAPI qua HTTP (ADR-0005).
- Lưu ảnh cắt đề xuất và ảnh đính kèm báo cáo trong các bucket MinIO riêng. Ảnh gửi chỉ để nhận diện được xử lý trong bộ nhớ (ADR-0006).
- Gom cụm tuần tự bằng một consumer Redis Stream (ADR-0007).
- Cắt khỏi phạm vi: dẫn đường, OCR biển phụ, OTA mô hình, huấn luyện lại tự động. Cảnh báo tốc độ bằng giọng nói là P2.

## Quy ước

- Tài liệu viết tiếng Việt; mã yêu cầu `FR-xx`, `NFR-xx`, `AI-xx` chỉ định nghĩa trong `docs/02-dac-ta-yeu-cau.md`.
- Đổi quyết định đã chốt: viết ADR mới, ADR cũ chuyển `Superseded`.
- Markdown theo `.markdownlint-cli2.yaml`; sơ đồ dùng Mermaid.

## Việc còn mở

- Hỏi GVHD Q3 giấy phép dữ liệu (`docs/02` §11). Đã chốt: Q1 `ai/data/labels.yaml` v1 gồm 46 lớp; Q2 DETR-R50 gốc là mô hình bắt buộc, RT-DETR-L bổ sung; Q4 huấn luyện trên Colab (200 điểm).
- Các ADR đang `Proposed`, chờ cả nhóm review để chuyển `Accepted`.
- Chưa chọn hướng mở rộng nào để đưa vào tài liệu. Đề xuất đang chờ quyết định:
  - P1: kết hợp 3 mô hình bằng Weighted Boxes Fusion + hiệu chỉnh độ tin cậy (ECE); bản đồ và hàng đợi duyệt cập nhật thời gian thực bằng SSE.
  - P2: chưng cất tri thức từ mô hình lớn sang YOLO điện thoại, hoặc phát hiện biển bị gỡ bằng quan sát âm.
  - Chỉ khi dư người/thời gian: một vòng lặp dữ liệu (gán nhãn ảnh khó → huấn luyện lại → đo cải thiện).
