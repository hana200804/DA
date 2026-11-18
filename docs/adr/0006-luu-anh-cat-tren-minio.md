# ADR-0006: Chỉ lưu ảnh cắt vùng biển trên MinIO, tải lên qua backend

**Status**: Proposed

## Bối cảnh

Đề tài dùng **MinIO để lưu hình ảnh biển báo**. Có ba loại ảnh: ảnh gửi để nhận diện (FR-02), ảnh cắt kèm đề xuất (FR-04) để kiểm duyệt viên xem khi phê duyệt, và ảnh đính kèm báo cáo (FR-10). Ảnh gốc chứa người đi đường, biển số xe; ảnh cắt chỉ chứa biển, khoảng 20 KB.

Thiết kế trước đây cho ứng dụng tải ảnh thẳng lên MinIO bằng presigned URL theo luồng ba bước: tạo lô, tải ảnh, xác nhận.

## Quyết định

- **Ảnh gửi để nhận diện không được lưu**: chỉ nằm trong bộ nhớ của request (NFR-09).
- **Chỉ lưu ảnh cắt vùng biển** của đề xuất và ảnh báo cáo, trong MinIO; CSDL lưu khoá đối tượng.
- Ứng dụng gửi đề xuất bằng **một request multipart** tới backend (JSON + ảnh cắt); backend ghi MinIO rồi ghi CSDL.
- Đọc ảnh riêng tư qua URL ký có hạn 10 phút; ảnh minh hoạ loại biển đọc công khai qua nginx.

Mỗi lần ghi ảnh dùng ID đề xuất mới. Ràng buộc duy nhất `(user_id, client_id)` trong MySQL quyết định bản ghi thắng khi nhận đồng thời. Backend trả lại đề xuất đã có cho yêu cầu trùng và dọn ảnh của lần ghi thua. Ảnh còn sót sau sự cố được đối chiếu với MySQL và dọn sau khoảng chờ đủ để giao dịch đang chạy hoàn tất.

## Các phương án đã xem xét

### Phương án A: Presigned URL, ứng dụng tải thẳng lên MinIO

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Cao — ba bước, đề xuất "mồ côi" khi tải ảnh lỗi, MinIO phải mở ra ngoài |
| Chi phí | Backend không chịu băng thông ảnh |
| Khả năng mở rộng | Tốt nhất |
| Mức quen thuộc với nhóm | Trung bình |

**Ưu điểm:** giảm tải backend với ảnh lớn.

**Nhược điểm:** phức tạp không cần thiết với ảnh 20 KB; hàng đợi offline phải nhớ trạng thái từng bước.

### Phương án B: Multipart qua backend

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp — một request, chống trùng bằng `clientId` |
| Chi phí | ~100 KB/giây ở tải ước lượng, không đáng kể |
| Khả năng mở rộng | Đủ |
| Mức quen thuộc với nhóm | Cao |

**Ưu điểm:** một bước, dễ gửi lại; backend kiểm tra định dạng và kích thước trước khi lưu; MinIO không cần mở ra ngoài để ghi.

**Nhược điểm:** ảnh đi qua backend.

### Phương án C: Lưu ảnh trong MySQL

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp |
| Chi phí | CSDL phình nhanh, sao lưu nặng |
| Khả năng mở rộng | Kém |
| Mức quen thuộc với nhóm | Cao |

**Ưu điểm:** một nơi lưu trữ.

**Nhược điểm:** trái đề tài (MinIO cho ảnh); truy vấn chậm.

## Phân tích đánh đổi

Presigned URL đáng giá khi ảnh lớn hoặc tải cao; ở đây ảnh cắt nhỏ và tải thấp, nên độ phức tạp ba bước không đổi được lợi ích nào. B đơn giản nhất mà vẫn đúng đề tài và riêng tư.

## Hệ quả

- Dễ hơn: hàng đợi gửi trên ứng dụng chỉ có một loại request; không có đề xuất thiếu ảnh.
- Khó hơn: backend phải giới hạn kích thước request (≤ 200 KB cho ảnh cắt, ≤ 10 MB cho ảnh nhận diện).
- Cần xem lại: nếu sau này lưu ảnh lớn (khung hình đầy đủ để huấn luyện lại), dùng presigned URL cho loại ảnh đó.

## Việc cần làm

1. [ ] Bucket `sign-crops`, `report-photos`, `sign-icons` và chính sách lưu giữ ([CSDL §11](../06-co-so-du-lieu.md#11-minio))
2. [ ] Kiểm thử IT-B09 và IT-B14 cho lưu ảnh nhận diện và tải ảnh đề xuất đồng thời
