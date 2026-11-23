export interface CategoryInput {
  name: string;
  description: string;
  sortOrder: number;
}
export interface CatalogCategory extends CategoryInput {
  code: string;
}
export interface SignTypeInput {
  categoryCode: string;
  name: string;
  description: string;
  speedKmh: number | null;
  active: boolean;
}
export interface CatalogSignType extends SignTypeInput {
  code: string;
  recognizable: boolean;
  iconUrl?: string | null;
  updatedAt?: string;
}

export function normalizeCategoryCode(code: string) {
  const value = code.trim().toUpperCase();
  if (!/^[A-Z][A-Z0-9_]{0,19}$/.test(value))
    throw new Error(
      'Mã nhóm phải bắt đầu bằng chữ cái và có tối đa 20 ký tự chữ, số hoặc dấu gạch dưới.',
    );
  return value;
}

export function normalizeSignTypeCode(code: string) {
  const value = code.trim();
  if (!value || value.length > 30)
    throw new Error('Mã biển phải có từ 1 đến 30 ký tự.');
  return value;
}

export function normalizeCategory(input: CategoryInput): CategoryInput {
  const name = input.name.trim();
  if (!name || name.length > 100)
    throw new Error('Tên nhóm phải có từ 1 đến 100 ký tự.');
  if (
    !Number.isInteger(input.sortOrder) ||
    input.sortOrder < -32768 ||
    input.sortOrder > 32767
  )
    throw new Error('Thứ tự hiển thị phải là số nguyên từ -32768 đến 32767.');
  return {
    name,
    description: input.description.trim(),
    sortOrder: input.sortOrder,
  };
}

export function normalizeSignType(input: SignTypeInput): SignTypeInput {
  const name = input.name.trim();
  if (!name || name.length > 200)
    throw new Error('Tên loại biển phải có từ 1 đến 200 ký tự.');
  if (
    input.speedKmh !== null &&
    (!Number.isInteger(input.speedKmh) ||
      input.speedKmh <= 0 ||
      input.speedKmh > 32767)
  )
    throw new Error('Giá trị tốc độ phải là số nguyên dương.');
  return {
    categoryCode: normalizeCategoryCode(input.categoryCode),
    name,
    description: input.description.trim(),
    speedKmh: input.speedKmh,
    active: input.active,
  };
}

export function validateCatalogIcon(file: File) {
  if (
    !['image/png', 'image/jpeg', 'image/webp'].includes(file.type) ||
    !file.size
  )
    throw new Error('Chọn ảnh PNG, JPEG hoặc WebP hợp lệ.');
}
