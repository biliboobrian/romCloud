# Serveur RomCloud — image multi-architecture (amd64, arm64)
FROM node:24-alpine

# Version affichée par le serveur (tag de la publication, passé par la CI).
ARG VERSION=""
ENV ROMCLOUD_VERSION=$VERSION

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=8080

# xz (XZ Utils) : compression des jeux PC (folder-pack.js).
RUN apk add --no-cache xz

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
