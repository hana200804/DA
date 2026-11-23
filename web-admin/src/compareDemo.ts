import {
  modelNames,
  validateComparisonImage,
  type Comparison,
  type Evaluation,
} from './compareTypes.ts';

// Reference-only measurements from ai/experiments/yolov8n-640-v1.1-temp-s0/meta.json.
export const referenceEvaluation: Evaluation = {
  model: 'yolov8',
  version: 'yolov8n-v1.1-temp',
  datasetVersion: 'v1.1',
  split: 'Validation · Ultralytics',
  map50: 0.86789,
  map50To95: 0.70177,
  precision: 0.8879,
  recall: 0.79773,
  referenceOnly: true,
};

export const compareDemo = {
  async compareImage(_token: string, file: File): Promise<Comparison> {
    validateComparisonImage(file);
    return {
      image: { width: 100, height: 100 },
      results: modelNames.map((name, index) => ({
        model: { name },
        inferenceMs: [97, 82, 169][index],
        detections: [
          {
            signTypeCode: 'P.127_60',
            confidence: [0.82, 0.92, 0.87][index],
            box: {
              x: 39 + index,
              y: 32 + index,
              width: 22 - index,
              height: 28 - index,
            },
          },
        ],
      })),
    };
  },
  async modelEvaluations(): Promise<Evaluation[]> {
    return [referenceEvaluation];
  },
};
