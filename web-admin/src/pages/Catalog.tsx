import { useState } from 'react';
import {
  ArrowUpRight,
  Check,
  ChevronRight,
  ListFilter,
  Pencil,
  Search,
} from 'lucide-react';
import { api, isDemo } from '../api';
import {
  Empty,
  ErrorPanel,
  ExportButton,
  Loading,
  Modal,
  Pagination,
  SearchBox,
  SignGraphic,
  SignImage,
  useLoad,
} from '../components';
import {
  dateLabel,
  errorMessage,
  matches,
  SessionExpiredError,
} from '../domain';
import type { Sign, SignType } from '../types';

interface Props {
  sessionId: string;
  notify: (s: string) => void;
}
export function Catalog({ sessionId, notify }: Props) {
  const [query, setQuery] = useState('');
  const [typeId, setTypeId] = useState('');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Sign | null>(null);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const types = useLoad(() => api.types(sessionId), [sessionId]);
  const result = useLoad(
    () =>
      api.signs(sessionId, { query, typeId, page, size: 8, sort: 'newest' }),
    [sessionId, query, typeId, page],
  );
  function open(sign: Sign, edit = false) {
    setSelected(sign);
    setName(sign.name);
    setEditing(edit);
    setError('');
  }
  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!selected || !name.trim()) return;
    setBusy(true);
    setError('');
    try {
      await api.editSign(sessionId, selected.id, name);
      setSelected(null);
      result.refresh();
      notify('Đã cập nhật tên biển báo.');
    } catch (e) {
      setError(errorMessage(e));
      if (e instanceof SessionExpiredError)
        window.dispatchEvent(new Event('visionmap:expired'));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">DỮ LIỆU BIỂN BÁO</div>
          <h1>
            Danh mục biển báo<span className="heading-dot">.</span>
          </h1>
          <p>Tra cứu và cập nhật thông tin biển báo trong hệ thống.</p>
        </div>
        <ExportButton
          rows={result.data?.rows || []}
          headers={['Mã biển', 'Tên biển báo', 'Loại', 'Mô tả']}
          values={(s: Sign) => [s.code, s.name, s.typeId, s.description]}
          filename="danh-muc-bien-bao.csv"
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
            placeholder={
              isDemo ? 'Tìm tên hoặc mã biển báo…' : 'Tìm theo tên biển báo…'
            }
          />
          <label className="select-control">
            <ListFilter size={16} />
            <select
              aria-label="Lọc loại biển báo"
              value={typeId}
              onChange={(e) => {
                setTypeId(e.target.value);
                setPage(1);
              }}
            >
              <option value="">Tất cả loại biển</option>
              {types.data?.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        {types.error && (
          <div className="inline-error" role="alert">
            Chưa tải được bộ lọc: {types.error}{' '}
            <button className="text-button" onClick={types.refresh}>
              Thử lại
            </button>
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
                <Empty />
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>BIỂN BÁO</th>
                        <th>PHÂN LOẠI</th>
                        <th>MÔ TẢ</th>
                        <th>CẬP NHẬT</th>
                        <th aria-label="Thao tác" />
                      </tr>
                    </thead>
                    <tbody>
                      {result.data.rows.map((sign) => (
                        <tr key={sign.id}>
                          <td>
                            <button
                              className="sign-cell"
                              onClick={() => open(sign)}
                            >
                              <span className="sign-tile">
                                <SignImage
                                  src={sign.photoUrl}
                                  code={sign.code}
                                />
                              </span>
                              <span>
                                <span className="code">{sign.code}</span>
                                <strong>{sign.name}</strong>
                              </span>
                            </button>
                          </td>
                          <td>
                            <span
                              className={`type-chip type-${sign.typeId[0]}`}
                            >
                              {types.data?.find((t) => t.id === sign.typeId)
                                ?.name || sign.typeId}
                            </span>
                          </td>
                          <td className="description-cell">
                            {sign.description || 'Chưa có mô tả'}
                          </td>
                          <td className="muted nowrap">
                            {dateLabel(sign.updatedAt || sign.createdAt)}
                          </td>
                          <td>
                            <button
                              className="icon-button"
                              aria-label={`Chỉnh sửa ${sign.code}`}
                              onClick={() => open(sign, true)}
                            >
                              <Pencil size={16} />
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
      <p className="footnote">
        {isDemo ? 'Các hình minh họa dùng để trình bày giao diện. ' : ''}Thông
        tin biển báo được quản lý tập trung và đồng bộ qua hệ thống.
      </p>
      {selected && (
        <Modal
          title={editing ? 'Chỉnh sửa biển báo' : 'Thông tin biển báo'}
          eyebrow={selected.code}
          onClose={() => setSelected(null)}
          busy={busy}
        >
          <div className="sign-detail-hero">
            <SignImage src={selected.photoUrl} code={selected.code} large />
            <span className="type-chip">
              {types.data?.find((t) => t.id === selected.typeId)?.name ||
                selected.typeId}
            </span>
          </div>
          <form onSubmit={save}>
            <label className="field">
              Tên biển báo
              {editing ? (
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  maxLength={255}
                  disabled={busy}
                />
              ) : (
                <strong>{selected.name}</strong>
              )}
            </label>
            <div className="field">
              <span>Mô tả</span>
              <p>{selected.description || 'Chưa có mô tả.'}</p>
            </div>
            <div className="detail-facts">
              <div>
                <span>Mã biển báo</span>
                <b>{selected.code}</b>
              </div>
              <div>
                <span>Ngày tạo</span>
                <b>{dateLabel(selected.createdAt)}</b>
              </div>
            </div>
            {editing && (
              <div className="notice">
                Bạn có thể cập nhật tên hiển thị của biển báo.
              </div>
            )}
            {error && (
              <div className="inline-error" role="alert">
                {error}
              </div>
            )}
            <div className="modal-actions">
              <button
                className="button"
                type="button"
                disabled={busy}
                onClick={() => setSelected(null)}
              >
                Đóng
              </button>
              {editing ? (
                <button
                  className="button primary"
                  disabled={
                    busy || !name.trim() || name.trim() === selected.name
                  }
                  type="submit"
                >
                  <Check size={16} />
                  {busy ? 'Đang lưu…' : 'Lưu thay đổi'}
                </button>
              ) : (
                <button
                  type="button"
                  className="button primary"
                  onClick={() => setEditing(true)}
                >
                  <Pencil size={16} />
                  Chỉnh sửa tên
                </button>
              )}
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}

const typeDescriptions: Record<string, string> = {
  P: 'Các điều cấm cần tuân thủ',
  W: 'Nhận biết nguy hiểm phía trước',
  R: 'Hướng dẫn hành động bắt buộc',
  I: 'Thông tin và hướng di chuyển',
  S: 'Thông tin bổ sung cho biển chính',
};
export function Types({
  sessionId,
  onBrowse,
}: {
  sessionId: string;
  onBrowse: () => void;
}) {
  const result = useLoad(() => api.types(sessionId), [sessionId]);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<SignType | null>(null);
  const rows =
    result.data?.filter((t) => matches(query, t.id, t.name, t.description)) ||
    [];
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">PHÂN LOẠI</div>
          <h1>
            Các loại biển báo<span className="heading-dot">.</span>
          </h1>
          <p>Hệ thống phân nhóm giúp tra cứu biển báo dễ dàng hơn.</p>
        </div>
        <span className="subtle-tag">
          {result.data?.length || 0} nhóm biển báo
        </span>
      </div>
      <div className="standalone-search">
        <SearchBox
          value={query}
          onChange={setQuery}
          placeholder="Tìm loại biển báo…"
        />
      </div>
      {result.loading ? (
        <Loading />
      ) : result.error ? (
        <ErrorPanel message={result.error} retry={result.refresh} />
      ) : !rows.length ? (
        <Empty />
      ) : (
        <div className="type-grid">
          {rows.map((t, i) => (
            <button
              className={`type-card family-${t.id[0]}`}
              key={t.id}
              onClick={() => setSelected(t)}
            >
              <div className="type-card-top">
                <span>NHÓM {String(i + 1).padStart(2, '0')}</span>
                <ArrowUpRight size={20} />
              </div>
              <div className="type-art">
                <SignGraphic type={t.id[0]} large />
              </div>
              <h2>{t.name}</h2>
              <p>
                {t.description ||
                  typeDescriptions[t.id[0]] ||
                  'Thông tin phân loại biển báo giao thông.'}
              </p>
              <div className="type-card-footer">
                <span>Xem thông tin</span>
                <ChevronRight size={17} />
              </div>
            </button>
          ))}
        </div>
      )}
      <div className="tip-card">
        <Search size={22} />
        <div>
          <strong>Bạn đang tìm một biển báo cụ thể?</strong>
          <p>Đến danh mục để tra cứu mã biển, tên và thông tin chi tiết.</p>
        </div>
        <button className="button" onClick={onBrowse}>
          Mở danh mục <ArrowUpRight size={15} />
        </button>
      </div>
      {selected && (
        <Modal
          title={selected.name}
          eyebrow="LOẠI BIỂN BÁO"
          onClose={() => setSelected(null)}
        >
          <div className="sign-detail-hero">
            <SignGraphic type={selected.id[0]} large />
          </div>
          <p>
            {selected.description ||
              typeDescriptions[selected.id[0]] ||
              'Chưa có mô tả.'}
          </p>
          <div className="detail-facts">
            <div>
              <span>Mã loại</span>
              <b>{selected.id}</b>
            </div>
          </div>
          <div className="modal-actions">
            <button
              className="button primary"
              onClick={() => {
                setSelected(null);
                onBrowse();
              }}
            >
              Tra cứu biển báo <ArrowUpRight size={16} />
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
