// Pont entre l'interface et le processus principal (Rust) : objet window.romcloud. Chaque appel
// renvoie { ok, data } ou { ok: false, error }.
// Injecté avant le chargement de la page ; les fonctions de Tauri sont lues au moment de l'appel.
(function () {
  const tauri = () => window.__TAURI__;
  const invoke = (cmd, args) => tauri().core.invoke(cmd, args);
  const asError = (error) => (error && typeof error === 'object' ? error : { key: null, vars: {}, message: String(error) });
  const call = (channel) => (...args) =>
    invoke('ipc', { channel, args: args.map((a) => (a === undefined ? null : a)) }).then(
      (data) => ({ ok: true, data }),
      (error) => ({ ok: false, error: asError(error) }),
    );
  const on = (event) => (fn) => {
    const listen = () => tauri().event.listen(event, (e) => fn(e.payload));
    if (window.__TAURI__) listen();
    else window.addEventListener('DOMContentLoaded', listen, { once: true });
  };

  // ---- Diffusion de l'écran sur un Chromecast : capture dans la page, morceaux WebM envoyés au
  // processus principal qui les sert au Chromecast (une capture par connexion du Chromecast).
  let stream = null;
  let recorder = null;

  function stopRecorder() {
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    recorder = null;
  }

  function releaseStream() {
    stopRecorder();
    for (const track of stream?.getTracks() || []) track.stop();
    stream = null;
  }

  /** Écran (et son du PC) choisi par l'utilisateur, demandé au clic sur la diffusion. */
  async function ensureStream() {
    if (stream && stream.active) return;
    stream = await navigator.mediaDevices.getDisplayMedia({
      video: { width: { max: 1280 }, height: { max: 720 }, frameRate: { max: 30 } },
      audio: true,
    });
    // Partage arrêté depuis Windows : fin de la diffusion.
    stream.getVideoTracks()[0]?.addEventListener('ended', () => {
      releaseStream();
      call('cast:stop')();
    });
  }

  function startRecorder() {
    stopRecorder();
    if (!stream) return;
    try {
      const audio = stream.getAudioTracks().length > 0;
      // VP8 et Opus : lus par tous les Chromecast.
      const mimeType = [audio ? 'video/webm;codecs=vp8,opus' : 'video/webm;codecs=vp8', 'video/webm'].find((t) => MediaRecorder.isTypeSupported(t));
      const current = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 4_000_000, audioBitsPerSecond: 128_000 });
      recorder = current;
      // Morceaux transmis dans l'ordre (lecture asynchrone des Blob).
      let queue = Promise.resolve();
      current.ondataavailable = (e) => {
        if (!e.data.size || recorder !== current) return;
        queue = queue.then(async () => {
          const data = new Uint8Array(await e.data.arrayBuffer());
          if (recorder === current) await invoke('cast_chunk', data);
        });
      };
      current.onerror = (e) => call('cast:captureError')(String(e.error?.message || e.error || 'MediaRecorder'));
      current.start(200);
    } catch (err) {
      call('cast:captureError')(err.message || String(err));
    }
  }

  on('capture:start')(() => startRecorder());
  on('capture:stop')(() => stopRecorder());
  on('cast:update')((state) => {
    if (state.status === 'idle') releaseStream();
  });

  window.romcloud = {
    settings: { get: call('settings:get'), save: call('settings:save') },
    api: { test: call('api:test'), systems: call('api:systems'), games: call('api:games'), search: call('api:search') },
    library: {
      downloaded: call('library:downloaded'),
      path: call('library:path'),
      remove: call('library:remove'),
      systemsWithGames: call('library:systemsWithGames'),
      usage: call('library:usage'),
    },
    connectivity: { state: call('connectivity:state'), check: call('connectivity:check'), onUpdate: on('connectivity:update') },
    netplay: { peers: call('netplay:peers'), systemAllows: call('netplay:systemAllows'), systemTogether: call('netplay:systemTogether'), onPeers: on('netplay:peers') },
    bios: { missing: call('bios:missing') },
    downloads: {
      start: call('downloads:start'),
      cancel: call('downloads:cancel'),
      dismiss: call('downloads:dismiss'),
      states: call('downloads:states'),
      onUpdate: on('downloads:update'),
    },
    launcher: {
      options: call('launcher:options'),
      choose: call('launcher:choose'),
      play: call('launcher:play'),
      resumable: call('launcher:resumable'),
      resumableIds: call('launcher:resumableIds'),
      check: call('launcher:check'),
      resumableOnline: call('launcher:resumableOnline'),
      describe: call('launcher:describe'),
    },
    states: { list: call('states:list'), action: call('states:action') },
    emulators: {
      list: call('emulators:list'),
      detect: call('emulators:detect'),
      setPath: call('emulators:setPath'),
      setArgs: call('emulators:setArgs'),
      launch: call('emulators:launch'),
      checkUpdate: call('emulators:checkUpdate'),
      update: call('emulators:update'),
    },
    dialog: { pickFile: call('dialog:pickFile'), pickFolder: call('dialog:pickFolder') },
    shell: { showItem: call('shell:showItem'), openExternal: call('shell:openExternal') },
    keyboard: { layout: call('keyboard:layout'), save: call('keyboard:save') },
    app: { version: call('app:version') },
    player: { resetCore: call('player:resetCore'), onCrash: on('player:crashed') },
    account: {
      state: call('account:state'),
      register: call('account:register'),
      login: call('account:login'),
      logout: call('account:logout'),
      playtime: call('account:playtime'),
      reportError: call('account:reportError'),
      onUpdate: on('account:update'),
    },
    cast: {
      discover: call('cast:discover'),
      // Écran à partager choisi d'abord (pendant le clic de l'utilisateur, exigé par le navigateur).
      start: async (device) => {
        try {
          await ensureStream();
        } catch (err) {
          return { ok: false, error: { key: 'cast.errors.capture', vars: { detail: err.message || String(err) }, message: String(err) } };
        }
        const result = await call('cast:start')(device);
        if (!result.ok) releaseStream();
        return result;
      },
      stop: async () => {
        releaseStream();
        return call('cast:stop')();
      },
      state: call('cast:state'),
      onUpdate: on('cast:update'),
    },
    update: { check: call('update:check'), install: call('update:install'), onProgress: on('update:progress') },
  };
})();
