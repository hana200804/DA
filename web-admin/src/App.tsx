import {
  Component,
  useCallback,
  useEffect,
  useState,
  type ReactNode,
} from 'react';
import {
  ArrowRight,
  ChartNoAxesColumnIncreasing,
  Check,
  ChevronRight,
  CircleHelp,
  ClipboardList,
  Clock3,
  Eye,
  EyeOff,
  Flag,
  LayoutDashboard,
  Leaf,
  LoaderCircle,
  LogOut,
  MapPinned,
  Menu,
  ScanLine,
  Search,
  ShieldCheck,
  Signpost,
  Tags,
  UserCog,
  UsersRound,
  X,
} from 'lucide-react';
import { api, isDemo, isLegacy, isSigna, signaSessionApi } from './api';
import { Empty, Loading, Modal } from './components';
import { errorMessage, SessionExpiredError } from './domain';
import { DEMO_KEY } from './demo';
import { Accounts } from './pages/Accounts';
import { AuditHistory } from './pages/AuditHistory';
import { Catalog, Types } from './pages/Catalog';
import { Contributors } from './pages/Contributors';
import { StatisticsPage } from './pages/Statistics';
import { ModelComparison } from './pages/ModelComparison';
import { MapSigns } from './pages/MapSigns';
import { Overview } from './pages/Overview';
import { Reviews } from './pages/Reviews';
import { SignReviews } from './pages/SignReviews';
import { SignaCatalog, SignaCategories } from './pages/SignaCatalog';
import { SignReports } from './pages/SignReports';
import {
  canOpenSignaPage,
  hasSignaPermission,
  isCurrentSignaSession,
} from './signaAccess';
import type { Admin, PageKey } from './types';

const menu = [
  { id: 'overview', title: 'Tổng quan', icon: LayoutDashboard },
  { id: 'signs', title: 'Danh mục biển báo', icon: Signpost },
  {
    id: 'types',
    title: isLegacy ? 'Loại biển báo' : 'Nhóm biển báo',
    icon: Tags,
  },
  { id: 'map', title: 'Bản đồ biển báo', icon: MapPinned },
  { id: 'pending', title: 'Biển báo chờ duyệt', icon: Clock3 },
  { id: 'reports', title: 'Báo cáo cộng đồng', icon: Flag },
  { id: 'audit', title: 'Lịch sử xử lý', icon: ClipboardList },
  { id: 'users', title: 'Người đóng góp', icon: UsersRound },
  { id: 'accounts', title: 'Quản lý người dùng', icon: UserCog },
  { id: 'compare', title: 'So sánh mô hình', icon: ScanLine },
  { id: 'stats', title: 'Thống kê', icon: ChartNoAxesColumnIncreasing },
] as const;
const pagesUnavailableInLegacy: PageKey[] = [
  'map',
  'audit',
  'accounts',
  'compare',
  'stats',
];
const visibleMenu = isLegacy
  ? menu.filter(({ id }) => !pagesUnavailableInLegacy.includes(id))
  : menu;
const sessionKey = `visionmap.session.${isDemo ? 'demo' : isSigna ? 'signa' : 'api'}`;
const expiredMessage = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';

function readStoredSession(): unknown {
  try {
    return JSON.parse(sessionStorage.getItem(sessionKey) || 'null');
  } catch {
    return null;
  }
}
function readSession(): Admin | null {
  if (isSigna) return null;
  const user = readStoredSession() as Admin | null;
  return typeof user?.sessionId === 'string' &&
    user.sessionId &&
    typeof user.username === 'string'
    ? user
    : null;
}
function readPage(): PageKey {
  const [id] = location.hash.slice(1).split('?');
  return visibleMenu.some((m) => m.id === id) ? (id as PageKey) : 'overview';
}
function Brand() {
  return (
    <span className="brand">
      <span className="brand-symbol">
        <svg viewBox="0 0 36 36" aria-hidden="true">
          <path d="m7 9 10 21h3L30 9h-8l-4 12-4-12Z" fill="currentColor" />
        </svg>
      </span>
      <span>
        vision<span>map</span>
        <small>ADMIN WORKSPACE</small>
      </span>
    </span>
  );
}

export default function App() {
  return (
    <AppErrorBoundary>
      <Workspace />
    </AppErrorBoundary>
  );
}
function Workspace() {
  const [admin, setAdmin] = useState<Admin | null>(readSession);
  const [restoring, setRestoring] = useState(isSigna);
  const [page, setPage] = useState<PageKey>(readPage);
  const [mobileMenu, setMobileMenu] = useState(false);
  const [toast, setToast] = useState('');
  const [loginMessage, setLoginMessage] = useState('');
  const [help, setHelp] = useState(false);
  const [logout, setLogout] = useState(false);
  const [busy, setBusy] = useState(false);
  const [logoutError, setLogoutError] = useState('');
  const [reset, setReset] = useState(false);
  const [version, setVersion] = useState(0);
  function navigate(key: PageKey) {
    setPage(key);
    location.hash = key;
    setMobileMenu(false);
    window.scrollTo({ top: 0 });
  }
  useEffect(() => {
    const onHash = () => {
      if (location.hash === '#main-content') return;
      setPage(readPage());
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  const endSession = useCallback((message = '') => {
    sessionStorage.removeItem(sessionKey);
    setAdmin(null);
    setLogout(false);
    setHelp(false);
    setToast('');
    setLoginMessage(message);
  }, []);
  useEffect(() => {
    const expired = () => endSession(expiredMessage);
    window.addEventListener('visionmap:expired', expired);
    return () => window.removeEventListener('visionmap:expired', expired);
  }, [endSession]);
  useEffect(() => {
    if (!isSigna) return;
    const stored = readStoredSession();
    if (!isCurrentSignaSession(stored)) {
      if (stored != null) endSession(expiredMessage);
      setRestoring(false);
      return;
    }
    let active = true;
    void signaSessionApi
      .restoreSession(stored)
      .then((identity) => {
        if (active) setAdmin(identity);
      })
      .catch((error) => {
        if (active) endSession(errorMessage(error));
      })
      .finally(() => {
        if (active) setRestoring(false);
      });
    return () => {
      active = false;
    };
  }, [endSession]);
  useEffect(() => {
    if (!isSigna || !admin?.expiresAt) return;
    const expires = Date.parse(admin.expiresAt);
    const expireIfNeeded = () => {
      if (Date.now() >= expires) endSession(expiredMessage);
    };
    const timer = setTimeout(
      expireIfNeeded,
      Math.min(2_147_483_647, Math.max(0, expires - Date.now())),
    );
    window.addEventListener('focus', expireIfNeeded);
    return () => {
      clearTimeout(timer);
      window.removeEventListener('focus', expireIfNeeded);
    };
  }, [admin?.expiresAt, endSession]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 4500);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    document.title = `VisionMap · ${menu.find((m) => m.id === page)?.title}`;
  }, [page]);
  async function doLogout() {
    if (!admin) return;
    setBusy(true);
    setLogoutError('');
    try {
      await api.logout(admin.sessionId);
      endSession();
    } catch (e) {
      if (e instanceof SessionExpiredError)
        window.dispatchEvent(new Event('visionmap:expired'));
      else if (isSigna)
        endSession(
          'Đã đăng xuất khỏi trình duyệt. Chưa xác nhận được việc thu hồi phiên trên máy chủ.',
        );
      else setLogoutError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  function handleLogin(user: Admin) {
    const { sessionId, expiresAt, email } = user;
    const session = isSigna ? { sessionId, expiresAt, email } : user;
    sessionStorage.setItem(sessionKey, JSON.stringify(session));
    setAdmin(user);
    setLoginMessage('');
    if (!location.hash || location.hash === '#main-content')
      navigate('overview');
    else setPage(readPage());
  }

  if (restoring)
    return (
      <div className="session-loading">
        <Loading />
      </div>
    );
  if (!admin) return <Login message={loginMessage} onLogin={handleLogin} />;

  const can = (permission: string) =>
    isDemo || hasSignaPermission(admin, permission);
  const canOpen = (key: PageKey) => !isSigna || canOpenSignaPage(admin, key);

  function renderPage(user: Admin) {
    const { sessionId } = user;
    if (!canOpen(page))
      return (
        <Empty
          title="Bạn không có quyền truy cập"
          text="Tài khoản hiện tại thiếu quyền cho màn hình này."
        />
      );

    switch (page) {
      case 'overview':
        return (
          <Overview
            sessionId={sessionId}
            fullName={user.fullName || user.username}
            canResolveReports={!isSigna || can('report:resolve')}
            navigate={navigate}
          />
        );
      case 'signs':
        return isLegacy ? (
          <Catalog sessionId={sessionId} notify={setToast} />
        ) : (
          <SignaCatalog
            token={sessionId}
            canManage={can('catalog:manage')}
            notify={setToast}
          />
        );
      case 'types': {
        const browseSigns = () => navigate('signs');
        return isLegacy ? (
          <Types sessionId={sessionId} onBrowse={browseSigns} />
        ) : (
          <SignaCategories
            token={sessionId}
            canManage={can('catalog:manage')}
            notify={setToast}
            onBrowse={browseSigns}
          />
        );
      }
      case 'pending':
      case 'reports':
        if (isLegacy)
          return (
            <Reviews
              key={page}
              sessionId={sessionId}
              kind={page}
              notify={setToast}
            />
          );
        return page === 'pending' ? (
          <SignReviews token={sessionId} notify={setToast} />
        ) : (
          <SignReports token={sessionId} notify={setToast} />
        );
      case 'map':
        return (
          <MapSigns
            token={sessionId}
            canReadAudit={can('audit:read')}
            canEdit={can('sign:edit')}
            notify={setToast}
          />
        );
      case 'audit':
        return <AuditHistory token={sessionId} />;
      case 'stats':
        return <StatisticsPage token={sessionId} />;
      case 'compare':
        return <ModelComparison token={sessionId} />;
      case 'accounts':
        return <Accounts admin={user} notify={setToast} />;
      case 'users':
        return isSigna ? (
          <Empty
            title="Chức năng chưa kết nối với Signa"
            text="Bạn có thể mở mục Biển báo chờ duyệt để sử dụng luồng kiểm duyệt."
          />
        ) : (
          <Contributors sessionId={sessionId} notify={setToast} />
        );
    }
  }

  return (
    <div className="workspace">
      <a className="skip-link" href="#main-content">
        Đến nội dung chính
      </a>
      {mobileMenu && (
        <div className="sidebar-shade" onClick={() => setMobileMenu(false)} />
      )}
      <aside className={`sidebar ${mobileMenu ? 'open' : ''}`}>
        <a
          className="brand-link"
          href="#overview"
          onClick={() => navigate('overview')}
          aria-label="VisionMap — Tổng quan"
        >
          <Brand />
        </a>
        <div className="workspace-label">
          <span className="workspace-emblem">
            <ShieldCheck size={17} />
          </span>
          <span>
            Quản trị hệ thống<small>VisionMap Workspace</small>
          </span>
          <ChevronRight size={15} />
        </div>
        <div className="nav-section-label">KHÔNG GIAN LÀM VIỆC</div>
        <nav aria-label="Điều hướng chính">
          {visibleMenu
            .filter(({ id }) => canOpen(id))
            .map((item, i) => (
              <div key={item.id}>
                {i === 3 && (
                  <div className="nav-section-label second">
                    KIỂM DUYỆT & CỘNG ĐỒNG
                  </div>
                )}
                <button
                  aria-current={page === item.id ? 'page' : undefined}
                  className={page === item.id ? 'active' : ''}
                  onClick={() => navigate(item.id)}
                >
                  <item.icon size={19} />
                  <span>{item.title}</span>
                  {item.id === 'pending' && <span className="nav-indicator" />}
                </button>
              </div>
            ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="community-note">
            <Leaf size={22} />
            <strong>
              Dữ liệu từ cộng đồng.
              <br />
              Giá trị cho mọi hành trình.
            </strong>
            <p>Cùng xây dựng bản đồ biển báo chính xác hơn mỗi ngày.</p>
          </div>
          <button className="help-button" onClick={() => setHelp(true)}>
            <CircleHelp size={18} />
            Hướng dẫn sử dụng
            <ArrowRight size={14} />
          </button>
          {!isDemo && (
            <div className="connection-state">
              <i />
              Kết nối qua API
            </div>
          )}
        </div>
      </aside>
      <div className="workspace-main">
        <header className="topbar">
          <div className="breadcrumbs">
            <button
              className="icon-button menu-toggle"
              aria-label="Mở điều hướng"
              onClick={() => setMobileMenu(true)}
            >
              <Menu size={21} />
            </button>
            <span>Không gian quản trị</span>
            <ChevronRight size={13} />
            <strong>{menu.find((m) => m.id === page)?.title}</strong>
          </div>
          <div className="topbar-actions">
            <button
              className="icon-button top-help"
              aria-label="Hướng dẫn sử dụng"
              onClick={() => setHelp(true)}
            >
              <CircleHelp size={19} />
            </button>
            <span className="topbar-divider" />
            <span className="admin-avatar">
              {(admin.fullName || admin.username)
                .split(' ')
                .slice(-2)
                .map((s) => s[0])
                .join('')
                .toUpperCase()}
            </span>
            <div className="admin-name">
              <strong>{admin.fullName || admin.username}</strong>
              <span>
                {admin.role === 'MODERATOR'
                  ? 'Kiểm duyệt viên'
                  : 'Quản trị viên'}
              </span>
            </div>
            <button
              className="icon-button"
              aria-label="Đăng xuất"
              onClick={() => {
                setLogout(true);
                setLogoutError('');
              }}
            >
              <LogOut size={18} />
            </button>
          </div>
        </header>
        {isDemo && (
          <div className="demo-banner">
            <span>
              <span className="demo-dot" aria-hidden="true" />
              <span>
                <b>Chế độ demo</b> · Thay đổi chỉ lưu trên trình duyệt này
              </span>
            </span>
            <button onClick={() => setReset(true)}>
              Đặt lại dữ liệu <ArrowRight size={13} />
            </button>
          </div>
        )}
        <main id="main-content" tabIndex={-1} key={version}>
          <div className="content-wrap">{renderPage(admin)}</div>
          <footer className="site-footer">
            <span>© {new Date().getFullYear()} VisionMap</span>
            <span>
              Thông tin chính xác. Hành trình an toàn.
              <span className="footer-leaf">✳</span>
            </span>
          </footer>
        </main>
      </div>
      {toast && (
        <div className="toast" role="status">
          <span>
            <Check size={16} />
          </span>
          {toast}
          <button
            className="icon-button"
            aria-label="Đóng thông báo"
            onClick={() => setToast('')}
          >
            <X size={16} />
          </button>
        </div>
      )}
      {logout && (
        <Modal
          title="Đăng xuất khỏi VisionMap?"
          eyebrow="TÀI KHOẢN"
          onClose={() => setLogout(false)}
          busy={busy}
        >
          <p>Bạn có thể đăng nhập lại để tiếp tục quản lý dữ liệu biển báo.</p>
          {logoutError && (
            <div className="inline-error" role="alert">
              {logoutError}
            </div>
          )}
          <div className="modal-actions">
            <button
              className="button"
              disabled={busy}
              onClick={() => setLogout(false)}
            >
              Ở lại
            </button>
            <button
              className="button primary"
              disabled={busy}
              onClick={doLogout}
            >
              {busy ? 'Đang đăng xuất…' : 'Đăng xuất'}
            </button>
          </div>
        </Modal>
      )}
      {reset && (
        <Modal
          title="Đặt lại dữ liệu mẫu?"
          eyebrow="BẢN TRẢI NGHIỆM"
          onClose={() => setReset(false)}
        >
          <p>
            Các thao tác thử nghiệm trên trình duyệt này sẽ được xóa. Dữ liệu
            mẫu ban đầu sẽ được khôi phục.
          </p>
          <div className="modal-actions">
            <button className="button" onClick={() => setReset(false)}>
              Hủy
            </button>
            <button
              className="button primary"
              onClick={() => {
                localStorage.removeItem(DEMO_KEY);
                setReset(false);
                setVersion((v) => v + 1);
                setToast('Đã khôi phục dữ liệu mẫu ban đầu.');
              }}
            >
              Khôi phục dữ liệu mẫu
            </button>
          </div>
        </Modal>
      )}
      {help && (
        <Modal
          title="Làm quen với VisionMap"
          eyebrow="HƯỚNG DẪN SỬ DỤNG"
          onClose={() => setHelp(false)}
        >
          <div className="help-step">
            <Search />
            <div>
              <h3>Tra cứu dữ liệu</h3>
              <p>
                Tìm biển theo tên, lọc loại biển và mở chi tiết. Chọn biểu tượng
                bút để sửa tên biển báo.
              </p>
            </div>
          </div>
          <div className="help-step">
            <ShieldCheck />
            <div>
              <h3>Kiểm duyệt từng đóng góp</h3>
              <p>
                Kiểm tra ảnh, địa chỉ, độ tin cậy AI và biển trùng trước khi phê
                duyệt hoặc từ chối.
              </p>
            </div>
          </div>
          <div className="help-step">
            <UsersRound />
            <div>
              <h3>Theo dõi cộng đồng</h3>
              <p>
                Xem thống kê và lịch sử đăng tải của từng người dùng. Mở bản ghi
                để xử lý hoặc xóa biển sai.
              </p>
            </div>
          </div>
          {!isDemo && (
            <div className="notice">
              Bạn đang làm việc với backend thật. Hãy kiểm tra thông tin trước
              khi xác nhận thay đổi.
            </div>
          )}
          <div className="modal-actions">
            <button className="button primary" onClick={() => setHelp(false)}>
              Đã hiểu <Check size={16} />
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
function Login({
  message,
  onLogin,
}: {
  message: string;
  onLogin: (u: Admin) => void;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      onLogin(await api.login(username.trim(), password));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-page">
      <section className="login-story">
        <Brand />
        <div className="login-story-copy">
          <span className="eyebrow">VISIONMAP ADMIN WORKSPACE</span>
          <h1>
            Biển báo rõ ràng.
            <br />
            Hành trình
            <br />
            <em>an toàn hơn.</em>
          </h1>
          <p>
            Không gian quản trị giúp kết nối đóng góp từ cộng đồng với những
            hành trình mỗi ngày.
          </p>
          <div className="login-road" aria-hidden="true">
            <svg viewBox="0 0 440 180">
              <path
                d="M-40 175c220 30 70-200 290-100s250-75 250-75"
                fill="none"
                stroke="#326c5d"
                strokeWidth="62"
              />
              <path
                d="M-40 175c220 30 70-200 290-100s250-75 250-75"
                fill="none"
                stroke="#b6d5ba"
                strokeWidth="3"
                strokeDasharray="12 14"
              />
              <path d="M305 10v84" stroke="#b6d5ba" strokeWidth="5" />
              <circle
                cx="305"
                cy="40"
                r="28"
                fill="#fff8e6"
                stroke="#ed9e80"
                strokeWidth="7"
              />
              <text
                x="305"
                y="49"
                textAnchor="middle"
                fill="#173d31"
                fontSize="25"
                fontWeight="700"
              >
                50
              </text>
            </svg>
          </div>
        </div>
        <div className="login-story-footer">
          <ShieldCheck size={19} /> Dữ liệu đáng tin cậy từ cộng đồng
        </div>
      </section>
      <section className="login-form-side">
        <div className="login-mobile-brand">
          <Brand />
        </div>
        <form className="login-form" onSubmit={submit}>
          <span className="login-icon">
            <ShieldCheck size={25} />
          </span>
          <div className="eyebrow">CHÀO MỪNG TRỞ LẠI</div>
          <h2>{isDemo ? 'Khám phá VisionMap' : 'Đăng nhập quản trị'}</h2>
          <p>
            {isDemo
              ? 'Thử các chức năng quản lý biển báo với dữ liệu mẫu.'
              : 'Đăng nhập để quản lý biển báo và kiểm duyệt đóng góp từ cộng đồng.'}
          </p>
          {message && (
            <div className="inline-error" role="alert">
              {message}
            </div>
          )}
          {isDemo ? (
            <div className="demo-login-info">
              <h3>Không cần tài khoản</h3>
              <p>Thay đổi chỉ lưu trên trình duyệt này và có thể đặt lại.</p>
            </div>
          ) : (
            <>
              <label className="field">
                {isSigna ? 'Email' : 'Tên đăng nhập'}
                <input
                  type={isSigna ? 'email' : 'text'}
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="username"
                  placeholder={
                    isSigna ? 'Nhập địa chỉ email' : 'Nhập tên đăng nhập'
                  }
                  required
                  disabled={busy}
                />
              </label>
              <label className="field">
                Mật khẩu
                <span className="password-field">
                  <input
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    type={visible ? 'text' : 'password'}
                    autoComplete="current-password"
                    placeholder="Nhập mật khẩu"
                    required
                    disabled={busy}
                  />
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
                    onClick={() => setVisible((v) => !v)}
                  >
                    {visible ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </span>
              </label>
            </>
          )}
          {error && (
            <div className="inline-error" role="alert">
              {error}
            </div>
          )}
          <button
            className="button primary login-submit"
            type="submit"
            disabled={busy}
          >
            {busy ? (
              <>
                <LoaderCircle className="spin" size={17} />
                Đang đăng nhập…
              </>
            ) : (
              <>
                {isDemo ? 'Vào không gian trải nghiệm' : 'Đăng nhập'}
                <ArrowRight size={18} />
              </>
            )}
          </button>
          {!isDemo && (
            <div className="login-note">
              <ShieldCheck size={14} />
              Dành cho tài khoản quản trị được cấp quyền
            </div>
          )}
        </form>
        <footer>
          © {new Date().getFullYear()} VisionMap{' '}
          <span>Thông tin chính xác. Hành trình an toàn.</span>
        </footer>
      </section>
    </div>
  );
}
class AppErrorBoundary extends Component<
  { children: ReactNode },
  { error: boolean }
> {
  state = { error: false };
  static getDerivedStateFromError() {
    return { error: true };
  }
  render() {
    return this.state.error ? (
      <div className="state-panel app-fallback">
        <ShieldCheck size={35} />
        <h1>Giao diện cần được tải lại</h1>
        <p>Có lỗi xảy ra khi hiển thị trang.</p>
        <button className="button primary" onClick={() => location.reload()}>
          Tải lại trang
        </button>
      </div>
    ) : (
      this.props.children
    );
  }
}
