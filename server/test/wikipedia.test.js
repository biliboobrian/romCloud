import { test } from 'node:test';
import assert from 'node:assert/strict';

const { matchArticle, scrapeWikipedia } = await import('../src/scraper/wikipedia.js');

test('article retenu seulement si son titre correspond au jeu', () => {
  const results = [{ title: 'Sonic the Hedgehog (série)' }, { title: 'Sonic the Hedgehog 2 (jeu vidéo, 16 bits)' }];
  assert.equal(matchArticle(results, 'Sonic the Hedgehog 2'), 'Sonic the Hedgehog 2 (jeu vidéo, 16 bits)');
  assert.equal(matchArticle(results, 'Sonic 3'), null);
  assert.equal(matchArticle([{ title: 'Legend of Zelda: A Link to the Past, The' }], 'The Legend of Zelda: A Link to the Past'),
    'Legend of Zelda: A Link to the Past, The');
});

test('résumé dans la première langue qui a un article sur un jeu', async () => {
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(url);
    const json = (body) => ({ ok: true, json: async () => body });
    if (url.startsWith('https://fr.wikipedia.org/w/api.php')) return json({ query: { search: [{ title: 'Streets of Rage' }] } });
    // Homonyme en français : pas un jeu -> ignoré.
    if (url.startsWith('https://fr.wikipedia.org/api/rest_v1')) return json({ type: 'standard', extract: 'Un film de 1995.' });
    if (url.startsWith('https://en.wikipedia.org/w/api.php')) return json({ query: { search: [{ title: 'Streets of Rage (video game)' }] } });
    if (url.startsWith('https://en.wikipedia.org/api/rest_v1/page/summary/Streets_of_Rage_(video_game)')) {
      return json({ type: 'standard', extract: 'Streets of Rage is a 1991 beat \'em up game.' });
    }
    return { ok: false };
  };
  const wiki = await scrapeWikipedia({ title: 'Streets of Rage', system: 'Mega Drive', languages: ['fr', 'en'] });
  assert.equal(wiki.text, 'Streets of Rage is a 1991 beat \'em up game.');
  // Adresse de l'article (sans content_urls dans la réponse : construite depuis le titre).
  assert.equal(wiki.url, 'https://en.wikipedia.org/wiki/Streets_of_Rage_(video_game)');
  assert.match(calls[0], /srsearch=Streets%20of%20Rage%20jeu%20vid%C3%A9o%20Mega%20Drive/);
});

test('aucun article correspondant : null', async () => {
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ query: { search: [{ title: 'Autre chose' }] } }) });
  assert.equal(await scrapeWikipedia({ title: 'Jeu inconnu', system: null, languages: ['fr', 'xx'] }), null);
});
