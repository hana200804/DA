# ADR-0004: Gom cụm đề xuất và điểm tin cậy Beta làm trợ lý phê duyệt

**Status**: Proposed

## Bối cảnh

Đề tài yêu cầu **quản lý và phê duyệt** dữ liệu biển báo trước khi hiển thị trên bản đồ. Dữ liệu đến từ đề xuất của người dùng: mỗi lần xe đi qua một biển ở chế độ camera sinh một đề xuất, nên cùng một biển thật sẽ có nhiều đề xuất, từ nhiều người, có thể khác mã (mô hình nhầm) và lệch vị trí (GPS).

Nếu kiểm duyệt viên phải xem từng đề xuất, khối lượng tăng theo số lượt đi qua chứ không theo số biển. Thiết kế trước đây đi theo hướng ngược lại — cụm tự lên `VERIFIED` không cần người — và như vậy làm mất bước phê duyệt mà đề tài yêu cầu.

## Quyết định

Chọn phương án B:

- Gom đề xuất thành **biển** theo nhóm, khoảng cách ≤ 20 m, hướng lệch ≤ 45°.
- Tính **điểm tin cậy** của biển bằng kỳ vọng hậu nghiệm Beta với tiên nghiệm Jeffreys, từ trọng số đề xuất, đánh giá của người dùng và quyết định của kiểm duyệt viên.
- Sinh **gợi ý** (Nên duyệt / Cần xem kỹ / Chờ thêm) và sắp hàng đợi theo gợi ý.
- **Kiểm duyệt viên quyết định** theo luồng mặc định. Tự duyệt (FR-25) là tính năng P1, mặc định tắt và có ngưỡng riêng.

Công thức và ngưỡng: [Thiết kế AI §6](../04-thiet-ke-ai.md#6-gom-cụm-và-điểm-tin-cậy).

## Các phương án đã xem xét

### Phương án A: Phê duyệt từng đề xuất

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp |
| Chi phí | Công duyệt tỉ lệ với số đề xuất |
| Khả năng mở rộng | Kém |
| Mức quen thuộc với nhóm | Cao — CRUD thông thường |

**Ưu điểm:** đơn giản, đúng chữ "phê duyệt".

**Nhược điểm:** duyệt lặp lại cùng một biển; hai đề xuất cùng biển được duyệt thành hai biển trùng trên bản đồ; không có đóng góp kỹ thuật để trình bày.

### Phương án B: Gom cụm + điểm tin cậy + gợi ý, người quyết định

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Trung bình — thuật toán gom cụm, công thức điểm, worker tuần tự |
| Chi phí | Một quyết định cho mỗi biển |
| Khả năng mở rộng | Tốt — công duyệt tỉ lệ với số biển |
| Mức quen thuộc với nhóm | Trung bình |

**Ưu điểm:** đúng đề tài; giảm số quyết định; mã cạnh tranh lộ ra để kiểm duyệt viên xem kỹ; điểm tin cậy tiếp tục cập nhật theo đánh giá nên biển sai quay lại hàng đợi.

**Nhược điểm:** cần dữ liệu nhiều lượt đi để đánh giá; nhiều tham số cần hiệu chỉnh.

### Phương án C: Tự công nhận hoàn toàn

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Trung bình |
| Chi phí | Công duyệt thấp nhất |
| Khả năng mở rộng | Tốt nhất |
| Mức quen thuộc với nhóm | Trung bình |

**Ưu điểm:** không cần người cho ca rõ ràng.

**Nhược điểm:** bỏ bước phê duyệt mà đề tài yêu cầu; với số người dùng nhỏ của đồ án, vài tài khoản có thể tự đẩy biển sai lên bản đồ.

## Phân tích đánh đổi

A đúng chữ nhưng không có giá trị kỹ thuật và tạo biển trùng. C hiệu quả nhất nhưng lệch đề tài. B giữ con người làm người quyết định, dùng thuật toán để giảm công và chỉ ra ca khó. B còn cho một thí nghiệm đo được: so với A trên cùng dữ liệu, về số quyết định và precision ([Kiểm thử §6](../08-kiem-thu.md#6-đánh-giá-phê-duyệt-có-trợ-giúp)). Uy tín người gửi (có trong thiết kế cũ) được bỏ để giảm tham số; chống lặp theo người và yêu cầu nhiều người khác nhau đã chặn phần lớn trường hợp một tài khoản gửi sai.

## Hệ quả

- Dễ hơn: kiểm duyệt viên chỉ xem mỗi biển một lần; trình bày đóng góp C3.
- Khó hơn: kiểm thử cần dữ liệu nhiều lượt; phải mô phỏng nhiễu và người dùng phá.
- Cần xem lại các ngưỡng (20 m, 0,8, 0,3) trên tuyến val trước khi đánh giá. Nếu precision thấp trong kịch bản người dùng phá, ghi nhận hạn chế và xem xét uy tín người gửi cho vòng thử nghiệm sau.

## Việc cần làm

1. [ ] Gom cụm, điểm, gợi ý trong module `sign`, unit test cho từng nhánh
2. [ ] Script mô phỏng phê duyệt từng đề xuất và phê duyệt có trợ giúp trên cùng dữ liệu
3. [ ] Hiệu chỉnh ngưỡng bằng video val, ghi lại cấu hình trước khi đánh giá cuối trên các tuyến thực địa giữ riêng
