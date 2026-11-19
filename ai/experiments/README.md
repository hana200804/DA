# Thí nghiệm

Mỗi lượt huấn luyện có một thư mục `ai/experiments/<mã thí nghiệm>/` ([Thiết kế AI §3](../../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện)).

Đặt mã theo dạng `<mô hình>-<cỡ>-<ảnh>-<ghi chú>-s<seed>`, ví dụ `yolov8n-640-base-s0` hoặc `rtdetr-l-640-synth-s1`.

| File | Nội dung | Có trong Git |
| --- | --- | --- |
| `config.yaml` | Toàn bộ siêu tham số, phiên bản dữ liệu, phiên bản danh mục nhãn | Có |
| `meta.json` | Commit, thời điểm, GPU, số giờ GPU, epoch dừng | Có |
| `metrics.json` | Kết quả của bộ đánh giá chung trên val | Có |
| `log.txt` hoặc `results.csv` | Log huấn luyện | Có nếu nhỏ hơn 5 MB |
| `weights/best.pt` | Trọng số tốt nhất | Không. Lưu trên ổ chung hoặc MinIO, ghi đường dẫn trong `meta.json` |
| `model_card.json` | Thẻ mô hình (AI-06), chỉ khi mô hình được phát hành | Có |

Không chạy bộ đánh giá trên test hoặc tập thực địa trước khi chốt cấu hình ([Thiết kế AI §4](../../docs/04-thiet-ke-ai.md#chọn-mô-hình-triển-khai--ai-04)).
