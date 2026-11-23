import { readDemo, writeDemo, contributors } from './demo.ts';
import { appendAudit } from './demoAudit.ts';
import { ReviewApiError } from './reviewApi.ts';
import {
  demoAdminEmail,
  demoAdminId,
  validateAccountPatch,
  type AccountFilters,
  type AccountPatch,
  type ManagedUser,
} from './accountTypes.ts';
import type { DemoDB } from './types';

function managedUsers(db: DemoDB): ManagedUser[] {
  return (
    db.managedUsers ?? [
      {
        id: demoAdminId,
        email: demoAdminEmail,
        displayName: 'Quản trị viên',
        role: 'ADMIN',
        status: 'ACTIVE',
      },
      ...contributors.map(
        ([id, displayName], index): ManagedUser => ({
          id,
          displayName,
          email: id + '@example.com',
          role: index === 0 ? 'MODERATOR' : 'USER',
          status: index === contributors.length - 1 ? 'LOCKED' : 'ACTIVE',
        }),
      ),
    ]
  );
}

export const accountsDemo = {
  async managedUsers(_token: string, filters: AccountFilters) {
    const search = filters.query?.trim().toLowerCase() || '';
    const rows = managedUsers(readDemo()).filter(
      (user) =>
        user.email.toLowerCase().includes(search) &&
        (!filters.role || user.role === filters.role) &&
        (!filters.status || user.status === filters.status),
    );
    return {
      rows: rows.slice(
        (filters.page - 1) * filters.size,
        filters.page * filters.size,
      ),
      total: rows.length,
      pages: Math.max(1, Math.ceil(rows.length / filters.size)),
    };
  },
  async updateManagedUser(_token: string, id: string, input: AccountPatch) {
    const { role, status } = validateAccountPatch(input);
    if (
      id === demoAdminId &&
      ((role && role !== 'ADMIN') || status === 'LOCKED')
    )
      throw new ReviewApiError(
        403,
        'FORBIDDEN',
        'Bạn không thể tự hạ vai trò hoặc tự khóa tài khoản.',
      );
    const db = readDemo();
    const users = managedUsers(db);
    const before = users.find((user) => user.id === id);
    if (!before)
      throw new ReviewApiError(404, 'NOT_FOUND', 'Không tìm thấy tài khoản.');
    const next = {
      ...before,
      role: role ?? before.role,
      status: status ?? before.status,
    };
    if (before.role === next.role && before.status === next.status) return;
    db.managedUsers = users.map((user) => (user.id === id ? next : user));
    if (before.role !== next.role)
      appendAudit(db, {
        entityType: 'USER',
        entityId: id,
        action: 'ROLE_CHANGED',
        beforeValue: { role: before.role },
        afterValue: { role: next.role },
      });
    if (before.status !== next.status)
      appendAudit(db, {
        entityType: 'USER',
        entityId: id,
        action: next.status === 'LOCKED' ? 'LOCKED' : 'UNLOCKED',
        beforeValue: { status: before.status },
        afterValue: { status: next.status },
      });
    writeDemo(db);
  },
};
