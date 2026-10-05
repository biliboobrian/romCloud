// Capture de l'écran et du son du PC, encodée en WebM et envoyée par morceaux au processus principal
// qui la sert au Chromecast. Une capture par connexion du Chromecast (le flux doit commencer par l'en-tête).
(function () {
  let recorder = null;
  let stream = null;

  const video = (sourceId) => ({
    mandatory: { chromeMediaSource: 'desktop', chromeMediaSourceId: sourceId, maxWidth: 1280, maxHeight: 720, maxFrameRate: 30 },
  });

  async function open(sourceId) {
    try {
      // Son du PC (boucle de sortie de Windows) avec l'image.
      return await navigator.mediaDevices.getUserMedia({ audio: { mandatory: { chromeMediaSource: 'desktop' } }, video: video(sourceId) });
    } catch {
      return navigator.mediaDevices.getUserMedia({ audio: false, video: video(sourceId) });
    }
  }

  function stop() {
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    recorder = null;
    for (const track of stream?.getTracks() || []) track.stop();
    stream = null;
  }

  window.capture.onStop(stop);
  window.capture.onStart(async (sourceId) => {
    stop();
    try {
      stream = await open(sourceId);
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
          if (recorder === current) window.capture.chunk(data); // pas de morceau d'une capture précédente
        });
      };
      current.onerror = (e) => window.capture.error(String(e.error?.message || e.error || 'MediaRecorder'));
      current.start(200);
    } catch (err) {
      stop();
      window.capture.error(err.message || String(err));
    }
  });
})();
