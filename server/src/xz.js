// Compression xz (LZMA2) des jeux PC, par l'outil « xz » (XZ Utils, XZ_PATH pour un autre
// emplacement) : format des compressions d'avant le zstd (zstd.js), toujours lu et converti. Le
// plus petit, mais décompressé lentement par les applications (décodeur Java d'Android : 30 fois
// moins vite que zstd).
import { cliCodec } from './codec.js';

const xz = cliCodec('xz', process.env.XZ_PATH || 'xz', ['-9e', '-T0', '-c', '-q'], ['-dc', '-T0', '-q']);

export const xzAvailable = () => xz.available();
export const xzCompress = (input, dest, options) => xz.compress(input, dest, options);
export const xzDecompressStream = (file, signal) => xz.decompressStream(file, signal);
