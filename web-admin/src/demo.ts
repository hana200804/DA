import type {
  Contributor,
  DemoDB,
  Report,
  Sign,
  SignType,
  Upload,
  UserDetail,
} from './types.ts';
import { distanceMeters, matches, paginate } from './domain.ts';

export const DEMO_KEY = 'visionmap.demo.v1';
export const signTypes: SignType[] = [
  {
    id: 'P',
    name: 'Biển báo cấm',
    description: 'Các điều cấm mà người tham gia giao thông phải chấp hành.',
  },
  {
    id: 'W',
    name: 'Biển cảnh báo',
    description: 'Cảnh báo tình huống nguy hiểm có thể gặp trên đường.',
  },
  {
    id: 'R',
    name: 'Biển hiệu lệnh',
    description: 'Các hiệu lệnh cần thực hiện khi tham gia giao thông.',
  },
  {
    id: 'I',
    name: 'Biển chỉ dẫn',
    description: 'Hướng dẫn hướng đi và các thông tin hữu ích trên đường.',
  },
  {
    id: 'S',
    name: 'Biển phụ',
    description: 'Bổ sung, làm rõ ý nghĩa của biển báo chính.',
  },
];
const signRows: [string, string, string, string][] = [
  [
    'P.127',
    'Tốc độ tối đa cho phép 50 km/h',
    'P',
    'Giới hạn tốc độ tối đa cho phép đối với phương tiện.',
  ],
  [
    'P.102',
    'Cấm đi ngược chiều',
    'P',
    'Cấm các loại xe đi vào theo chiều đặt biển, trừ xe được ưu tiên theo quy định.',
  ],
  [
    'W.207',
    'Giao nhau với đường không ưu tiên',
    'W',
    'Cảnh báo sắp đến nơi giao nhau với đường không ưu tiên.',
  ],
  [
    'R.301a',
    'Hướng đi phải theo',
    'R',
    'Các phương tiện chỉ được đi thẳng theo hướng mũi tên.',
  ],
  [
    'P.106a',
    'Cấm xe ô tô tải',
    'P',
    'Báo đường cấm các loại xe ô tô tải theo quy định.',
  ],
  [
    'W.224',
    'Đường người đi bộ cắt ngang',
    'W',
    'Cảnh báo đoạn đường thường có người đi bộ qua đường.',
  ],
  [
    'I.434a',
    'Bến xe buýt',
    'I',
    'Chỉ dẫn vị trí dừng đón và trả khách của xe buýt.',
  ],
  [
    'P.123a',
    'Cấm rẽ trái',
    'P',
    'Báo cấm các loại xe rẽ trái tại vị trí giao nhau.',
  ],
  [
    'W.203b',
    'Đường bị hẹp về phía trái',
    'W',
    'Cảnh báo phía trước phần đường xe chạy bị hẹp về phía trái.',
  ],
  [
    'R.303',
    'Nơi giao nhau chạy theo vòng xuyến',
    'R',
    'Các phương tiện đi theo chiều vòng xuyến được chỉ dẫn.',
  ],
  [
    'I.423a',
    'Vị trí người đi bộ sang ngang',
    'I',
    'Chỉ dẫn nơi dành cho người đi bộ sang ngang.',
  ],
  [
    'S.501',
    'Phạm vi tác dụng của biển',
    'S',
    'Cho biết chiều dài đoạn đường có hiệu lực của biển báo chính.',
  ],
  ['P.130', 'Cấm dừng xe và đỗ xe', 'P', 'Báo nơi cấm dừng và đỗ các loại xe.'],
  [
    'W.201a',
    'Chỗ ngoặt nguy hiểm',
    'W',
    'Cảnh báo sắp tới chỗ ngoặt nguy hiểm về bên trái.',
  ],
  [
    'P.124a',
    'Cấm quay đầu xe',
    'P',
    'Báo cấm các loại xe quay đầu theo quy định.',
  ],
  ['I.408', 'Nơi đỗ xe', 'I', 'Chỉ dẫn vị trí bãi đỗ xe dành cho phương tiện.'],
];
export const contributors = [
  ['minhanh', 'Nguyễn Minh Anh'],
  ['quanghuy', 'Trần Quang Huy'],
  ['thanhha', 'Lê Thanh Hà'],
  ['ducminh', 'Phạm Đức Minh'],
  ['linhnguyen', 'Nguyễn Thùy Linh'],
  ['hoangnam', 'Vũ Hoàng Nam'],
];
export function seedDemo(): DemoDB {
  const signs: Sign[] = signRows.map(
    ([code, name, typeId, description], i) => ({
      id: `sign-${i + 1}`,
      code,
      name,
      typeId,
      description,
      photoUrl: '',
      createdAt: '2026-10-01T08:30:00+07:00',
    }),
  );
  const streets = [
    'Nguyễn Trãi, Thanh Xuân, Hà Nội',
    'Xuân Thủy, Cầu Giấy, Hà Nội',
    'Trần Duy Hưng, Cầu Giấy, Hà Nội',
    'Giải Phóng, Hai Bà Trưng, Hà Nội',
    'Láng Hạ, Đống Đa, Hà Nội',
    'Võ Chí Công, Tây Hồ, Hà Nội',
  ];
  const uploads: Upload[] = Array.from({ length: 38 }, (_, i) => {
    const sign = signs[i % signs.length];
    const createdAt = `2026-11-0${5 - (i % 5)}T${String(9 + (i % 10)).padStart(2, '0')}:30:00+07:00`;
    return {
      id: `VM-${String(2401 + i)}`,
      trafficSignId: sign.code,
      trafficSignName: sign.name,
      fullAddress: `${22 + i * 3} ${streets[i % streets.length]}`,
      latitude: 21.008 + (i % 6) * 0.003,
      longitude: 105.797 + (i % 8) * 0.004,
      photoBase64: '',
      bbox: null,
      accuracyAI: 0.79 + (i % 20) / 100,
      createdAt,
      decision:
        i >= 12 && i < 35
          ? {
              status: i < 31 ? 'APPROVED' : 'REJECTED',
              at: new Date(
                Date.parse(createdAt) + ((i % 6) + 1) * 3_600_000,
              ).toISOString(),
            }
          : undefined,
      createdBy: contributors[i % contributors.length][0],
      status:
        i < 12
          ? 'PENDING'
          : i < 31
            ? 'APPROVAL'
            : i < 35
              ? 'REJECTED'
              : 'DELETED',
      numberDuplicate: i % 4 === 0 ? 2 : 0,
    };
  });
  for (let i = 0; i < 3; i++) {
    const reference = uploads[i];
    const duplicate = uploads[i + 16];
    duplicate.latitude = reference.latitude + 0.0002;
    duplicate.longitude = reference.longitude + 0.0002;
    duplicate.fullAddress = reference.fullAddress;
  }
  uploads.forEach((row) => {
    row.numberDuplicate = nearbyApproved(uploads, row).length;
  });
  return {
    version: 1,
    signs,
    uploads,
    reports: uploads
      .filter((u) => u.status === 'APPROVAL')
      .slice(0, 3)
      .flatMap((u, i) => {
        const report: Report = {
          ...u,
          id: `BC-${100 + i}`,
          rttsId: u.id,
          custId: contributors[(i + 1) % 6][0],
          reason: [
            'Biển báo đã được tháo dỡ tại vị trí này.',
            'Vị trí biển báo chưa chính xác.',
            'Biển thực tế bị che khuất, cần kiểm tra lại.',
          ][i],
          status: 'PENDING',
          reasonCode:
            i === 0 ? 'NOT_EXIST' : i === 1 ? 'WRONG_LOCATION' : 'OTHER',
        };
        return i === 1
          ? [
              report,
              {
                ...report,
                id: 'BC-103',
                custId: contributors[4][0],
                reasonCode: 'WRONG_TYPE',
                reason: 'Người báo cáo cho rằng mã biển hiện tại chưa đúng.',
              },
            ]
          : [report];
      }),
  };
}
export function readDemo(): DemoDB {
  try {
    const d = JSON.parse(localStorage.getItem(DEMO_KEY) || 'null');
    if (
      d?.version === 1 &&
      Array.isArray(d.signs) &&
      Array.isArray(d.uploads) &&
      Array.isArray(d.reports)
    )
      return d;
  } catch {
    /* Khởi tạo lại dữ liệu mẫu bị hỏng. */
  }
  return seedDemo();
}
export function writeDemo(db: DemoDB) {
  localStorage.setItem(DEMO_KEY, JSON.stringify(db));
}
export function nearbyApproved(uploads: Upload[], row: Upload) {
  return uploads.filter(
    (u) =>
      u.status === 'APPROVAL' &&
      u.trafficSignId === row.trafficSignId &&
      u.id !== row.id &&
      distanceMeters(u, row) <= 150,
  );
}
export function stats(db: DemoDB): Contributor[] {
  return contributors.map(([username]) => {
    const rows = db.uploads.filter((u) => u.createdBy === username);
    return {
      custId: username,
      pending: rows.filter((u) => u.status === 'PENDING').length,
      approval: rows.filter((u) => u.status === 'APPROVAL').length,
      rejected: rows.filter((u) => u.status === 'REJECTED').length,
      deleted: rows.filter((u) => u.status === 'DELETED').length,
    };
  });
}
export function userInfo(db: DemoDB, username: string): UserDetail {
  const row = stats(db).find((s) => s.custId === username);
  if (!row) throw new Error('Không tìm thấy người dùng.');
  return {
    id: username,
    username,
    fullName: contributors.find((c) => c[0] === username)![1],
    email: `${username}@example.com`,
    createdAt: '2026-10-02T08:00:00+07:00',
    ...row,
  };
}
export function decideUpload(
  db: DemoDB,
  id: string,
  decision: 'APPROVE' | 'REJECT',
) {
  const row = db.uploads.find((u) => u.id === id);
  if (!row || row.status !== 'PENDING')
    throw new Error('Biển báo không còn ở trạng thái chờ duyệt.');
  row.status = decision === 'APPROVE' ? 'APPROVAL' : 'REJECTED';
  row.decision = {
    status: decision === 'APPROVE' ? 'APPROVED' : 'REJECTED',
    at: new Date().toISOString(),
  };
}
export function decideReport(
  db: DemoDB,
  id: string,
  decision: 'APPROVE' | 'REJECT',
) {
  const row = db.reports.find((u) => u.id === id);
  if (!row || row.status !== 'PENDING')
    throw new Error('Báo cáo đã được xử lý.');
  row.status = decision === 'APPROVE' ? 'APPROVAL' : 'REJECTED';
  if (decision === 'APPROVE') {
    const upload = db.uploads.find((u) => u.id === row.rttsId);
    if (upload) upload.status = 'DELETED';
  }
}
export { matches, paginate };
