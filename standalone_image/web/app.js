/* Local Dream Image — static UI, no framework. */
(function () {
  var GALLERY_KEY = 'localdream-image-gallery';
  var state = { engine: {}, busy: false };

  function byId(id) { return document.getElementById(id); }
  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) { node.className = cls; }
    if (text !== undefined && text !== null) { node.textContent = String(text); }
    return node;
  }
  function say(text, bad) {
    var node = byId('message');
    node.textContent = text || '';
    node.style.color = bad ? 'var(--danger)' : 'var(--muted)';
  }
  function api(path, options) {
    return fetch(path, options).then(function (res) {
      return res.json().then(function (body) {
        if (!body || body.success === false) {
          throw new Error((body && body.error) || 'HTTP ' + res.status);
        }
        return body.data;
      });
    });
  }
  function get(path) { return api(path, { credentials: 'same-origin' }); }
  function post(path, body) {
    return api(path, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    });
  }

  function renderModels(data) {
    var list = byId('models');
    list.textContent = '';
    (data.models || []).forEach(function (m) {
      var card = el('div', 'model');
      var head = el('div', 'model-head');
      head.appendChild(el('span', 'model-name', m.name));
      head.appendChild(el('span', 'chip ' + (
        m.status === 'loaded' ? 'loaded' :
        (m.installed ? 'installed' : '')), m.status.replace('_', ' ')));
      card.appendChild(head);
      card.appendChild(el('p', 'model-meta',
        '~' + m.size_mb + ' MB · ' + m.license + ' · ' + m.notes));
      var actions = el('div', 'model-actions');
      if (!m.installed) {
        var dl = el('button', '', 'Download');
        dl.type = 'button';
        dl.addEventListener('click', function () {
          post('/api/download', { id: m.id }).then(function (r) {
            say(r.status === 'started' ? 'Downloading ' + m.name + '…' : r.status);
            pollDownload();
          }).catch(function (e) { say(e.message, true); });
        });
        actions.appendChild(dl);
      } else if (m.status === 'loaded') {
        var off = el('button', '', 'Unload');
        off.type = 'button';
        off.addEventListener('click', function () {
          post('/api/unload', { id: m.id }).then(function () {
            say('Engine unloaded.'); refresh();
          }).catch(function (e) { say(e.message, true); });
        });
        actions.appendChild(off);
      } else {
        var on = el('button', 'primary', 'Load');
        on.type = 'button';
        on.addEventListener('click', function () {
          say('Loading model…');
          post('/api/load', { id: m.id }).then(function () {
            byId('size').value = String(m.size);
            byId('steps').value = String(m.steps);
            say(m.name + ' ready.'); refresh();
          }).catch(function (e) { say(e.message, true); });
        });
        actions.appendChild(on);
      }
      var link = el('a', '', 'Model page ↗');
      link.href = m.source; link.target = '_blank'; link.rel = 'noopener noreferrer';
      actions.appendChild(link);
      card.appendChild(actions);
      list.appendChild(card);
    });
  }

  function refresh() {
    get('/api/models').then(function (data) {
      state.engine = data.engine || {};
      renderModels(data);
      byId('engine').textContent = state.engine.running ? 'Engine online' : 'Engine offline';
      byId('engine').className = 'pill' + (state.engine.running ? ' on' : '');
      byId('go').disabled = !state.engine.running || state.busy;
      byId('cancel').disabled = !state.busy;
    }).catch(function (e) { say(e.message, true); });
  }

  function pollDownload() {
    get('/api/download/status').then(function (d) {
      var node = byId('download');
      node.textContent = '';
      if (d.status === 'downloading') {
        node.textContent = 'Downloading ' + (d.downloaded_mb || 0) + ' / ' +
          (d.total_mb || '?') + ' MB (' + (d.percent || 0) + '%)';
      } else if (d.status === 'completed') {
        node.textContent = 'Download complete.';
        refresh();
      } else if (d.status === 'error') {
        node.textContent = 'Download failed: ' + d.error;
      }
    }).catch(function () {});
  }

  function renderResults(items) {
    var node = byId('results');
    node.textContent = '';
    if (!items || !items.length) { node.appendChild(el('p', 'muted', 'No GGUF models found.')); return; }
    items.forEach(function (item) {
      var row = el('div', 'result-row');
      row.appendChild(el('span', '', item.repo + ' · ' + item.file));
      var copy = el('button', '', 'Copy link');
      copy.type = 'button';
      copy.addEventListener('click', function () {
        var url = item.source + '/resolve/main/' + item.file;
        if (navigator.clipboard) {
          navigator.clipboard.writeText(url).then(function () {
            say('Link copied. Add it to CATALOG in server.py to enable download.');
          });
        } else { say(url); }
      });
      row.appendChild(copy);
      node.appendChild(row);
    });
  }

  function gallery() {
    try { return JSON.parse(localStorage.getItem(GALLERY_KEY) || '[]'); }
    catch (e) { return []; }
  }
  function renderGallery() {
    var node = byId('gallery');
    node.textContent = '';
    var items = gallery();
    if (!items.length) { node.appendChild(el('p', 'muted', 'No images yet.')); return; }
    items.forEach(function (item) {
      var img = el('img', '', '');
      img.src = item.path; img.alt = 'generated image';
      img.addEventListener('click', function () {
        byId('preview').src = item.path;
        byId('save').href = item.path;
        byId('result').hidden = false;
      });
      node.appendChild(img);
    });
  }
  function remember(item) {
    var items = gallery();
    items.unshift(item);
    try { localStorage.setItem(GALLERY_KEY, JSON.stringify(items.slice(0, 12))); }
    catch (e) {}
    renderGallery();
  }

  function generate() {
    var size = parseInt(byId('size').value, 10);
    var body = {
      prompt: byId('prompt').value,
      negative_prompt: byId('negative').value,
      size: size,
      steps: parseInt(byId('steps').value, 10),
      seed: parseInt(byId('seed').value, 10)
    };
    if (!body.prompt.trim()) { say('Enter a prompt first.', true); return; }
    state.busy = true;
    byId('go').disabled = true; byId('cancel').disabled = false;
    byId('progress').hidden = false; byId('bar').style.width = '0%';
    say('Generating…');
    fetch('/api/generate', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    }).then(function (res) {
      if (!res.ok || !res.body) { throw new Error('HTTP ' + res.status); }
      var reader = res.body.getReader(), decoder = new TextDecoder(), buffer = '';
      function pump() {
        return reader.read().then(function (out) {
          if (out.done) { return; }
          buffer += decoder.decode(out.value, { stream: true });
          var lines = buffer.split('\n'); buffer = lines.pop();
          lines.forEach(function (line) {
            var ev;
            try { ev = JSON.parse(line.trim()); } catch (e) { return; }
            if (ev.t === 'progress') {
              byId('stage').textContent = ev.stage || 'working';
              if (typeof ev.completed === 'number' && typeof ev.total === 'number' && ev.total) {
                byId('bar').style.width = Math.min(100, Math.round(ev.completed * 100 / ev.total)) + '%';
                byId('steps-label').textContent = ev.completed + '/' + ev.total;
              }
            } else if (ev.t === 'done') {
              byId('bar').style.width = '100%';
              byId('preview').src = ev.path;
              byId('save').href = ev.path;
              byId('save').download = ev.id + '.png';
              byId('result').hidden = false;
              remember({ path: ev.path });
              say('Done in ' + Math.round((ev.ms || 0) / 1000) + 's.');
            } else if (ev.t === 'error') {
              say(ev.e || 'Generation failed.', true);
            }
          });
          return pump();
        });
      }
      return pump();
    }).catch(function (e) { say(e.message, true); }).then(function () {
      state.busy = false;
      byId('cancel').disabled = true;
      refresh();
    });
  }

  byId('go').addEventListener('click', generate);
  byId('cancel').addEventListener('click', function () {
    post('/api/cancel', {}).then(function () { say('Cancel requested.'); })
      .catch(function (e) { say(e.message, true); });
  });
  byId('search-btn').addEventListener('click', function () {
    var q = byId('search').value.trim();
    if (!q) { say('Enter a search term.', true); return; }
    get('/api/search?q=' + encodeURIComponent(q))
      .then(renderResults).catch(function (e) { say(e.message, true); });
  });

  refresh();
  pollDownload();
  renderGallery();
  setInterval(refresh, 6000);
  setInterval(pollDownload, 3000);
})();
