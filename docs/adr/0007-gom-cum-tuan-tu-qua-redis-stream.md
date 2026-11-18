# ADR-0007: Gom cụm đề xuất tuần tự qua Redis Stream

**Status**: Proposed

## Bối cảnh

Gom cụm ([ADR-0004](0004-diem-tin-cay-ban-do.md)) đọc các biển gần một đề xuất rồi quyết định gán hay tạo biển mới. Nếu hai đề xuất của **cùng một biển** được xử lý đồng thời, cả hai có thể cùng không thấy biển nào và cùng tạo biển mới — bản đồ có biển trùng, kiểm duyệt viên duyệt hai lần. Khoá dòng thông thường không giúp được vì biển chưa tồn tại; khoá khoảng trên chỉ mục không gian của InnoDB không đáng tin cho trường hợp này.

Tải ước lượng ~5 đề xuất/giây, chỉ tiêu 50/giây (NFR-04). Đề tài dùng Redis cho dữ liệu tạm thời.

## Quyết định

Chọn phương án B. API lưu đề xuất `RECEIVED` và commit MySQL trước khi thử `XADD` vào `stream:submissions`. Môi trường demo chạy một bản sao backend, với một consumer đọc stream bằng `XREADGROUP`. Khi mất Redis hoặc enqueue lỗi, MySQL vẫn giữ đề xuất để xử lý lại.

Worker khóa hàng đề xuất, kiểm tra trạng thái `RECEIVED` và gom cụm trong cùng giao dịch với việc cập nhật trạng thái. Sau khi commit, worker `XACK` rồi `XDEL`. Nếu giao dịch lỗi, worker giữ mục để thử lại. Khi đã xử lý, các bản giao lại chỉ được xác nhận và dọn hàng đợi. Worker đọc lại mục chưa xác nhận khi khởi động, thử lại có khoảng nghỉ khi lỗi và quét định kỳ các đề xuất còn `RECEIVED` quá một phút để đưa lại vào hàng đợi.

## Các phương án đã xem xét

### Phương án A: Gom cụm đồng bộ trong request, khoá theo ô địa lý

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Trung bình — `GET_LOCK` của MySQL theo ô lưới, xử lý điểm nằm sát biên ô |
| Chi phí | Không thêm thành phần |
| Khả năng mở rộng | Tốt — các ô khác nhau chạy song song |
| Mức quen thuộc với nhóm | Thấp |

**Ưu điểm:** người dùng nhận kết quả gom cụm ngay.

**Nhược điểm:** đề xuất sát biên ô phải khoá nhiều ô, dễ sai; request chậm khi gom cụm chậm; lỗi gom cụm làm hỏng request gửi đề xuất.

### Phương án B: Redis Stream, một consumer

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp — một vòng lặp đọc, xử lý, xác nhận |
| Chi phí | Dùng Redis đã có |
| Khả năng mở rộng | Một luồng. Đo thông lượng xử lý khi kiểm thử tải |
| Mức quen thuộc với nhóm | Trung bình |

**Ưu điểm:** một worker tuần tự hóa việc tìm và tạo cụm. API gửi đề xuất hoàn tất trước khi gom cụm. Có thể theo dõi số đề xuất còn `RECEIVED`, tuổi đề xuất chờ lâu nhất và số mục chưa xác nhận.

**Nhược điểm:** gom cụm chậm hơn vài trăm mili giây so với đồng bộ; thông lượng giới hạn ở một luồng.

### Phương án C: Hàng đợi trong bộ nhớ, một luồng

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp nhất |
| Chi phí | Không |
| Khả năng mở rộng | Không chạy được khi có hơn một bản backend |
| Mức quen thuộc với nhóm | Cao |

**Ưu điểm:** đơn giản.

**Nhược điểm:** mất việc khi tiến trình dừng (phải dựa hoàn toàn vào quét lại); không quan sát được hàng đợi; hai bản backend sẽ có hai luồng gom cụm song song, quay lại race condition.

## Phân tích đánh đổi

Phương án B giữ xử lý tuần tự ở tải demo và phục hồi công việc từ MySQL khi khởi động lại. Nếu cần tăng số worker, phải thiết kế lại cách phân vùng và xử lý các biển sát ranh giới để tránh tạo cụm trùng.

## Hệ quả

- Dễ hơn: suy luận và kiểm thử gom cụm (IT-B03); API gửi đề xuất đơn giản.
- Khó hơn: ứng dụng thấy đề xuất ở `RECEIVED` một lúc trước khi `GROUPED`; cần metric độ dài stream.
- Cần xem lại thông lượng sau kiểm thử tải và trước khi chạy nhiều bản sao backend.

## Việc cần làm

1. [ ] Worker với consumer group `clustering`, xử lý lại mục chưa `XACK` khi khởi động
2. [ ] Quét đề xuất `RECEIVED` cũ hơn 1 phút khi khởi động và định kỳ
3. [ ] Kiểm thử IT-B03 và IT-B12 cho đề xuất đồng thời, lỗi enqueue và giao lại mục
