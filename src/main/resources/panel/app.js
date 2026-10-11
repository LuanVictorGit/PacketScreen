'use strict';

(() => {
  const state = {
    strings: {},
    data: null,
    csrf: null,
    offset: 0,
    clock: null,
    poll: null,
    uploading: false
  };

  const $ = (selector) => document.querySelector(selector);

  function t(key, values) {
    let text = Object.prototype.hasOwnProperty.call(state.strings, key) ? state.strings[key] : key;
    if (values) {
      for (const [name, value] of Object.entries(values)) {
        text = text.split('{' + name + '}').join(String(value));
      }
    }
    return text;
  }

  function el(tag, options, ...children) {
    const node = document.createElement(tag);
    const settings = options || {};
    for (const [key, value] of Object.entries(settings)) {
      if (value === undefined || value === null || value === false) {
        continue;
      }
      if (key === 'text') {
        node.textContent = value;
      } else if (key === 'class') {
        node.className = value;
      } else if (key.startsWith('on')) {
        node.addEventListener(key.slice(2), value);
      } else if (value === true) {
        node.setAttribute(key, '');
      } else {
        node.setAttribute(key, String(value));
      }
    }
    for (const child of children) {
      if (child !== null && child !== undefined) {
        node.append(child);
      }
    }
    return node;
  }

  function formatBytes(bytes) {
    const locale = document.documentElement.lang || undefined;
    if (bytes >= 1073741824) {
      return (bytes / 1073741824).toLocaleString(locale, { maximumFractionDigits: 1 }) + ' GB';
    }
    if (bytes >= 1048576) {
      return (bytes / 1048576).toLocaleString(locale, { maximumFractionDigits: 1 }) + ' MB';
    }
    if (bytes <= 0) {
      return '0 KB';
    }
    return Math.max(1, Math.round(bytes / 1024)).toLocaleString(locale) + ' KB';
  }

  function formatDate(time) {
    return new Date(time).toLocaleString(document.documentElement.lang || undefined, {
      dateStyle: 'short',
      timeStyle: 'short'
    });
  }

  function toast(message, kind) {
    const node = el('div', { class: 'toast ' + (kind || ''), text: message });
    $('#toasts').append(node);
    setTimeout(() => node.remove(), kind === 'error' ? 7000 : 4000);
  }

  async function api(path, options) {
    const settings = options || {};
    const method = settings.method || 'GET';
    const headers = Object.assign({}, settings.headers || {});
    if (method !== 'GET' && state.csrf) {
      headers['X-CSRF-Token'] = state.csrf;
    }
    const response = await fetch(path, {
      method,
      headers,
      body: settings.body,
      credentials: 'same-origin',
      cache: 'no-store'
    });
    let body = null;
    try {
      body = await response.json();
    } catch (ignored) {
      body = null;
    }
    if (!response.ok) {
      const error = new Error(body && body.error ? body.error : t('error-generic'));
      error.status = response.status;
      throw error;
    }
    return body;
  }

  function sendJson(path, method, value) {
    return api(path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(value)
    });
  }

  function gate(message, hint) {
    stopTimers();
    $('#app').hidden = true;
    $('#gate').hidden = false;
    $('#gate-message').textContent = message;
    $('#gate-hint').textContent = hint || '';
    document.body.classList.remove('booting');
  }

  function stopTimers() {
    clearInterval(state.clock);
    clearInterval(state.poll);
    state.clock = null;
    state.poll = null;
  }

  function applyStrings() {
    document.title = t('title');
    if (state.strings.lang) {
      document.documentElement.lang = state.strings.lang;
    }
    for (const node of document.querySelectorAll('[data-text]')) {
      node.textContent = t(node.dataset.text);
    }
  }

  async function boot() {
    try {
      const response = await fetch('/api/strings', { cache: 'no-store', credentials: 'same-origin' });
      state.strings = await response.json();
    } catch (ignored) {
      state.strings = {};
    }
    applyStrings();
    const token = location.hash.length > 1 ? location.hash.slice(1) : null;
    if (token) {
      history.replaceState(null, '', location.pathname);
      gate(t('signing-in'));
      try {
        await sendJson('/api/login', 'POST', { token });
      } catch (error) {
        gate(error.message, t('new-link-hint'));
        return;
      }
    }
    try {
      await load();
    } catch (error) {
      if (error.status === 401) {
        gate(t('no-session'), t('new-link-hint'));
      } else {
        gate(error.message);
      }
      return;
    }
    $('#gate').hidden = true;
    $('#app').hidden = false;
    document.body.classList.remove('booting');
    state.clock = setInterval(tick, 1000);
    state.poll = setInterval(() => {
      if (document.visibilityState === 'visible' && !state.uploading) {
        load().catch(handleFailure);
      }
    }, 15000);
  }

  function handleFailure(error) {
    if (error.status === 401) {
      gate(t('session-ended'), t('new-link-hint'));
    } else {
      toast(error.message, 'error');
    }
  }

  async function load() {
    const data = await api('/api/state');
    state.data = data;
    state.csrf = data.csrf;
    state.offset = data.now - Date.now();
    render();
  }

  function tick() {
    if (!state.data) {
      return;
    }
    const remaining = Math.max(0, state.data.expires - (Date.now() + state.offset));
    const minutes = Math.floor(remaining / 60000);
    const seconds = Math.floor((remaining % 60000) / 1000);
    const node = $('#session-time');
    node.textContent = t('session-ends', { time: minutes + ':' + String(seconds).padStart(2, '0') });
    node.classList.toggle('ending', remaining < 120000);
    if (remaining === 0) {
      gate(t('session-ended'), t('new-link-hint'));
    }
  }

  function render() {
    const data = state.data;
    $('#session-user').textContent = data.user;
    tick();
    const limits = data.limits;
    $('#upload-hint').textContent = t('upload-hint', {
      image: formatBytes(limits.image),
      video: formatBytes(limits.video)
    });
    $('#storage-label').textContent = t('storage', {
      used: formatBytes(limits.used),
      max: formatBytes(limits.storage)
    });
    const ratio = limits.storage > 0 ? Math.min(1, limits.used / limits.storage) : 0;
    const bar = $('#storage-bar');
    bar.style.width = (ratio * 100).toFixed(1) + '%';
    bar.classList.toggle('full', ratio > 0.9);
    renderLibrary();
    renderScreens();
    renderCreate();
  }

  function commandFor(upload) {
    const dash = upload.id.lastIndexOf('-');
    const base = (dash > 0 ? upload.id.slice(0, dash) : 'screen').slice(0, 32) || 'screen';
    const sub = upload.kind === 'video' ? 'video' : 'create';
    return '/packetscreen ' + sub + ' ' + base + ' upload:' + upload.id;
  }

  function compatible(upload, screen) {
    if (screen.type === 'image') {
      return upload.kind === 'image';
    }
    return upload.kind === 'video' || upload.id.endsWith('.gif');
  }

  function allowed(type) {
    const can = state.data.can;
    return type === 'video' ? can.video : can.create;
  }

  function renderLibrary() {
    const data = state.data;
    const list = $('#library');
    list.replaceChildren();
    $('#library-count').textContent = String(data.uploads.length);
    $('#library-empty').hidden = data.uploads.length > 0;
    for (const upload of data.uploads) {
      const source = '/api/uploads/' + encodeURIComponent(upload.id);
      const preview = upload.kind === 'image'
        ? el('img', { src: source, alt: upload.id, loading: 'lazy', decoding: 'async' })
        : el('video', { src: source + '#t=0.1', preload: 'metadata', muted: true, controls: true, playsinline: true });
      const usage = upload.usedBy.length > 0 ? t('used-by', { screens: upload.usedBy.join(', ') }) : t('unused');
      const actions = el('div', { class: 'media-actions' },
        el('button', {
          class: 'button',
          type: 'button',
          text: t('copy-command'),
          onclick: () => copy(commandFor(upload))
        }),
        el('button', {
          class: 'button danger',
          type: 'button',
          text: t('delete'),
          onclick: () => removeUpload(upload)
        })
      );
      const targets = data.screens.filter((screen) => compatible(upload, screen) && allowed(screen.type));
      let apply = null;
      if (targets.length > 0) {
        const select = el('select', { 'aria-label': t('apply-choose') },
          el('option', { value: '', text: t('apply-choose') }),
          ...targets.map((screen) => el('option', { value: screen.name, text: screen.name }))
        );
        apply = el('div', { class: 'apply' },
          select,
          el('button', {
            class: 'button primary',
            type: 'button',
            text: t('apply'),
            onclick: () => applyUpload(upload, select.value)
          })
        );
      }
      const item = el('li', { class: 'media' },
        el('div', { class: 'media-preview' }, preview),
        el('div', { class: 'media-body' },
          el('div', { class: 'media-title' },
            el('span', { class: 'media-id', text: upload.id }),
            el('span', { class: 'kind ' + upload.kind, text: t('kind-' + upload.kind) })
          ),
          el('span', { class: 'media-meta', text: formatBytes(upload.size) + ' · ' + formatDate(upload.modified) }),
          el('span', { class: 'media-meta', text: usage }),
          apply,
          actions
        )
      );
      list.append(item);
    }
  }

  function renderScreens() {
    const data = state.data;
    const list = $('#screens');
    list.replaceChildren();
    $('#screens-count').textContent = String(data.screens.length);
    $('#screens-empty').hidden = data.screens.length > 0;
    for (const screen of data.screens) {
      const remove = data.can.remove
        ? el('button', {
          class: 'button danger',
          type: 'button',
          text: t('remove'),
          onclick: () => removeScreen(screen)
        })
        : el('span');
      const row = el('div', { class: 'screen-row', role: 'listitem' },
        el('div', { class: 'screen-name' },
          el('span', { class: 'screen-type', text: t('kind-' + screen.type) }),
          screen.name
        ),
        el('span', { class: 'screen-source', text: screen.source }),
        el('span', { class: 'screen-cell' }, el('strong', { text: screen.width + ' × ' + screen.height })),
        el('span', {
          class: 'screen-cell',
          text: screen.world + ' ' + screen.x + ', ' + screen.y + ', ' + screen.z + ' · ' + t('facing-' + screen.facing)
        }),
        el('span', { class: 'screen-cell', text: screen.state + ' · ' + t('viewers', { count: screen.viewers }) }),
        remove
      );
      list.append(row);
    }
  }

  function renderCreate() {
    const data = state.data;
    const section = $('#create');
    const usable = data.uploads.filter((upload) => allowed(upload.kind === 'video' ? 'video' : 'image'));
    section.hidden = !(data.can.create || data.can.video) || usable.length === 0;
    if (section.hidden) {
      return;
    }
    const form = $('#create-form');
    const media = form.elements.upload;
    const previous = media.value;
    media.replaceChildren(...usable.map((upload) => el('option', {
      value: upload.id,
      text: upload.id + ' (' + t('kind-' + upload.kind) + ')'
    })));
    if (usable.some((upload) => upload.id === previous)) {
      media.value = previous;
    }
    const world = form.elements.world;
    if (world.options.length !== data.worlds.length) {
      world.replaceChildren(...data.worlds.map((name) => el('option', { value: name, text: name })));
      if (data.defaults.world) {
        world.value = data.defaults.world;
      }
    }
    const facing = form.elements.facing;
    if (facing.options.length === 0) {
      const facings = ['north', 'south', 'east', 'west'].concat(data.vertical ? ['up', 'down'] : []);
      facing.replaceChildren(...facings.map((name) => el('option', { value: name, text: t('facing-' + name) })));
      facing.value = data.defaults.facing;
      form.elements.x.value = data.defaults.x;
      form.elements.y.value = data.defaults.y;
      form.elements.z.value = data.defaults.z;
      form.elements.width.value = data.defaults.width;
      form.elements.height.value = data.defaults.height;
      form.elements.width.max = data.limits.maxWidth;
      form.elements.height.max = data.limits.maxHeight;
      form.elements.width.min = data.limits.minSize;
      form.elements.height.min = data.limits.minSize;
    }
  }

  async function copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast(t('copied'), 'success');
    } catch (ignored) {
      window.prompt(t('copy-command'), text);
    }
  }

  async function applyUpload(upload, screen) {
    if (!screen) {
      toast(t('apply-choose'), 'error');
      return;
    }
    try {
      await sendJson('/api/screens/' + encodeURIComponent(screen) + '/source', 'POST', { upload: upload.id });
      toast(t('applied', { media: upload.id, screen }), 'success');
      await load();
    } catch (error) {
      handleFailure(error);
    }
  }

  async function removeUpload(upload) {
    if (!window.confirm(t('delete-confirm', { name: upload.id }))) {
      return;
    }
    try {
      await api('/api/uploads/' + encodeURIComponent(upload.id), { method: 'DELETE' });
      toast(t('deleted', { name: upload.id }), 'success');
      await load();
    } catch (error) {
      handleFailure(error);
    }
  }

  async function removeScreen(screen) {
    if (!window.confirm(t('remove-confirm', { name: screen.name }))) {
      return;
    }
    try {
      await api('/api/screens/' + encodeURIComponent(screen.name), { method: 'DELETE' });
      toast(t('removed', { name: screen.name }), 'success');
      await load();
    } catch (error) {
      handleFailure(error);
    }
  }

  async function createScreen(event) {
    event.preventDefault();
    const form = event.target;
    const fields = {};
    for (const name of ['name', 'upload', 'world', 'facing', 'x', 'y', 'z', 'width', 'height']) {
      fields[name] = String(form.elements[name].value).trim();
    }
    if (!/^[A-Za-z0-9_-]{1,32}$/.test(fields.name)) {
      toast(t('invalid-name'), 'error');
      form.elements.name.focus();
      return;
    }
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    try {
      await sendJson('/api/screens', 'POST', fields);
      toast(t('created', { name: fields.name }), 'success');
      form.elements.name.value = '';
      const width = Math.ceil(Number(fields.width) - 1e-9);
      const height = Math.ceil(Number(fields.height) - 1e-9);
      const right = { south: [1, 0], north: [-1, 0], east: [0, -1], west: [0, 1] }[fields.facing] || [1, 0];
      const step = (fields.facing === 'up' || fields.facing === 'down' ? Math.max(width, height) : width) + 1;
      form.elements.x.value = Number(fields.x) + right[0] * step;
      form.elements.z.value = Number(fields.z) + right[1] * step;
      await load();
    } catch (error) {
      handleFailure(error);
    } finally {
      button.disabled = false;
    }
  }

  function uploadFile(file) {
    return new Promise((resolve) => {
      const status = el('span', { class: 'queue-status', text: t('uploading', { name: file.name }) });
      const fill = el('div', { class: 'meter-fill' });
      const item = el('li', { class: 'queue-item' },
        el('span', { class: 'queue-name', text: file.name + ' · ' + formatBytes(file.size) }),
        el('div', { class: 'meter' }, fill),
        status
      );
      $('#queue').prepend(item);
      const request = new XMLHttpRequest();
      request.open('POST', '/api/upload');
      request.setRequestHeader('Content-Type', 'application/octet-stream');
      request.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
      request.setRequestHeader('X-CSRF-Token', state.csrf);
      request.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
          fill.style.width = ((event.loaded / event.total) * 100).toFixed(1) + '%';
        }
      });
      request.addEventListener('load', () => {
        let body = null;
        try {
          body = JSON.parse(request.responseText);
        } catch (ignored) {
          body = null;
        }
        if (request.status >= 200 && request.status < 300) {
          fill.style.width = '100%';
          item.classList.add('done');
          status.textContent = t('uploaded', { name: body && body.id ? body.id : file.name });
        } else {
          item.classList.add('failed');
          status.textContent = t('upload-failed', {
            name: file.name,
            reason: body && body.error ? body.error : t('error-generic')
          });
          if (request.status === 401) {
            gate(t('session-ended'), t('new-link-hint'));
          }
        }
        resolve();
      });
      request.addEventListener('error', () => {
        item.classList.add('failed');
        status.textContent = t('upload-failed', { name: file.name, reason: t('error-network') });
        resolve();
      });
      request.send(file);
    });
  }

  async function uploadAll(files) {
    if (!files || files.length === 0) {
      return;
    }
    state.uploading = true;
    try {
      for (const file of files) {
        await uploadFile(file);
      }
    } finally {
      state.uploading = false;
    }
    try {
      await load();
    } catch (error) {
      handleFailure(error);
    }
  }

  async function logout() {
    try {
      await api('/api/logout', { method: 'POST' });
    } catch (ignored) {
      state.csrf = null;
    }
    gate(t('signed-out'), t('new-link-hint'));
  }

  function bind() {
    const input = $('#file-input');
    const zone = $('#dropzone');
    input.addEventListener('change', () => {
      const files = Array.from(input.files);
      input.value = '';
      uploadAll(files);
    });
    zone.addEventListener('dragover', (event) => {
      event.preventDefault();
      zone.classList.add('over');
    });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', (event) => {
      event.preventDefault();
      zone.classList.remove('over');
      uploadAll(Array.from(event.dataTransfer.files));
    });
    $('#create-form').addEventListener('submit', createScreen);
    $('#logout').addEventListener('click', logout);
  }

  document.addEventListener('DOMContentLoaded', () => {
    bind();
    boot();
  });
})();
