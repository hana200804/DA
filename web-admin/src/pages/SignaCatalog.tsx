import { useState } from 'react';
import {
  ArrowUpRight,
  ChevronRight,
  ListFilter,
  Pencil,
  Plus,
  Search,
} from 'lucide-react';
import { catalogApi } from '../api';
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
  normalizeSignTypeCode,
  validateCatalogIcon,
  type CatalogCategory,
  type CatalogSignType,
  type SignTypeInput,
} from '../catalogTypes';
import {
  dateLabel,
  errorMessage,
  matches,
  paginate,
  SessionExpiredError,
} from '../domain';

type Props = {
  token: string;
  canManage: boolean;
  notify: (message: string) => void;
};
const iconFamilies: Record<string, string> = {
  PROHIBITORY: 'P',
  WARNING: 'W',
  MANDATORY: 'R',
  AUXILIARY: 'S',
  OTHER: 'I',
};

function catalogError(error: unknown) {
  if (error instanceof SessionExpiredError)
    window.dispatchEvent(new Event('visionmap:expired'));
  return errorMessage(error);
}

function iconSource(url?: string | null) {
  return url?.startsWith('/icons/')
    ? new URL(url, location.origin).href
    : (url ?? undefined);
}

export function SignaCatalog({ token, canManage, notify }: Props) {
  const categories = useLoad(() => catalogApi.catalogCategories(), [token]);
  const result = useLoad(
    () => catalogApi.catalogTypes(canManage ? token : undefined),
    [token, canManage],
  );
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [selection, setSelection] = useState<{
    type?: CatalogSignType;
    editing: boolean;
  } | null>(null);
  const rows = (result.data ?? []).filter(
    (type) =>
      (!category || category === type.categoryCode) &&
      (!status || (status === 'active') === type.active) &&
      matches(query, type.code, type.name, type.description),
  );
  const paged = paginate(rows, page, 8);
  const groupName = (code: string) =>
    categories.data?.find((row) => row.code === code)?.name || code;

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">DỮ LIỆU BIỂN BÁO</div>
          <h1>
            Danh mục biển báo<span className="heading-dot">.</span>
          </h1>
          <p>Tra cứu và cập nhật thông tin loại biển trong hệ thống.</p>
        </div>
        <div className="catalog-actions">
          <ExportButton
            rows={paged.rows}
            headers={['Mã biển', 'Tên biển báo', 'Nhóm', 'Ý nghĩa']}
            values={(type: CatalogSignType) => [
              type.code,
              type.name,
              groupName(type.categoryCode),
              type.description,
            ]}
            filename="danh-muc-bien-bao.csv"
          />
          {canManage && (
            <button
              className="button primary"
              onClick={() => setSelection({ editing: true })}
            >
              <Plus size={16} /> Thêm loại biển
            </button>
          )}
        </div>
      </div>
      <section className="panel">
        <div className="panel-toolbar catalog-toolbar">
          <SearchBox
            value={query}
            onChange={(value) => {
              setQuery(value);
              setPage(1);
            }}
            placeholder="Tìm tên hoặc mã biển báo…"
          />
          <label className="select-control">
            <ListFilter size={16} />
            <select
              aria-label="Lọc nhóm biển"
              value={category}
              onChange={(event) => {
                setCategory(event.target.value);
                setPage(1);
              }}
            >
              <option value="">Tất cả nhóm biển</option>
              {categories.data?.map((row) => (
                <option key={row.code} value={row.code}>
                  {row.name}
                </option>
              ))}
            </select>
          </label>
          <select
            className="standalone-select"
            aria-label="Trạng thái loại biển"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(1);
            }}
          >
            <option value="">Mọi trạng thái</option>
            <option value="active">Đang dùng</option>
            <option value="inactive">Ngừng sử dụng</option>
          </select>
        </div>
        {categories.error && (
          <ErrorPanel message={categories.error} retry={categories.refresh} />
        )}
        {result.loading ? (
          <Loading />
        ) : result.error ? (
          <ErrorPanel message={result.error} retry={result.refresh} />
        ) : (
          <>
            {paged.rows.length ? (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>BIỂN BÁO</th>
                      <th>PHÂN LOẠI</th>
                      <th>Ý NGHĨA</th>
                      <th>TRẠNG THÁI</th>
                      <th aria-label="Thao tác" />
                    </tr>
                  </thead>
                  <tbody>
                    {paged.rows.map((type) => (
                      <tr key={type.code}>
                        <td>
                          <button
                            className="sign-cell"
                            onClick={() =>
                              setSelection({ type, editing: false })
                            }
                          >
                            <span className="sign-tile">
                              <SignImage
                                code={type.code}
                                src={iconSource(type.iconUrl)}
                              />
                            </span>
                            <span>
                              <span className="code">{type.code}</span>
                              <strong>{type.name}</strong>
                              {!type.recognizable && (
                                <small>Chỉ nhập tay</small>
                              )}
                            </span>
                          </button>
                        </td>
                        <td>
                          <span
                            className={
                              'type-chip type-' +
                              (iconFamilies[type.categoryCode] ?? 'I')
                            }
                          >
                            {groupName(type.categoryCode)}
                          </span>
                        </td>
                        <td className="description-cell">
                          {type.description || '—'}
                        </td>
                        <td>{type.active ? 'Đang dùng' : 'Ngừng sử dụng'}</td>
                        <td>
                          {canManage && (
                            <button
                              className="icon-button"
                              aria-label={'Chỉnh sửa ' + type.code}
                              onClick={() =>
                                setSelection({ type, editing: true })
                              }
                            >
                              <Pencil size={16} />
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty
                title="Không có loại biển phù hợp"
                text="Bạn có thể đổi từ khóa hoặc bộ lọc."
              />
            )}
            <Pagination
              data={paged}
              page={Math.min(page, paged.pages)}
              size={8}
              onChange={setPage}
            />
          </>
        )}
      </section>
      {selection && (
        <SignTypeDialog
          token={token}
          canManage={canManage}
          type={selection.type}
          editing={selection.editing}
          categories={categories.data ?? []}
          close={() => setSelection(null)}
          onRefresh={result.refresh}
          onSaved={() => {
            setSelection(null);
            result.refresh();
            notify('Đã lưu loại biển.');
          }}
        />
      )}
    </>
  );
}

function SignTypeDialog({
  token,
  type,
  editing,
  canManage,
  categories,
  close,
  onRefresh,
  onSaved,
}: {
  token: string;
  type?: CatalogSignType;
  editing: boolean;
  canManage: boolean;
  categories: CatalogCategory[];
  close: () => void;
  onRefresh: () => void;
  onSaved: () => void;
}) {
  const [edit, setEdit] = useState(editing);
  const [code, setCode] = useState(type?.code ?? '');
  const [categoryCode, setCategory] = useState(type?.categoryCode ?? '');
  const [name, setName] = useState(type?.name ?? '');
  const [description, setDescription] = useState(type?.description ?? '');
  const [speed, setSpeed] = useState(String(type?.speedKmh ?? ''));
  const [active, setActive] = useState(type?.active ?? true);
  const [file, setFile] = useState<File | null>(null);
  const [savedCode, setSavedCode] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const values: SignTypeInput = {
    categoryCode,
    name,
    description,
    speedKmh: speed.trim() ? Number(speed) : null,
    active,
  };
  const changed =
    !type ||
    categoryCode !== type.categoryCode ||
    name.trim() !== type.name ||
    description.trim() !== (type.description ?? '') ||
    values.speedKmh !== (type.speedKmh ?? null) ||
    active !== type.active;

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (busy || !canManage || (!changed && !file && !savedCode)) return;
    setBusy(true);
    setError('');
    let saved = savedCode;
    try {
      const key = saved ?? normalizeSignTypeCode(code);
      if (file) validateCatalogIcon(file);
      if (!saved && changed) {
        await catalogApi.saveSignType(token, key, values, !type);
        saved = key;
        setSavedCode(key);
      }
      if (file) await catalogApi.uploadCatalogIcon(token, key, file);
      onSaved();
    } catch (error) {
      const message = catalogError(error);
      setError(
        saved
          ? 'Thông tin loại biển đã lưu. Ảnh chưa tải lên. ' + message
          : message,
      );
      if (saved) onRefresh();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={type?.name ?? 'Thêm loại biển'}
      eyebrow={type?.code ?? 'LOẠI BIỂN MỚI'}
      onClose={close}
      wide
      busy={busy}
    >
      {edit ? (
        <form onSubmit={save}>
          <p className="muted">
            {type?.recognizable
              ? 'Có trong danh mục nhãn nhận diện.'
              : 'Chỉ nhập tay · mô hình không nhận diện loại biển này.'}
          </p>
          <fieldset className="catalog-fields" disabled={busy || !!savedCode}>
            <label className="field">
              Mã biển
              <input
                required
                maxLength={30}
                value={code}
                readOnly={!!type}
                onChange={(event) => setCode(event.target.value)}
              />
            </label>
            <label className="field">
              Nhóm biển
              <select
                className="standalone-select"
                required
                value={categoryCode}
                onChange={(event) => setCategory(event.target.value)}
              >
                <option value="">Chọn nhóm biển</option>
                {categories.map((row) => (
                  <option key={row.code} value={row.code}>
                    {row.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              Tên loại biển
              <input
                required
                maxLength={200}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            <label className="field">
              Tốc độ (km/h)
              <input
                type="number"
                min="1"
                max="32767"
                step="1"
                value={speed}
                placeholder="Chỉ nhập cho biển tốc độ"
                onChange={(event) => setSpeed(event.target.value)}
              />
            </label>
            <label className="field catalog-full">
              Ý nghĩa
              <textarea
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>
            <label className="field">
              Trạng thái
              <select
                className="standalone-select"
                value={active ? 'active' : 'inactive'}
                onChange={(event) => setActive(event.target.value === 'active')}
              >
                <option value="active">Đang dùng</option>
                <option value="inactive">Ngừng sử dụng</option>
              </select>
            </label>
          </fieldset>
          <label className="field catalog-icon-field">
            Ảnh minh họa
            <input
              type="file"
              accept="image/png,image/jpeg,image/webp"
              disabled={busy}
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            />
          </label>
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
              onClick={close}
            >
              {savedCode ? 'Đóng' : 'Hủy'}
            </button>
            <button
              className="button primary"
              type="submit"
              disabled={busy || (savedCode ? !file : !changed && !file)}
            >
              {busy
                ? 'Đang lưu…'
                : savedCode
                  ? 'Thử tải ảnh lại'
                  : 'Lưu loại biển'}
            </button>
          </div>
        </form>
      ) : (
        type && (
          <>
            <div className="sign-detail-hero">
              <SignImage
                code={type.code}
                src={iconSource(type.iconUrl)}
                large
              />
            </div>
            <p>{type.description || 'Chưa có ý nghĩa.'}</p>
            <dl className="details-list">
              <div>
                <dt>Nhóm biển</dt>
                <dd>
                  {categories.find((group) => group.code === type.categoryCode)
                    ?.name || type.categoryCode}
                </dd>
              </div>
              <div>
                <dt>Nhận diện</dt>
                <dd>
                  {type.recognizable
                    ? 'Có trong danh mục nhãn'
                    : 'Chỉ nhập tay'}
                </dd>
              </div>
              <div>
                <dt>Tốc độ</dt>
                <dd>{type.speedKmh != null ? type.speedKmh + ' km/h' : '—'}</dd>
              </div>
              <div>
                <dt>Trạng thái</dt>
                <dd>{type.active ? 'Đang dùng' : 'Ngừng sử dụng'}</dd>
              </div>
              {type.updatedAt && (
                <div>
                  <dt>Cập nhật</dt>
                  <dd>{dateLabel(type.updatedAt)}</dd>
                </div>
              )}
            </dl>
            <div className="modal-actions">
              <button className="button" onClick={close}>
                Đóng
              </button>
              {canManage && (
                <button
                  className="button primary"
                  onClick={() => setEdit(true)}
                >
                  <Pencil size={16} /> Chỉnh sửa
                </button>
              )}
            </div>
          </>
        )
      )}
    </Modal>
  );
}

export function SignaCategories({
  token,
  canManage,
  notify,
  onBrowse,
}: Props & { onBrowse: () => void }) {
  const result = useLoad(() => catalogApi.catalogCategories(), [token]);
  const [query, setQuery] = useState('');
  const [selection, setSelection] = useState<{
    category?: CatalogCategory;
    editing: boolean;
  } | null>(null);
  const rows =
    result.data?.filter((row) =>
      matches(query, row.code, row.name, row.description),
    ) ?? [];
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">PHÂN LOẠI</div>
          <h1>
            Các nhóm biển báo<span className="heading-dot">.</span>
          </h1>
          <p>Tra cứu các nhóm dùng để phân loại biển báo.</p>
        </div>
        <div className="catalog-actions">
          <span className="subtle-tag">
            {result.data?.length ?? 0} nhóm biển
          </span>
          {canManage && (
            <button
              className="button primary"
              onClick={() => setSelection({ editing: true })}
            >
              <Plus size={16} /> Thêm nhóm biển
            </button>
          )}
        </div>
      </div>
      <div className="standalone-search">
        <SearchBox
          value={query}
          onChange={setQuery}
          placeholder="Tìm nhóm biển báo…"
        />
      </div>
      {result.loading ? (
        <Loading />
      ) : result.error ? (
        <ErrorPanel message={result.error} retry={result.refresh} />
      ) : rows.length ? (
        <div className="type-grid">
          {rows.map((row) => {
            const family = iconFamilies[row.code] ?? 'I';
            return (
              <button
                key={row.code}
                className={'type-card family-' + family}
                onClick={() => setSelection({ category: row, editing: false })}
              >
                <div className="type-card-top">
                  <span>{row.code}</span>
                  <ArrowUpRight size={20} />
                </div>
                <div className="type-art">
                  <SignGraphic type={family} large />
                </div>
                <h2>{row.name}</h2>
                <p>{row.description || 'Chưa có mô tả.'}</p>
                <div className="type-card-footer">
                  <span>Xem thông tin</span>
                  <ChevronRight size={17} />
                </div>
              </button>
            );
          })}
        </div>
      ) : (
        <Empty
          title="Không có nhóm biển phù hợp"
          text="Hãy thử một từ khóa khác."
        />
      )}
      <div className="tip-card">
        <Search size={22} />
        <div>
          <strong>Tra cứu một biển cụ thể</strong>
          <p>Xem mã biển, tên và ý nghĩa trong danh mục.</p>
        </div>
        <button className="button" onClick={onBrowse}>
          Mở danh mục <ArrowUpRight size={15} />
        </button>
      </div>
      {selection && (
        <CategoryDialog
          token={token}
          category={selection.category}
          editing={selection.editing}
          canManage={canManage}
          close={() => setSelection(null)}
          onSaved={() => {
            setSelection(null);
            result.refresh();
            notify('Đã lưu nhóm biển.');
          }}
        />
      )}
    </>
  );
}

function CategoryDialog({
  token,
  category,
  editing,
  canManage,
  close,
  onSaved,
}: {
  token: string;
  category?: CatalogCategory;
  editing: boolean;
  canManage: boolean;
  close: () => void;
  onSaved: () => void;
}) {
  const [edit, setEdit] = useState(editing);
  const [code, setCode] = useState(category?.code ?? '');
  const [name, setName] = useState(category?.name ?? '');
  const [description, setDescription] = useState(category?.description ?? '');
  const [sort, setSort] = useState(String(category?.sortOrder ?? 0));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!canManage || busy || !sort.trim()) return;
    setError('');
    setBusy(true);
    try {
      await catalogApi.saveCategory(
        token,
        code,
        { name, description, sortOrder: Number(sort) },
        !category,
      );
      onSaved();
    } catch (error) {
      setError(catalogError(error));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title={category?.name ?? 'Thêm nhóm biển'}
      eyebrow={category?.code ?? 'NHÓM BIỂN MỚI'}
      onClose={close}
      busy={busy}
    >
      {edit ? (
        <form onSubmit={save}>
          <fieldset className="catalog-fields" disabled={busy}>
            <label className="field">
              Mã nhóm
              <input
                required
                readOnly={!!category}
                maxLength={20}
                value={code}
                onChange={(event) => setCode(event.target.value)}
              />
            </label>
            <label className="field">
              Thứ tự hiển thị
              <input
                required
                type="number"
                min="-32768"
                max="32767"
                value={sort}
                onChange={(event) => setSort(event.target.value)}
              />
            </label>
            <label className="field catalog-full">
              Tên nhóm
              <input
                required
                maxLength={100}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            <label className="field catalog-full">
              Mô tả
              <textarea
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>
          </fieldset>
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
              onClick={close}
            >
              Hủy
            </button>
            <button className="button primary" type="submit" disabled={busy}>
              {busy ? 'Đang lưu…' : 'Lưu nhóm biển'}
            </button>
          </div>
        </form>
      ) : (
        category && (
          <>
            <div className="sign-detail-hero">
              <SignGraphic type={iconFamilies[category.code] ?? 'I'} large />
            </div>
            <p>{category.description || 'Chưa có mô tả.'}</p>
            <dl className="details-list">
              <div>
                <dt>Mã nhóm</dt>
                <dd>{category.code}</dd>
              </div>
              <div>
                <dt>Thứ tự hiển thị</dt>
                <dd>{category.sortOrder}</dd>
              </div>
            </dl>
            <div className="modal-actions">
              <button className="button" onClick={close}>
                Đóng
              </button>
              {canManage && (
                <button
                  className="button primary"
                  onClick={() => setEdit(true)}
                >
                  <Pencil size={16} /> Chỉnh sửa nhóm
                </button>
              )}
            </div>
          </>
        )
      )}
    </Modal>
  );
}
