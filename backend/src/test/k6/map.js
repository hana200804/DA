import { check } from 'k6';
import http from 'k6/http';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const bbox = (__ENV.SAMPLE_BBOX || '105.79,21.00,105.86,21.06').split(',');
const [minLng, minLat, maxLng, maxLat] = bbox.map(Number);
if (bbox.length !== 4 || bbox.some((value) => !value.trim() || !Number.isFinite(Number(value)))
    || minLng < -180 || maxLng > 180 || minLat < -90 || maxLat > 90
    || minLng >= maxLng || minLat >= maxLat) {
  throw new Error('SAMPLE_BBOX phải là minLng,minLat,maxLng,maxLat theo WGS84');
}

export const options = {
  vus: 50,
  duration: __ENV.MAP_DURATION || '1m',
  discardResponseBodies: true,
  thresholds: {
    http_req_duration: ['p(95)<300'],
    http_req_failed: ['rate==0'],
  },
};

export default function () {
  const width = (maxLng - minLng) / 2;
  const height = (maxLat - minLat) / 2;
  const west = minLng + Math.random() * width;
  const south = minLat + Math.random() * height;
  const bounds = [west, south, west + width, south + height].join(',');
  const response = http.get(`${baseUrl}/api/v1/signs?bbox=${bounds}&zoom=17`, {
    tags: { name: 'GET /api/v1/signs' },
    responseCallback: http.expectedStatuses(200),
  });
  check(response, { 'bản đồ trả 200': (r) => r.status === 200 });
}
