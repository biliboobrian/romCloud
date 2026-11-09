// Réglages des émulateurs du catalogue proposés dans la fenêtre « Configurer … » (application
// Windows) : fichier, section et clé de chaque réglage, valeurs possibles et leur libellé. Lecture et
// écriture des fichiers : desktop-tauri/src-tauri/src/emulator_config.rs.
//
// Champs : { file, section, key, label, type: 'select' | 'bool' | 'number', options, min, max, unit,
// on/off (valeurs écrites pour un booléen), hint }. Libellés : { en, fr } ou texte commun.
//
// Eden et ses forks : valeurs des énumérations de src/common/settings_enums.h d'Eden (écrites en
// nombres dans qt-config.ini), clés et sections de settings.h. Dolphin : Config\Dolphin.ini et
// Config\GFX.ini.
(() => {
  const L = (en, fr) => ({ en, fr });
  const opts = (list) => list.map(([value, label]) => ({ value: String(value), label }));
  const QT = 'qt-config.ini';
  const edenBool = { type: 'bool', on: 'true', off: 'false' };
  const iniBool = { type: 'bool', on: 'True', off: 'False' };

  const eden = (name) => ({
    note: L(
      `Settings shared by Eden, Lichii, Orchid and Fritchii (same qt-config.ini). Close ${name} before saving: it rewrites its settings when it closes. Games started by RomCloud use them; a game’s own settings (right-click › Properties in ${name}) take precedence.`,
      `Réglages communs à Eden, Lichii, Orchid et Fritchii (même qt-config.ini). Fermez ${name} avant d’enregistrer : il réécrit ses réglages en se fermant. Les jeux lancés par RomCloud les utilisent ; les réglages propres à un jeu (clic droit › Propriétés dans ${name}) passent avant.`,
    ),
    groups: [
      {
        title: L('Graphics', 'Graphismes'),
        fields: [
          { file: QT, section: 'Renderer', key: 'backend', label: L('Graphics API', 'API graphique'), type: 'select',
            options: opts([[1, 'Vulkan'], [0, 'OpenGL (GLSL)'], [3, 'OpenGL (GLASM)'], [4, 'OpenGL (SPIR-V)']]) },
          { file: QT, section: 'Renderer', key: 'resolution_setup', label: L('Resolution', 'Résolution'), type: 'select',
            options: opts([[0, '0.25×'], [1, '0.5× (360p / 540p)'], [2, '0.75× (540p / 810p)'], [3, L('1× (720p / 1080p, native)', '1× (720p / 1080p, native)')],
              [4, '1.25×'], [5, '1.5× (1080p / 1620p)'], [6, '2× (1440p / 2160p)'], [7, '3× (2160p / 3240p)'], [8, '4×'], [9, '5×'], [10, '6×'], [11, '7×'], [12, '8×']]),
            hint: L('Handheld / docked. Above 1×: sharper picture, more demanding.', 'Portable / station d’accueil. Au-delà de 1× : image plus fine, plus gourmand.') },
          { file: QT, section: 'Renderer', key: 'scaling_filter', label: L('Window adapting filter', 'Filtre de mise à l’échelle'), type: 'select',
            options: opts([[0, L('Nearest neighbor', 'Plus proche voisin')], [1, L('Bilinear', 'Bilinéaire')], [2, L('Bicubic', 'Bicubique')], [3, 'Gaussian'], [4, 'Lanczos'],
              [5, 'ScaleForce'], [6, 'AMD FidelityFX Super Resolution (FSR)'], [7, 'Area'], [8, 'Zero-Tangent'], [9, 'B-Spline'], [10, 'Mitchell'], [11, 'Spline-1'],
              [12, 'MMPX'], [13, 'Snapdragon GSR'], [14, 'Snapdragon GSR (edge)']]) },
          { file: QT, section: 'Renderer', key: 'fsr_sharpening_slider', label: L('FSR sharpness', 'Netteté de FSR'), type: 'number', min: 0, max: 200, unit: '%' },
          { file: QT, section: 'Renderer', key: 'anti_aliasing', label: L('Anti-aliasing', 'Anticrénelage'), type: 'select',
            options: opts([[0, L('None', 'Aucun')], [1, 'FXAA'], [2, 'SMAA']]) },
          { file: QT, section: 'Renderer', key: 'aspect_ratio', label: L('Aspect ratio', 'Format d’image'), type: 'select',
            options: opts([[0, L('16:9 (default)', '16:9 (par défaut)')], [1, '4:3'], [2, '21:9'], [3, '16:10'], [4, L('Stretch to window', 'Étiré à la fenêtre')]]) },
          { file: QT, section: 'Renderer', key: 'fullscreen_mode', label: L('Full screen mode', 'Mode plein écran'), type: 'select',
            options: opts([[0, L('Borderless window', 'Fenêtre sans bordure')], [1, L('Exclusive full screen', 'Plein écran exclusif')]]) },
          { file: QT, section: 'Renderer', key: 'use_vsync', label: L('VSync', 'Synchronisation verticale'), type: 'select',
            options: opts([[2, L('FIFO (VSync on)', 'FIFO (activée)')], [3, L('FIFO relaxed', 'FIFO souple')], [1, L('Mailbox (low latency)', 'Mailbox (faible latence)')], [0, L('Immediate (off)', 'Immédiate (désactivée)')]]) },
          { file: QT, section: 'Renderer', key: 'max_anisotropy', label: L('Anisotropic filtering', 'Filtrage anisotrope'), type: 'select',
            options: opts([[0, L('Automatic', 'Automatique')], [1, L('Game default', 'Celui du jeu')], [2, '2×'], [3, '4×'], [4, '8×'], [5, '16×']]) },
        ],
      },
      {
        title: L('Graphics (advanced)', 'Graphismes (avancé)'),
        fields: [
          { file: QT, section: 'Renderer', key: 'gpu_accuracy', label: L('GPU accuracy', 'Précision du GPU'), type: 'select',
            options: opts([[1, L('High', 'Élevée')], [0, L('Low (faster)', 'Faible (plus rapide)')]]) },
          { file: QT, section: 'Renderer', key: 'use_disk_shader_cache', label: L('Disk shader cache', 'Cache des shaders sur le disque'), ...edenBool },
          { file: QT, section: 'Renderer', key: 'use_asynchronous_shaders', label: L('Asynchronous shader building (less stutter, brief glitches)', 'Compilation asynchrone des shaders (moins de saccades, défauts passagers)'), ...edenBool },
          { file: QT, section: 'Renderer', key: 'accelerate_astc', label: L('ASTC texture decoding', 'Décodage des textures ASTC'), type: 'select',
            options: opts([[1, 'GPU'], [0, 'CPU'], [2, L('CPU, asynchronous', 'CPU, asynchrone')]]) },
          { file: QT, section: 'Renderer', key: 'astc_recompression', label: L('ASTC recompression', 'Recompression ASTC'), type: 'select',
            options: opts([[0, L('Uncompressed (best quality)', 'Non compressé (meilleure qualité)')], [1, L('BC1 (low quality)', 'BC1 (qualité faible)')], [2, L('BC3 (medium quality)', 'BC3 (qualité moyenne)')]]),
            hint: L('BC1 / BC3 save video memory on graphics cards with 4 GB or less.', 'BC1 / BC3 économisent la mémoire vidéo des cartes graphiques de 4 Go ou moins.') },
          { file: QT, section: 'Renderer', key: 'vram_usage_mode', label: L('Video memory use', 'Usage de la mémoire vidéo'), type: 'select',
            options: opts([[0, L('Conservative', 'Modéré')], [1, L('Aggressive', 'Agressif')]]) },
          { file: QT, section: 'Renderer', key: 'nvdec_emulation', label: L('Video decoding (NVDEC)', 'Décodage des vidéos (NVDEC)'), type: 'select',
            options: opts([[1, 'CPU'], [2, 'GPU'], [0, L('Off (no in-game videos)', 'Désactivé (pas de vidéos)')]]) },
          { file: QT, section: 'Renderer', key: 'dyna_state', label: L('Extended dynamic state (Vulkan)', 'État dynamique étendu (Vulkan)'), type: 'select',
            options: opts([[0, L('Disabled', 'Désactivé')], [1, 'EDS1'], [2, L('EDS2 (default)', 'EDS2 (par défaut)')], [3, 'EDS3']]) },
        ],
      },
      {
        title: L('System', 'Système'),
        fields: [
          { file: QT, section: 'System', key: 'use_docked_mode', label: L('Console mode', 'Mode de la console'), type: 'select',
            options: opts([[1, L('Docked (TV)', 'Station d’accueil (TV)')], [0, L('Handheld', 'Portable')]]) },
          { file: QT, section: 'System', key: 'language_index', label: L('Console language', 'Langue de la console'), type: 'select',
            options: opts([[2, 'Français'], [13, 'Français (Canada)'], [1, 'English (US)'], [12, 'English (UK)'], [3, 'Deutsch'], [4, 'Italiano'], [5, 'Español'], [14, 'Español (Latinoamérica)'],
              [8, 'Nederlands'], [9, 'Português'], [17, 'Português (Brasil)'], [10, 'Русский'], [18, 'Polski'], [19, 'ไทย'], [0, '日本語'], [7, '한국어'],
              [6, '中文'], [15, '简体中文'], [16, '繁體中文'], [11, '台灣']]) },
          { file: QT, section: 'System', key: 'region_index', label: L('Console region', 'Région de la console'), type: 'select',
            options: opts([[2, L('Europe', 'Europe')], [1, L('USA', 'États-Unis')], [0, L('Japan', 'Japon')], [3, L('Australia', 'Australie')], [4, L('China', 'Chine')], [5, L('Korea', 'Corée')], [6, L('Taiwan', 'Taïwan')]]) },
        ],
      },
      {
        title: L('Performance', 'Performances'),
        fields: [
          { file: QT, section: 'Core', key: 'use_multi_core', label: L('Multicore CPU emulation', 'Émulation multicœur du processeur'), ...edenBool },
          { file: QT, section: 'Core', key: 'memory_layout_mode', label: L('Console memory', 'Mémoire de la console'), type: 'select',
            options: opts([[0, L('4 GB (default)', '4 Go (par défaut)')], [1, L('6 GB (unsafe)', '6 Go (risqué)')], [2, L('8 GB (unsafe)', '8 Go (risqué)')], [3, L('10 GB (unsafe)', '10 Go (risqué)')], [4, L('12 GB (unsafe)', '12 Go (risqué)')]]),
            hint: L('More memory helps high-resolution mods; some games refuse to start with it.', 'Plus de mémoire aide les mods en haute résolution ; certains jeux refusent alors de démarrer.') },
          { file: QT, section: 'Core', key: 'use_speed_limit', label: L('Limit speed', 'Limiter la vitesse'), ...edenBool },
          { file: QT, section: 'Core', key: 'speed_limit', label: L('Speed limit', 'Vitesse maximale'), type: 'number', min: 1, max: 9999, unit: '%' },
          { file: QT, section: 'Cpu', key: 'cpu_accuracy', label: L('CPU accuracy', 'Précision du processeur'), type: 'select',
            options: opts([[0, L('Auto', 'Automatique')], [1, L('Accurate', 'Précise')], [2, L('Unsafe (faster)', 'Risquée (plus rapide)')], [3, L('Paranoid (slow)', 'Paranoïaque (lente)')]]) },
        ],
      },
      {
        title: L('Audio', 'Son'),
        fields: [
          { file: QT, section: 'Audio', key: 'output_engine', label: L('Output engine', 'Moteur de sortie'), type: 'select',
            options: opts([[0, L('Auto', 'Automatique')], [1, 'Cubeb'], [2, 'SDL3'], [3, L('None (muted)', 'Aucun (muet)')]]) },
          { file: QT, section: 'Audio', key: 'volume', label: L('Volume', 'Volume'), type: 'number', min: 0, max: 200, unit: '%' },
          { file: QT, section: 'Audio', key: 'audio_muted', label: L('Mute', 'Couper le son'), ...edenBool },
        ],
      },
    ],
  });

  const DOL = 'Dolphin.ini';
  const GFX = 'GFX.ini';
  const dolphin = {
    note: L(
      'Close Dolphin before saving: it rewrites its settings when it closes. Controllers are set in Dolphin (Controllers); a game’s own settings (right-click › Properties) take precedence.',
      'Fermez Dolphin avant d’enregistrer : il réécrit ses réglages en se fermant. Les manettes se règlent dans Dolphin (Manettes) ; les réglages propres à un jeu (clic droit › Propriétés) passent avant.',
    ),
    groups: [
      {
        title: L('Graphics', 'Graphismes'),
        fields: [
          { file: DOL, section: 'Core', key: 'GFXBackend', label: L('Graphics backend', 'Moteur graphique'), type: 'select',
            options: opts([['Vulkan', 'Vulkan'], ['D3D12', 'Direct3D 12'], ['D3D', 'Direct3D 11'], ['OGL', 'OpenGL']]) },
          { file: GFX, section: 'Settings', key: 'InternalResolution', label: L('Internal resolution', 'Résolution interne'), type: 'select',
            options: opts([[1, L('Native (640×528)', 'Native (640×528)')], [2, '2× (720p)'], [3, '3× (1080p)'], [4, '4× (1440p)'], [5, '5×'], [6, '6× (4K)'], [7, '7×'], [8, '8× (5K)']]) },
          { file: GFX, section: 'Settings', key: 'AspectRatio', label: L('Aspect ratio', 'Format d’image'), type: 'select',
            options: opts([[0, L('Auto', 'Automatique')], [1, L('Force 16:9', 'Forcer 16:9')], [2, L('Force 4:3', 'Forcer 4:3')], [3, L('Stretch to window', 'Étiré à la fenêtre')]]) },
          { file: GFX, section: 'Settings', key: 'wideScreenHack', label: L('Widescreen hack', 'Astuce écran large'), ...iniBool },
          { file: GFX, section: 'Settings', key: 'MSAA', label: L('Anti-aliasing (MSAA)', 'Anticrénelage (MSAA)'), type: 'select',
            options: opts([[1, L('None', 'Aucun')], [2, '2×'], [4, '4×'], [8, '8×']]) },
          { file: GFX, section: 'Enhancements', key: 'MaxAnisotropy', label: L('Anisotropic filtering', 'Filtrage anisotrope'), type: 'select',
            options: opts([[0, '1×'], [1, '2×'], [2, '4×'], [3, '8×'], [4, '16×']]) },
          { file: GFX, section: 'Hardware', key: 'VSync', label: L('VSync', 'Synchronisation verticale'), ...iniBool },
          { file: GFX, section: 'Settings', key: 'ShowFPS', label: L('Show FPS', 'Afficher les images par seconde'), ...iniBool },
        ],
      },
      {
        title: L('General', 'Général'),
        fields: [
          { file: DOL, section: 'Display', key: 'Fullscreen', label: L('Start games in full screen', 'Jeux en plein écran'), ...iniBool },
          { file: DOL, section: 'Core', key: 'CPUThread', label: L('Dual core (faster)', 'Double cœur (plus rapide)'), ...iniBool },
          { file: DOL, section: 'Interface', key: 'ConfirmStop', label: L('Confirm before stopping a game', 'Confirmer l’arrêt d’un jeu'), ...iniBool },
        ],
      },
    ],
  };

  window.EMULATOR_CONFIG = {
    eden: eden('Eden'),
    lichii: eden('Lichii'),
    orchid: eden('Orchid'),
    fritchii: eden('Fritchii'),
    dolphin,
  };
})();
