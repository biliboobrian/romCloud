# Serveur RomCloud — image multi-architecture (amd64, arm64)

# chdman (MAME) : conversion des jeux sur CD en CHD (chd.js). Paquet d'Alpine edge (testing) seulement,
# lié à des bibliothèques plus récentes que celles de l'image : copié avec elles (et SDL3, que la
# libSDL2 d'edge charge au démarrage) et son propre chargeur musl, lancé par /usr/local/bin/chdman
# sans toucher aux bibliothèques du système.
FROM alpine:edge AS chdman
RUN apk add --no-cache --repository=https://dl-cdn.alpinelinux.org/alpine/edge/testing mame-tools \
 && mkdir -p /opt/chdman/lib \
 && cp /usr/bin/chdman /opt/chdman/chdman \
 && cp -L /lib/ld-musl-*.so.1 /opt/chdman/ld-musl.so.1 \
 && for bin in /usr/bin/chdman /usr/lib/libSDL3.so.0; do \
      cp -L "$bin" /opt/chdman/lib/ 2>/dev/null; \
      ldd "$bin" | awk '$2 == "=>" && $3 ~ /^\// { print $3 }' | xargs -r -I{} cp -L {} /opt/chdman/lib/; \
    done \
 && rm -f /opt/chdman/lib/chdman \
 && printf '#!/bin/sh\nexec /opt/chdman/ld-musl.so.1 --library-path /opt/chdman/lib /opt/chdman/chdman "$@"\n' > /opt/chdman/run \
 && chmod +x /opt/chdman/run \
 && /opt/chdman/run help > /tmp/chdman-help.txt 2>&1; grep -q "chdman" /tmp/chdman-help.txt || { cat /tmp/chdman-help.txt; exit 1; }

FROM node:24-alpine

# Version affichée par le serveur (tag de la publication, passé par la CI).
ARG VERSION=""
ENV ROMCLOUD_VERSION=$VERSION

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=8080

# zstd : compression des jeux PC ; xz (XZ Utils) : lecture des compressions d'avant (folder-pack.js) ;
# chdman : conversion des jeux sur CD en CHD (chd.js) ; bsdtar (libarchive-tools) : lecture des
# archives .7z et .rar à convertir en CHD.
RUN apk add --no-cache xz zstd libarchive-tools
COPY --from=chdman /opt/chdman /opt/chdman
RUN ln -s /opt/chdman/run /usr/local/bin/chdman \
 && chdman help > /tmp/chdman-help.txt 2>&1; grep -q "chdman" /tmp/chdman-help.txt || { cat /tmp/chdman-help.txt; exit 1; }; rm -f /tmp/chdman-help.txt

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
