import type { PageKey } from './types';

export interface SignaIdentity {
  id: string;
  displayName: string;
  role: string;
  permissions: string[];
  email?: string;
}

export interface SignaSession {
  sessionId: string;
  expiresAt: string;
  email: string;
}

type IdentityAccess = { role?: unknown; permissions?: unknown };

export function canEnterSigna(account: IdentityAccess | null | undefined) {
  return (
    (account?.role === 'ADMIN' || account?.role === 'MODERATOR') &&
    Array.isArray(account.permissions) &&
    account.permissions.includes('review:decide')
  );
}

export function hasSignaPermission(
  account: IdentityAccess,
  permission: string,
) {
  if (!canEnterSigna(account)) return false;
  if (
    (permission === 'catalog:manage' || permission === 'user:manage') &&
    account.role !== 'ADMIN'
  )
    return false;
  return (
    Array.isArray(account.permissions) &&
    account.permissions.includes(permission)
  );
}

const routePermissions: Partial<Record<PageKey, string>> = {
  pending: 'review:decide',
  reports: 'report:resolve',
  audit: 'audit:read',
  accounts: 'user:manage',
  compare: 'recognition:compare',
  stats: 'stats:read',
};

export function canOpenSignaPage(account: IdentityAccess, page: PageKey) {
  const permission = routePermissions[page];
  return permission
    ? hasSignaPermission(account, permission)
    : canEnterSigna(account);
}

export function isCurrentSignaSession(
  session: unknown,
  now = Date.now(),
): session is SignaSession {
  if (!session || typeof session !== 'object') return false;
  const { sessionId, expiresAt, email } = session as Partial<SignaSession>;
  return (
    typeof sessionId === 'string' &&
    !!sessionId.trim() &&
    typeof expiresAt === 'string' &&
    Date.parse(expiresAt) > now &&
    typeof email === 'string' &&
    !!email.trim()
  );
}
