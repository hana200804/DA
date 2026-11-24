import { check } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { Rate } from 'k6/metrics';

const baseUrl = (__ENV.RECOGNITION_URL || 'http://recognition:8000').replace(/\/+$/, '');
const image = open(__ENV.IMAGE_PATH || './photo.jpg', 'b');
const models = ['yolov5', 'yolov8', 'detr'];
const backpressure = new Rate('recognition_backpressure');
const expectedStatuses = http.expectedStatuses(200, 503);

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'p(95)'],
  scenarios: Object.fromEntries(models.map((model) => [model, {
    executor: 'constant-arrival-rate',
    rate: 2,
    timeUnit: '1s',
    duration: __ENV.RECOGNITION_DURATION || '1m',
    preAllocatedVUs: 10,
    maxVUs: 20,
  }])),
  thresholds: {
    'http_req_duration{model:yolov5,status:200}': ['p(95)<=1000'],
    'http_req_duration{model:yolov8,status:200}': ['p(95)<=1000'],
    'http_req_duration{model:detr,status:200}': ['p(95)<=3000'],
    'checks{endpoint:detect}': ['rate==1'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  const health = http.get(`${baseUrl}/healthz`);
  if (health.status !== 200) {
    throw new Error(`Dịch vụ nhận diện chưa sẵn sàng (HTTP ${health.status})`);
  }
}

export default function () {
  const model = exec.scenario.name;
  const response = http.post(`${baseUrl}/v1/detect`, {
    model,
    image: http.file(image, 'photo.jpg', 'image/jpeg'),
  }, {
    tags: { model },
    responseCallback: expectedStatuses,
  });

  const queueBusy = response.status === 503 && response.headers['Retry-After'] === '1';
  backpressure.add(queueBusy, { model });
  check(response, {
    'nhận diện trả 200 hoặc 503 kèm Retry-After': (r) => r.status === 200 || queueBusy,
  }, { endpoint: 'detect', model });
}
