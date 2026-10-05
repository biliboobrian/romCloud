const test = require('node:test');
const assert = require('node:assert/strict');
const { encodeQuery, parseDns, collectDevices, encodeMessage, decodeMessage } = require('../src/main/cast');

// Réponse mDNS d'un Chromecast : PTR, puis SRV, TXT et A en additionnels (noms compressés).
function chromecastAnswer({ fn = 'Salon', ca = '4101', md = 'Chromecast', ip = [192, 168, 1, 42] } = {}) {
  const parts = [];
  let size = 0;
  const offsets = {};
  const push = (b) => {
    parts.push(b);
    size += b.length;
  };
  const u16 = (n) => Buffer.from([n >> 8, n & 0xff]);
  const name = (labels, pointer) => {
    const b = [];
    for (const l of labels) b.push(Buffer.from([Buffer.byteLength(l)]), Buffer.from(l));
    b.push(pointer === undefined ? Buffer.from([0]) : Buffer.from([0xc0 | (pointer >> 8), pointer & 0xff]));
    return Buffer.concat(b);
  };
  const record = (owner, type, rdata) => push(Buffer.concat([owner, u16(type), u16(0x8001), Buffer.from([0, 0, 0, 120]), u16(rdata.length), rdata]));

  push(Buffer.from([0, 0, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 3]));
  offsets.service = size;
  const service = name(['_googlecast', '_tcp', 'local']);
  // PTR : _googlecast._tcp.local → Chromecast-abc._googlecast._tcp.local
  record(service, 12, name(['Chromecast-abc'], offsets.service));
  offsets.instance = size - (Buffer.byteLength('Chromecast-abc') + 3);
  const instance = Buffer.from([0xc0, offsets.instance]);
  const host = name(['abc', 'local']);
  record(instance, 33, Buffer.concat([u16(0), u16(0), u16(8009), host]));
  const txt = ['id=abc123', `fn=${fn}`, `md=${md}`, `ca=${ca}`].map((s) => Buffer.concat([Buffer.from([Buffer.byteLength(s)]), Buffer.from(s)]));
  record(instance, 16, Buffer.concat(txt));
  record(host, 1, Buffer.from(ip));
  return Buffer.concat(parts);
}

test('requête mDNS : une question PTR pour _googlecast._tcp.local', () => {
  const q = encodeQuery('_googlecast._tcp.local');
  assert.equal(q.readUInt16BE(4), 1);
  assert.equal(q.readUInt16BE(q.length - 4), 12);
  assert.ok(q.includes(Buffer.from('_googlecast')));
});

test('réponse mDNS : appareil avec nom, modèle, adresse et port', () => {
  const devices = collectDevices(parseDns(chromecastAnswer()));
  assert.deepEqual(devices, [{ id: 'abc123', name: 'Salon', model: 'Chromecast', host: '192.168.1.42', port: 8009 }]);
});

test('enceintes et groupes (sans sortie vidéo) ignorés', () => {
  assert.equal(collectDevices(parseDns(chromecastAnswer({ ca: '4100', md: 'Google Nest Mini' }))).length, 0);
  assert.equal(collectDevices(parseDns(chromecastAnswer({ md: 'Google Cast Group' }))).length, 0);
});

test('adresse de l’expéditeur utilisée si l’enregistrement A manque', () => {
  const records = parseDns(chromecastAnswer()).filter((r) => r.type !== 1);
  const sources = new Map([['chromecast-abc._googlecast._tcp.local', '10.0.0.7']]);
  assert.equal(collectDevices(records, sources)[0].host, '10.0.0.7');
});

test('message Cast : encodage puis décodage', () => {
  const frame = encodeMessage({ source: 'sender-0', destination: 'receiver-0', namespace: 'urn:x-cast:com.google.cast.receiver', data: { type: 'LAUNCH', appId: 'CC1AD845', requestId: 1 } });
  assert.equal(frame.readUInt32BE(0), frame.length - 4);
  const msg = decodeMessage(frame.subarray(4));
  assert.equal(msg.source, 'sender-0');
  assert.equal(msg.destination, 'receiver-0');
  assert.equal(msg.namespace, 'urn:x-cast:com.google.cast.receiver');
  assert.deepEqual(msg.data, { type: 'LAUNCH', appId: 'CC1AD845', requestId: 1 });
});

test('message Cast long (longueur sur plusieurs octets)', () => {
  const url = `http://192.168.1.10:5000/${'a'.repeat(300)}.webm`;
  const frame = encodeMessage({ source: 's', destination: 'd', namespace: 'n', data: { url } });
  assert.equal(decodeMessage(frame.subarray(4)).data.url, url);
});
