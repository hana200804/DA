import { useState } from 'react';
import {
  ArrowUpRight,
  Check,
  CircleAlert,
  Copy,
  MapPin,
  ShieldCheck,
  Trash2,
  X,
} from 'lucide-react';
import { api, isDemo } from '../api';
import {
  Badge,
  Empty,
  ErrorPanel,
  Evidence,
  Loading,
  Modal,
  Pagination,
  SearchBox,
  SignGraphic,
  useLoad,
} from '../components';
import {
  dateLabel,
  errorMessage,
  percentage,
  SessionExpiredError,
} from '../domain';
import type { Report, Upload } from '../types';

interface DetailProps {
  row: Upload | Report;
  kind: 'pending' | 'reports' | 'history';
  sessionId: string;
  onClose: () => void;
  onDone: (message: string) => void;
}
export function UploadDetail({
  row,
  kind,
  sessionId,
  onClose,
  onDone,
}: DetailProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [confirm, setConfirm] = useState<
    'APPROVE' | 'REJECT' | 'DELETE' | null
  >(null);
  const duplicates = useLoad(
    () =>
      kind === 'reports' ? Promise.resolve([]) : api.duplicates(sessionId, row),
    [sessionId, row.id, kind],
  );
  const report = kind === 'reports' ? (row as Report) : null;
  async function execute() {
    if (!confirm) return;
    setBusy(true);
    setError('');
    try {
      if (confirm === 'DELETE') await api.remove(sessionId, row.id);
      else
        await api.decide(
          sessionId,
          kind === 'reports' ? 'reports' : 'pending',
          row.id,
          confirm,
        );
      onDone(
        confirm === 'DELETE'
          ? 'Đã xóa biển báo khỏi dữ liệu hiển thị.'
          : confirm === 'APPROVE'
            ? 'Đã chấp nhận yêu cầu.'
            : 'Đã từ chối yêu cầu.',
      );
    } catch (e) {
      setError(errorMessage(e));
      if (e instanceof SessionExpiredError)
        window.dispatchEvent(new Event('visionmap:expired'));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title={report ? 'Xem xét báo cáo' : 'Chi tiết biển báo đăng tải'}
      eyebrow={row.id}
      onClose={onClose}
      wide
      busy={busy}
    >
      <div className="review-layout">
        <div>
          <Evidence
            src={row.photoBase64}
            bbox={row.bbox}
            code={row.trafficSignId}
            demo={isDemo}
          />
          <div className="photo-caption">
            <span>Ảnh biển báo</span>
            <span>
              {row.bbox ? 'Có vùng nhận diện AI' : 'Không có vùng nhận diện'}
            </span>
          </div>
        </div>
        <div>
          <div className="review-sign-title">
            <SignGraphic code={row.trafficSignId} />
            <div>
              <span className="code">{row.trafficSignId}</span>
              <h3>{row.trafficSignName || 'Biển báo được đăng tải'}</h3>
            </div>
          </div>
          <div className="confidence">
            <span>Độ tin cậy AI</span>
            <b>{percentage(row.accuracyAI).toFixed(1)}%</b>
            <div>
              <i style={{ width: `${percentage(row.accuracyAI)}%` }} />
            </div>
          </div>
          <dl className="details-list">
            <div>
              <dt>Địa chỉ</dt>
              <dd>{row.fullAddress || 'Chưa có địa chỉ'}</dd>
            </div>
            <div>
              <dt>Tọa độ</dt>
              <dd>
                {row.latitude.toFixed(5)}, {row.longitude.toFixed(5)}{' '}
                <a
                  className="text-button"
                  target="_blank"
                  rel="noreferrer"
                  href={`https://www.openstreetmap.org/?mlat=${row.latitude}&mlon=${row.longitude}#map=18/${row.latitude}/${row.longitude}`}
                  aria-label="Xem vị trí trên bản đồ"
                >
                  <ArrowUpRight size={15} />
                </a>
              </dd>
            </div>
            <div>
              <dt>{report ? 'Người báo cáo' : 'Người đăng'}</dt>
              <dd>@{report ? report.custId : row.createdBy}</dd>
            </div>
            <div>
              <dt>Ngày ghi nhận</dt>
              <dd>{dateLabel(row.createdAt, true)}</dd>
            </div>
            {kind === 'history' && (
              <div>
                <dt>Trạng thái</dt>
                <dd>
                  <Badge status={row.status} />
                </dd>
              </div>
            )}
          </dl>
          {report && (
            <div className="report-reason">
              <CircleAlert size={18} />
              <div>
                <strong>Lý do báo cáo</strong>
                <p>{report.reason}</p>
              </div>
            </div>
          )}
        </div>
      </div>
      {kind !== 'reports' && (
        <section className="duplicates">
          <h3>
            <Copy size={17} /> Biển báo đã duyệt ở vị trí tương tự
          </h3>
          {duplicates.loading ? (
            <p className="muted">Đang kiểm tra biển báo trùng…</p>
          ) : duplicates.error ? (
            <div className="inline-error">
              {duplicates.error}{' '}
              <button className="text-button" onClick={duplicates.refresh}>
                Thử lại
              </button>
            </div>
          ) : duplicates.data?.length ? (
            <div className="duplicate-list">
              {duplicates.data.map((d) => (
                <div key={d.id}>
                  <SignGraphic code={d.trafficSignId} />
                  <div>
                    <b>{d.trafficSignId}</b>
                    <p>{d.fullAddress}</p>
                    <small>
                      {d.latitude.toFixed(5)}, {d.longitude.toFixed(5)}
                    </small>
                  </div>
                  <Badge status="APPROVAL" />
                </div>
              ))}
            </div>
          ) : (
            <div className="notice">
              <ShieldCheck size={17} /> Chưa tìm thấy biển báo đã duyệt ở vị trí
              tương tự.
            </div>
          )}
        </section>
      )}
      {error && (
        <div className="inline-error" role="alert">
          {error}
        </div>
      )}
      {confirm ? (
        <div className="confirm-box" role="alert">
          <strong>
            {confirm === 'DELETE'
              ? 'Xóa biển báo này?'
              : confirm === 'REJECT'
                ? 'Từ chối yêu cầu này?'
                : report
                  ? 'Chấp nhận báo cáo này?'
                  : 'Phê duyệt biển báo này?'}
          </strong>
          <p>
            {confirm === 'DELETE' || (report && confirm === 'APPROVE')
              ? 'Biển báo liên quan sẽ bị loại khỏi dữ liệu hiển thị cho người dùng.'
              : confirm === 'REJECT'
                ? 'Yêu cầu sẽ được chuyển sang trạng thái từ chối.'
                : 'Biển báo sẽ được chuyển sang trạng thái đã duyệt.'}
          </p>
          <div>
            <button
              className="button"
              disabled={busy}
              onClick={() => setConfirm(null)}
            >
              Quay lại
            </button>
            <button
              className={`button ${confirm === 'APPROVE' ? 'primary' : 'danger'}`}
              disabled={busy}
              onClick={execute}
            >
              {busy ? 'Đang xử lý…' : 'Xác nhận'}
            </button>
          </div>
        </div>
      ) : (
        <div className="modal-actions">
          <button className="button" onClick={onClose}>
            Đóng
          </button>
          {(kind === 'pending' ||
            kind === 'reports' ||
            row.status === 'PENDING') && (
            <>
              <button
                className="button danger-outline"
                onClick={() => setConfirm('REJECT')}
              >
                <X size={17} />
                Từ chối
              </button>
              <button
                className="button primary"
                disabled={
                  kind !== 'reports' &&
                  (!!duplicates.error || duplicates.loading)
                }
                onClick={() => setConfirm('APPROVE')}
              >
                <Check size={17} />
                {report ? 'Chấp nhận báo cáo' : 'Phê duyệt'}
              </button>
            </>
          )}
          {kind === 'history' && row.status !== 'DELETED' && (
            <button
              className="button danger-outline"
              onClick={() => setConfirm('DELETE')}
            >
              <Trash2 size={16} />
              Xóa biển báo
            </button>
          )}
        </div>
      )}
    </Modal>
  );
}
export function Reviews({
  sessionId,
  kind,
  notify,
}: {
  sessionId: string;
  kind: 'pending' | 'reports';
  notify: (m: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState('newest');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Upload | Report | null>(null);
  const result = useLoad(
    () => api[kind](sessionId, { query, sort, page, size: 7, typeId: '' }),
    [sessionId, kind, query, sort, page],
  );
  const report = kind === 'reports';
  function done(message: string) {
    setSelected(null);
    notify(message);
    if (page > 1 && result.data?.rows.length === 1) setPage((p) => p - 1);
    else result.refresh();
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">KIỂM DUYỆT DỮ LIỆU</div>
          <h1>
            {report ? 'Báo cáo từ cộng đồng' : 'Biển báo chờ duyệt'}
            <span className="heading-dot">.</span>
          </h1>
          <p>
            {report
              ? 'Xem xét phản hồi để giữ dữ liệu biển báo luôn chính xác.'
              : 'Kiểm tra và xác nhận các biển báo do người dùng đóng góp.'}
          </p>
        </div>
        <span className={`count-pill ${report ? 'red' : ''}`}>
          {result.data?.total ?? '…'} {report ? 'báo cáo' : 'đang chờ'}
        </span>
      </div>
      <div className={`notice review-notice ${report ? 'warning' : ''}`}>
        <ShieldCheck size={20} />
        <span>
          {report
            ? 'Kiểm tra nội dung báo cáo trước khi quyết định. Chấp nhận báo cáo có thể xóa biển báo liên quan.'
            : 'Đối chiếu ảnh, vị trí và biển báo trùng trước khi phê duyệt.'}
        </span>
      </div>
      <section className="panel">
        {!report && (
          <div className="panel-toolbar">
            <SearchBox
              value={query}
              onChange={(v) => {
                setQuery(v);
                setPage(1);
              }}
              placeholder="Tìm theo địa chỉ…"
            />
            <select
              className="standalone-select"
              aria-label="Sắp xếp biển báo chờ duyệt"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPage(1);
              }}
            >
              <option value="newest">Mới nhất trước</option>
              <option value="oldest">Cũ nhất trước</option>
            </select>
          </div>
        )}
        {report && (
          <div className="panel-title">
            <h2>Yêu cầu cần xem xét</h2>
            <span className="muted">Báo cáo chưa được xử lý</span>
          </div>
        )}
        {result.loading ? (
          <Loading />
        ) : result.error ? (
          <ErrorPanel message={result.error} retry={result.refresh} />
        ) : (
          result.data && (
            <>
              {!result.data.rows.length ? (
                <Empty
                  title={
                    query
                      ? 'Không tìm thấy kết quả'
                      : report
                        ? 'Chưa có báo cáo cần xử lý'
                        : 'Đã xử lý hết biển báo chờ duyệt'
                  }
                  text={
                    query
                      ? 'Thử tìm một địa chỉ khác.'
                      : 'Các đóng góp mới sẽ được hiển thị tại đây.'
                  }
                />
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>BIỂN BÁO</th>
                        <th>
                          {report ? 'NỘI DUNG BÁO CÁO' : 'VỊ TRÍ GHI NHẬN'}
                        </th>
                        <th>{report ? 'NGƯỜI BÁO CÁO' : 'ĐỘ TIN CẬY AI'}</th>
                        <th>NGÀY GHI NHẬN</th>
                        <th aria-label="Thao tác" />
                      </tr>
                    </thead>
                    <tbody>
                      {result.data.rows.map((row) => (
                        <tr key={row.id}>
                          <td>
                            <button
                              className="sign-cell"
                              onClick={() => setSelected(row)}
                            >
                              <span className="sign-tile">
                                <SignGraphic code={row.trafficSignId} />
                              </span>
                              <span>
                                <span className="code">
                                  {row.trafficSignId}
                                </span>
                                <strong>{row.trafficSignName || row.id}</strong>
                                {!report && row.numberDuplicate > 0 && (
                                  <small className="duplicate-tag">
                                    <Copy size={11} />
                                    {row.numberDuplicate} biển gần vị trí
                                  </small>
                                )}
                              </span>
                            </button>
                          </td>
                          <td>
                            <div className="address-cell">
                              {report ? (
                                <>
                                  <strong>{(row as Report).reason}</strong>
                                  <small>{row.fullAddress}</small>
                                </>
                              ) : (
                                <>
                                  <MapPin size={14} />
                                  <span>
                                    {row.fullAddress || 'Chưa có địa chỉ'}
                                  </span>
                                </>
                              )}
                            </div>
                          </td>
                          <td>
                            {report ? (
                              <span className="muted">
                                @{(row as Report).custId}
                              </span>
                            ) : (
                              <div className="ai-score">
                                <b>{percentage(row.accuracyAI).toFixed(1)}%</b>
                                <span>
                                  <i
                                    style={{
                                      width: `${percentage(row.accuracyAI)}%`,
                                    }}
                                  />
                                </span>
                              </div>
                            )}
                          </td>
                          <td className="muted nowrap">
                            {dateLabel(row.createdAt)}
                          </td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => setSelected(row)}
                            >
                              Xem xét <ArrowUpRight size={15} />
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
                size={7}
                onChange={setPage}
              />
            </>
          )
        )}
      </section>
      {selected && (
        <UploadDetail
          row={selected}
          kind={kind}
          sessionId={sessionId}
          onClose={() => setSelected(null)}
          onDone={done}
        />
      )}
    </>
  );
}
