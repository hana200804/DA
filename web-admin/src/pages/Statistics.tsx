import { useState } from 'react';
import {
  CalendarDays,
  Check,
  Clock3,
  MapPinned,
  Minus,
  Plus,
  X,
} from 'lucide-react';
import { reviewApi } from '../api';
import { Empty, ErrorPanel, Loading, useLoad } from '../components';
import { errorMessage } from '../domain';
import { mapView } from '../mapGeometry';
import type { MapCluster } from '../mapTypes';
import { adminMapHeight, adminMapWidth, ReviewMap } from '../reviewMap';
import {
  dailyChartRows,
  decisionRates,
  statisticsRange,
  type DailySubmissions,
  type StatisticsFilters,
} from '../statisticsTypes';

const dateFields = { fromDate: '', toDate: '' };
const formatCount = (value: number) => value.toLocaleString('vi-VN');
const percent = new Intl.NumberFormat('vi-VN', {
  style: 'percent',
  maximumFractionDigits: 1,
});
const decimal = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 });

function formatDuration(ms: number | null) {
  if (ms === null || !Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 60_000) return decimal.format(ms / 1000) + ' giây';
  if (ms < 3_600_000) return decimal.format(ms / 60_000) + ' phút';
  if (ms < 86_400_000) return decimal.format(ms / 3_600_000) + ' giờ';
  return decimal.format(ms / 86_400_000) + ' ngày';
}

export function StatisticsPage({ token }: { token: string }) {
  const [filters, setFilters] = useState<StatisticsFilters>(dateFields);
  const [validation, setValidation] = useState('');
  const result = useLoad(
    () => reviewApi.statistics(token, filters),
    [token, filters],
  );
  const data = result.data;

  function apply(values: typeof dateFields) {
    try {
      statisticsRange(values);
      setFilters(values);
      setValidation('');
    } catch (error) {
      setValidation(errorMessage(error));
    }
  }

  const total =
    data?.submissionsByDay.reduce((sum, row) => sum + row.count, 0) ?? 0;
  const rates = data && decisionRates(data.decisions);
  const categoryTotal =
    data?.signsByCategory.reduce((sum, row) => sum + row.count, 0) ?? 0;
  const maxGroup =
    data?.signsByCategory.reduce((max, row) => Math.max(max, row.count), 1) ??
    1;

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">QUẢN TRỊ HỆ THỐNG</div>
          <h1>
            Thống kê<span className="heading-dot">.</span>
          </h1>
          <p>
            Theo dõi đề xuất, kết quả kiểm duyệt và phân bố biển trên bản đồ.
          </p>
        </div>
      </div>
      <section className="panel statistics-filter">
        <form
          className="panel-toolbar audit-toolbar"
          onSubmit={(event) => {
            event.preventDefault();
            const fields = new FormData(event.currentTarget);
            apply({
              fromDate: String(fields.get('fromDate') ?? ''),
              toDate: String(fields.get('toDate') ?? ''),
            });
          }}
        >
          <label className="field">
            Từ ngày
            <input type="date" name="fromDate" />
          </label>
          <label className="field">
            Đến ngày
            <input type="date" name="toDate" />
          </label>
          <div className="audit-filter-actions">
            <button className="button primary" type="submit">
              Lọc thống kê
            </button>
            <button
              className="button"
              type="reset"
              onClick={() => apply(dateFields)}
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
      </section>
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
                  label: 'Tổng đề xuất',
                  value: formatCount(total),
                  note: 'Đề xuất nhận trong khoảng đã chọn',
                  color: 'green',
                  Icon: CalendarDays,
                },
                {
                  label: 'Tỉ lệ duyệt',
                  value: rates ? percent.format(rates.approved) : '—',
                  note:
                    formatCount(data.decisions.approved) +
                    ' đề xuất được duyệt',
                  color: 'green',
                  Icon: Check,
                },
                {
                  label: 'Tỉ lệ từ chối',
                  value: rates ? percent.format(rates.rejected) : '—',
                  note:
                    formatCount(data.decisions.rejected) +
                    ' đề xuất bị từ chối',
                  color: 'red',
                  Icon: X,
                },
                {
                  label: 'Thời gian xử lý trung bình',
                  value: formatDuration(data.processingTime.averageMs),
                  note:
                    formatCount(data.processingTime.samples) +
                    ' đề xuất có thời điểm quyết định',
                  color: 'amber',
                  Icon: Clock3,
                },
              ].map(({ label, value, note, color, Icon }) => (
                <article className={'stat-card ' + color} key={label}>
                  <div>
                    <span>{label}</span>
                    <span className="stat-icon">
                      <Icon size={18} />
                    </span>
                  </div>
                  <strong>{value}</strong>
                  <footer>{note}</footer>
                </article>
              ))}
            </div>
            <section className="panel statistics-panel">
              <div className="panel-title">
                <div>
                  <h2>Số đề xuất theo ngày</h2>
                  <p>
                    Ngày theo UTC+7. Tỉ lệ duyệt và từ chối tính trên các đề
                    xuất đã có quyết định.
                  </p>
                </div>
              </div>
              <DailyChart
                rows={dailyChartRows(data.submissionsByDay, filters)}
              />
            </section>
            <section className="panel statistics-panel">
              <div className="panel-title">
                <div>
                  <h2>Số biển theo nhóm</h2>
                  <p>
                    Toàn bộ {formatCount(categoryTotal)} biển đã duyệt, chia
                    theo nhóm, bất kể khoảng ngày đã chọn.
                  </p>
                </div>
              </div>
              {data.signsByCategory.length ? (
                <ul className="statistics-categories">
                  {data.signsByCategory.map(({ categoryCode, name, count }) => (
                    <li key={categoryCode}>
                      <span>{name}</span>
                      <div
                        className="statistics-category-bar"
                        aria-hidden="true"
                      >
                        <i style={{ width: (count / maxGroup) * 100 + '%' }} />
                      </div>
                      <strong>{formatCount(count)}</strong>
                    </li>
                  ))}
                </ul>
              ) : (
                <Empty
                  title="Chưa có biển đã duyệt"
                  text="Số liệu sẽ xuất hiện khi có biển được duyệt."
                />
              )}
            </section>
            <section className="panel statistics-panel">
              <div className="panel-title">
                <div>
                  <h2>Bản đồ mật độ biển báo</h2>
                  <p>
                    Mỗi ô đếm số biển đã duyệt tại đó, bất kể khoảng ngày đã
                    chọn.
                  </p>
                </div>
                <MapPinned size={20} />
              </div>
              {data.densityCells.length ? (
                <DensityMap
                  key={(filters.fromDate ?? '') + ':' + (filters.toDate ?? '')}
                  cells={data.densityCells}
                />
              ) : (
                <Empty
                  title="Chưa có dữ liệu mật độ"
                  text="Bản đồ mật độ sẽ xuất hiện khi có biển được duyệt."
                />
              )}
            </section>
          </>
        )
      )}
    </>
  );
}

function DailyChart({ rows }: { rows: DailySubmissions[] }) {
  if (!rows.length)
    return (
      <Empty title="Chưa có đề xuất" text="Hãy thử một khoảng ngày khác." />
    );
  const max = rows.reduce((value, row) => Math.max(value, row.count), 1);
  return (
    <>
      <div
        className="statistics-chart-scroll"
        tabIndex={0}
        aria-label="Biểu đồ số đề xuất theo ngày"
      >
        <div className="activity-chart statistics-chart" aria-hidden="true">
          <div className="chart-y">
            <span>{max}</span>
            <span>{Math.floor(max / 2)}</span>
            <span>0</span>
          </div>
          <div className="chart-bars" style={{ minWidth: rows.length * 44 }}>
            {rows.map(({ date, count }) => (
              <div className="chart-col" key={date}>
                <div className="bar-area">
                  <span className="bar-value">{count}</span>
                  <div
                    className="bar"
                    title={date + ' · ' + count + ' đề xuất'}
                    style={{
                      height: (count / max) * 100 + '%',
                      visibility: count ? 'visible' : 'hidden',
                    }}
                  />
                </div>
                <span>
                  {date.slice(8)}/{date.slice(5, 7)}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>
      <details className="statistics-data">
        <summary>Xem số liệu theo ngày</summary>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>NGÀY</th>
                <th>SỐ ĐỀ XUẤT</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ date, count }) => (
                <tr key={date}>
                  <td>
                    {date.slice(8)}/{date.slice(5, 7)}/{date.slice(0, 4)}
                  </td>
                  <td>{formatCount(count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  );
}

function densityViewport(cells: MapCluster[]) {
  const first = cells[0].location;
  const edges = cells.reduce(
    ({ north, south, west, east }, { location }) => ({
      north: Math.max(north, location.lat),
      south: Math.min(south, location.lat),
      west: Math.min(west, location.lng),
      east: Math.max(east, location.lng),
    }),
    { north: first.lat, south: first.lat, west: first.lng, east: first.lng },
  );
  const center = {
    lat: (edges.north + edges.south) / 2,
    lng: (edges.west + edges.east) / 2,
  };
  let zoom = 15;
  for (; zoom > 2; zoom--) {
    const {
      bounds: [west, south, east, north],
    } = mapView(center, zoom, adminMapWidth - 64, adminMapHeight - 64);
    if (
      edges.north <= north &&
      edges.south >= south &&
      edges.west >= west &&
      edges.east <= east
    )
      break;
  }
  return { center, zoom };
}

function DensityMap({ cells }: { cells: MapCluster[] }) {
  const [viewport, setViewport] = useState(() => densityViewport(cells));
  const max = cells.reduce((peak, cell) => Math.max(peak, cell.count), 0);
  return (
    <div className="map-content">
      <div className="statistics-map-toolbar">
        <div className="map-controls">
          <button
            className="button"
            aria-label="Thu nhỏ mật độ"
            disabled={viewport.zoom <= 2}
            onClick={() =>
              setViewport({ ...viewport, zoom: viewport.zoom - 1 })
            }
          >
            <Minus size={16} />
          </button>
          <button
            className="button"
            aria-label="Phóng to mật độ"
            disabled={viewport.zoom >= 19}
            onClick={() =>
              setViewport({ ...viewport, zoom: viewport.zoom + 1 })
            }
          >
            <Plus size={16} />
          </button>
          <button
            className="button"
            onClick={() => setViewport(densityViewport(cells))}
          >
            Xem toàn vùng
          </button>
        </div>
        <div className="statistics-legend">
          <span>Mật độ thấp</span>
          <i aria-hidden="true" />
          <span>Mật độ cao</span>
        </div>
      </div>
      <ReviewMap
        wider
        nearby={[]}
        density={cells}
        center={viewport.center}
        zoom={viewport.zoom}
        onPan={(center) => setViewport((current) => ({ ...current, center }))}
      />
      <p className="muted">
        {formatCount(cells.length)} ô có biển · Ô nhiều nhất có{' '}
        {formatCount(max)} biển
      </p>
    </div>
  );
}
