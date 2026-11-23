import {
  ArrowRight,
  ArrowUpRight,
  Check,
  Clock3,
  Flag,
  MapPin,
  ScanLine,
  ShieldCheck,
  Signpost,
  UsersRound,
} from 'lucide-react';
import { api, catalogApi, isDemo, isSigna, reviewApi } from '../api';
import {
  Empty,
  ErrorPanel,
  Loading,
  SignGraphic,
  useLoad,
} from '../components';
import { dateLabel, percentage } from '../domain';
import { readDemo } from '../demo';
import { reportReasonLabels } from '../reportTypes';
import type { PageKey } from '../types';

export function Overview({
  sessionId,
  fullName,
  canResolveReports,
  navigate,
}: {
  sessionId: string;
  fullName: string;
  canResolveReports: boolean;
  navigate: (p: PageKey) => void;
}) {
  const groupedReports = useLoad(async () => {
    if (!canResolveReports || (!isDemo && !isSigna)) return null;
    const result = await reviewApi.reportQueue(sessionId, {
      page: 1,
      size: 2,
      status: 'PENDING',
    });
    return {
      total: result.total,
      rows: result.rows.flatMap(({ sign, reports }) => {
        const report = reports[0];
        return report
          ? [
              {
                id: sign.id,
                trafficSignId: sign.signTypeCode,
                reason: reportReasonLabels[report.reason],
                createdAt: report.createdAt,
              },
            ]
          : [];
      }),
    };
  }, [sessionId, canResolveReports]);
  const result = useLoad(async () => {
    const f = { query: '', typeId: '', page: 1, size: 4, sort: 'newest' };
    const [signs, pending, reports, users] = await Promise.all([
      isDemo || isSigna
        ? catalogApi.catalogTypes().then((rows) => ({
            total: rows.filter((row) => row.active).length,
          }))
        : api.signs(sessionId, f),
      isDemo || isSigna
        ? reviewApi.queue(sessionId, { page: 1, size: 4 }).then((queue) => ({
            ...queue,
            rows: queue.rows.map((row) => ({
              id: row.id,
              trafficSignId: row.signTypeCode,
              trafficSignName: row.signName,
              fullAddress:
                row.location.lat.toFixed(5) +
                ', ' +
                row.location.lng.toFixed(5),
              accuracyAI: row.confidence,
            })),
          }))
        : api.pending(sessionId, f),
      isDemo || isSigna ? null : api.reports(sessionId, f),
      isSigna ? null : api.users(sessionId, f),
    ]);
    return { signs, pending, reports, users };
  }, [sessionId]);
  const data = result.data;
  const reports = isDemo || isSigna ? groupedReports.data : data?.reports;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">KHÔNG GIAN QUẢN TRỊ</div>
          <h1>
            Tổng quan hệ thống<span className="heading-dot">.</span>
          </h1>
          <p>Chào {fullName}. Cùng xây dựng dữ liệu giao thông đáng tin cậy.</p>
        </div>
        <span className="date-label">
          {new Intl.DateTimeFormat('vi-VN', {
            day: 'numeric',
            month: 'long',
            year: 'numeric',
            timeZone: 'Asia/Bangkok',
          }).format(new Date())}
        </span>
      </div>
      {result.loading ? (
        <Loading />
      ) : result.error ? (
        <ErrorPanel message={result.error} retry={result.refresh} />
      ) : (
        data && (
          <>
            <div className="stats-grid">
              {[
                {
                  label: 'Danh mục biển báo',
                  value: data.signs?.total,
                  Icon: Signpost,
                  to: 'signs',
                  color: 'green',
                  note: 'Loại biển trong hệ thống',
                },
                {
                  label: 'Biển báo chờ duyệt',
                  value: data.pending.total,
                  Icon: Clock3,
                  to: 'pending',
                  color: 'amber',
                  note: 'Cần kiểm tra và xác nhận',
                },
                {
                  label:
                    isDemo || isSigna
                      ? 'Biển có báo cáo chờ xử lý'
                      : 'Báo cáo cần xử lý',
                  value: reports?.total,
                  Icon: Flag,
                  to: 'reports',
                  color: 'red',
                  note: 'Phản hồi từ cộng đồng',
                },
                {
                  label: 'Người đóng góp',
                  value: data.users?.total,
                  Icon: UsersRound,
                  to: 'users',
                  color: 'blue',
                  note: 'Thành viên đã đăng biển báo',
                },
              ]
                .filter(({ to }) => to !== 'reports' || canResolveReports)
                .map((s) => (
                  <button
                    className={`stat-card ${s.color}`}
                    key={s.to}
                    onClick={() => navigate(s.to as PageKey)}
                  >
                    <div>
                      <span>{s.label}</span>
                      <span className="stat-icon">
                        <s.Icon size={19} />
                      </span>
                    </div>
                    <strong>{s.value?.toLocaleString('vi-VN') ?? '—'}</strong>
                    <footer>
                      <span>{s.note}</span>
                      <ArrowUpRight size={16} />
                    </footer>
                  </button>
                ))}
            </div>
            <div className="overview-grid">
              <section className="panel pending-panel">
                <div className="panel-title">
                  <div>
                    <h2>Đóng góp cần bạn xem xét</h2>
                    <p>Các hồ sơ trong hàng đợi kiểm duyệt</p>
                  </div>
                  <button
                    className="text-button"
                    onClick={() => navigate('pending')}
                  >
                    Xem tất cả <ArrowUpRight size={16} />
                  </button>
                </div>
                {!data.pending.rows.length ? (
                  <Empty
                    title="Không có biển báo chờ duyệt"
                    text="Các đóng góp mới sẽ xuất hiện tại đây."
                  />
                ) : (
                  <div className="queue-list">
                    {data.pending.rows.map((row) => (
                      <button
                        key={row.id}
                        className="queue-row"
                        onClick={() => navigate('pending')}
                      >
                        <span className="sign-tile">
                          <SignGraphic code={row.trafficSignId} />
                        </span>
                        <span className="queue-info">
                          <strong>
                            {row.trafficSignName || row.trafficSignId}
                          </strong>
                          <small>
                            <MapPin size={12} />
                            {row.fullAddress || 'Chưa có địa chỉ'}
                          </small>
                        </span>
                        <span className="queue-confidence">
                          <b>{percentage(row.accuracyAI).toFixed(0)}%</b>
                          <small>
                            {isDemo || isSigna
                              ? 'điểm tin cậy biển'
                              : 'độ tin cậy AI'}
                          </small>
                        </span>
                        <ArrowUpRight size={16} />
                      </button>
                    ))}
                  </div>
                )}
                <div className="panel-footer">
                  <span className="status-dot" /> {data.pending.total} đóng góp
                  đang chờ kiểm duyệt{' '}
                  <button
                    className="text-button"
                    onClick={() => navigate('pending')}
                  >
                    Bắt đầu duyệt <ArrowRight size={15} />
                  </button>
                </div>
              </section>
              <section className="quality-card">
                <div className="eyebrow">
                  DỮ LIỆU TỐT HƠN, HÀNH TRÌNH AN TOÀN HƠN
                </div>
                <h2>
                  Mỗi biển báo đúng.
                  <br />
                  Một hành trình an tâm.
                </h2>
                <p>
                  Kiểm duyệt cẩn thận giúp cộng đồng tiếp cận thông tin giao
                  thông chính xác.
                </p>
                <div className="road-art" aria-hidden="true">
                  <svg viewBox="0 0 310 160">
                    <path
                      d="M-30 145C70 145 30 32 150 52s87 98 200 10"
                      fill="none"
                      stroke="#42776a"
                      strokeWidth="47"
                    />
                    <path
                      d="M-30 145C70 145 30 32 150 52s87 98 200 10"
                      fill="none"
                      stroke="#b9d4bc"
                      strokeWidth="2"
                      strokeDasharray="9 10"
                    />
                    <circle cx="82" cy="68" r="7" fill="#d1e7c7" />
                    <path d="M234 12v73" stroke="#b9d4bc" strokeWidth="4" />
                    <circle
                      cx="234"
                      cy="30"
                      r="23"
                      fill="#faf6e9"
                      stroke="#f39a79"
                      strokeWidth="5"
                    />
                    <text
                      x="234"
                      y="37"
                      textAnchor="middle"
                      fontSize="20"
                      fill="#183e35"
                      fontWeight="700"
                    >
                      50
                    </text>
                    <path d="m48 113-13-25 28 2Z" fill="#d1e7c7" />
                  </svg>
                </div>
                <button onClick={() => navigate('reports')}>
                  Xem báo cáo cộng đồng <ArrowUpRight size={17} />
                </button>
              </section>
            </div>
            <div className="overview-bottom">
              <section className="panel">
                <div className="panel-title">
                  <div>
                    <h2>
                      {isDemo
                        ? 'Nhịp đóng góp trong tuần mẫu'
                        : 'Quy trình kiểm duyệt'}
                    </h2>
                    <p>
                      {isDemo
                        ? 'Số biển báo đăng tải · 30/10–05/11/2026'
                        : 'Ba bước để xác nhận một biển báo'}
                    </p>
                  </div>
                  <span className="legend">
                    <i /> {isDemo ? 'Biển báo' : 'VisionMap'}
                  </span>
                </div>
                {isDemo ? (
                  <ActivityChart />
                ) : (
                  <div className="workflow">
                    <div>
                      <ScanLine />
                      <strong>01. Kiểm tra ảnh</strong>
                      <p>Đối chiếu loại biển và vùng nhận diện.</p>
                    </div>
                    <div>
                      <MapPin />
                      <strong>02. Xác minh vị trí</strong>
                      <p>Kiểm tra tọa độ và biển ở gần.</p>
                    </div>
                    <div>
                      <ShieldCheck />
                      <strong>03. Ra quyết định</strong>
                      <p>Phê duyệt hoặc từ chối từng đóng góp.</p>
                    </div>
                  </div>
                )}
              </section>
              {canResolveReports && (
                <section className="panel reports-summary">
                  <div className="panel-title">
                    <h2>Cần chú ý</h2>
                    <Flag size={18} />
                  </div>
                  {groupedReports.error ? (
                    <ErrorPanel
                      message={groupedReports.error}
                      retry={groupedReports.refresh}
                    />
                  ) : !reports ? (
                    <Loading />
                  ) : !reports.rows.length ? (
                    <div className="all-clear">
                      <Check size={23} />
                      <strong>Chưa có báo cáo mới</strong>
                      <p>Tiếp tục theo dõi phản hồi cộng đồng.</p>
                    </div>
                  ) : (
                    reports.rows.slice(0, 2).map((row) => (
                      <button
                        className="report-summary-row"
                        key={row.id}
                        onClick={() => navigate('reports')}
                      >
                        <span className="report-dot" />
                        <span>
                          <strong>{row.reason}</strong>
                          <small>
                            {row.trafficSignId} · {dateLabel(row.createdAt)}
                          </small>
                        </span>
                        <ArrowUpRight size={16} />
                      </button>
                    ))
                  )}
                  <button
                    className="text-button report-summary-link"
                    onClick={() => navigate('reports')}
                  >
                    Tất cả báo cáo <ArrowRight size={15} />
                  </button>
                </section>
              )}
            </div>
          </>
        )
      )}
    </>
  );
}
function ActivityChart() {
  const rows = readDemo().uploads;
  const days = [
    '2026-10-30',
    '2026-10-31',
    '2026-11-01',
    '2026-11-02',
    '2026-11-03',
    '2026-11-04',
    '2026-11-05',
  ];
  const values = days.map(
    (d) => rows.filter((r) => r.createdAt.startsWith(d)).length,
  );
  const max = Math.max(1, ...values);
  return (
    <div
      className="activity-chart"
      role="img"
      aria-label={days.map((d, i) => `${d}: ${values[i]} biển báo`).join('; ')}
    >
      <div className="chart-y">
        <span>{max}</span>
        <span>{Math.round(max / 2)}</span>
        <span>0</span>
      </div>
      <div className="chart-bars">
        {values.map((v, i) => (
          <div
            className={`chart-col ${i === 6 ? 'current' : ''}`}
            key={days[i]}
          >
            <div className="bar-area">
              <span className="bar-value">{v}</span>
              <div
                className="bar"
                style={{
                  height: `${Math.max(v > 0 ? 5 : 1, (v / max) * 100)}%`,
                }}
                title={`${v} biển báo`}
              />
            </div>
            <span>
              {days[i].slice(8)}/{days[i].slice(5, 7)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
