export const accountRoles = {
  USER: 'Người dùng',
  MODERATOR: 'Kiểm duyệt viên',
  ADMIN: 'Quản trị viên',
};
export const accountStatuses = { ACTIVE: 'Đang hoạt động', LOCKED: 'Đã khóa' };

export type AccountRole = keyof typeof accountRoles;
export type AccountStatus = keyof typeof accountStatuses;
export interface ManagedUser {
  id: string;
  email: string;
  displayName: string;
  role: AccountRole;
  status: AccountStatus;
  createdAt?: string | null;
  lastLoginAt?: string | null;
}
export interface AccountFilters {
  page: number;
  size: number;
  query?: string;
  role?: AccountRole | '';
  status?: AccountStatus | '';
}
export interface AccountPatch {
  role?: AccountRole;
  status?: AccountStatus;
}

export const demoAdminId = 'admin.demo';
export const demoAdminEmail = 'admin@example.com';

export function isOwnAccount(
  target: ManagedUser,
  identity: { id?: string; email: string },
) {
  if (identity.id) return target.id === identity.id;
  return (
    !!identity.email &&
    target.email.toLowerCase() === identity.email.toLowerCase()
  );
}

export function validateAccountPatch(patch: AccountPatch) {
  const { role, status } = patch;
  if (role === undefined && status === undefined)
    throw new Error('Chưa có thay đổi tài khoản.');
  if (role !== undefined && !Object.hasOwn(accountRoles, role))
    throw new Error('Vai trò không hợp lệ.');
  if (status !== undefined && !Object.hasOwn(accountStatuses, status))
    throw new Error('Trạng thái không hợp lệ.');
  return { role, status };
}
