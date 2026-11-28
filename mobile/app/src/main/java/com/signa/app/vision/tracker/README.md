# Tracker

`SignTracker.kt` ghép phát hiện với track theo IoU giảm dần, chấp nhận từ 0,3 và cho phép mã khác nhau. Mỗi phát hiện góp phiếu bằng độ tin cậy nhân diện tích box. Track xuất hiện trên lớp phủ khi đã khớp ít nhất 3 trong 5 khung và có phát hiện ở khung hiện tại.

Track đã xác nhận kết thúc sau hơn 10 khung phân tích liên tiếp bị mất hoặc khi đóng pipeline. Track mang mã có tổng phiếu lớn nhất, giá trị tin cậy lấy trung bình các phát hiện mang mã đó. Nếu phiếu mã thắng dưới 60% tổng phiếu, đề xuất kèm `SPLIT_VOTE`. Ảnh và GPS lấy cùng lúc từ box lớn nhất của mã thắng. Track thiếu vị trí hợp lệ ở khung đó không đưa vào hàng đợi.
