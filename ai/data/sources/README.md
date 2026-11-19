# Nguồn dữ liệu Signa

## VNTS đã lọc trùng

- Ảnh và phân chia ban đầu do `maitam` công bố. Khung biển lấy từ bản VNTS VQA của `Anakonkai`. `tungitachi` lọc ảnh gần trùng, sau đó lấy mẫu thành bản `dedup-1k`. Signa dùng revision `04a6b91bef3f92c729f0ea7c383a1e3dbfece82d`. [Kaggle VNTS](https://www.kaggle.com/datasets/maitam/vietnamese-traffic-signs) · [VNTS VQA](https://huggingface.co/datasets/Anakonkai/vietnamese-traffic-sign-vqa) · [VNTS dedup-1k](https://huggingface.co/datasets/tungitachi/vietnamese-traffic-sign-vqa-dedup-1k)
- Trang bản lọc trùng khai báo CC BY 4.0. Trang bản VQA ghi giấy phép nguồn ảnh là CC BY-SA 4.0. Signa lưu ghi công và áp dụng điều kiện CC BY-SA 4.0 cho ảnh và nhãn VNTS đã sửa khi chia sẻ. Q3 cần xác nhận lại giấy phép trên trang Kaggle gốc trước khi phát hành dữ liệu chính thức. [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
- Signa trích mỗi ảnh một lần từ Parquet, chuyển khung sang YOLO và COCO, đổi mã có ánh xạ rõ ràng và giữ tập test đã chọn của bên phát hành. Các thay đổi nằm ở [quy trình dữ liệu](../README.md).

## Ứng viên mở rộng

| Nguồn | Bằng chứng và phần cần kiểm tra |
| --- | --- |
| [Vietnam Traffic Sign Detection Dataset](https://huggingface.co/datasets/star092304/Traffic-sign-detection-VietNam) | Đã tải và kiểm kê 10.157 ảnh. Bản [VN82 cho Colab](vn82.md) giữ 9.978 ảnh. Cần xác nhận chênh lệch giữa metadata CC BY và liên kết CC BY-NC trong README trước khi phát hành. |
| [Vietnam Traffic Sign của giang](https://universe.roboflow.com/giang-yp9g1/vietnam-traffic-sign-altsi) | Roboflow ghi CC BY 4.0 và hiển thị 56 lớp. URL xuất YOLOv8 trả HTTP 403 trong lần tải này. Khi có bản xuất, kiểm tra phân chia theo video và đọc giá trị tốc độ cho lớp `P-127`. |
| [Vietnamese Traffic Signs của Charles Milliaud](https://github.com/CharlesMld/vietnam_traffic_signs_dataset/tree/1bc2c306135dfcbcf62ee435ae7794f9d50161ce) | Kho có giấy phép MIT và README ghi ảnh trích từ 20 video. Bản ZIP đã tải. Cần xác minh quyền với video nguồn và khôi phục nhóm video trước khi nhập vào Signa. |
| [VTSDB100](https://nhattruongpham.github.io/publications/) | Tác giả mô tả 100 lớp, thu ở TP.HCM. Cần tìm bản tải và điều khoản sử dụng của chính bộ dữ liệu. |
| [Video và ảnh thực địa của nhóm](../../../docs/05-du-lieu.md#4-video-thực-địa) | Nhóm thu thập ảnh gốc, video và GPS. Chọn tuyến giữ riêng trước khi trích khung. |

Mã biển đối chiếu theo Phụ lục B, C, D và F của QCVN 41:2024/BGTVT. Ví dụ, QCVN có nhiều biến thể `W.207` nên nhãn nguồn chỉ ghi `W.207` cần rà từng ảnh trước khi xác định mã cụ thể. [Thông tư và phụ lục trên Cổng Thông tin điện tử Chính phủ](https://vanban.chinhphu.vn/?docid=211908&pageid=27160)
