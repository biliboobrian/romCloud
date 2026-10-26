// Compression zstd des jeux PC, par l'outil « zstd » (ZSTD_PATH pour un autre emplacement). Niveau
// 19 et fenêtre de 128 Mo (--long=27) : meilleur rapport place / temps mesuré sur des jeux Unity
// (1,4 Go) : archives 2 % plus grosses qu'en xz -9e, compression 1,6 fois plus rapide et
// décompression 30 fois plus rapide dans les applications (zstd natif contre xz en Java sur Android).
// Plus haut (--ultra -22) : 0,1 % de gagné pour une compression 1,7 fois plus lente.
import { cliCodec } from './codec.js';

/** Fenêtre des archives (2^27 octets) : à donner aussi aux décodeurs (limite par défaut de zstd). */
export const ZSTD_WINDOW_LOG = 27;

const zstd = cliCodec(
  'zstd',
  process.env.ZSTD_PATH || 'zstd',
  ['-19', `--long=${ZSTD_WINDOW_LOG}`, '-T0', '-q', '-c'],
  ['-dc', `--long=${ZSTD_WINDOW_LOG}`, '-q'],
);

export const zstdAvailable = () => zstd.available();
export const zstdCompress = (input, dest, options) => zstd.compress(input, dest, options);
export const zstdDecompressStream = (file, signal) => zstd.decompressStream(file, signal);
