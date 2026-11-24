import { check } from 'k6';
import http from 'k6/http';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const crop = open(__ENV.IMAGE_PATH || './crop.jpg', 'b');
if (crop.byteLength === 0 || crop.byteLength > 200 * 1024) {
  throw new Error('IMAGE_PATH phải trỏ đến ảnh cắt JPEG tối đa 200 KB');
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    submissions: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 25,
      maxVUs: 100,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '1m', target: 50 },
      ],
    },
  },
  thresholds: {
    'http_req_failed{scenario:submissions}': ['rate<0.01'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  const email = __ENV.SUBMISSION_EMAIL;
  const password = __ENV.SUBMISSION_PASSWORD;
  if (!email || !password) {
    throw new Error('Cần đặt SUBMISSION_EMAIL và SUBMISSION_PASSWORD');
  }

  const response = http.post(`${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ email, password, client: 'MOBILE' }), {
      headers: { 'Content-Type': 'application/json' },
      responseType: 'text',
    });
  if (response.status !== 200) {
    throw new Error(`Đăng nhập tài khoản thử tải thất bại (HTTP ${response.status})`);
  }
  const token = response.json('token');
  if (typeof token !== 'string' || token.length === 0) {
    throw new Error('Đăng nhập tài khoản thử tải không trả token');
  }
  return { token };
}

export default function ({ token }) {
  const metadata = {
    clientId: crypto.randomUUID(),
    signTypeCode: __ENV.SIGN_TYPE_CODE || 'P.123a',
    confidence: 0.88,
    location: { lat: 21.0285, lng: 105.8048 },
    headingDeg: 92,
    source: 'CAMERA',
    flags: [],
    model: {
      name: __ENV.MODEL_NAME || 'yolov8n',
      version: __ENV.MODEL_VERSION || 'yolov8n-v1.0',
      labelMapVersion: 1,
    },
    capturedAt: new Date().toISOString(),
  };

  const response = http.post(`${baseUrl}/api/v1/submissions`, {
    metadata: http.file(JSON.stringify(metadata), 'metadata.json', 'application/json'),
    crop: http.file(crop, 'crop.jpg', 'image/jpeg'),
  }, {
    headers: { Authorization: `Bearer ${token}` },
    responseCallback: http.expectedStatuses(201),
  });
  check(response, { 'đề xuất mới trả 201': (r) => r.status === 201 });
}
