// Options par défaut des émulateurs intégrés (moteur intégré de Windows, LibretroDroid, RomCloud PC),
// dans les réglages d'un système et la fiche d'un jeu. Les listes viennent du catalogue envoyé par les
// applications (/api/core-options) : options déclarées par chaque cœur qu'elles ont lancé, sinon
// celles du catalogue livré avec le serveur (tous les cœurs du moteur intégré). Une option absente
// des deux s'ajoute à la main (clé et valeur, bloc « Autre option »).
//
// Niveaux : options du système, puis du jeu (qui les remplacent) ; le joueur garde le dernier mot
// pendant la partie.
import { getLanguage, t } from './i18n.js';

const $ = (sel, root = document) => root.querySelector(sel);

/**
 * Éditeur dans [root] (élément vide). [api] : appel à l'API de l'administration ; [escapeHtml].
 * load({ systemId, values, inherited }) : options enregistrées du niveau, et celles du niveau
 * au-dessus (jeu : celles du système) ; value() : options choisies { clé: valeur }.
 */
export function coreOptionsEditor(root, { api, escapeHtml }) {
  let values = {};
  let inherited = {};
  let catalog = [];
  let selected = '';
  let filter = '';
  let loadId = 0;

  root.classList.add('core-options');
  root.innerHTML = `
    <span class="muted"></span>
    <div class="row">
      <select class="co-core"></select>
      <input type="search" class="co-filter">
    </div>
    <div class="co-list"></div>
    <div class="co-custom">
      <strong class="co-custom-title"></strong>
      <ul class="co-set players"></ul>
      <details class="co-other">
        <summary class="co-other-title"></summary>
        <div class="row">
          <input class="co-key mono" autocomplete="off" spellcheck="false">
          <input class="co-value mono" autocomplete="off" spellcheck="false">
          <button type="button" class="btn small co-add"></button>
        </div>
      </details>
    </div>`;
  const coreSelect = $('.co-core', root);
  const filterInput = $('.co-filter', root);

  const translate = () => {
    $('span.muted', root).textContent = t('coreOptions.hint');
    filterInput.placeholder = t('coreOptions.filter');
    $('.co-custom-title', root).textContent = t('coreOptions.set');
    $('.co-other-title', root).textContent = t('coreOptions.other');
    $('.co-key', root).placeholder = t('coreOptions.key');
    $('.co-value', root).placeholder = t('coreOptions.value');
    $('.co-add', root).textContent = t('coreOptions.add');
  };

  /** Option connue d'un cœur du catalogue (libellé, valeurs). */
  const known = (key) => {
    for (const entry of catalog) {
      const option = entry.options.find((o) => o.key === key);
      if (option) return option;
    }
    return null;
  };

  // Libellés français donnés par le cœur (catalogue livré : labelFr, categoryFr…), sinon anglais.
  const local = (item, field) => (getLanguage() === 'fr' && item?.[`${field}Fr`]) || item?.[field] || '';

  const valueLabel = (option, value) => {
    const found = option?.values.find((v) => v.value === value);
    return found ? local(found, 'label') : value;
  };

  function set(key, value) {
    if (value === '' || value === null || value === undefined) delete values[key];
    else values[key] = value;
    render();
  }

  function renderCores() {
    coreSelect.innerHTML = '';
    if (!catalog.length) {
      coreSelect.innerHTML = `<option value="">${escapeHtml(t('coreOptions.noCatalog'))}</option>`;
      coreSelect.disabled = true;
      return;
    }
    coreSelect.disabled = false;
    catalog.forEach((entry, i) => {
      const option = document.createElement('option');
      option.value = String(i);
      const platform = entry.builtin ? t('coreOptions.builtin') : t(`platform.${entry.platform}`) || entry.platform;
      option.textContent = `${entry.core} — ${platform} (${t('coreOptions.count', { n: entry.options.length })})`;
      coreSelect.append(option);
    });
    // Cœur dont une option est déjà choisie, sinon le premier.
    const withValue = catalog.findIndex((entry) => entry.options.some((o) => o.key in values));
    if (!catalog[Number(selected)]) selected = String(withValue >= 0 ? withValue : 0);
    coreSelect.value = selected;
  }

  function renderList() {
    const list = $('.co-list', root);
    list.innerHTML = '';
    const entry = catalog[Number(selected)];
    if (!entry) return;
    const words = filter.toLowerCase();
    const options = entry.options.filter((o) => !words || `${local(o, 'label')} ${o.label} ${o.key} ${local(o, 'category')}`.toLowerCase().includes(words));
    if (!options.length) {
      list.innerHTML = `<p class="muted">${escapeHtml(t('coreOptions.none'))}</p>`;
      return;
    }
    let category = null;
    for (const option of options) {
      // Options regroupées sous le nom de leur catégorie (ordre du cœur).
      const name = local(option, 'category');
      if (name !== category) {
        category = name;
        if (name) {
          const head = document.createElement('h4');
          head.className = 'co-category';
          head.textContent = name;
          list.append(head);
        }
      }
      const row = document.createElement('label');
      row.className = `co-option${option.key in values ? ' chosen' : ''}`;
      row.title = local(option, 'info') || option.key;
      const select = document.createElement('select');
      // Sans valeur à ce niveau : celle du niveau au-dessus, sinon celle du cœur.
      const fallback = option.key in inherited
        ? t('coreOptions.inherited', { value: valueLabel(option, inherited[option.key]) })
        : option.default === ''
          ? t('coreOptions.auto')
          : t('coreOptions.coreDefault', { value: valueLabel(option, option.default) });
      select.innerHTML = `<option value="">${escapeHtml(fallback)}</option>`;
      const choices = [...option.values];
      // Valeur enregistrée que le cœur ne propose plus : gardée dans la liste.
      if (option.key in values && !choices.some((v) => v.value === values[option.key])) choices.push({ value: values[option.key], label: values[option.key] });
      for (const v of choices) {
        const el = document.createElement('option');
        el.value = v.value;
        el.textContent = local(v, 'label');
        select.append(el);
      }
      select.value = values[option.key] ?? '';
      select.onchange = () => set(option.key, select.value);
      row.innerHTML = `<span>${escapeHtml(local(option, 'label'))}<span class="muted mono">${escapeHtml(option.key)}</span></span>`;
      row.append(select);
      list.append(row);
    }
  }

  /** Options choisies à ce niveau, catalogue ou non : retirables une à une. */
  function renderSet() {
    const list = $('.co-set', root);
    list.innerHTML = '';
    const keys = Object.keys(values).sort();
    if (!keys.length) {
      list.innerHTML = `<li class="muted">${escapeHtml(t('coreOptions.empty'))}</li>`;
      return;
    }
    for (const key of keys) {
      const option = known(key);
      const li = document.createElement('li');
      li.innerHTML = `<div><strong>${escapeHtml(local(option, 'label') || key)}</strong> = ${escapeHtml(valueLabel(option, values[key]))}<span class="muted mono">${escapeHtml(key)}</span></div>`;
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'btn small ghost';
      remove.textContent = '✕';
      remove.title = t('coreOptions.remove');
      remove.onclick = () => set(key, '');
      li.append(remove);
      list.append(li);
    }
  }

  function render() {
    renderList();
    renderSet();
  }

  coreSelect.onchange = () => {
    selected = coreSelect.value;
    render();
  };
  filterInput.oninput = () => {
    filter = filterInput.value;
    renderList();
  };
  $('.co-add', root).onclick = () => {
    const key = $('.co-key', root).value.trim();
    const value = $('.co-value', root).value;
    if (!key || value === '') return;
    $('.co-key', root).value = '';
    $('.co-value', root).value = '';
    set(key, value);
  };

  return {
    async load({ systemId, values: current = {}, inherited: above = {} }) {
      translate();
      values = { ...current };
      inherited = above;
      catalog = [];
      selected = '';
      filter = '';
      filterInput.value = '';
      renderCores();
      render();
      const id = ++loadId;
      const list = await api(`/core-options?system=${encodeURIComponent(systemId)}`);
      if (id !== loadId) return;
      catalog = list;
      renderCores();
      render();
    },
    value: () => ({ ...values }),
  };
}
