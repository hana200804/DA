import { useRef } from 'react';
import type { MapCluster } from './mapTypes';
import type { Location, NearbySign } from './reviewTypes';
import { mapView } from './mapGeometry';

export const reviewMapWidth = 420;
export const reviewMapHeight = 210;
const tileUrl =
  import.meta.env.VITE_REVIEW_TILE_URL ||
  'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

type MapProps = {
  center: Location;
  location?: Location;
  nearby: NearbySign[];
  clusters?: MapCluster[];
  density?: MapCluster[];
  zoom?: number;
  wider?: boolean;
  onChange?: (location: Location) => void;
  onPan?: (center: Location) => void;
  onSelect?: (sign: NearbySign) => void;
  onCluster?: (location: Location) => void;
};

export const adminMapWidth = 720;
export const adminMapHeight = 360;

export function ReviewMap({
  center,
  location,
  nearby,
  clusters,
  density,
  zoom = 18,
  wider,
  onChange,
  onPan,
  onSelect,
  onCluster,
}: MapProps) {
  const width = wider ? adminMapWidth : reviewMapWidth;
  const height = wider ? adminMapHeight : reviewMapHeight;
  const view = mapView(center, zoom, width, height);
  const drag = useRef<{ id: number; x: number; y: number } | null>(null);
  const marker = location && view.toScreen(location);
  const maxDensity =
    density?.reduce((max, cell) => Math.max(max, cell.count), 1) ?? 1;
  function screenPoint(event: React.PointerEvent<SVGSVGElement>) {
    const bounds = event.currentTarget.getBoundingClientRect();
    return {
      x: ((event.clientX - bounds.left) / bounds.width) * width,
      y: ((event.clientY - bounds.top) / bounds.height) * height,
    };
  }
  function move(event: React.PointerEvent<SVGSVGElement>) {
    const { x, y } = screenPoint(event);
    onChange?.(
      view.fromScreen(
        Math.min(width, Math.max(0, x)),
        Math.min(height, Math.max(0, y)),
      ),
    );
  }

  return (
    <div className={'review-map' + (wider ? ' admin-map' : '')}>
      <svg
        viewBox={'0 0 ' + width + ' ' + height}
        role={onSelect || onCluster ? 'group' : 'img'}
        aria-label={
          density
            ? 'Bản đồ mật độ biển báo'
            : wider
              ? 'Bản đồ quản lý biển báo'
              : onChange
                ? 'Bản đồ chỉnh vị trí biển'
                : 'Vị trí biển và các biển đã duyệt lân cận'
        }
        className={
          onChange
            ? 'review-map-editable'
            : onPan
              ? 'admin-map-pannable'
              : undefined
        }
        onPointerDown={
          onChange || onPan
            ? (event) => {
                if (!event.isPrimary || event.button !== 0) return;
                drag.current = { id: event.pointerId, ...screenPoint(event) };
                event.currentTarget.setPointerCapture(event.pointerId);
                if (onChange) move(event);
              }
            : undefined
        }
        onPointerMove={
          onChange
            ? (event) => {
                if (drag.current?.id === event.pointerId) move(event);
              }
            : undefined
        }
        onPointerUp={(event) => {
          const start = drag.current;
          if (start?.id !== event.pointerId) return;
          drag.current = null;
          if (!onChange && onPan) {
            const end = screenPoint(event);
            if (Math.hypot(end.x - start.x, end.y - start.y) >= 5)
              onPan(
                view.fromScreen(
                  width / 2 + start.x - end.x,
                  height / 2 + start.y - end.y,
                ),
              );
          }
        }}
        onLostPointerCapture={() => {
          drag.current = null;
        }}
      >
        {view.tiles().map(({ x, y, left, top }) => (
          <image
            key={x + ':' + y}
            x={left}
            y={top}
            width="256"
            height="256"
            href={tileUrl
              .replace('{z}', String(zoom))
              .replace('{x}', String(x))
              .replace('{y}', String(y))}
          />
        ))}
        {density?.map((cell, index) => {
          const point = view.toScreen(cell.location);
          const intensity = Math.sqrt(cell.count / maxDensity);
          const color =
            intensity < 0.5
              ? '#4b9968'
              : intensity < 0.8
                ? '#c89227'
                : '#b5422d';
          return (
            <g key={index} className="density-cell">
              <circle
                cx={point.x}
                cy={point.y}
                r={10 + intensity * 16}
                fill={color}
                fillOpacity={0.42 + intensity * 0.42}
                stroke="white"
                strokeWidth="1.5"
              >
                <title>{cell.count} biển trong ô</title>
              </circle>
              <text
                x={point.x}
                y={point.y}
                dy=".35em"
                textAnchor="middle"
                fill="#162c23"
                fontSize="11"
                fontWeight="700"
              >
                {cell.count}
              </text>
            </g>
          );
        })}
        {nearby.map((sign) => {
          const p = view.toScreen(sign.location);
          const label = 'Chọn biển ' + sign.signTypeCode + ' · ' + sign.id;
          return (
            <circle
              key={sign.id}
              cx={p.x}
              cy={p.y}
              r={wider ? 8 : 5}
              fill="#326c5d"
              stroke="white"
              strokeWidth="2"
              role={onSelect ? 'button' : undefined}
              tabIndex={onSelect ? 0 : undefined}
              aria-label={onSelect ? label : undefined}
              className={onSelect ? 'admin-map-marker' : undefined}
              onPointerDown={
                onSelect ? (event) => event.stopPropagation() : undefined
              }
              onClick={onSelect ? () => onSelect(sign) : undefined}
              onKeyDown={
                onSelect
                  ? (event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onSelect(sign);
                      }
                    }
                  : undefined
              }
            >
              <title>{sign.signTypeCode}</title>
            </circle>
          );
        })}
        {clusters?.map((cluster) => {
          const p = view.toScreen(cluster.location);
          return (
            <g
              key={p.x + ':' + p.y}
              role="button"
              tabIndex={0}
              className="admin-map-marker"
              aria-label={'Phóng to cụm ' + cluster.count + ' biển'}
              onPointerDown={(event) => event.stopPropagation()}
              onClick={() => onCluster?.(cluster.location)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onCluster?.(cluster.location);
                }
              }}
            >
              <circle
                cx={p.x}
                cy={p.y}
                r="16"
                fill="#326c5d"
                stroke="white"
                strokeWidth="2"
              />
              <text
                x={p.x}
                y={p.y}
                dy=".35em"
                textAnchor="middle"
                fontSize="10"
                fill="white"
              >
                {cluster.count}
              </text>
            </g>
          );
        })}
        {marker && (
          <circle
            cx={marker.x}
            cy={marker.y}
            r="8"
            fill="#ad432e"
            stroke="white"
            strokeWidth="3"
          />
        )}
      </svg>
      <a
        href="https://www.openstreetmap.org/copyright"
        target="_blank"
        rel="noreferrer"
      >
        © OpenStreetMap
      </a>
    </div>
  );
}
