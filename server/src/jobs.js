// File de tâches en mémoire, exécutées une par une ; dans une tâche, SCRAPE_CONCURRENCY jeux
// scrapés en même temps (ScreenScraper limite le nombre de requêtes simultanées selon le compte).
import { config, screenscraperEnabled } from './config.js';
import { QuotaError, scrapeGame } from './scraper/index.js';
import { screenscraperMaxThreads } from './scraper/screenscraper.js';

let nextId = 1;
const jobs = [];
let running = false;

function publicJob(job) {
  const { items, cancelRequested, ...rest } = job;
  return { ...rest, cancellable: job.status === 'queued' || job.status === 'running' };
}

export function listJobs() {
  return jobs.slice(-20).reverse().map(publicJob);
}

export function cancelJob(id) {
  const job = jobs.find((j) => j.id === Number(id));
  if (!job) return false;
  job.cancelRequested = true;
  if (job.status === 'queued') job.status = 'cancelled';
  return true;
}

/** Programme le scraping d'une liste de jeux. */
export function enqueueScrape({ label, systemId, gameIds, source = 'auto' }) {
  const job = {
    id: nextId++,
    type: 'scrape',
    label,
    systemId,
    source,
    status: 'queued',
    total: gameIds.length,
    done: 0,
    ok: 0,
    notFound: 0,
    failed: 0,
    lastError: null,
    createdAt: new Date().toISOString(),
    finishedAt: null,
    items: gameIds,
    cancelRequested: false,
  };
  jobs.push(job);
  if (jobs.length > 100) jobs.splice(0, jobs.length - 100);
  run();
  return publicJob(job);
}

/** Jeux scrapés en même temps : réglage du serveur, borné par le compte ScreenScraper s'il sert. */
export function scrapeConcurrency(source, { configured = config.scrapeConcurrency, ssEnabled = screenscraperEnabled(), ssMax = screenscraperMaxThreads() } = {}) {
  const usesSS = source === 'screenscraper' || (source === 'auto' && ssEnabled);
  return Math.max(1, usesSS && ssMax ? Math.min(configured, ssMax) : configured);
}

async function scrapeOne(job, gameId) {
  try {
    const game = await scrapeGame(gameId, job.source);
    if (game.scrapeStatus === 'ok') job.ok++;
    else if (game.scrapeStatus === 'notfound') job.notFound++;
    else job.failed++;
  } catch (err) {
    job.failed++;
    job.lastError = err.message;
    if (err instanceof QuotaError) {
      job.cancelRequested = true;
    }
  }
  job.done++;
}

/** Jeux de la tâche répartis entre plusieurs « ouvriers » qui prennent chacun le suivant. */
async function runJob(job) {
  let next = 0;
  const worker = async (index) => {
    // Limite relue avant chaque jeu : maxthreads de ScreenScraper connu après la première réponse.
    while (!job.cancelRequested && next < job.items.length && index < scrapeConcurrency(job.source)) {
      await scrapeOne(job, job.items[next++]);
    }
  };
  const count = Math.min(config.scrapeConcurrency, job.items.length);
  await Promise.all(Array.from({ length: count }, (_, i) => worker(i)));
}

async function run() {
  if (running) return;
  running = true;
  try {
    let job;
    while ((job = jobs.find((j) => j.status === 'queued'))) {
      job.status = 'running';
      await runJob(job);
      job.status = job.cancelRequested ? (job.lastError ? 'failed' : 'cancelled') : 'done';
      job.finishedAt = new Date().toISOString();
    }
  } finally {
    running = false;
  }
}
