# Nhật ký dữ liệu

Phiên bản dữ liệu `vMAJOR.MINOR`: tăng `MAJOR` khi đổi danh mục nhãn, tăng `MINOR` khi thêm hoặc sửa ảnh ([Dữ liệu §9](../../docs/05-du-lieu.md#9-định-dạng-phiên-bản-và-lưu-trữ)).

## v1.0 · VN82 chia lại theo nhóm ảnh cùng cảnh

- Danh mục v1 gồm 42 mã QCVN 41:2024 đủ 100 box train và 4 lớp `other_<nhóm>`. Danh mục mẫu v0 chuyển sang `labels-v0.yaml`.
- Dùng cả 9.978 ảnh VN82. Đổi nhãn theo [bảng ánh xạ](sources/vn82-to-signa.csv) và bỏ 877 box đèn tín hiệu.
- Chia lại theo 2.108 nhóm ảnh cùng cảnh. Không còn cặp cùng cảnh nằm khác tập. Xuất YOLO, COCO và [manifest](manifests/v1.0.csv). [Chi tiết](README.md#bộ-dữ-liệu-v10)

## v0.2 · Đã thu hồi

- Bản 2.144 ảnh từ train của VN82 theo danh mục v0. Đã xóa ảnh, manifest và hướng dẫn vì ba lỗi:
  - Gộp lớp 68 (chỗ đỗ xe) và 69 (bến xe buýt) vào `other_prohibitory`, làm sai nhãn 117 box.
  - Bỏ mọi ảnh có box ngoài v0. Lớp `P.102` chỉ còn 3 trên 441 box và không còn box `W.207a` nào.
  - Chia theo dải 500 tên ảnh chỉ được 13, 4 và 2 nhóm. Val và test thiếu một số lớp.
- Giữ [bảng rà 638 box W.207 và ID 31](reviews/vn82-qcvn-review.csv) cho lần chia lại sau.

## v0.1 · Chạy thử

- Trích và kiểm tra 359 ảnh VNTS đã lọc trùng. Dùng 90 ảnh với 176 box có nhãn phù hợp danh mục v0.
- Xuất YOLO, COCO và [manifest](manifests/v0.1.csv). Giữ test của nguồn và chọn validation theo dải tên ảnh trong train của nguồn.
- Ghi phiên bản, điều khoản và cách tạo lại trong [hướng dẫn dữ liệu](README.md).
