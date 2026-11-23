import { useEffect, useState } from 'react';
import {
  ArrowUpRight,
  LockKeyhole,
  LockKeyholeOpen,
  Search,
} from 'lucide-react';
import { accountsApi } from '../api';
import {
  accountRoles,
  accountStatuses,
  isOwnAccount,
  type AccountFilters,
  type AccountPatch,
  type AccountRole,
  type AccountStatus,
  type ManagedUser,
} from '../accountTypes';
import {
  Empty,
  ErrorPanel,
  Loading,
  Modal,
  Pagination,
  SearchBox,
  useLoad,
} from '../components';
import { dateLabel, errorMessage, SessionExpiredError } from '../domain';
import type { Admin } from '../types';

const size = 8;

export function Accounts({
  admin,
  notify,
}: {
  admin: Admin;
  notify: (message: string) => void;
}) {
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [role, setRole] = useState<AccountFilters['role']>('');
  const [status, setStatus] = useState<AccountFilters['status']>('');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<ManagedUser | null>(null);
  const result = useLoad(
    () =>
      accountsApi.managedUsers(admin.sessionId, {
        query,
        page,
        size,
        role,
        status,
      }),
    [admin.sessionId, query, page, role, status],
  );
  const pages = result.data?.pages;

  useEffect(() => {
    if (pages !== undefined && page > pages) setPage(pages);
  }, [page, pages]);

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">QUẢN TRỊ HỆ THỐNG</div>
          <h1>
            Quản lý người dùng<span className="heading-dot">.</span>
          </h1>
          <p>Tìm tài khoản theo email và quản lý quyền truy cập.</p>
        </div>
        <span className="count-pill">
          {result.data?.total ?? '…'} tài khoản
        </span>
      </div>
      <section className="panel">
        <form
          className="panel-toolbar accounts-toolbar"
          onSubmit={(event) => {
            event.preventDefault();
            setQuery(input.trim());
            setPage(1);
          }}
        >
          <SearchBox
            value={input}
            onChange={(value) => {
              setInput(value);
              if (!value) {
                setQuery('');
                setPage(1);
              }
            }}
            placeholder="Tìm theo email…"
          />
          <select
            className="standalone-select"
            aria-label="Lọc vai trò"
            value={role}
            onChange={(event) => {
              setRole(event.target.value as AccountRole | '');
              setPage(1);
            }}
          >
            <option value="">Tất cả vai trò</option>
            {Object.entries(accountRoles).map(([key, label]) => (
              <option value={key} key={key}>
                {label}
              </option>
            ))}
          </select>
          <select
            className="standalone-select"
            aria-label="Lọc trạng thái tài khoản"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as AccountStatus | '');
              setPage(1);
            }}
          >
            <option value="">Tất cả trạng thái</option>
            {Object.entries(accountStatuses).map(([key, label]) => (
              <option value={key} key={key}>
                {label}
              </option>
            ))}
          </select>
          <button className="button primary" type="submit">
            <Search size={16} /> Tìm
          </button>
        </form>
        {result.loading ? (
          <Loading />
        ) : result.error ? (
          <ErrorPanel message={result.error} retry={result.refresh} />
        ) : (
          result.data && (
            <>
              {result.data.rows.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>NGƯỜI DÙNG</th>
                        <th>VAI TRÒ</th>
                        <th>TRẠNG THÁI</th>
                        <th>ĐĂNG NHẬP GẦN NHẤT</th>
                        <th aria-label="Thao tác" />
                      </tr>
                    </thead>
                    <tbody>
                      {result.data.rows.map((user, index) => (
                        <tr key={user.id}>
                          <td>
                            <button
                              className="user-cell"
                              onClick={() => setSelected(user)}
                            >
                              <span className={'avatar color-' + (index % 4)}>
                                {(user.displayName || user.email)
                                  .slice(0, 2)
                                  .toUpperCase()}
                              </span>
                              <span>
                                <strong>
                                  {user.displayName || user.email}
                                  {isOwnAccount(user, admin) ? ' (Bạn)' : ''}
                                </strong>
                                <small>{user.email}</small>
                              </span>
                            </button>
                          </td>
                          <td>{accountRoles[user.role]}</td>
                          <td>
                            <span
                              className={
                                'number-pill ' +
                                (user.status === 'ACTIVE' ? 'green' : 'amber')
                              }
                            >
                              {accountStatuses[user.status]}
                            </span>
                          </td>
                          <td className="muted nowrap">
                            {user.lastLoginAt
                              ? dateLabel(user.lastLoginAt, true)
                              : '—'}
                          </td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => setSelected(user)}
                            >
                              Chi tiết <ArrowUpRight size={15} />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty
                  title="Không tìm thấy tài khoản"
                  text="Kiểm tra email hoặc thay đổi bộ lọc."
                />
              )}
              <Pagination
                data={result.data}
                page={Math.min(page, result.data.pages)}
                size={size}
                onChange={setPage}
              />
            </>
          )
        )}
      </section>
      {selected && (
        <AccountDialog
          admin={admin}
          user={selected}
          close={() => setSelected(null)}
          onSaved={(message) => {
            setSelected(null);
            result.refresh();
            notify(message);
          }}
        />
      )}
    </>
  );
}

function AccountDialog({
  admin,
  user,
  close,
  onSaved,
}: {
  admin: Admin;
  user: ManagedUser;
  close: () => void;
  onSaved: (message: string) => void;
}) {
  const ownAccount = isOwnAccount(user, admin);
  const [role, setRole] = useState(user.role);
  const [confirmation, setConfirmation] = useState<'role' | 'status' | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const nextStatus = user.status === 'ACTIVE' ? 'LOCKED' : 'ACTIVE';
  const locks = nextStatus === 'LOCKED';

  async function save() {
    if (busy || ownAccount || !confirmation) return;
    const patch: AccountPatch =
      confirmation === 'role' ? { role } : { status: nextStatus };
    setBusy(true);
    setError('');
    try {
      await accountsApi.updateManagedUser(admin.sessionId, user.id, patch);
      onSaved(
        confirmation === 'role'
          ? 'Đã đổi vai trò tài khoản.'
          : locks
            ? 'Đã khóa tài khoản.'
            : 'Đã mở khóa tài khoản.',
      );
    } catch (error) {
      setError(errorMessage(error));
      if (error instanceof SessionExpiredError)
        window.dispatchEvent(new Event('visionmap:expired'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={user.displayName || user.email}
      eyebrow="TÀI KHOẢN"
      onClose={close}
      busy={busy}
    >
      <dl className="details-list">
        <div>
          <dt>Email</dt>
          <dd>{user.email}</dd>
        </div>
        <div>
          <dt>Vai trò hiện tại</dt>
          <dd>{accountRoles[user.role]}</dd>
        </div>
        <div>
          <dt>Trạng thái</dt>
          <dd>{accountStatuses[user.status]}</dd>
        </div>
        {user.createdAt && (
          <div>
            <dt>Ngày tạo</dt>
            <dd>{dateLabel(user.createdAt, true)}</dd>
          </div>
        )}
      </dl>
      {ownAccount ? (
        <p className="muted">
          Bạn không thể tự hạ vai trò hoặc tự khóa tài khoản.
        </p>
      ) : (
        !confirmation && (
          <label className="field account-role">
            Vai trò mới
            <select
              className="standalone-select"
              value={role}
              onChange={(event) => setRole(event.target.value as AccountRole)}
            >
              {Object.entries(accountRoles).map(([key, label]) => (
                <option value={key} key={key}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        )
      )}
      {error && (
        <div className="inline-error" role="alert">
          {error}
        </div>
      )}
      {confirmation ? (
        <div className="confirm-box">
          <strong>
            {confirmation === 'role'
              ? 'Đổi vai trò tài khoản?'
              : locks
                ? 'Khóa tài khoản?'
                : 'Mở khóa tài khoản?'}
          </strong>
          <p>
            {confirmation === 'role'
              ? 'Vai trò mới: ' + accountRoles[role] + '.'
              : locks
                ? 'Máy chủ sẽ thu hồi các phiên hiện tại của tài khoản.'
                : 'Người dùng có thể đăng nhập lại sau khi mở khóa.'}
          </p>
          <div>
            <button
              className="button"
              disabled={busy}
              onClick={() => {
                setConfirmation(null);
                setError('');
              }}
            >
              Quay lại
            </button>
            <button
              className={
                'button ' +
                (confirmation === 'status' && locks ? 'danger' : 'primary')
              }
              disabled={busy}
              onClick={save}
            >
              {busy ? 'Đang lưu…' : 'Xác nhận'}
            </button>
          </div>
        </div>
      ) : (
        <div className="modal-actions">
          <button className="button" onClick={close}>
            Đóng
          </button>
          {!ownAccount && (
            <>
              <button
                className={'button ' + (locks ? 'danger-outline' : '')}
                onClick={() => setConfirmation('status')}
              >
                {locks ? (
                  <>
                    <LockKeyhole size={16} /> Khóa tài khoản
                  </>
                ) : (
                  <>
                    <LockKeyholeOpen size={16} /> Mở khóa tài khoản
                  </>
                )}
              </button>
              <button
                className="button primary"
                disabled={role === user.role}
                onClick={() => setConfirmation('role')}
              >
                Đổi vai trò
              </button>
            </>
          )}
        </div>
      )}
    </Modal>
  );
}
