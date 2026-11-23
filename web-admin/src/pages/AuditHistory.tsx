import { useEffect, useState } from 'react';
import { ArrowUpRight, Search } from 'lucide-react';
import { reviewApi } from '../api';
import {
  auditActionLabels,
  auditEntityLabels,
  auditQuery,
  formatAuditTime,
  type AuditFilters,
  type AuditLog,
} from '../auditTypes';
import {
  Empty,
  ErrorPanel,
  Loading,
  Modal,
  Pagination,
  useLoad,
} from '../components';
import { errorMessage } from '../domain';

const pageSize = 10;
const blankFilters = {
  signId: '',
  actorId: '',
  action: '',
  fromDate: '',
  toDate: '',
};
type FilterValues = typeof blankFilters;

function filtersFromHash(): FilterValues {
  const [, query] = location.hash.split('?');
  return {
    ...blankFilters,
    signId: new URLSearchParams(query).get('signId') ?? '',
  };
}

function actorLabel(event: AuditLog) {
  return (
    event.actorName ||
    (event.actorType === 'SYSTEM' ? 'Hệ thống' : event.actorId || '—')
  );
}
function actionLabel(action: string) {
  return Object.hasOwn(auditActionLabels, action)
    ? auditActionLabels[action]
    : action;
}

export function AuditHistory({ token }: { token: string }) {
  const [values, setValues] = useState(filtersFromHash);
  const [filters, setFilters] = useState(filtersFromHash);
  const [page, setPage] = useState(1);
  const [validation, setValidation] = useState('');
  const [selected, setSelected] = useState<AuditLog | null>(null);
  const query: AuditFilters = { ...filters, page, size: pageSize };
  const history = useLoad(
    () => reviewApi.auditLogs(token, query),
    [token, filters, page],
  );

  useEffect(() => {
    function onHashChange() {
      if (!location.hash.startsWith('#audit')) return;
      const next = filtersFromHash();
      setValues(next);
      setFilters(next);
      setSelected(null);
      setValidation('');
      setPage(1);
    }
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  function apply(next: FilterValues) {
    const trimmed = {
      ...next,
      signId: next.signId.trim(),
      actorId: next.actorId.trim(),
    };
    try {
      auditQuery({ ...trimmed, page: 1, size: pageSize });
      setValues(trimmed);
      setFilters(trimmed);
      setValidation('');
      setPage(1);
    } catch (error) {
      setValidation(errorMessage(error));
    }
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">KIỂM DUYỆT DỮ LIỆU</div>
          <h1>
            Lịch sử xử lý<span className="heading-dot">.</span>
          </h1>
          <p>Tra cứu thay đổi theo biển, người thực hiện hoặc thời điểm.</p>
        </div>
        <span className="count-pill">{history.data?.total ?? '…'} bản ghi</span>
      </div>
      <section className="panel">
        <form
          className="panel-toolbar audit-toolbar"
          onSubmit={(event) => {
            event.preventDefault();
            apply(values);
          }}
        >
          <label className="field">
            ID biển
            <input
              value={values.signId}
              placeholder="ID hồ sơ biển"
              onChange={(event) =>
                setValues({ ...values, signId: event.target.value })
              }
            />
          </label>
          <label className="field">
            ID người thực hiện
            <input
              value={values.actorId}
              placeholder="ID tài khoản"
              onChange={(event) =>
                setValues({ ...values, actorId: event.target.value })
              }
            />
          </label>
          <label className="field">
            Hành động
            <select
              className="standalone-select"
              value={values.action}
              onChange={(event) =>
                setValues({ ...values, action: event.target.value })
              }
            >
              <option value="">Tất cả</option>
              {Object.entries(auditActionLabels).map(([id, label]) => (
                <option key={id} value={id}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Từ ngày
            <input
              type="date"
              value={values.fromDate}
              onChange={(event) =>
                setValues({ ...values, fromDate: event.target.value })
              }
            />
          </label>
          <label className="field">
            Đến ngày
            <input
              type="date"
              value={values.toDate}
              onChange={(event) =>
                setValues({ ...values, toDate: event.target.value })
              }
            />
          </label>
          <div className="audit-filter-actions">
            <button className="button primary" type="submit">
              <Search size={16} /> Lọc
            </button>
            <button
              className="button"
              type="button"
              onClick={() => apply(blankFilters)}
            >
              Xóa bộ lọc
            </button>
          </div>
        </form>
        {validation && (
          <div className="inline-error audit-error" role="alert">
            {validation}
          </div>
        )}
        {history.loading ? (
          <Loading />
        ) : history.error ? (
          <ErrorPanel message={history.error} retry={history.refresh} />
        ) : (
          history.data && (
            <>
              {history.data.rows.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>THỜI GIAN</th>
                        <th>ĐỐI TƯỢNG</th>
                        <th>HÀNH ĐỘNG</th>
                        <th>NGƯỜI THỰC HIỆN</th>
                        <th aria-label="Chi tiết" />
                      </tr>
                    </thead>
                    <tbody>
                      {history.data.rows.map((event) => (
                        <tr key={event.id}>
                          <td className="muted nowrap">
                            {formatAuditTime(event.createdAt)}
                          </td>
                          <td>
                            <div className="audit-entity">
                              <strong>
                                {auditEntityLabels[event.entityType] ||
                                  event.entityType}
                              </strong>
                              {event.entityType === 'SIGN' ? (
                                <button
                                  className="text-button audit-id"
                                  onClick={() =>
                                    apply({
                                      ...filters,
                                      signId: event.entityId,
                                    })
                                  }
                                >
                                  {event.entityId}
                                </button>
                              ) : (
                                <small className="muted">
                                  {event.entityId}
                                </small>
                              )}
                            </div>
                          </td>
                          <td>{actionLabel(event.action)}</td>
                          <td>
                            {event.actorId ? (
                              <button
                                className="text-button"
                                title={event.actorId}
                                onClick={() =>
                                  apply({ ...filters, actorId: event.actorId! })
                                }
                              >
                                {actorLabel(event)}
                              </button>
                            ) : (
                              actorLabel(event)
                            )}
                          </td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => setSelected(event)}
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
                  title="Chưa có bản ghi phù hợp"
                  text="Hãy đổi bộ lọc hoặc thực hiện một thao tác kiểm duyệt để tạo lịch sử."
                />
              )}
              <Pagination
                data={history.data}
                size={pageSize}
                page={page}
                onChange={setPage}
              />
            </>
          )
        )}
      </section>
      {selected && (
        <AuditDetail event={selected} close={() => setSelected(null)} />
      )}
    </>
  );
}

function AuditDetail({ event, close }: { event: AuditLog; close: () => void }) {
  return (
    <Modal
      title="Chi tiết lịch sử xử lý"
      eyebrow={actionLabel(event.action)}
      onClose={close}
      wide
    >
      <dl className="details-list audit-detail">
        <div>
          <dt>Thời gian</dt>
          <dd>{formatAuditTime(event.createdAt)}</dd>
        </div>
        <div>
          <dt>Đối tượng</dt>
          <dd>
            {auditEntityLabels[event.entityType] || event.entityType} ·{' '}
            {event.entityId}
          </dd>
        </div>
        <div>
          <dt>Người thực hiện</dt>
          <dd>
            {actorLabel(event)}
            {event.actorId && event.actorId !== actorLabel(event)
              ? ' · ' + event.actorId
              : ''}
          </dd>
        </div>
        <div>
          <dt>Lý do</dt>
          <dd>{event.reason || '—'}</dd>
        </div>
        {event.requestId && (
          <div>
            <dt>Mã truy vết</dt>
            <dd>{event.requestId}</dd>
          </div>
        )}
      </dl>
      <div className="review-layout audit-values">
        <section>
          <h3>Trước khi xử lý</h3>
          <pre>
            {event.beforeValue == null
              ? '—'
              : JSON.stringify(event.beforeValue, null, 2)}
          </pre>
        </section>
        <section>
          <h3>Sau khi xử lý</h3>
          <pre>
            {event.afterValue == null
              ? '—'
              : JSON.stringify(event.afterValue, null, 2)}
          </pre>
        </section>
      </div>
      <div className="modal-actions">
        <button className="button" onClick={close}>
          Đóng
        </button>
      </div>
    </Modal>
  );
}
