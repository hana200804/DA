FROM python:3.11.18-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    SIGNA_DEVICE=cpu \
    SIGNA_YOLOV5_REPO=/opt/yolov5 \
    OMP_NUM_THREADS=2 \
    OPENBLAS_NUM_THREADS=1

RUN apt-get update \
 && apt-get install -y --no-install-recommends git libgl1 libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/* \
 && git init /opt/yolov5 \
 && git -C /opt/yolov5 fetch --depth 1 https://github.com/ultralytics/yolov5.git 1e7cf74cb64d719b34f7315aa13eab9e32cde672 \
 && git -C /opt/yolov5 checkout --detach FETCH_HEAD

WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
RUN python -m pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.8.0 torchvision==0.23.0 \
 && python -m pip install --no-cache-dir -e '.[serve-all]'

COPY service ./service
COPY data/labels.yaml ./data/labels.yaml

CMD ["uvicorn", "service.app:app", "--host", "0.0.0.0", "--port", "8000"]
