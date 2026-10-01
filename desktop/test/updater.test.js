const test = require('node:test');
const assert = require('node:assert/strict');
const { isNewer, parseVersion, pickAsset } = require('../src/main/updater');

test('comparaison des versions', () => {
  assert.equal(isNewer('1.12.0', '1.11.0'), true);
  assert.equal(isNewer('v1.11.1', '1.11.0'), true);
  assert.equal(isNewer('2.0', '1.99.99'), true);
  assert.equal(isNewer('1.11.0', '1.11.0'), false);
  assert.equal(isNewer('1.11', '1.11.0'), false);
  assert.equal(isNewer('1.9.0', '1.10.0'), false);
  // Versions de développement : jamais de mise à jour.
  assert.equal(parseVersion('1.0.0-dev'), null);
  assert.equal(isNewer('1.12.0', '1.0.0-dev'), false);
});

test('fichier de la Release : installeur ou portable, avec SHA256', () => {
  const release = {
    tag_name: 'v1.12.0',
    assets: [
      { name: 'RomCloud-1.12.0.apk', size: 1, browser_download_url: 'apk' },
      { name: 'RomCloud-Windows-1.12.0-x64.exe', size: 10, browser_download_url: 'setup', digest: `sha256:${'A'.repeat(64)}` },
      { name: 'RomCloud-Windows-1.12.0-portable.exe', size: 20, browser_download_url: 'portable' },
    ],
  };
  assert.deepEqual(pickAsset(release, false), {
    version: '1.12.0', name: 'RomCloud-Windows-1.12.0-x64.exe', url: 'setup', size: 10, sha256: 'a'.repeat(64),
  });
  assert.equal(pickAsset(release, true).url, 'portable');
  assert.equal(pickAsset(release, true).sha256, null);
  assert.equal(pickAsset({ tag_name: 'v1.12.0', assets: [] }, false), null);
});
