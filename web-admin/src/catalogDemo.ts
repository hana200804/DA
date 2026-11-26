import { readDemo, writeDemo } from './demo.ts';
import { appendAudit } from './demoAudit.ts';
import {
  normalizeCategory,
  normalizeCategoryCode,
  normalizeSignType,
  normalizeSignTypeCode,
  validateCatalogIcon,
  type CategoryInput,
  type CatalogCategory,
  type CatalogSignType,
  type SignTypeInput,
} from './catalogTypes.ts';
import { ReviewApiError } from './reviewApi.ts';
import type { DemoDB } from './types';

const categories: CatalogCategory[] = [
  {
    code: 'PROHIBITORY',
    name: 'Biển cấm',
    description: 'Các điều cấm cần tuân thủ.',
    sortOrder: 1,
  },
  {
    code: 'WARNING',
    name: 'Biển nguy hiểm và cảnh báo',
    description: 'Cảnh báo nguy hiểm trên đường.',
    sortOrder: 2,
  },
  {
    code: 'MANDATORY',
    name: 'Biển hiệu lệnh',
    description: 'Hướng dẫn hành động bắt buộc.',
    sortOrder: 3,
  },
  {
    code: 'OTHER',
    name: 'Biển chỉ dẫn và biển khác',
    description: 'Thông tin và hướng di chuyển.',
    sortOrder: 4,
  },
  {
    code: 'AUXILIARY',
    name: 'Biển phụ',
    description: 'Bổ sung ý nghĩa cho biển chính.',
    sortOrder: 5,
  },
];
const categoryCodes: Record<string, string> = {
  P: 'PROHIBITORY',
  W: 'WARNING',
  R: 'MANDATORY',
  I: 'OTHER',
  S: 'AUXILIARY',
};
// Sample codes from the current model label map, for the demo catalog only.
const sampleModelCodes = new Set([
  'P.102',
  'P.106a',
  'P.123a',
  'P.124a',
  'P.130',
  'W.207',
  'W.224',
  'I.434a',
  'R.303',
  'W.201a',
]);

export function readCatalogCategories(db: DemoDB) {
  return (
    db.catalogCategories ?? categories.map((category) => ({ ...category }))
  );
}

export function readCatalogTypes(db = readDemo()): CatalogSignType[] {
  if (db.catalogTypes) return db.catalogTypes;
  const samples = db.signs.map((row) => ({
    code: row.code,
    name: row.name,
    description: row.description,
    categoryCode: categoryCodes[row.typeId] ?? 'OTHER',
    speedKmh: row.code === 'P.127' ? 50 : null,
    recognizable: sampleModelCodes.has(row.code),
    active: true,
    iconUrl: row.photoUrl,
    updatedAt: row.updatedAt ?? row.createdAt,
  }));
  for (const speed of [50, 60]) {
    samples.push({
      code: 'P.127_' + speed,
      name: 'Tốc độ tối đa cho phép ' + speed + ' km/h',
      description: 'Giới hạn tốc độ tối đa cho phép.',
      categoryCode: 'PROHIBITORY',
      speedKmh: speed,
      recognizable: true,
      active: true,
      iconUrl: '',
      updatedAt: samples[0]?.updatedAt,
    });
  }
  return samples;
}

function duplicate() {
  return new ReviewApiError(409, 'CONFLICT', 'Mã này đã có trong danh mục.');
}

function missing() {
  return new ReviewApiError(
    404,
    'NOT_FOUND',
    'Không tìm thấy mã trong danh mục.',
  );
}

export const catalogDemo = {
  async catalogCategories() {
    return [...readCatalogCategories(readDemo())].sort(
      (a, b) => a.sortOrder - b.sortOrder,
    );
  },
  async catalogTypes() {
    return readCatalogTypes();
  },
  async saveCategory(
    _token: string,
    code: string,
    input: CategoryInput,
    create: boolean,
  ) {
    const key = normalizeCategoryCode(code);
    const values = normalizeCategory(input);
    const db = readDemo();
    const rows = readCatalogCategories(db);
    const existing = rows.find((row) => row.code === key);
    if (create && existing) throw duplicate();
    if (!create && !existing) throw missing();
    const saved = { code: key, ...values };
    db.catalogCategories = existing
      ? rows.map((row) => (row.code === key ? saved : row))
      : [...rows, saved];
    appendAudit(db, {
      entityType: 'SIGN_CATEGORY',
      entityId: key,
      action: create ? 'CREATED' : 'UPDATED',
      beforeValue: existing ?? null,
      afterValue: saved,
    });
    writeDemo(db);
  },
  async saveSignType(
    _token: string,
    code: string,
    input: SignTypeInput,
    create: boolean,
  ) {
    const key = normalizeSignTypeCode(code);
    const values = normalizeSignType(input);
    const db = readDemo();
    if (
      !readCatalogCategories(db).some(
        (category) => category.code === values.categoryCode,
      )
    )
      throw missing();
    const rows = readCatalogTypes(db);
    const existing = rows.find((row) => row.code === key);
    if (create && existing) throw duplicate();
    if (!create && !existing) throw missing();
    const saved = {
      ...existing,
      ...values,
      code: key,
      recognizable: existing?.recognizable ?? false,
      updatedAt: new Date().toISOString(),
    };
    db.catalogTypes = existing
      ? rows.map((row) => (row.code === key ? saved : row))
      : [...rows, saved];
    appendAudit(db, {
      entityType: 'SIGN_TYPE',
      entityId: key,
      action: create
        ? 'CREATED'
        : existing?.active && !saved.active
          ? 'DEACTIVATED'
          : 'UPDATED',
      beforeValue: existing && { ...existing, iconUrl: undefined },
      afterValue: { ...saved, iconUrl: undefined },
    });
    writeDemo(db);
  },
  async uploadCatalogIcon(_token: string, code: string, file: File) {
    validateCatalogIcon(file);
    const data = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    for (let offset = 0; offset < data.length; offset += 8192)
      binary += String.fromCharCode(...data.subarray(offset, offset + 8192));
    const db = readDemo();
    const rows = readCatalogTypes(db);
    if (!rows.some((row) => row.code === code)) throw missing();
    db.catalogTypes = rows.map((row) =>
      row.code === code
        ? {
            ...row,
            iconUrl: 'data:' + file.type + ';base64,' + btoa(binary),
            updatedAt: new Date().toISOString(),
          }
        : row,
    );
    appendAudit(db, {
      entityType: 'SIGN_TYPE',
      entityId: code,
      action: 'UPDATED',
      afterValue: { iconUpdated: true },
    });
    writeDemo(db);
  },
};
