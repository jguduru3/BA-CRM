# syntax=docker/dockerfile:1.7
# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /build

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

COPY . .
ARG VITE_API_URL=
ENV VITE_API_URL=${VITE_API_URL}
RUN npm run build

# ── Stage 2: serve (unprivileged nginx, port 8080) ───────────────────────────
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
# nginx's official entrypoint runs envsubst on /etc/nginx/templates/*.template,
# so the API upstream can differ per environment without rebuilding the image.
ENV API_UPSTREAM=http://api:8080 \
    NGINX_ENVSUBST_FILTER=API_UPSTREAM
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=builder /build/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
