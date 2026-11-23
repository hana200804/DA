import { useEffect, useState } from 'react';
import {
  ArrowUpRight,
  Check,
  CircleAlert,
  ShieldCheck,
  Trash2,
  X,
} from 'lucide-react';
import { isDemo, reviewApi } from '../api';
import {
  Empty,
  ErrorPanel,
  Evidence,
  Loading,
  Modal,
  Pagination,
  SignGraphic,
  useLoad,
} from '../components';
import { dateLabel, errorMessage, SessionExpiredError } from '../domain';
import { ReviewApiError } from '../reviewApi';
import { ReviewMap } from '../reviewMap';
import {
  reportReasonLabels,
  type ReportDecision,
  type ReportGroup,
  type ReportStatus,
} from '../reportTypes';

const statusLabels: Record<ReportStatus, string> = {
  PENDING: 'Chờ xử lý',
  ACCEPTED: 'Đã chấp nhận',
  DISMISSED: 'Đã từ chối',
};
const pageSize = 7;

export function SignReports({
  token,
  notify,
}: {
  token: string;
  notify: (message: string) => void;
}) {
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<ReportStatus>('PENDING');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [conflict, setConflict] = useState('');
  const result = useLoad(
    () => reviewApi.reportQueue(token, { page, size: pageSize, status }),
    [token, page, status],
  );
  const data = result.data;
  const selected = data?.rows.find((group) => group.sign.id === selectedId);

  useEffect(() => {
    if (!data) return;
    if (page > data.pages) setPage(data.pages);
    else if (
      selectedId &&
      !data.rows.some((group) => group.sign.id === selectedId)
    )
      setSelectedId(null);
  }, [data, page, selectedId]);

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">KIỂM DUYỆT DỮ LIỆU</div>
          <h1>
            Báo cáo từ cộng đồng<span className="heading-dot">.</span>
          </h1>
          <p>Xem xét phản hồi để giữ dữ liệu biển báo luôn chính xác.</p>
        </div>
        <span className="count-pill red">
          {data?.total ?? '…'} biển có báo cáo
        </span>
      </div>
      <div className="notice review-notice warning">
        <ShieldCheck size={20} />
        <span>
          Các báo cáo về cùng một biển được nhóm lại. Khi chấp nhận, chọn sửa
          thông tin hoặc gỡ biển khỏi bản đồ.
        </span>
      </div>
      {conflict && !selected && (
        <div className="inline-error" role="alert">
          {conflict}
        </div>
      )}
      <section className="panel">
        <div className="panel-title">
          <h2>Yêu cầu cần xem xét</h2>
          <select
            className="standalone-select"
            aria-label="Trạng thái báo cáo"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as ReportStatus);
              setSelectedId(null);
              setConflict('');
              setPage(1);
            }}
          >
            {Object.entries(statusLabels).map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </div>
        {result.loading ? (
          <Loading />
        ) : result.error ? (
          <ErrorPanel message={result.error} retry={result.refresh} />
        ) : (
          data && (
            <>
              {data.rows.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>BIỂN BÁO</th>
                        <th>NỘI DUNG BÁO CÁO</th>
                        <th>NGƯỜI BÁO CÁO</th>
                        <th>NGÀY GHI NHẬN</th>
                        <th aria-label="Thao tác" />
                      </tr>
                    </thead>
                    <tbody>
                      {data.rows.map((group) => {
                        const { sign, reports } = group;
                        const first = reports[0];
                        return (
                          <tr key={sign.id}>
                            <td>
                              <button
                                className="sign-cell"
                                onClick={() => setSelectedId(sign.id)}
                              >
                                <span className="sign-tile">
                                  <SignGraphic code={sign.signTypeCode} />
                                </span>
                                <span>
                                  <span className="code">
                                    {sign.signTypeCode}
                                  </span>
                                  <strong>
                                    {sign.signName || sign.signTypeCode}
                                  </strong>
                                </span>
                              </button>
                            </td>
                            <td>
                              <div className="address-cell">
                                <strong>
                                  {first
                                    ? reportReasonLabels[first.reason]
                                    : 'Báo cáo'}
                                </strong>
                                <small>
                                  {reports.length} báo cáo cùng biển
                                </small>
                              </div>
                            </td>
                            <td className="muted">
                              {[
                                ...new Set(
                                  reports
                                    .map((report) => report.reporterName)
                                    .filter(Boolean),
                                ),
                              ].join(', ') || '—'}
                            </td>
                            <td className="muted nowrap">
                              {first ? dateLabel(first.createdAt) : '—'}
                            </td>
                            <td>
                              <button
                                className="text-button"
                                onClick={() => setSelectedId(sign.id)}
                              >
                                Xem xét <ArrowUpRight size={15} />
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty
                  title="Chưa có báo cáo ở trạng thái này"
                  text="Bạn có thể chọn trạng thái khác để xem báo cáo."
                />
              )}
              <Pagination
                data={data}
                page={page}
                size={pageSize}
                onChange={setPage}
              />
            </>
          )
        )}
      </section>
      {selected && (
        <ReportDialog
          key={
            selected.sign.id +
            ':' +
            selected.sign.version +
            ':' +
            selected.reports
              .map(({ id, status }) => id + ':' + status)
              .join(',')
          }
          group={selected}
          conflict={conflict}
          token={token}
          onClose={() => setSelectedId(null)}
          onDone={(message) => {
            setConflict('');
            notify(message);
            result.refresh();
          }}
          onConflict={() => {
            setConflict(
              'Báo cáo hoặc biển đã thay đổi. Danh sách đang được tải lại.',
            );
            result.refresh();
          }}
        />
      )}
    </>
  );
}

function ReportDialog({
  group,
  conflict,
  token,
  onClose,
  onDone,
  onConflict,
}: {
  group: ReportGroup;
  conflict: string;
  token: string;
  onClose: () => void;
  onDone: (message: string) => void;
  onConflict: () => void;
}) {
  const { sign, reports } = group;
  const [selectedId, setSelectedId] = useState(reports[0]?.id);
  const report = reports.find((row) => row.id === selectedId) ?? reports[0];
  const [action, setAction] = useState<ReportDecision['action'] | null>(null);
  const [code, setCode] = useState(sign.signTypeCode);
  const [lat, setLat] = useState(String(sign.location.lat));
  const [lng, setLng] = useState(String(sign.location.lng));
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const location = { lat: Number(lat), lng: Number(lng) };
  const validLocation =
    !!lat.trim() &&
    !!lng.trim() &&
    Number.isFinite(location.lat) &&
    Number.isFinite(location.lng) &&
    Math.abs(location.lat) <= 90 &&
    Math.abs(location.lng) <= 180;
  const codeChanged = code.trim() !== sign.signTypeCode;
  const locationChanged =
    location.lat !== sign.location.lat || location.lng !== sign.location.lng;
  const mayAccept =
    sign.status === 'APPROVED' || sign.status === 'UNDER_REVIEW';
  const validDecision =
    action === 'DISMISS'
      ? !!note.trim()
      : action === 'EDIT'
        ? mayAccept &&
          !!code.trim() &&
          validLocation &&
          (codeChanged || locationChanged)
        : action === 'REMOVE' && mayAccept;

  function resetForm() {
    setAction(null);
    setError('');
    setNote('');
    setCode(sign.signTypeCode);
    setLat(String(sign.location.lat));
    setLng(String(sign.location.lng));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!action || !validDecision || busy || report?.status !== 'PENDING')
      return;
    const { version } = sign;
    const decision: ReportDecision =
      action === 'DISMISS'
        ? { action, note }
        : action === 'REMOVE'
          ? { action, version }
          : {
              action,
              version,
              changes: {
                ...(codeChanged ? { signTypeCode: code.trim() } : {}),
                ...(locationChanged ? { location } : {}),
              },
            };
    setBusy(true);
    setError('');
    try {
      await reviewApi.resolveReport(token, report.id, decision);
      onDone(
        {
          DISMISS: 'Đã từ chối báo cáo.',
          EDIT: 'Đã chấp nhận báo cáo và sửa biển.',
          REMOVE: 'Đã chấp nhận báo cáo và gỡ biển.',
        }[action],
      );
    } catch (error) {
      if (error instanceof SessionExpiredError) {
        window.dispatchEvent(new Event('visionmap:expired'));
      } else if (error instanceof ReviewApiError && error.status === 409) {
        resetForm();
        onConflict();
      } else {
        setError(errorMessage(error));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="Xem xét báo cáo"
      eyebrow={sign.id}
      onClose={onClose}
      wide
      busy={busy}
    >
      <div className="review-layout">
        <div>
          {report?.photoUrl ? (
            <Evidence
              src={report.photoUrl}
              code={sign.signTypeCode}
              demo={isDemo}
            />
          ) : (
            <Empty
              title="Báo cáo chưa có ảnh"
              text="Bạn vẫn có thể xem lý do và vị trí biển."
            />
          )}
          <div className="photo-caption">
            <span>Ảnh đính kèm báo cáo</span>
          </div>
        </div>
        <div>
          <div className="review-sign-title">
            <SignGraphic code={sign.signTypeCode} />
            <div>
              <span className="code">{sign.signTypeCode}</span>
              <h3>{sign.signName || sign.signTypeCode}</h3>
            </div>
          </div>
          <dl className="details-list">
            <div>
              <dt>Tọa độ</dt>
              <dd>
                {sign.location.lat.toFixed(5)}, {sign.location.lng.toFixed(5)}
              </dd>
            </div>
            <div>
              <dt>Trạng thái biển</dt>
              <dd>
                {mayAccept
                  ? sign.status === 'UNDER_REVIEW'
                    ? 'Đang rà soát'
                    : 'Đã duyệt'
                  : sign.status === 'REMOVED'
                    ? 'Đã gỡ'
                    : 'Chưa công bố'}
              </dd>
            </div>
          </dl>
          <ReviewMap
            center={sign.location}
            location={
              action === 'EDIT' && validLocation ? location : sign.location
            }
            nearby={[]}
            onChange={
              action === 'EDIT' && !busy
                ? (value) => {
                    setLat(String(value.lat));
                    setLng(String(value.lng));
                  }
                : undefined
            }
          />
        </div>
      </div>
      <section className="duplicates">
        <h3>
          <CircleAlert size={17} /> Báo cáo về cùng biển ({reports.length})
        </h3>
        <div className="duplicate-list">
          {reports.map((row) => (
            <div key={row.id}>
              <div>
                <b>{reportReasonLabels[row.reason]}</b>
                {row.description && <p>{row.description}</p>}
                <small>
                  {row.reporterName || 'Người báo cáo'} ·{' '}
                  {dateLabel(row.createdAt, true)} · {statusLabels[row.status]}
                </small>
                {row.resolution && (
                  <p>
                    {row.resolution === 'EDITED'
                      ? 'Biển đã được sửa'
                      : 'Biển đã được gỡ'}
                  </p>
                )}
                {row.resolutionNote && (
                  <p>Lý do từ chối: {row.resolutionNote}</p>
                )}
              </div>
              <button
                type="button"
                className="button"
                aria-pressed={report?.id === row.id}
                disabled={busy}
                aria-label={
                  'Chọn báo cáo ' +
                  reportReasonLabels[row.reason] +
                  ' ' +
                  row.id
                }
                onClick={() => {
                  setSelectedId(row.id);
                  resetForm();
                }}
              >
                {report?.id === row.id ? 'Đang xem' : 'Chọn'}
              </button>
            </div>
          ))}
        </div>
      </section>
      {(error || conflict) && (
        <div className="inline-error" role="alert">
          {error || conflict}
        </div>
      )}
      {report?.status === 'PENDING' && action ? (
        <form className="confirm-box review-decision" onSubmit={submit}>
          <strong>
            {action === 'EDIT'
              ? 'Chấp nhận và sửa biển'
              : action === 'REMOVE'
                ? 'Chấp nhận và gỡ biển'
                : 'Từ chối báo cáo'}
          </strong>
          {action === 'EDIT' && (
            <div className="review-fields">
              <label className="field">
                Mã biển
                <input
                  required
                  maxLength={30}
                  value={code}
                  disabled={busy}
                  onChange={(event) => setCode(event.target.value)}
                />
              </label>
              <label className="field">
                Vĩ độ
                <input
                  required
                  type="number"
                  min="-90"
                  max="90"
                  step="any"
                  value={lat}
                  disabled={busy}
                  onChange={(event) => setLat(event.target.value)}
                />
              </label>
              <label className="field">
                Kinh độ
                <input
                  required
                  type="number"
                  min="-180"
                  max="180"
                  step="any"
                  value={lng}
                  disabled={busy}
                  onChange={(event) => setLng(event.target.value)}
                />
              </label>
            </div>
          )}
          {action === 'REMOVE' && (
            <p>Biển sẽ rời bản đồ sau khi báo cáo được chấp nhận.</p>
          )}
          {action === 'DISMISS' && (
            <label className="field">
              Lý do từ chối
              <textarea
                required
                maxLength={1000}
                value={note}
                disabled={busy}
                onChange={(event) => setNote(event.target.value)}
              />
            </label>
          )}
          <div className="modal-actions">
            <button
              type="button"
              className="button"
              disabled={busy}
              onClick={resetForm}
            >
              Quay lại
            </button>
            <button
              type="submit"
              className={'button ' + (action === 'EDIT' ? 'primary' : 'danger')}
              disabled={busy || !validDecision}
            >
              {busy ? 'Đang xử lý…' : 'Xác nhận'}
            </button>
          </div>
        </form>
      ) : (
        <div className="modal-actions">
          <button className="button" onClick={onClose}>
            Đóng
          </button>
          {report?.status === 'PENDING' && (
            <>
              <button
                className="button danger-outline"
                onClick={() => setAction('DISMISS')}
              >
                <X size={16} /> Từ chối
              </button>
              {mayAccept && (
                <>
                  <button
                    className="button danger-outline"
                    onClick={() => setAction('REMOVE')}
                  >
                    <Trash2 size={16} /> Chấp nhận và gỡ biển
                  </button>
                  <button
                    className="button primary"
                    onClick={() => setAction('EDIT')}
                  >
                    <Check size={16} /> Chấp nhận và sửa biển
                  </button>
                </>
              )}
            </>
          )}
        </div>
      )}
    </Modal>
  );
}
