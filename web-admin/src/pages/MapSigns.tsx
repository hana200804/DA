import { useState } from 'react';
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  MapPin,
  Minus,
  Plus,
  Trash2,
} from 'lucide-react';
import { reviewApi } from '../api';
import {
  Empty,
  ErrorPanel,
  Loading,
  SignGraphic,
  useLoad,
} from '../components';
import { errorMessage, SessionExpiredError } from '../domain';
import { mapView } from '../mapGeometry';
import { isPublished, type MapSignInput, type MapSignPatch } from '../mapTypes';
import { ReviewApiError } from '../reviewApi';
import { adminMapHeight, adminMapWidth, ReviewMap } from '../reviewMap';
import type { Location, ReviewDetail } from '../reviewTypes';

const initialCenter = { lat: 21.019, lng: 105.811 };
const minZoom = 3;
const maxZoom = 19;
const clampZoom = (zoom: number) => Math.max(minZoom, Math.min(maxZoom, zoom));

type Draft = {
  code: string;
  lat: string;
  lng: string;
  heading: string;
  positionChosen: boolean;
};
type Action = 'create' | 'edit' | 'remove';

function draftFor(
  location: Location,
  code = '',
  heading?: number | null,
  positionChosen = true,
): Draft {
  return {
    code,
    lat: String(location.lat),
    lng: String(location.lng),
    heading: String(heading ?? ''),
    positionChosen,
  };
}

function parseLocation(lat: string, lng: string): Location | null {
  const latitude = Number(lat);
  const longitude = Number(lng);
  return lat.trim() &&
    lng.trim() &&
    Number.isFinite(latitude) &&
    Number.isFinite(longitude) &&
    Math.abs(latitude) <= 90 &&
    Math.abs(longitude) <= 180
    ? { lat: latitude, lng: longitude }
    : null;
}

export function MapSigns({
  token,
  canEdit,
  canReadAudit,
  notify,
}: {
  token: string;
  canEdit: boolean;
  canReadAudit: boolean;
  notify: (message: string) => void;
}) {
  const [center, setCenter] = useState(initialCenter);
  const [zoom, setZoom] = useState(14);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [goToLat, setGoToLat] = useState('');
  const [goToLng, setGoToLng] = useState('');
  const view = mapView(center, zoom, adminMapWidth, adminMapHeight);
  const map = useLoad(
    () => reviewApi.mapSigns(token, view.bounds, zoom),
    [token, center.lat, center.lng, zoom],
  );
  const detail = useLoad(
    () =>
      selectedId ? reviewApi.detail(token, selectedId) : Promise.resolve(null),
    [token, selectedId],
  );
  const catalog = useLoad(() => reviewApi.mapSignTypes(), [token]);
  const sign = detail.data?.id === selectedId ? detail.data : null;
  const published = sign && isPublished(sign.status) ? sign : null;
  const signs = map.data?.mode === 'SIGNS' ? map.data.items : [];
  const clusters = map.data?.mode === 'CLUSTERS' ? map.data.items : [];
  const pin = draft ? parseLocation(draft.lat, draft.lng) : null;
  const heading = draft?.heading.trim() ? Number(draft.heading) : null;
  const validHeading =
    heading === null ||
    (Number.isInteger(heading) && heading >= 0 && heading <= 359);
  const patch: MapSignPatch = {};
  if (action === 'edit' && published && draft && pin) {
    if (draft.code !== published.signTypeCode) patch.signTypeCode = draft.code;
    if (
      pin.lat !== published.location.lat ||
      pin.lng !== published.location.lng
    )
      patch.location = pin;
    if (heading !== (published.headingDeg ?? null)) patch.headingDeg = heading;
  }
  const validDraft = !!(
    draft?.code &&
    draft.positionChosen &&
    pin &&
    validHeading
  );
  const ready =
    action === 'remove'
      ? !!published && !!reason.trim()
      : action === 'create'
        ? validDraft &&
          !!catalog.data?.some((type) => type.code === draft?.code)
        : action === 'edit' && validDraft && Object.keys(patch).length > 0;

  function select(id: string) {
    setSelectedId(id);
    setAction(null);
    setDraft(null);
    setError('');
  }

  function start(next: Action, sign?: ReviewDetail) {
    setError('');
    setReason('');
    setAction(next);
    if (next === 'create') {
      setSelectedId(null);
      setDraft(draftFor(center, '', null, false));
    } else if (next === 'edit' && sign) {
      setCenter(sign.location);
      setDraft(draftFor(sign.location, sign.signTypeCode, sign.headingDeg));
    }
  }

  function changePosition(location: Location) {
    setDraft(
      (previous) =>
        previous && {
          ...previous,
          lat: String(location.lat),
          lng: String(location.lng),
          positionChosen: true,
        },
    );
  }

  function cancel() {
    setAction(null);
    setDraft(null);
    setReason('');
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!action || !ready || busy || !canEdit) return;
    setBusy(true);
    setError('');
    try {
      if (action === 'create' && draft && pin) {
        const input: MapSignInput = {
          signTypeCode: draft.code,
          location: pin,
          headingDeg: heading,
        };
        await reviewApi.createMapSign(token, input);
        setCenter(pin);
      } else if (action === 'edit' && published) {
        await reviewApi.patchMapSign(
          token,
          published.id,
          published.version,
          patch,
        );
        if (patch.location) setCenter(patch.location);
        detail.refresh();
      } else if (action === 'remove' && published) {
        await reviewApi.removeMapSign(
          token,
          published.id,
          published.version,
          reason,
        );
        setSelectedId(null);
      }
      cancel();
      notify(
        {
          create: 'Đã thêm biển lên bản đồ.',
          edit: 'Đã lưu thay đổi biển.',
          remove: 'Đã gỡ biển khỏi bản đồ.',
        }[action],
      );
      map.refresh();
    } catch (error) {
      if (error instanceof SessionExpiredError) {
        window.dispatchEvent(new Event('visionmap:expired'));
      } else if (
        error instanceof ReviewApiError &&
        (error.status === 409 || (error.status === 404 && action !== 'create'))
      ) {
        cancel();
        setError('Biển đã thay đổi hoặc đã được gỡ. Bản đồ đang được tải lại.');
        detail.refresh();
        map.refresh();
      } else {
        setError(errorMessage(error));
      }
    } finally {
      setBusy(false);
    }
  }

  function pan(x: number, y: number) {
    setCenter(view.fromScreen(adminMapWidth / 2 + x, adminMapHeight / 2 + y));
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">QUẢN LÝ DỮ LIỆU</div>
          <h1>
            Bản đồ biển báo<span className="heading-dot">.</span>
          </h1>
          <p>Chọn một biển trên bản đồ hoặc chọn vị trí để thêm biển mới.</p>
        </div>
        {canEdit && (
          <button
            className="button primary"
            disabled={busy || !!action}
            onClick={() => start('create')}
          >
            <Plus size={17} /> Thêm biển
          </button>
        )}
      </div>
      {error && (
        <div className="inline-error" role="alert">
          {error}
        </div>
      )}
      <section className="panel">
        <div className="panel-toolbar map-toolbar">
          <div className="map-controls" aria-label="Điều hướng bản đồ">
            <button
              className="button"
              aria-label="Dịch bản đồ lên"
              disabled={busy}
              onClick={() => pan(0, -160)}
            >
              <ArrowUp size={16} />
            </button>
            <button
              className="button"
              aria-label="Dịch bản đồ xuống"
              disabled={busy}
              onClick={() => pan(0, 160)}
            >
              <ArrowDown size={16} />
            </button>
            <button
              className="button"
              aria-label="Dịch bản đồ sang trái"
              disabled={busy}
              onClick={() => pan(-280, 0)}
            >
              <ArrowLeft size={16} />
            </button>
            <button
              className="button"
              aria-label="Dịch bản đồ sang phải"
              disabled={busy}
              onClick={() => pan(280, 0)}
            >
              <ArrowRight size={16} />
            </button>
            <button
              className="button"
              aria-label="Thu nhỏ bản đồ"
              disabled={busy || zoom === minZoom}
              onClick={() => setZoom(clampZoom(zoom - 1))}
            >
              <Minus size={16} />
            </button>
            <button
              className="button"
              aria-label="Phóng to bản đồ"
              disabled={busy || zoom === maxZoom}
              onClick={() => setZoom(clampZoom(zoom + 1))}
            >
              <Plus size={16} />
            </button>
          </div>
          <details className="map-goto">
            <summary>Tới tọa độ</summary>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                const location = parseLocation(goToLat, goToLng);
                if (location) {
                  setCenter(location);
                  setZoom(Math.max(zoom, 14));
                }
              }}
            >
              <label className="field">
                Vĩ độ bản đồ
                <input
                  type="number"
                  required
                  min="-90"
                  max="90"
                  step="any"
                  value={goToLat}
                  onChange={(event) => setGoToLat(event.target.value)}
                />
              </label>
              <label className="field">
                Kinh độ bản đồ
                <input
                  type="number"
                  required
                  min="-180"
                  max="180"
                  step="any"
                  value={goToLng}
                  onChange={(event) => setGoToLng(event.target.value)}
                />
              </label>
              <button
                type="submit"
                className="button"
                disabled={busy || !parseLocation(goToLat, goToLng)}
              >
                <MapPin size={16} /> Tới vị trí
              </button>
            </form>
          </details>
        </div>
        <div className="map-content">
          <ReviewMap
            center={center}
            zoom={zoom}
            wider
            location={pin ?? published?.location}
            nearby={signs}
            clusters={clusters}
            onChange={draft && !busy ? changePosition : undefined}
            onPan={!draft && !busy ? setCenter : undefined}
            onSelect={!action ? (marker) => select(marker.id) : undefined}
            onCluster={
              !busy
                ? (location) => {
                    setCenter(location);
                    setZoom(clampZoom(zoom + 2));
                  }
                : undefined
            }
          />
          {map.loading && (
            <p className="muted" role="status">
              Đang tải biển trong vùng…
            </p>
          )}
          {map.error && <ErrorPanel message={map.error} retry={map.refresh} />}
          {map.data?.mode === 'CLUSTERS' && (
            <p className="muted">Chọn một cụm để phóng to và xem từng biển.</p>
          )}
          {map.data?.mode === 'SIGNS' && signs.length === 0 && (
            <p className="muted">Chưa có biển trong vùng đang xem.</p>
          )}
          {draft && (
            <p className="muted">
              Bấm vị trí trên bản đồ, kéo ghim hoặc nhập tọa độ để đặt biển.
            </p>
          )}
          {selectedId && detail.loading && <Loading />}
          {selectedId && detail.error && (
            <ErrorPanel message={detail.error} retry={detail.refresh} />
          )}
          {sign && !published && (
            <Empty
              title="Biển không còn hiển thị"
              text="Chọn biển khác trên bản đồ để tiếp tục."
            />
          )}
          {published && !action && (
            <section className="duplicates">
              <div className="review-sign-title">
                <SignGraphic code={published.signTypeCode} />
                <div>
                  <span className="code">{published.signTypeCode}</span>
                  <h3>{published.signName || published.signTypeCode}</h3>
                </div>
              </div>
              <dl className="details-list">
                <div>
                  <dt>Trạng thái</dt>
                  <dd>
                    {published.status === 'APPROVED'
                      ? 'Đã duyệt'
                      : 'Đang rà soát'}
                  </dd>
                </div>
                <div>
                  <dt>Tọa độ</dt>
                  <dd>
                    {published.location.lat.toFixed(6)},{' '}
                    {published.location.lng.toFixed(6)}
                  </dd>
                </div>
                <div>
                  <dt>Hướng</dt>
                  <dd>
                    {published.headingDeg == null
                      ? 'Chưa xác định'
                      : published.headingDeg + '°'}
                  </dd>
                </div>
              </dl>
              {(canReadAudit || canEdit) && (
                <div className="modal-actions">
                  {canReadAudit && (
                    <a
                      className="button"
                      href={'#audit?signId=' + encodeURIComponent(published.id)}
                    >
                      Xem lịch sử biển
                    </a>
                  )}
                  {canEdit && (
                    <>
                      <button
                        className="button danger-outline"
                        onClick={() => start('remove', published)}
                      >
                        <Trash2 size={16} /> Gỡ biển
                      </button>
                      <button
                        className="button primary"
                        onClick={() => start('edit', published)}
                      >
                        Sửa biển
                      </button>
                    </>
                  )}
                </div>
              )}
            </section>
          )}
          {action && (
            <form className="confirm-box review-decision" onSubmit={submit}>
              <strong>
                {action === 'create'
                  ? 'Thêm biển mới'
                  : action === 'edit'
                    ? 'Sửa thông tin biển'
                    : 'Gỡ biển khỏi bản đồ'}
              </strong>
              {draft && (
                <>
                  {catalog.error && (
                    <ErrorPanel
                      message={catalog.error}
                      retry={catalog.refresh}
                    />
                  )}
                  <div className="review-fields">
                    <label className="field">
                      Mã biển
                      <select
                        className="standalone-select"
                        required
                        value={draft.code}
                        disabled={busy}
                        onChange={(event) =>
                          setDraft({ ...draft, code: event.target.value })
                        }
                      >
                        <option value="">Chọn mã biển</option>
                        {published &&
                          !catalog.data?.some(
                            ({ code }) => code === published.signTypeCode,
                          ) && (
                            <option value={published.signTypeCode}>
                              {published.signTypeCode}
                            </option>
                          )}
                        {catalog.data?.map(({ code, name }) => (
                          <option key={code} value={code}>
                            {code} · {name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="field">
                      Vĩ độ
                      <input
                        required
                        type="number"
                        min="-90"
                        max="90"
                        step="any"
                        value={draft.lat}
                        disabled={busy}
                        onChange={(event) =>
                          setDraft({
                            ...draft,
                            lat: event.target.value,
                            positionChosen: true,
                          })
                        }
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
                        value={draft.lng}
                        disabled={busy}
                        onChange={(event) =>
                          setDraft({
                            ...draft,
                            lng: event.target.value,
                            positionChosen: true,
                          })
                        }
                      />
                    </label>
                    <label className="field">
                      Hướng (độ)
                      <input
                        type="number"
                        min="0"
                        max="359"
                        step="1"
                        value={draft.heading}
                        disabled={busy}
                        placeholder="Chưa xác định"
                        onChange={(event) =>
                          setDraft({ ...draft, heading: event.target.value })
                        }
                      />
                    </label>
                  </div>
                </>
              )}
              {action === 'remove' && (
                <>
                  <p>
                    Biển {published?.signTypeCode} sẽ rời bản đồ sau khi xác
                    nhận.
                  </p>
                  <label className="field">
                    Lý do gỡ biển
                    <textarea
                      required
                      maxLength={1000}
                      value={reason}
                      disabled={busy}
                      onChange={(event) => setReason(event.target.value)}
                    />
                  </label>
                </>
              )}
              <div className="modal-actions">
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  onClick={cancel}
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  className={
                    'button ' + (action === 'remove' ? 'danger' : 'primary')
                  }
                  disabled={!ready || busy}
                >
                  {busy
                    ? 'Đang lưu…'
                    : action === 'remove'
                      ? 'Xác nhận gỡ biển'
                      : 'Lưu biển'}
                </button>
              </div>
            </form>
          )}
        </div>
      </section>
    </>
  );
}
