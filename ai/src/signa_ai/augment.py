"""Tăng cường dữ liệu dùng chung cho YOLOv5, YOLOv8 và DETR (docs/05 §6, ai/configs/augment.md).

Phép hình học (co giãn, dịch, xoay) dùng tham số sẵn có của từng bộ công cụ. Các phép ảnh
(độ sáng, nhoè, thời tiết, nhiễu, JPEG) chạy cùng một pipeline Albumentations: hàm
``use_signa_photometric`` thay pipeline mặc định của Ultralytics và YOLOv5 bằng pipeline này.
"""

from __future__ import annotations

import random
from typing import Any

# Phép hình học. YOLO áp dụng cho mọi ảnh, nên DETR cũng dùng p=1.
SCALE = 0.5  # ±50%
TRANSLATE = 0.1  # ±10%
DEGREES = 10.0  # ±10°

# Phép ảnh: (giới hạn, xác suất áp dụng).
BRIGHTNESS_CONTRAST = (0.3, 0.5)
MOTION_BLUR = ((3, 9), 0.2)
WEATHER_P = 0.1  # mưa hoặc sương mù, chọn một
NOISE_STD = ((0.01, 0.05), 0.2)  # độ lệch chuẩn theo tỉ lệ giá trị điểm ảnh tối đa
JPEG_QUALITY = ((40, 90), 0.3)

# Tham số `model.train(...)` của Ultralytics (YOLOv8, RT-DETR).
ULTRALYTICS_ARGS: dict[str, float] = {
    "fliplr": 0.0,
    "flipud": 0.0,
    "hsv_h": 0.0,
    "hsv_s": 0.0,
    "hsv_v": 0.0,  # độ sáng do pipeline chung đảm nhận
    "degrees": DEGREES,
    "translate": TRANSLATE,
    "scale": SCALE,
    "shear": 0.0,
    "perspective": 0.0,
    "mosaic": 0.0,
    "mixup": 0.0,
    "cutmix": 0.0,
    "copy_paste": 0.0,
}

# Khoá tăng cường trong hyp.yaml của YOLOv5 trùng tên với Ultralytics.
YOLOV5_HYP_KEYS = (
    "fliplr", "flipud", "hsv_h", "hsv_s", "hsv_v", "degrees", "translate", "scale",
    "shear", "perspective", "mosaic", "mixup", "copy_paste",
)


def photometric_transforms() -> list[Any]:
    """Các phép ảnh dùng chung, không đổi vị trí box."""
    import albumentations as A

    limit, p_bc = BRIGHTNESS_CONTRAST
    (blur_min, blur_max), p_blur = MOTION_BLUR
    std_range, p_noise = NOISE_STD
    quality_range, p_jpeg = JPEG_QUALITY
    return [
        A.RandomBrightnessContrast(brightness_limit=limit, contrast_limit=limit, p=p_bc),
        A.MotionBlur(blur_limit=(blur_min, blur_max), p=p_blur),
        A.OneOf([A.RandomRain(p=1.0), A.RandomFog(p=1.0)], p=WEATHER_P),
        A.GaussNoise(std_range=std_range, p=p_noise),
        A.ImageCompression(quality_range=quality_range, p=p_jpeg),
    ]


def detr_transforms(bbox_format: str = "coco") -> Any:
    """Pipeline cho DETR-R50: hình học như YOLO rồi các phép ảnh dùng chung."""
    import albumentations as A

    geometric = A.Affine(
        scale=(1 - SCALE, 1 + SCALE),
        translate_percent=(-TRANSLATE, TRANSLATE),
        rotate=(-DEGREES, DEGREES),
        keep_ratio=True,
        fill=114,
        p=1.0,
    )
    return A.Compose(
        [geometric, *photometric_transforms()],
        bbox_params=A.BboxParams(
            format=bbox_format, label_fields=["class_labels"], min_visibility=0.3
        ),
    )


def _init_ultralytics(self: Any, p: float = 1.0) -> None:
    import albumentations as A

    self.p = p
    self.contains_spatial = False
    self.transform = A.Compose(photometric_transforms())
    _seed(self.transform)


def _init_yolov5(self: Any, size: int = 640) -> None:
    import albumentations as A

    # YOLOv5 truyền cả box vào transform nên cần bbox_params dù không có phép hình học.
    self.transform = A.Compose(
        photometric_transforms(),
        bbox_params=A.BboxParams(format="yolo", label_fields=["class_labels"]),
    )


def _seed(transform: Any) -> None:
    if hasattr(transform, "set_random_seed"):
        try:
            import torch

            transform.set_random_seed(torch.initial_seed())
        except ImportError:
            transform.set_random_seed(random.getrandbits(32))


def use_signa_photometric(albumentations_cls: type, toolkit: str) -> None:
    """Thay ``__init__`` của lớp Albumentations trong bộ công cụ bằng pipeline chung.

    Gọi trước khi tạo dataloader, trong cùng tiến trình huấn luyện.
    """
    inits = {"ultralytics": _init_ultralytics, "yolov5": _init_yolov5}
    if toolkit not in inits:
        raise ValueError(f"toolkit phải là một trong {sorted(inits)}, nhận {toolkit!r}")
    albumentations_cls.__init__ = inits[toolkit]


def patch_ultralytics() -> None:
    from ultralytics.data.augment import Albumentations

    use_signa_photometric(Albumentations, "ultralytics")


def patch_yolov5() -> None:
    try:
        from yolov5.utils.augmentations import Albumentations  # gói pip `yolov5`
    except ImportError:
        # Chạy trong thư mục clone ultralytics/yolov5.
        from utils.augmentations import Albumentations

    use_signa_photometric(Albumentations, "yolov5")
