# Serveur RomCloud — image multi-architecture (amd64, arm64)
FROM node:22-alpine

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=8080

WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src
COPY public ./public
COPY platforms ./platforms
COPY bios-hashes.json ./

# Exécution sans droits root (utilisateur « node », uid 1000).
RUN mkdir -p /data && chown node:node /data
USER node

VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/api/info" > /dev/null || exit 1

CMD ["node", "--disable-warning=ExperimentalWarning", "src/index.js"]
