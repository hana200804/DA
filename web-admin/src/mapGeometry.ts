import type { Location } from './reviewTypes.ts';
import type { MapBounds } from './mapTypes.ts';

const maxLatitude = 85.05112878;
const tileSize = 256;

export function mapView(
  center: Location,
  zoom: number,
  width: number,
  height: number,
) {
  const tileCount = 2 ** zoom;
  const world = tileCount * tileSize;
  function project({ lat, lng }: Location) {
    const radians =
      (Math.max(-maxLatitude, Math.min(maxLatitude, lat)) * Math.PI) / 180;
    return {
      x: ((lng + 180) / 360) * world,
      y: ((1 - Math.asinh(Math.tan(radians)) / Math.PI) / 2) * world,
    };
  }
  function unproject(x: number, y: number): Location {
    const clampedX = Math.min(world, Math.max(0, x));
    const clampedY = Math.min(world, Math.max(0, y));
    const lat =
      (Math.atan(Math.sinh(Math.PI * (1 - (2 * clampedY) / world))) * 180) /
      Math.PI;
    const lng = (clampedX / world) * 360 - 180;
    return { lat: Number(lat.toFixed(7)), lng: Number(lng.toFixed(7)) };
  }
  const origin = project(center);
  const left = origin.x - width / 2;
  const top = origin.y - height / 2;
  const northwest = unproject(left, top);
  const southeast = unproject(left + width, top + height);
  const bounds: MapBounds = [
    northwest.lng,
    southeast.lat,
    southeast.lng,
    northwest.lat,
  ];

  return {
    bounds,
    fromScreen(x: number, y: number) {
      return unproject(left + x, top + y);
    },
    toScreen(location: Location) {
      const p = project(location);
      return { x: p.x - left, y: p.y - top };
    },
    tiles() {
      const tiles: { x: number; y: number; left: number; top: number }[] = [];
      for (
        let y = Math.max(0, Math.floor(top / tileSize));
        y < tileCount && y * tileSize < top + height;
        y++
      ) {
        for (
          let x = Math.max(0, Math.floor(left / tileSize));
          x < tileCount && x * tileSize < left + width;
          x++
        )
          tiles.push({
            x,
            y,
            left: x * tileSize - left,
            top: y * tileSize - top,
          });
      }
      return tiles;
    },
  };
}
