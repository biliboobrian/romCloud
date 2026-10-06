// Pont entre l'interface (page web isolée) et le processus principal.
const { contextBridge, ipcRenderer } = require('electron');

const call = (channel) => (...args) => ipcRenderer.invoke(channel, ...args);

contextBridge.exposeInMainWorld('romcloud', {
  settings: { get: call('settings:get'), save: call('settings:save') },
  api: {
    test: call('api:test'),
    systems: call('api:systems'),
    games: call('api:games'),
    search: call('api:search'),
  },
  library: { downloaded: call('library:downloaded'), path: call('library:path'), remove: call('library:remove') },
  bios: { missing: call('bios:missing') },
  downloads: {
    start: call('downloads:start'),
    cancel: call('downloads:cancel'),
    dismiss: call('downloads:dismiss'),
    states: call('downloads:states'),
    onUpdate: (fn) => ipcRenderer.on('downloads:update', (_e, payload) => fn(payload)),
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
  emulators: {
    list: call('emulators:list'),
    detect: call('emulators:detect'),
    setPath: call('emulators:setPath'),
    setArgs: call('emulators:setArgs'),
    launch: call('emulators:launch'),
  },
  dialog: { pickFile: call('dialog:pickFile'), pickFolder: call('dialog:pickFolder') },
  shell: { showItem: call('shell:showItem'), openExternal: call('shell:openExternal') },
  keyboard: { layout: call('keyboard:layout'), save: call('keyboard:save') },
  app: { version: call('app:version') },
  player: {
    resetCore: call('player:resetCore'),
    onCrash: (fn) => ipcRenderer.on('player:crashed', (_e, crash) => fn(crash)),
  },
  account: {
    state: call('account:state'),
    register: call('account:register'),
    login: call('account:login'),
    logout: call('account:logout'),
    playtime: call('account:playtime'),
    reportError: call('account:reportError'),
    onUpdate: (fn) => ipcRenderer.on('account:update', () => fn()),
  },
  cast: {
    discover: call('cast:discover'),
    start: call('cast:start'),
    stop: call('cast:stop'),
    state: call('cast:state'),
    onUpdate: (fn) => ipcRenderer.on('cast:update', (_e, state) => fn(state)),
  },
  update: {
    check: call('update:check'),
    install: call('update:install'),
    onProgress: (fn) => ipcRenderer.on('update:progress', (_e, payload) => fn(payload)),
  },
});
