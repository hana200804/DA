export const modelNames = ['yolov5', 'yolov8', 'detr'] as const;
export type ModelName = (typeof modelNames)[number];
export const modelLabels: Record<ModelName, string> = {
  yolov5: 'YOLOv5',
  yolov8: 'YOLOv8',
  detr: 'DETR',
};

export interface ImageSize {
  width: number;
  height: number;
}
export interface ModelBox extends ImageSize {
  x: number;
  y: number;
}
export interface Detection {
  box: ModelBox;
  signTypeCode: string;
  confidence: number;
}
export interface ModelResult {
  model: { name: ModelName; version?: string };
  image?: ImageSize;
  inferenceMs?: number;
  detections?: Detection[];
  error?: { message: string };
}
export interface Comparison {
  image?: ImageSize;
  results: ModelResult[];
}
export interface Evaluation {
  model: ModelName;
  version: string;
  datasetVersion?: string;
  split: string;
  map50?: number | null;
  map50To95?: number | null;
  precision?: number | null;
  recall?: number | null;
  referenceOnly?: boolean;
}

export function validateComparisonImage(file: File) {
  if (
    !file.size ||
    !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)
  )
    throw new Error('Chọn ảnh JPEG, PNG hoặc WebP hợp lệ.');
  if (file.size > 10_000_000)
    throw new Error('Ảnh phải nhỏ hơn hoặc bằng 10 MB.');
}

export function boxPercent(box: ModelBox, image?: ImageSize) {
  if (
    !image ||
    ![box.x, box.y, box.width, box.height, image.width, image.height].every(
      Number.isFinite,
    ) ||
    box.width <= 0 ||
    box.height <= 0 ||
    image.width <= 0 ||
    image.height <= 0 ||
    !Number.isFinite(box.x + box.width) ||
    !Number.isFinite(box.y + box.height)
  )
    return null;
  const left = Math.max(0, box.x);
  const top = Math.max(0, box.y);
  const right = Math.min(image.width, box.x + box.width);
  const bottom = Math.min(image.height, box.y + box.height);
  if (right <= left || bottom <= top) return null;
  return {
    left: (left / image.width) * 100,
    top: (top / image.height) * 100,
    width: ((right - left) / image.width) * 100,
    height: ((bottom - top) / image.height) * 100,
  };
}
