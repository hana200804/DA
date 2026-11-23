import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import {
  ArrowDownToLine,
  ArrowLeft,
  ArrowRight,
  Check,
  CircleAlert,
  LoaderCircle,
  Search,
  Signpost,
  X,
} from 'lucide-react';
import type { Page, Status } from './types';
import {
  boundingBox,
  csvCell,
  errorMessage,
  normalizedImage,
  SessionExpiredError,
  statusLabels,
} from './domain';

export function useLoad<T>(loader: () => Promise<T>, keys: unknown[]) {
  const [state, setState] = useState<{
    data: T | null;
    loading: boolean;
    error: string;
  }>({ data: null, loading: true, error: '' });
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    setState({ data: null, loading: true, error: '' });
    loader()
      .then((data) => {
        if (active) setState({ data, loading: false, error: '' });
      })
      .catch((error) => {
        if (active) {
          setState({ data: null, loading: false, error: errorMessage(error) });
          if (error instanceof SessionExpiredError)
            window.dispatchEvent(new Event('visionmap:expired'));
        }
      });
    return () => {
      active = false;
    };
  }, [...keys, revision]);
  return { ...state, refresh: () => setRevision((n) => n + 1) };
}
export function Loading() {
  return (
    <div className="state-panel" role="status">
      <LoaderCircle className="spin" size={25} />
      <strong>Đang tải dữ liệu…</strong>
      <span>Vui lòng chờ trong giây lát.</span>
    </div>
  );
}
export function ErrorPanel({
  message,
  retry,
}: {
  message: string;
  retry: () => void;
}) {
  return (
    <div className="state-panel error" role="alert">
      <CircleAlert size={28} />
      <strong>Chưa thể tải dữ liệu</strong>
      <span>{message}</span>
      <button className="button" onClick={retry}>
        Thử lại
      </button>
    </div>
  );
}
export function Empty({
  title = 'Không tìm thấy kết quả',
  text = 'Thử thay đổi từ khóa hoặc bộ lọc để tìm kiếm lại.',
}: {
  title?: string;
  text?: string;
}) {
  return (
    <div className="state-panel">
      <div className="empty-icon">
        <Search size={26} />
      </div>
      <strong>{title}</strong>
      <span>{text}</span>
    </div>
  );
}
export function Badge({ status }: { status: Status }) {
  return (
    <span className={`badge ${status?.toLowerCase()}`}>
      <i />
      {statusLabels[status] || status}
    </span>
  );
}
export function Pagination({
  data,
  page,
  size,
  onChange,
}: {
  data: Page<unknown>;
  page: number;
  size: number;
  onChange: (p: number) => void;
}) {
  return (
    <div className="pagination">
      <span>
        {data.total
          ? `${(page - 1) * size + 1}–${Math.min(page * size, data.total)}`
          : '0'}{' '}
        trên {data.total} kết quả
      </span>
      <div>
        <button
          className="icon-button"
          aria-label="Trang trước"
          disabled={page <= 1}
          onClick={() => onChange(page - 1)}
        >
          <ArrowLeft size={16} />
        </button>
        <span>
          Trang <b>{page}</b> / {data.pages}
        </span>
        <button
          className="icon-button"
          aria-label="Trang sau"
          disabled={page >= data.pages}
          onClick={() => onChange(page + 1)}
        >
          <ArrowRight size={16} />
        </button>
      </div>
    </div>
  );
}
export function SearchBox({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
}) {
  return (
    <div className="search-box">
      <Search size={18} />
      <input
        aria-label={placeholder}
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      {value && (
        <button
          className="icon-button"
          type="button"
          aria-label="Xóa tìm kiếm"
          onClick={() => onChange('')}
        >
          <X size={15} />
        </button>
      )}
    </div>
  );
}
export function Modal({
  title,
  eyebrow,
  children,
  onClose,
  wide = false,
  busy = false,
}: {
  title: string;
  eyebrow?: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
  busy?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  const locked = useRef(busy);
  const id = useId();
  close.current = onClose;
  locked.current = busy;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    ref.current?.focus();
    function keydown(e: KeyboardEvent) {
      if (e.key === 'Escape' && !locked.current) close.current();
      if (e.key === 'Tab') {
        const focusable = ref.current?.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), select:not(:disabled), a[href], textarea:not(:disabled)',
        );
        if (!focusable?.length) {
          e.preventDefault();
          return;
        }
        const first = focusable[0],
          last = focusable[focusable.length - 1];
        if (
          e.shiftKey &&
          (document.activeElement === first ||
            document.activeElement === ref.current)
        ) {
          e.preventDefault();
          last.focus();
        } else if (
          !e.shiftKey &&
          (document.activeElement === last ||
            document.activeElement === ref.current)
        ) {
          e.preventDefault();
          first.focus();
        }
      }
    }
    document.addEventListener('keydown', keydown);
    return () => {
      document.body.style.overflow = overflow;
      document.removeEventListener('keydown', keydown);
      previous?.focus();
    };
  }, []);
  return (
    <div
      className="modal-shade"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !busy) onClose();
      }}
    >
      <div
        className={`modal ${wide ? 'wide' : ''}`}
        ref={ref}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby={id}
      >
        <header>
          <div>
            {eyebrow && <span className="eyebrow">{eyebrow}</span>}
            <h2 id={id}>{title}</h2>
          </div>
          <button
            className="icon-button"
            aria-label="Đóng cửa sổ"
            disabled={busy}
            onClick={onClose}
          >
            <X size={22} />
          </button>
        </header>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}
export function SignGraphic({
  code = '',
  type = '',
  large = false,
}: {
  code?: string;
  type?: string;
  large?: boolean;
}) {
  const family = type || code[0];
  return (
    <span
      className={`sign-graphic ${large ? 'large' : ''}`}
      aria-label={`Hình minh họa ${code}`}
    >
      {code === 'P.127' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <circle
            cx="40"
            cy="40"
            r="33"
            fill="white"
            stroke="#e14f4e"
            strokeWidth="7"
          />
          <text
            x="40"
            y="51"
            fontSize="31"
            fontWeight="700"
            textAnchor="middle"
            fill="#233338"
          >
            50
          </text>
        </svg>
      ) : code === 'P.102' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <circle cx="40" cy="40" r="35" fill="#e14f4e" />
          <rect x="15" y="34" width="50" height="12" rx="2" fill="white" />
        </svg>
      ) : family === 'W' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <path
            d="M40 6 76 70H4Z"
            stroke="#e14f4e"
            strokeWidth="6"
            strokeLinejoin="round"
            fill="#ffe6a0"
          />
          <path
            d="M40 29v20m0 9v1"
            stroke="#293735"
            strokeWidth="6"
            strokeLinecap="round"
          />
        </svg>
      ) : family === 'R' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <circle cx="40" cy="40" r="35" fill="#4286c7" />
          <path
            d="M40 59V24m-12 13 12-13 12 13"
            fill="none"
            stroke="white"
            strokeWidth="7"
            strokeLinejoin="round"
          />
        </svg>
      ) : code === 'I.434a' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <rect x="8" y="5" width="64" height="70" rx="5" fill="#4286c7" />
          <rect x="19" y="14" width="42" height="48" rx="6" fill="white" />
          <rect x="24" y="21" width="32" height="21" rx="2" fill="#4286c7" />
          <circle cx="28" cy="52" r="4" fill="#4286c7" />
          <circle cx="52" cy="52" r="4" fill="#4286c7" />
          <path d="M25 62v5m30-5v5" stroke="white" strokeWidth="6" />
        </svg>
      ) : code === 'I.423a' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <rect x="6" y="5" width="68" height="70" rx="5" fill="#4286c7" />
          <path d="M40 12 70 65H10Z" fill="white" />
          <circle cx="42" cy="32" r="4" fill="#233338" />
          <path
            d="m39 38-5 12 11 10m-7-15 12 3m-14 1-9 11"
            fill="none"
            stroke="#233338"
            strokeWidth="4"
            strokeLinecap="round"
          />
        </svg>
      ) : family === 'I' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <rect x="8" y="5" width="64" height="70" rx="7" fill="#4286c7" />
          <path
            d="M30 58V23h14a11 11 0 0 1 0 22H30"
            fill="none"
            stroke="white"
            strokeWidth="7"
          />
        </svg>
      ) : family === 'P' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <circle
            cx="40"
            cy="40"
            r="33"
            fill="white"
            stroke="#e14f4e"
            strokeWidth="7"
          />
          <path d="m17 17 46 46" stroke="#e14f4e" strokeWidth="7" />
        </svg>
      ) : family === 'S' ? (
        <svg viewBox="0 0 80 80" aria-hidden="true">
          <rect
            x="5"
            y="18"
            width="70"
            height="44"
            rx="3"
            fill="white"
            stroke="#293735"
            strokeWidth="3"
          />
          <path
            d="M17 40h46m-39-7-7 7 7 7m32-14 7 7-7 7"
            fill="none"
            stroke="#293735"
            strokeWidth="3"
          />
        </svg>
      ) : (
        <Signpost size={large ? 70 : 30} />
      )}
    </span>
  );
}
export function SignImage({
  src,
  code,
  large = false,
}: {
  src?: string;
  code: string;
  large?: boolean;
}) {
  const [failed, setFailed] = useState(false);
  const url = normalizedImage(src);
  useEffect(() => setFailed(false), [src]);
  return url && !failed ? (
    <img
      className={`sign-image ${large ? 'large' : ''}`}
      src={url}
      alt={`Biển báo ${code}`}
      onError={() => setFailed(true)}
    />
  ) : (
    <SignGraphic code={code} large={large} />
  );
}
export function Evidence({
  src,
  bbox,
  code,
  demo,
}: {
  src: string;
  bbox?: string | null;
  code: string;
  demo: boolean;
}) {
  const [failed, setFailed] = useState(false);
  const url = normalizedImage(src);
  const box = boundingBox(bbox);
  useEffect(() => setFailed(false), [src]);
  if (!url || failed)
    return (
      <div className="evidence-empty">
        <div className="evidence-grid" />
        <SignGraphic code={code} large />
        <span>
          {demo ? 'Ảnh minh họa, không phải ảnh chụp' : 'Chưa có ảnh hợp lệ'}
        </span>
        {!demo && <small>Không thể hiển thị ảnh của bản ghi này</small>}
      </div>
    );
  return (
    <div className="evidence">
      <div>
        <img
          src={url}
          alt={`Ảnh thực tế biển ${code}`}
          onError={() => setFailed(true)}
        />
        {box && (
          <span
            className="bbox"
            style={{
              left: `${box.left}%`,
              top: `${box.top}%`,
              width: `${box.width}%`,
              height: `${box.height}%`,
            }}
          />
        )}
      </div>
      <a href={url} target="_blank" rel="noreferrer">
        Mở ảnh gốc ↗
      </a>
    </div>
  );
}
export function ExportButton<T>({
  rows,
  headers,
  values,
  filename,
}: {
  rows: T[];
  headers: string[];
  values: (row: T) => unknown[];
  filename: string;
}) {
  function download() {
    const content =
      '\uFEFF' +
      [headers, ...rows.map(values)]
        .map((r) => r.map(csvCell).join(','))
        .join('\r\n');
    const url = URL.createObjectURL(
      new Blob([content], { type: 'text/csv;charset=utf-8;' }),
    );
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <button
      className="button"
      disabled={!rows.length}
      onClick={download}
      title="Xuất dữ liệu của trang đang xem"
    >
      <ArrowDownToLine size={16} />
      Xuất CSV trang này
    </button>
  );
}
export function SuccessIcon() {
  return (
    <span className="success-icon">
      <Check size={15} />
    </span>
  );
}
