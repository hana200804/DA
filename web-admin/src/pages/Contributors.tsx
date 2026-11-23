import { useState } from 'react';
import {
  ArrowLeft,
  ArrowUpRight,
  ChevronRight,
  UsersRound,
} from 'lucide-react';
import { api } from '../api';
import {
  Badge,
  Empty,
  ErrorPanel,
  ExportButton,
  Loading,
  Pagination,
  SearchBox,
  SignGraphic,
  useLoad,
} from '../components';
import { dateLabel } from '../domain';
import type { Contributor, Upload } from '../types';
import { UploadDetail } from './Reviews';

export function Contributors({
  sessionId,
  notify,
}: {
  sessionId: string;
  notify: (m: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [sort, setSort] = useState('total');
  const [username, setUsername] = useState('');
  const result = useLoad(
    () => api.users(sessionId, { query, page, size: 8, sort, typeId: '' }),
    [sessionId, query, page, sort, username],
  );
  if (username)
    return (
      <ContributorDetail
        sessionId={sessionId}
        username={username}
        onBack={() => setUsername('')}
        notify={notify}
      />
    );
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">CỘNG ĐỒNG VISIONMAP</div>
          <h1>
            Người đóng góp<span className="heading-dot">.</span>
          </h1>
          <p>Theo dõi đóng góp và chất lượng dữ liệu từ cộng đồng.</p>
        </div>
        <ExportButton
          rows={result.data?.rows || []}
          headers={['Người dùng', 'Chờ duyệt', 'Đã duyệt', 'Từ chối', 'Đã xóa']}
          values={(u: Contributor) => [
            u.custId,
            u.pending,
            u.approval,
            u.rejected,
            u.deleted,
          ]}
          filename="nguoi-dong-gop.csv"
        />
      </div>
      <section className="panel">
        <div className="panel-toolbar">
          <SearchBox
            value={query}
            onChange={(v) => {
              setQuery(v);
              setPage(1);
            }}
            placeholder="Tìm tên đăng nhập…"
          />
          <select
            className="standalone-select"
            aria-label="Sắp xếp người đóng góp"
            value={sort}
            onChange={(e) => {
              setSort(e.target.value);
              setPage(1);
            }}
          >
            <option value="total">Đã duyệt nhiều nhất</option>
            <option value="pending">Chờ duyệt nhiều nhất</option>
          </select>
        </div>
        {result.loading ? (
          <Loading />
        ) : result.error ? (
          <ErrorPanel message={result.error} retry={result.refresh} />
        ) : (
          result.data && (
            <>
              {!result.data.rows.length ? (
                <Empty />
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>NGƯỜI DÙNG</th>
                        <th>TỔNG ĐĂNG TẢI</th>
                        <th>ĐÃ DUYỆT</th>
                        <th>CHỜ DUYỆT</th>
                        <th>TỪ CHỐI / XÓA</th>
                        <th aria-label="Thao tác" />
                      </tr>
                    </thead>
                    <tbody>
                      {result.data.rows.map((u, i) => (
                        <tr key={u.custId}>
                          <td>
                            <button
                              className="user-cell"
                              onClick={() => setUsername(u.custId)}
                            >
                              <span className={`avatar color-${i % 4}`}>
                                {u.custId.slice(0, 2).toUpperCase()}
                              </span>
                              <span>
                                <strong>@{u.custId}</strong>
                                <small>Thành viên cộng đồng</small>
                              </span>
                            </button>
                          </td>
                          <td>
                            <b>
                              {u.pending + u.approval + u.deleted + u.rejected}
                            </b>
                          </td>
                          <td>
                            <span className="number-pill green">
                              {u.approval}
                            </span>
                          </td>
                          <td>
                            <span className="number-pill amber">
                              {u.pending}
                            </span>
                          </td>
                          <td className="muted">
                            {u.rejected} / {u.deleted}
                          </td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => setUsername(u.custId)}
                            >
                              Chi tiết <ArrowUpRight size={15} />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <Pagination
                data={result.data}
                page={page}
                size={8}
                onChange={setPage}
              />
            </>
          )
        )}
      </section>
      <div className="tip-card">
        <UsersRound size={23} />
        <div>
          <strong>Mỗi đóng góp đều giúp bản đồ tốt hơn.</strong>
          <p>
            Xem lịch sử của từng thành viên để kiểm tra và xử lý các biển báo đã
            đăng.
          </p>
        </div>
      </div>
    </>
  );
}
function ContributorDetail({
  sessionId,
  username,
  onBack,
  notify,
}: {
  sessionId: string;
  username: string;
  onBack: () => void;
  notify: (m: string) => void;
}) {
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Upload | null>(null);
  const [revision, setRevision] = useState(0);
  const info = useLoad(
    () => api.user(sessionId, username),
    [sessionId, username, revision],
  );
  const history = useLoad(
    () => api.history(sessionId, username, page),
    [sessionId, username, page, revision],
  );
  const counts = info.data
    ? [
        { label: 'Đã duyệt', value: info.data.approval, className: 'green' },
        { label: 'Chờ duyệt', value: info.data.pending, className: 'amber' },
        { label: 'Từ chối', value: info.data.rejected, className: 'red' },
        { label: 'Đã xóa', value: info.data.deleted, className: 'gray' },
      ]
    : [];
  const total = counts.reduce((sum, c) => sum + c.value, 0);
  return (
    <>
      <button className="text-button back-button" onClick={onBack}>
        <ArrowLeft size={17} />
        Tất cả người đóng góp
      </button>
      <div className="page-heading">
        <div>
          <div className="eyebrow">HỒ SƠ ĐÓNG GÓP</div>
          <h1>
            {info.data?.fullName || `@${username}`}
            <span className="heading-dot">.</span>
          </h1>
          <p>
            @{username}
            {info.data && ` · Tham gia ${dateLabel(info.data.createdAt)}`}
          </p>
        </div>
        <span className="avatar big">{username.slice(0, 2).toUpperCase()}</span>
      </div>
      {info.error && <ErrorPanel message={info.error} retry={info.refresh} />}
      <div className="contributor-stats">
        {counts.map((c) => (
          <div className="panel mini-stat" key={c.label}>
            <span>
              <i className={c.className} />
              {c.label}
            </span>
            <strong>{c.value}</strong>
            <small>
              {total ? Math.round((c.value / total) * 100) : 0}% tổng đăng tải
            </small>
          </div>
        ))}
      </div>
      <section className="panel">
        <div className="panel-title">
          <h2>Lịch sử đăng tải</h2>
          <span className="muted">{history.data?.total ?? '…'} biển báo</span>
        </div>
        {history.loading ? (
          <Loading />
        ) : history.error ? (
          <ErrorPanel message={history.error} retry={history.refresh} />
        ) : (
          history.data && (
            <>
              {!history.data.rows.length ? (
                <Empty
                  title="Chưa có lịch sử đăng tải"
                  text="Các biển báo do thành viên này đăng sẽ xuất hiện tại đây."
                />
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>BIỂN BÁO</th>
                        <th>ĐỊA CHỈ</th>
                        <th>TRẠNG THÁI</th>
                        <th>NGÀY ĐĂNG</th>
                        <th aria-label="Thao tác" />
                      </tr>
                    </thead>
                    <tbody>
                      {history.data.rows.map((row) => (
                        <tr key={row.id}>
                          <td>
                            <div className="sign-cell">
                              <span className="sign-tile">
                                <SignGraphic code={row.trafficSignId} />
                              </span>
                              <span>
                                <span className="code">
                                  {row.trafficSignId}
                                </span>
                                <strong>{row.trafficSignName || row.id}</strong>
                              </span>
                            </div>
                          </td>
                          <td className="description-cell">
                            {row.fullAddress}
                          </td>
                          <td>
                            <Badge status={row.status} />
                          </td>
                          <td className="muted nowrap">
                            {dateLabel(row.createdAt)}
                          </td>
                          <td>
                            <button
                              className="icon-button"
                              aria-label={`Xem ${row.id}`}
                              onClick={() => setSelected(row)}
                            >
                              <ChevronRight size={18} />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <Pagination
                data={history.data}
                page={page}
                size={6}
                onChange={setPage}
              />
            </>
          )
        )}
      </section>
      {selected && (
        <UploadDetail
          row={selected}
          kind="history"
          sessionId={sessionId}
          onClose={() => setSelected(null)}
          onDone={(m) => {
            setSelected(null);
            notify(m);
            setRevision((r) => r + 1);
          }}
        />
      )}
    </>
  );
}
