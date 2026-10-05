// Navigation dans l'interface à la manette (API Gamepad, disposition « standard » : Xbox, DualShock…).
// Croix ou stick gauche : déplace le focus vers l'élément le plus proche dans cette direction ;
// A : valider ; B : retour (comme Échap) ; X : fiche du jeu ; Y : recherche ; LB / RB : filtre
// précédent / suivant ; stick droit : défilement. Ignorée quand la fenêtre n'a pas le focus (jeu
// lancé). Chargé par la page (window.RomCloudGamepad) et par les tests (module CommonJS).
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.RomCloudGamepad = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  /** Boutons de la disposition standard (https://w3c.github.io/gamepad/#remapping). */
  const BTN = { A: 0, B: 1, X: 2, Y: 3, LB: 4, RB: 5, UP: 12, DOWN: 13, LEFT: 14, RIGHT: 15 };
  const DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };
  const DEAD_ZONE = 0.5;
  const REPEAT_DELAY = 400;
  const REPEAT_RATE = 110;

  /**
   * Élément vers lequel aller depuis le rectangle [from] dans la direction [dir] parmi [candidates]
   * ({ rect, ... }) : le plus proche dont le centre est de ce côté, l'écart sur l'autre axe comptant
   * triple (on reste dans la même rangée ou colonne). [cone] : seulement ceux en face ou dans un
   * cône de 45°. null s'il n'y en a aucun.
   */
  function pickTarget(from, candidates, dir, cone = true) {
    const [dx, dy] = DIRS[dir];
    const cx = (r) => r.left + r.width / 2;
    const cy = (r) => r.top + r.height / 2;
    let best = null;
    let bestScore = Infinity;
    for (const c of candidates) {
      const r = c.rect;
      // Centre au-delà de celui de départ, dans la direction demandée.
      const along = dx ? (cx(r) - cx(from)) * dx : (cy(r) - cy(from)) * dy;
      if (along <= 1) continue;
      // Dans un cône de 45° ou en face (sinon, en bout de rangée, droite partirait vers la barre du haut).
      const facing = dx
        ? r.top < from.top + from.height && r.top + r.height > from.top
        : r.left < from.left + from.width && r.left + r.width > from.left;
      const offset = dx ? Math.abs(cy(r) - cy(from)) : Math.abs(cx(r) - cx(from));
      if (cone && !facing && offset > along) continue;
      // Distance entre bords (0 si les rectangles se chevauchent sur cet axe).
      const gap = dx
        ? Math.max(0, dx > 0 ? r.left - (from.left + from.width) : from.left - (r.left + r.width))
        : Math.max(0, dy > 0 ? r.top - (from.top + from.height) : from.top - (r.top + r.height));
      const score = gap + offset * 3;
      if (score < bestScore) {
        bestScore = score;
        best = c;
      }
    }
    return best;
  }

  /** Élément le plus proche du rectangle [from] (focus perdu après un nouvel affichage). */
  function nearest(from, candidates) {
    const d = (r) => Math.hypot(r.left + r.width / 2 - (from.left + from.width / 2), r.top + r.height / 2 - (from.top + from.height / 2));
    return candidates.reduce((best, c) => (!best || d(c.rect) < d(best.rect) ? c : best), null);
  }

  function start() {
    // Éléments atteignables : boutons, champs, cartes de jeux et de systèmes, lignes de la liste.
    const FOCUSABLE = 'button, input, select, a[href], [data-card], [data-system]';
    let prev = new Set();
    let held = null; // direction tenue : { dir, next } (répétition)
    let lastRect = null;
    let announced = false;
    let running = false;

    const active = () => (document.activeElement && document.activeElement !== document.body ? document.activeElement : null);

    /** Portée de la navigation : la fenêtre ouverte, sinon la page (et les notifications). */
    function candidates() {
      const modal = document.querySelector('#modalRoot .modal');
      const roots = modal ? [modal] : [document.querySelector('.topbar'), document.querySelector('#banners'), document.querySelector('#main'), document.querySelector('#toasts')];
      const list = [];
      for (const root of roots) {
        if (!root) continue;
        for (const el of root.querySelectorAll(FOCUSABLE)) {
          if (el.disabled || el.closest('.hidden')) continue;
          // Bouton ⓘ d'une jaquette : la carte elle-même suffit (X ouvre la fiche).
          if (el.matches('.info-btn')) continue;
          const rect = el.getBoundingClientRect();
          if (!rect.width || !rect.height || getComputedStyle(el).visibility === 'hidden') continue;
          list.push({ el, rect });
        }
      }
      return list;
    }

    function focus(el) {
      if (!el.matches('button, input, select, a[href]') && !el.hasAttribute('tabindex')) el.tabIndex = -1;
      el.focus({ preventScroll: true });
      el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      // Bannière fixe du carrousel : l'élément ne doit pas rester caché dessous.
      const head = document.querySelector('.carousel-head');
      if (head && !head.contains(el)) {
        const hidden = head.getBoundingClientRect().bottom - el.getBoundingClientRect().top;
        if (hidden > 0) document.querySelector('#main').scrollTop -= hidden + 12;
      }
      lastRect = el.getBoundingClientRect();
    }

    function move(dir) {
      const current = active();
      // Liste déroulante : gauche / droite changent sa valeur.
      if (current?.matches('select') && (dir === 'left' || dir === 'right')) {
        const i = current.selectedIndex + (dir === 'right' ? 1 : -1);
        if (i >= 0 && i < current.options.length) {
          current.selectedIndex = i;
          current.dispatchEvent(new Event('change', { bubbles: true }));
        }
        return;
      }
      const list = candidates();
      if (!current || !list.some((c) => c.el === current)) {
        // Rien de sélectionné (nouvel écran) : l'élément le plus proche du précédent, sinon le premier du contenu.
        const main = list.filter((c) => !c.el.closest('.topbar'));
        const first = lastRect ? nearest(lastRect, list) : main[0] || list[0];
        if (first) focus(first.el);
        return;
      }
      const from = current.getBoundingClientRect();
      const others = list.filter((c) => c.el !== current);
      // Haut / bas : à défaut d'élément en face, le plus proche de ce côté (barre du haut, bannière).
      const target = pickTarget(from, others, dir) || (dir === 'up' || dir === 'down' ? pickTarget(from, others, dir, false) : null);
      if (target) return focus(target.el);
      // Plus rien dans cette direction : la page défile (texte de la fiche d'un jeu).
      if (dir === 'up' || dir === 'down') scroll(dir === 'down' ? 1 : -1);
    }

    function scroll(sign) {
      const main = document.querySelector('.modal .body') || document.querySelector('#main');
      main.scrollBy({ top: sign * main.clientHeight * 0.4, behavior: 'smooth' });
    }

    function activate() {
      const el = active();
      if (!el) return move('down');
      const event = new CustomEvent('padactivate', { bubbles: true, cancelable: true });
      if (!el.dispatchEvent(event)) return;
      if (el.matches('input[type=text], input[type=search], input[type=password]')) return el.select();
      el.click();
    }

    function back() {
      const el = active();
      // Champ de texte : le quitter d'abord (Échap y est ignoré par l'interface).
      if (el?.matches('input, select')) return el.blur();
      (el || document.body).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
    }

    function details() {
      const card = active()?.closest('[data-card]');
      if (card) card.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
    }

    function search() {
      const box = document.querySelector('#globalSearch, #gameSearch');
      if (box) focus(box);
    }

    /** Filtre précédent / suivant (Tous, Téléchargés, À télécharger). */
    function cycleFilter(step) {
      const chips = [...document.querySelectorAll('[data-filter]')];
      if (!chips.length) return;
      const i = chips.findIndex((c) => c.classList.contains('selected'));
      chips[(i + step + chips.length) % chips.length].click();
    }

    const ACTIONS = { [BTN.A]: activate, [BTN.B]: back, [BTN.X]: details, [BTN.Y]: search, [BTN.LB]: () => cycleFilter(-1), [BTN.RB]: () => cycleFilter(1) };

    function snapshot() {
      const pressed = new Set();
      let x = 0;
      let y = 0;
      let scrollY = 0;
      for (const pad of navigator.getGamepads()) {
        if (!pad) continue;
        pad.buttons.forEach((b, i) => { if (b.pressed) pressed.add(i); });
        x = Math.abs(pad.axes[0] || 0) > Math.abs(x) ? pad.axes[0] : x;
        y = Math.abs(pad.axes[1] || 0) > Math.abs(y) ? pad.axes[1] : y;
        scrollY = Math.abs(pad.axes[3] || 0) > Math.abs(scrollY) ? pad.axes[3] : scrollY;
      }
      let dir = null;
      if (pressed.has(BTN.UP)) dir = 'up';
      else if (pressed.has(BTN.DOWN)) dir = 'down';
      else if (pressed.has(BTN.LEFT)) dir = 'left';
      else if (pressed.has(BTN.RIGHT)) dir = 'right';
      else if (Math.max(Math.abs(x), Math.abs(y)) > DEAD_ZONE) dir = Math.abs(x) > Math.abs(y) ? (x > 0 ? 'right' : 'left') : (y > 0 ? 'down' : 'up');
      return { pressed, dir, scrollY };
    }

    function frame(now) {
      const { pressed, dir, scrollY } = snapshot();
      // Fenêtre inactive (jeu au premier plan) : rien, et les boutons tenus au retour ne comptent pas.
      if (!document.hasFocus()) {
        prev = pressed;
        held = dir ? { dir, next: Infinity } : null;
      } else {
        const fresh = [...pressed].filter((b) => !prev.has(b) && ACTIONS[b]);
        if (fresh.length || dir) document.body.classList.add('pad-nav');
        for (const b of fresh) ACTIONS[b]();
        prev = pressed;
        if (!dir) held = null;
        else if (!held || held.dir !== dir) {
          held = { dir, next: now + REPEAT_DELAY };
          move(dir);
        } else if (now >= held.next) {
          held.next = now + REPEAT_RATE;
          move(dir);
        }
        if (Math.abs(scrollY) > DEAD_ZONE) {
          const main = document.querySelector('.modal .body') || document.querySelector('#main');
          main.scrollTop += scrollY * 18;
        }
      }
      if (navigator.getGamepads().some(Boolean)) requestAnimationFrame(frame);
      else running = false;
    }

    window.addEventListener('gamepadconnected', () => {
      if (!announced) {
        announced = true;
        document.dispatchEvent(new CustomEvent('padconnected'));
      }
      if (running) return;
      running = true;
      requestAnimationFrame(frame);
    });
    // Souris : plus de cadre de sélection de la manette.
    window.addEventListener('mousemove', () => document.body.classList.remove('pad-nav'));
  }

  return { pickTarget, nearest, start };
});
