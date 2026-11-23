import { useEffect, useState } from 'react';
import { Images, ScanLine } from 'lucide-react';
import { comparisonApi, isDemo } from '../api';
import { Empty, ErrorPanel, Loading, useLoad } from '../components';
import { referenceEvaluation } from '../compareDemo';
import {
  boxPercent,
  modelLabels,
  modelNames,
  validateComparisonImage,
  type Comparison,
  type Evaluation,
  type ModelName,
} from '../compareTypes';
import { errorMessage, SessionExpiredError } from '../domain';

const formatPercent = (value?: number | null) =>
  value == null || !Number.isFinite(value)
    ? '—'
    : (value * 100).toFixed(2) + '%';

export function ModelComparison({ token }: { token: string }) {
  const [file, setFile] = useState<File | null>(null);
  const [src, setSrc] = useState('');
  const [result, setResult] = useState<Comparison | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const evaluation = useLoad(() => comparisonApi.modelEvaluations(), []);

  const evaluationRows = evaluation.data ?? [];
  const rows = evaluationRows.some((row) => row.model === 'yolov8')
    ? evaluationRows
    : [...evaluationRows, referenceEvaluation];

  useEffect(() => {
    if (!file) {
      setSrc('');
      return;
    }
    const url = URL.createObjectURL(file);
    setSrc(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  function choose(event: React.ChangeEvent<HTMLInputElement>) {
    const image = event.currentTarget.files?.[0];
    setResult(null);
    setError('');
    setFile(null);
    if (!image) return;
    try {
      validateComparisonImage(image);
      setFile(image);
    } catch (error) {
      event.currentTarget.value = '';
      setError(errorMessage(error));
    }
  }

  async function compare(event: React.FormEvent) {
    event.preventDefault();
    if (!file || busy) return;
    setBusy(true);
    setError('');
    setResult(null);
    try {
      setResult(await comparisonApi.compareImage(token, file));
    } catch (error) {
      setError(errorMessage(error));
      if (error instanceof SessionExpiredError)
        window.dispatchEvent(new Event('visionmap:expired'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">NHẬN DIỆN BIỂN BÁO</div>
          <h1>
            So sánh mô hình<span className="heading-dot">.</span>
          </h1>
          <p>Chạy YOLOv5, YOLOv8 và DETR trên cùng một ảnh.</p>
        </div>
      </div>
      {isDemo && (
        <div className="notice review-notice" role="note">
          <ScanLine size={18} />
          <span>
            Demo hiển thị khung, điểm và thời gian giả lập. Ảnh của bạn chỉ được
            xem trước trong trình duyệt.
          </span>
        </div>
      )}
      <section className="panel">
        <form className="panel-toolbar comparison-upload" onSubmit={compare}>
          <label className="field">
            Ảnh so sánh · JPEG, PNG hoặc WebP, tối đa 10 MB
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={choose}
              disabled={busy}
            />
          </label>
          <button
            className="button primary"
            type="submit"
            disabled={!file || busy}
          >
            <Images size={16} /> {busy ? 'Đang so sánh…' : 'So sánh 3 mô hình'}
          </button>
        </form>
        {error && (
          <div className="inline-error audit-error" role="alert">
            {error}
          </div>
        )}
        {busy ? (
          <Loading />
        ) : result && src ? (
          <div className="comparison-grid">
            {modelNames.map((name) => (
              <ModelCard key={name} name={name} result={result} src={src} />
            ))}
          </div>
        ) : src ? (
          <div className="comparison-preview">
            <img src={src} alt="Ảnh xem trước để so sánh" />
          </div>
        ) : (
          <Empty
            title="Chọn một ảnh để bắt đầu"
            text="Kết quả của từng mô hình sẽ xuất hiện cạnh nhau."
          />
        )}
      </section>
      <section className="panel comparison-metrics">
        <div className="panel-title">
          <div>
            <h2>Chỉ số đánh giá mô hình</h2>
            <p>Chỉ số theo tập dữ liệu và phiên bản mô hình.</p>
          </div>
        </div>
        {evaluation.error && (
          <ErrorPanel message={evaluation.error} retry={evaluation.refresh} />
        )}
        <EvaluationTable rows={rows} />
      </section>
    </>
  );
}

function ModelCard({
  src,
  name,
  result,
}: {
  src: string;
  name: ModelName;
  result: Comparison;
}) {
  const model = result.results.find((item) => item?.model?.name === name);
  const detections = model?.error ? [] : model?.detections;
  const image = model?.image ?? result.image;
  return (
    <article className="comparison-card">
      <header>
        <div>
          <h2>{modelLabels[name]}</h2>
          {model?.model.version && <small>{model.model.version}</small>}
        </div>
        {model?.inferenceMs != null && Number.isFinite(model.inferenceMs) && (
          <span className="subtle-tag">{model.inferenceMs} ms</span>
        )}
      </header>
      <div className="comparison-image">
        <img src={src} alt={'Ảnh dùng cho ' + modelLabels[name]} />
        {detections?.map((row, index) => {
          const bounds = boxPercent(row.box, image);
          return bounds ? (
            <span
              className="comparison-box"
              key={index}
              style={{
                left: bounds.left + '%',
                top: bounds.top + '%',
                width: bounds.width + '%',
                height: bounds.height + '%',
              }}
            >
              <span aria-hidden="true">{index + 1}</span>
            </span>
          ) : null;
        })}
      </div>
      {model?.error ? (
        <p className="muted">{model.error.message}</p>
      ) : !detections ? (
        <p className="muted">Mô hình chưa trả kết quả.</p>
      ) : detections.length ? (
        <ol className="comparison-detections">
          {detections.map((row, index) => (
            <li key={index}>
              <strong>{row.signTypeCode}</strong>{' '}
              <span>{formatPercent(row.confidence)}</span>
            </li>
          ))}
        </ol>
      ) : (
        <p className="muted">Không tìm thấy biển báo.</p>
      )}
    </article>
  );
}

function EvaluationTable({ rows }: { rows: Evaluation[] }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>MÔ HÌNH</th>
            <th>TẬP ĐÁNH GIÁ</th>
            <th>mAP@0.5</th>
            <th>mAP@0.5:0.95</th>
            <th>PRECISION</th>
            <th>RECALL</th>
          </tr>
        </thead>
        <tbody>
          {modelNames.flatMap((name) => {
            const modelRows = rows.filter((row) => row.model === name);
            if (!modelRows.length)
              return [
                <tr key={name}>
                  <th scope="row">{modelLabels[name]}</th>
                  <td colSpan={5}>Chưa có kết quả đánh giá.</td>
                </tr>,
              ];
            return modelRows.map((row, index) => (
              <tr key={name + ':' + index}>
                <th scope="row">
                  {modelLabels[name]}
                  <small className="comparison-version">{row.version}</small>
                </th>
                <td>
                  {row.split} {row.datasetVersion && '· ' + row.datasetVersion}
                  {row.referenceOnly && (
                    <small className="comparison-version">
                      Chỉ số tham khảo
                    </small>
                  )}
                </td>
                <td>{formatPercent(row.map50)}</td>
                <td>{formatPercent(row.map50To95)}</td>
                <td>{formatPercent(row.precision)}</td>
                <td>{formatPercent(row.recall)}</td>
              </tr>
            ));
          })}
        </tbody>
      </table>
    </div>
  );
}
