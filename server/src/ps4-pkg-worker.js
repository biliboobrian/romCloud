// Conversion d'un paquet PS4 dans un fil d'exécution à part (lectures, déchiffrement et
// décompression synchrones : le serveur reste disponible pendant ce temps).
import { parentPort, workerData } from 'node:worker_threads';
import { extractPs4Pkg } from './ps4-pkg.js';

const result = extractPs4Pkg(workerData.pkgFile, workerData.zipFile, (done, total) => parentPort.postMessage({ type: 'progress', done, total }));
parentPort.postMessage({ type: 'done', result });
