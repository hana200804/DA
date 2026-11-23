FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_DATA_MODE=api
ARG VITE_API_CONTRACT=signa
ENV VITE_DATA_MODE=$VITE_DATA_MODE
ENV VITE_API_CONTRACT=$VITE_API_CONTRACT
RUN npm run build

FROM nginx:stable-alpine
ARG VITE_API_CONTRACT=signa
ENV SIGNA_TARGET=http://nginx:80
ENV NGINX_ENVSUBST_FILTER="^SIGNA_TARGET$"
COPY nginx.conf nginx.legacy.conf /opt/visionmap/
RUN case "$VITE_API_CONTRACT" in \
      signa) mkdir -p /etc/nginx/templates && mv /opt/visionmap/nginx.conf /etc/nginx/templates/default.conf.template ;; \
      legacy) mv /opt/visionmap/nginx.legacy.conf /etc/nginx/conf.d/default.conf ;; \
      *) echo "VITE_API_CONTRACT must be signa or legacy" >&2; exit 1 ;; \
    esac
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 3000
CMD ["nginx", "-g", "daemon off;"]
