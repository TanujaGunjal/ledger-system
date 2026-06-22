# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci --quiet

# VITE_ vars are baked into the bundle at build time, not at runtime.
# For local Docker use, the defaults (localhost) are correct because the
# browser on the host machine reaches services via mapped ports.
# Override for cloud deployments:
#   docker build --build-arg VITE_LEDGER_API_BASE=https://api.example.com ...
ARG VITE_LEDGER_API_BASE=http://localhost:9090
ARG VITE_RECON_API_BASE=http://localhost:9091
ENV VITE_LEDGER_API_BASE=$VITE_LEDGER_API_BASE
ENV VITE_RECON_API_BASE=$VITE_RECON_API_BASE

COPY . .
RUN npm run build

# ── Stage 2: Serve ────────────────────────────────────────────────────────────
# nginx:alpine is ~8 MB. Serves the pre-built static bundle.
# This is what a reviewer should run — not the dev server.
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
