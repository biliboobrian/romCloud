// File de tâches en mémoire, exécutées une par une (ScreenScraper limite le nombre
// de requêtes simultanées pour les comptes gratuits).
import { QuotaError, scrapeGame } from './scraper/index.js';

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

async function run() {
  if (running) return;
  running = true;
  try {
    let job;
    while ((job = jobs.find((j) => j.status === 'queued'))) {
      job.status = 'running';
      for (const gameId of job.items) {
        if (job.cancelRequested) break;
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
      job.status = job.cancelRequested ? (job.lastError ? 'failed' : 'cancelled') : 'done';
      job.finishedAt = new Date().toISOString();
    }
  } finally {
    running = false;
  }
}
