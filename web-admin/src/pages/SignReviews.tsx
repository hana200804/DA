import { useState } from 'react';
import {
  ArrowUpRight,
  Check,
  Copy,
  ShieldCheck,
  Trash2,
  X,
} from 'lucide-react';
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
import { errorMessage, SessionExpiredError } from '../domain';
import { ReviewApiError } from '../reviewApi';
import {
  isReviewable,
  reviewReasonProblem,
  suggestionLabels,
  type Location,
  type ReviewDecision,
  type ReviewDetail,
  type ReviewFilters,
  type ReviewSign,
  type ReviewSuggestion,
} from '../reviewTypes';
import { ReviewMap } from '../reviewMap';
import { reviewApi, isDemo } from '../api';

const labels = {
  PENDING: 'Chờ duyệt',
  UNDER_REVIEW: 'Đang rà soát',
  APPROVED: 'Đã duyệt',
  REJECTED: 'Đã từ chối',
  REMOVED: 'Đã gỡ',
};
const score = (value: number) => (value * 100).toFixed(1) + '%';
const position = ({ lat, lng }: Location) =>
  lat.toFixed(5) + ', ' + lng.toFixed(5);

export function SignReviews({
  token,
  notify,
}: {
  token: string;
  notify: (message: string) => void;
}) {
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<ReviewFilters['status']>('');
  const [suggestion, setSuggestion] = useState<ReviewSuggestion | ''>('');
  const [selected, setSelected] = useState<string | null>(null);
  const result = useLoad(
    () => reviewApi.queue(token, { page, size: 7, status, suggestion }),
    [token, page, status, suggestion],
  );

  function done(message: string) {
    setSelected(null);
    notify(message);
    if (page > 1 && result.data?.rows.length === 1) setPage(page - 1);
    else result.refresh();
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">KIỂM DUYỆT DỮ LIỆU</div>
          <h1>
            Biển báo chờ duyệt<span className="heading-dot">.</span>
          </h1>
          <p>Xem xét các đề xuất đã gom vào từng hồ sơ biển.</p>
        </div>
        <span className="count-pill">
          {result.data?.total ?? '…'} cần xem xét
        </span>
      </div>
      <div className="notice review-notice">
        <ShieldCheck size={20} />
        <span>Đối chiếu ảnh, phân bố mã và vị trí trước khi quyết định.</span>
      </div>
      <section className="panel">
        <div className="panel-toolbar">
          <select
            className="standalone-select"
            aria-label="Trạng thái biển"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as ReviewFilters['status']);
              setPage(1);
            }}
          >
            <option value="">Tất cả trạng thái</option>
            <option value="PENDING">Chờ duyệt</option>
            <option value="UNDER_REVIEW">Đang rà soát</option>
          </select>
          <select
            className="standalone-select"
            aria-label="Gợi ý kiểm duyệt"
            value={suggestion}
            onChange={(event) => {
              setSuggestion(event.target.value as ReviewSuggestion | '');
              setPage(1);
            }}
          >
            <option value="">Tất cả gợi ý</option>
            {Object.entries(suggestionLabels).map(([id, label]) => (
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
          result.data && (
            <>
              {result.data.rows.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>BIỂN BÁO</th>
                        <th>VỊ TRÍ</th>
                        <th>ĐIỂM TIN CẬY</th>
                        <th>GỢI Ý</th>
                        <th aria-label="Thao tác" />
                      </tr>
                    </thead>
                    <tbody>
                      {result.data.rows.map((row) => (
                        <tr key={row.id}>
                          <td>
                            <button
                              className="sign-cell"
                              onClick={() => setSelected(row.id)}
                            >
                              <span className="sign-tile">
                                <SignGraphic code={row.signTypeCode} />
                              </span>
                              <span>
                                <span className="code">{row.signTypeCode}</span>
                                <strong>
                                  {row.signName || row.signTypeCode}
                                </strong>
                                <small>{labels[row.status]}</small>
                              </span>
                            </button>
                          </td>
                          <td className="muted nowrap">
                            {position(row.location)}
                          </td>
                          <td>
                            <div className="ai-score">
                              <b>{score(row.confidence)}</b>
                              <span>
                                <i style={{ width: score(row.confidence) }} />
                              </span>
                            </div>
                          </td>
                          <td>
                            {row.suggestion
                              ? suggestionLabels[row.suggestion]
                              : '—'}
                          </td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => setSelected(row.id)}
                            >
                              Xem xét <ArrowUpRight size={15} />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty
                  title="Không có hồ sơ cần xem xét"
                  text="Hãy thử chọn bộ lọc khác."
                />
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
        <ReviewDialog
          id={selected}
          token={token}
          onClose={() => setSelected(null)}
          onDone={done}
          refreshQueue={result.refresh}
        />
      )}
    </>
  );
}

function ReviewDialog({
  id,
  token,
  onClose,
  onDone,
  refreshQueue,
}: {
  id: string;
  token: string;
  onClose: () => void;
  onDone: (message: string) => void;
  refreshQueue: () => void;
}) {
  const detail = useLoad(() => reviewApi.detail(token, id), [token, id]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [staleVersion, setStaleVersion] = useState<number | null>(null);

  async function save(sign: ReviewDetail, decision: ReviewDecision) {
    if (busy || staleVersion === sign.version) return;
    setBusy(true);
    setMessage('');
    try {
      await reviewApi.decide(token, sign, decision);
      onDone(
        {
          approve: 'Đã duyệt biển báo.',
          edit: 'Đã cập nhật và xác nhận biển báo.',
          reject: 'Đã từ chối biển báo.',
          keep: 'Đã giữ biển trên bản đồ.',
          remove: 'Đã gỡ biển khỏi bản đồ.',
        }[decision.action],
      );
    } catch (error) {
      if (error instanceof SessionExpiredError) {
        window.dispatchEvent(new Event('visionmap:expired'));
      } else if (
        error instanceof ReviewApiError &&
        error.code === 'SIGN_ALREADY_PROCESSED'
      ) {
        setMessage('Biển đã được người khác xử lý. Dữ liệu đang được tải lại.');
        setStaleVersion(sign.version);
        detail.refresh();
        refreshQueue();
      } else {
        setMessage(errorMessage(error));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="Chi tiết hồ sơ biển báo"
      eyebrow={id}
      onClose={onClose}
      wide
      busy={busy}
    >
      {message && (
        <div className="inline-error" role="alert">
          {message}
        </div>
      )}
      {detail.loading ? (
        <Loading />
      ) : detail.error ? (
        <ErrorPanel message={detail.error} retry={detail.refresh} />
      ) : (
        detail.data && (
          <ReviewForm
            key={detail.data.id + ':' + detail.data.version}
            sign={detail.data}
            busy={busy}
            disabled={staleVersion === detail.data.version}
            onClose={onClose}
            onSave={save}
          />
        )
      )}
    </Modal>
  );
}

type Action = 'approve' | 'correct' | 'reject' | 'keep' | 'edit' | 'remove';
const actionLabels: Record<Action, string> = {
  approve: 'Phê duyệt',
  correct: 'Sửa rồi duyệt',
  reject: 'Từ chối',
  keep: 'Giữ biển',
  edit: 'Sửa và xác nhận',
  remove: 'Gỡ biển',
};

function ReviewForm({
  sign,
  busy,
  disabled,
  onClose,
  onSave,
}: {
  sign: ReviewDetail;
  busy: boolean;
  disabled: boolean;
  onClose: () => void;
  onSave: (sign: ReviewDetail, decision: ReviewDecision) => Promise<void>;
}) {
  const [action, setAction] = useState<Action | null>(null);
  const [photo, setPhoto] = useState(0);
  const [reason, setReason] = useState('');
  const [reasonTouched, setReasonTouched] = useState(false);
  const [code, setCode] = useState(sign.signTypeCode);
  const [lat, setLat] = useState(String(sign.location.lat));
  const [lng, setLng] = useState(String(sign.location.lng));
  const weights = Object.entries(sign.codeWeights).sort((a, b) => b[1] - a[1]);
  const editing = action === 'correct' || action === 'edit';
  const needsReason = action === 'reject' || action === 'remove';
  const reasonProblem = needsReason ? reviewReasonProblem(reason) : null;
  const coordinate = { lat: Number(lat), lng: Number(lng) };
  const validLocation =
    !!lat.trim() &&
    !!lng.trim() &&
    Number.isFinite(coordinate.lat) &&
    Number.isFinite(coordinate.lng) &&
    Math.abs(coordinate.lat) <= 90 &&
    Math.abs(coordinate.lng) <= 180;
  const changed =
    code.trim() !== sign.signTypeCode ||
    coordinate.lat !== sign.location.lat ||
    coordinate.lng !== sign.location.lng;
  const submission = sign.submissions[photo];
  const actions: Action[] =
    sign.status === 'PENDING'
      ? ['reject', 'correct', 'approve']
      : ['remove', 'edit', 'keep'];

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy || disabled || !action) return;
    if (action === 'reject' || action === 'remove') {
      if (!reasonProblem) void onSave(sign, { action, reason });
    } else if (action === 'correct' || action === 'edit') {
      if (code.trim() && validLocation && changed) {
        void onSave(sign, {
          action: action === 'correct' ? 'approve' : 'edit',
          ...(code.trim() !== sign.signTypeCode
            ? { signTypeCode: code.trim() }
            : {}),
          ...(coordinate.lat !== sign.location.lat ||
          coordinate.lng !== sign.location.lng
            ? { location: coordinate }
            : {}),
        });
      }
    } else {
      void onSave(sign, { action });
    }
  }

  return (
    <>
      <div className="review-layout">
        <div>
          {submission ? (
            <>
              <Evidence
                src={submission.cropUrl ?? ''}
                code={submission.signTypeCode}
                demo={isDemo}
              />
              <div className="photo-caption">
                <span>
                  Ảnh cắt đề xuất {photo + 1}/{sign.submissions.length}
                </span>
                <span>Độ tin cậy AI {score(submission.confidence)}</span>
              </div>
              {sign.submissions.length > 1 && (
                <div className="review-photo-actions">
                  {sign.submissions.map((row, index) => (
                    <button
                      key={row.id}
                      className="button"
                      aria-pressed={photo === index}
                      onClick={() => setPhoto(index)}
                    >
                      Ảnh {index + 1} · {row.signTypeCode}
                    </button>
                  ))}
                </div>
              )}
            </>
          ) : (
            <Empty
              title="Chưa có ảnh đề xuất"
              text="Kiểm tra hồ sơ và vị trí trên bản đồ."
            />
          )}
        </div>
        <div>
          <div className="review-sign-title">
            <SignGraphic code={sign.signTypeCode} />
            <div>
              <span className="code">{sign.signTypeCode}</span>
              <h3>{sign.signName || sign.signTypeCode}</h3>
            </div>
          </div>
          <div className="confidence">
            <span>Điểm tin cậy của biển</span>
            <b>{score(sign.confidence)}</b>
            <div>
              <i style={{ width: score(sign.confidence) }} />
            </div>
          </div>
          <dl className="details-list">
            <div>
              <dt>Trạng thái</dt>
              <dd>{labels[sign.status]}</dd>
            </div>
            <div>
              <dt>Gợi ý</dt>
              <dd>
                {sign.suggestion ? suggestionLabels[sign.suggestion] : '—'}
              </dd>
            </div>
            <div>
              <dt>Tọa độ</dt>
              <dd>{position(sign.location)}</dd>
            </div>
            <div>
              <dt>Trọng số theo mã</dt>
              <dd>
                {weights.length
                  ? weights
                      .map(
                        ([key, weight]) =>
                          key +
                          ' (' +
                          weight.toLocaleString('vi-VN', {
                            maximumFractionDigits: 2,
                          }) +
                          ')',
                      )
                      .join(' · ')
                  : '—'}
              </dd>
            </div>
          </dl>
          <ReviewMap
            center={sign.location}
            location={editing && validLocation ? coordinate : sign.location}
            nearby={sign.nearbySigns}
            onChange={
              editing && !busy
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
          <Copy size={17} /> Biển đã duyệt gần vị trí này
        </h3>
        {sign.nearbySigns.length ? (
          <div className="duplicate-list">
            {sign.nearbySigns.map((row) => (
              <div key={row.id}>
                <SignGraphic code={row.signTypeCode} />
                <div>
                  <b>{row.signTypeCode}</b>
                  <p>{position(row.location)}</p>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="notice">Chưa tìm thấy biển đã duyệt ở gần.</div>
        )}
      </section>
      {isReviewable(sign.status) && !disabled && action ? (
        <form className="confirm-box review-decision" onSubmit={submit}>
          <strong>{actionLabels[action]}</strong>
          {editing && (
            <div className="review-fields">
              <label className="field">
                Mã biển
                <input
                  required
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  disabled={busy}
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
                  onChange={(event) => setLat(event.target.value)}
                  disabled={busy}
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
                  onChange={(event) => setLng(event.target.value)}
                  disabled={busy}
                />
              </label>
            </div>
          )}
          {needsReason && (
            <>
              <label className="field">
                Lý do
                <textarea
                  required
                  value={reason}
                  aria-invalid={reasonTouched && !!reasonProblem}
                  aria-describedby={
                    reasonTouched && reasonProblem
                      ? 'review-reason-error'
                      : undefined
                  }
                  onChange={(event) => {
                    setReason(event.target.value);
                    setReasonTouched(true);
                  }}
                  onBlur={() => setReasonTouched(true)}
                  disabled={busy}
                />
              </label>
              {reasonTouched && reasonProblem && (
                <div
                  className="inline-error"
                  id="review-reason-error"
                  role="alert"
                >
                  {reasonProblem}
                </div>
              )}
            </>
          )}
          <div className="modal-actions">
            <button
              type="button"
              className="button"
              disabled={busy}
              onClick={() => {
                setAction(null);
                setReason('');
                setReasonTouched(false);
                setCode(sign.signTypeCode);
                setLat(String(sign.location.lat));
                setLng(String(sign.location.lng));
              }}
            >
              Quay lại
            </button>
            <button
              className={'button ' + (needsReason ? 'danger' : 'primary')}
              type="submit"
              disabled={
                busy ||
                !!reasonProblem ||
                (editing && (!code.trim() || !validLocation || !changed))
              }
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
          {isReviewable(sign.status) &&
            !disabled &&
            actions.map((item) => (
              <button
                key={item}
                className={
                  'button ' +
                  (item === 'reject' || item === 'remove'
                    ? 'danger-outline'
                    : item === 'correct' || item === 'edit'
                      ? ''
                      : 'primary')
                }
                onClick={() => setAction(item)}
              >
                {item === 'reject' ? (
                  <X size={16} />
                ) : item === 'remove' ? (
                  <Trash2 size={16} />
                ) : item === 'approve' || item === 'keep' ? (
                  <Check size={16} />
                ) : null}
                {actionLabels[item]}
              </button>
            ))}
        </div>
      )}
    </>
  );
}
