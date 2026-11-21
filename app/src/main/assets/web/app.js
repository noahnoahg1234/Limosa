'use strict';

const $ = (s, el = document) => el.querySelector(s);
const native = window.LimosaApp || null;
let disks = [];
let vms = [];
let busy = false;

function fmt(bytes) {
  if (bytes == null) return '–';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0, v = bytes;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1)) + ' ' + u[i];
}

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('show'), 3500);
}

async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const r = await fetch(path, opts);
  const text = await r.text();
  const data = text ? JSON.parse(text) : null;
  if (!r.ok) throw new Error((data && data.error) || r.statusText);
  return data;
}

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children) if (c != null) e.append(c);
  return e;
}

async function act(fn, okMsg) {
  if (busy) return;
  busy = true;
  try {
    await fn();
    if (okMsg) toast(okMsg);
  } catch (e) {
    toast(e.message);
  } finally {
    busy = false;
    refresh();
  }
}

// ---------------------------------------------------------------- rendering

function renderStatus(s) {
  const st = s.storage;
  $('#st-virtual').textContent = fmt(st.virtualBytes);
  $('#st-physical').textContent = fmt(st.physicalBytes);
  const pct = st.virtualBytes ? Math.max(0.2, (st.physicalBytes / st.virtualBytes) * 100) : 0;
  $('#st-bar').style.width = Math.min(100, pct).toFixed(2) + '%';
  const ratio = st.physicalBytes ? (st.logicalBytes / st.physicalBytes) : 0;
  $('#st-detail').textContent =
    `${st.disks} disk(s) · saved ${fmt(Math.max(0, st.savedBytes))} vs. full-size images` +
    (ratio > 1.05 ? ` · dedup+compression ${ratio.toFixed(1)}×` : '') +
    ` · ${fmt(st.freeBytes)} free on phone`;

  const idle = s.idle;
  $('#activity-dot').className = 'dot' + (idle.inactive ? ' idle' : '');
  $('#activity-text').textContent = idle.inactive
    ? `Phone inactive (${idle.reason}) – VMs paused`
    : `Phone active · battery ${idle.batteryPercent}%${idle.charging ? ' ⚡' : ''}`;
}

function vmCard(vm) {
  const state = vm.state;
  const running = state === 'running' || state === 'starting';
  const paused = state === 'paused';
  const stopped = state === 'stopped';
  const chipText = paused && vm.pauseReason === 'idle' ? 'auto-paused' : state;
  const details = [
    vm.arch, `${vm.memoryMb} MB`, `${vm.cpus} CPU`,
    vm.disk ? `disk: ${vm.disk}` : null, vm.cdrom ? `cd: ${vm.cdrom}` : null,
    vm.autoPause ? 'auto-pause' : 'always on',
  ].filter(Boolean).join(' · ');
  const name = encodeURIComponent(vm.name);
  return el('div', { class: 'card' },
    el('div', { class: 'row spread' },
      el('span', { class: 'vm-title' }, vm.name),
      el('span', { class: 'chip ' + state }, chipText)),
    el('div', { class: 'small muted' }, details),
    el('div', { class: 'actions' },
      stopped ? el('button', { class: 'primary', onclick: () => act(() => api('POST', `/api/vms/${name}/start`), 'Starting ' + vm.name) }, 'Start') : null,
      running || paused ? el('a', { class: 'btn', href: `console.html?vm=${name}&port=${vm.vncWebSocketPort}` }, 'Console') : null,
      running ? el('button', { onclick: () => act(() => api('POST', `/api/vms/${name}/pause`)) }, 'Pause') : null,
      paused ? el('button', { class: 'primary', onclick: () => act(() => api('POST', `/api/vms/${name}/resume`)) }, 'Resume') : null,
      running || paused ? el('button', { onclick: () => act(() => api('POST', `/api/vms/${name}/stop`), 'Shutdown requested') }, 'Shut down') : null,
      running || paused ? el('button', { class: 'danger', onclick: () => confirm(`Force off ${vm.name}? Unsaved guest data is lost.`) && act(() => api('POST', `/api/vms/${name}/kill`)) }, 'Force off') : null,
      el('button', { onclick: () => showLog(vm.name) }, 'Log'),
      stopped ? el('button', { class: 'danger', onclick: () => confirm(`Delete VM ${vm.name}? Its disk is kept.`) && act(() => api('DELETE', `/api/vms/${name}`)) }, 'Delete') : null,
    ));
}

function diskCard(d) {
  const used = vms.filter(v => v.disk === d.name || v.cdrom === d.name).map(v => v.name);
  const name = encodeURIComponent(d.name);
  return el('div', { class: 'card' },
    el('div', { class: 'row spread' },
      el('span', { class: 'vm-title' }, d.name),
      el('span', { class: 'chip' }, d.readOnly ? 'image (read-only)' : 'disk')),
    el('div', { class: 'small muted' },
      `${fmt(d.size)} virtual · ${fmt(d.allocated)} written` + (used.length ? ` · used by ${used.join(', ')}` : '')),
    el('div', { class: 'actions' },
      el('button', { onclick: () => {
        const target = prompt('Name for the copy (shares blocks, uses no extra space):', d.name + '-copy');
        if (target) act(() => api('POST', `/api/disks/${name}/clone`, { target }), 'Cloned');
      } }, 'Clone'),
      el('a', { class: 'btn', href: `/disk/${name}`, download: d.name + '.img' }, 'Download'),
      el('button', { class: 'danger', onclick: () => confirm(`Delete ${d.name}? This cannot be undone.`) && act(() => api('DELETE', `/api/disks/${name}`), 'Deleted') }, 'Delete'),
    ));
}

function render() {
  const vmBox = $('#vms');
  vmBox.replaceChildren(...(vms.length ? vms.map(vmCard) : [el('div', { class: 'card empty' }, 'No VMs yet. Import an installer ISO, then create a VM.')]));
  const dBox = $('#disks');
  dBox.replaceChildren(...(disks.length ? disks.map(diskCard) : [el('div', { class: 'card empty' }, 'No disks yet.')]));
}

async function refresh() {
  try {
    const [s, v, d] = await Promise.all([api('GET', '/api/status'), api('GET', '/api/vms'), api('GET', '/api/disks')]);
    vms = v; disks = d;
    renderStatus(s);
    if (!document.querySelector('dialog[open]')) render();
  } catch (e) {
    $('#activity-text').textContent = 'Not connected: ' + e.message;
  }
}

async function showLog(name) {
  $('#log-title').textContent = 'Log – ' + name;
  try {
    const lines = await api('GET', `/api/vms/${encodeURIComponent(name)}/log`);
    $('#log-body').textContent = lines.length ? lines.join('\n') : '(empty)';
  } catch (e) {
    $('#log-body').textContent = e.message;
  }
  $('#log-dialog').showModal();
}

// ---------------------------------------------------------------- dialogs

function fillSelect(sel, items, noneLabel, extra) {
  sel.replaceChildren(el('option', { value: '' }, noneLabel), ...(extra || []),
    ...items.map(d => el('option', { value: d.name }, `${d.name} (${fmt(d.size)})`)));
}

$('#new-vm').addEventListener('click', () => {
  const f = $('#vm-form');
  f.reset();
  fillSelect(f.disk, disks.filter(d => !d.readOnly), 'None', [el('option', { value: '__new__', selected: true }, 'Create a new disk')]);
  fillSelect(f.cdrom, disks.filter(d => d.readOnly), 'None');
  if (disks.some(d => d.readOnly)) { f.cdrom.selectedIndex = 1; f.bootFromCdrom.checked = true; }
  $('#vm-dialog').showModal();
});

$('#vm-form').addEventListener('submit', (ev) => {
  const f = ev.target;
  if (ev.submitter && ev.submitter.value !== 'save') return;
  const cfg = {
    name: f.name.value.trim(), arch: f.arch.value,
    memoryMb: +f.memoryMb.value, cpus: +f.cpus.value,
    disk: f.disk.value, cdrom: f.cdrom.value || null,
    bootFromCdrom: f.bootFromCdrom.checked, autoPause: f.autoPause.checked,
    network: f.network.checked, extraArgs: f.extraArgs.value,
  };
  act(async () => {
    if (cfg.disk === '__new__') {
      const diskName = cfg.name + '-disk';
      await api('POST', '/api/disks', { name: diskName, sizeGb: +f.newDiskGb.value || 32 });
      cfg.disk = diskName;
    }
    await api('POST', '/api/vms', cfg);
  }, 'VM saved');
});

$('#new-disk').addEventListener('click', () => { $('#disk-form').reset(); $('#disk-dialog').showModal(); });
$('#disk-form').addEventListener('submit', (ev) => {
  if (ev.submitter && ev.submitter.value !== 'save') return;
  const f = ev.target;
  act(() => api('POST', '/api/disks', { name: f.name.value.trim(), sizeGb: +f.sizeGb.value }), 'Disk created');
});

$('#import-iso').addEventListener('click', () => {
  $('#iso-form').reset();
  $('#iso-file-wrap').hidden = !!native; // the app uses Android's file picker instead
  $('#iso-dialog').showModal();
});

window.limosaImportDone = (name, error) => {
  if (error === 'cancelled') toast('Import cancelled');
  else if (error) toast('Import failed: ' + error);
  else toast('Imported ' + name);
  refresh();
};

$('#iso-form').addEventListener('submit', (ev) => {
  if (ev.submitter && ev.submitter.value !== 'save') return;
  const f = ev.target;
  const name = f.name.value.trim();
  const ro = f.readOnly.checked;
  if (native) {
    const err = native.importImage(name, ro);
    if (err) toast(err); else toast('Pick the file… importing runs in the background');
    return;
  }
  const file = f.file.files[0];
  if (!file) { toast('Choose a file'); return; }
  toast('Uploading ' + file.name + '…');
  act(async () => {
    const r = await fetch(`/api/disks/${encodeURIComponent(name)}?readOnly=${ro}`, { method: 'PUT', body: file });
    if (!r.ok) throw new Error((await r.json()).error);
  }, 'Imported ' + name);
});

// ---------------------------------------------------------------- settings

async function loadSettings() {
  const s = await api('GET', '/api/settings');
  const f = $('#settings');
  for (const [k, v] of Object.entries(s)) {
    if (!f[k]) continue;
    if (f[k].type === 'checkbox') f[k].checked = v; else f[k].value = v;
  }
}

$('#settings').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const f = ev.target;
  act(() => api('POST', '/api/settings', {
    enabled: f.enabled.checked,
    screenOffDelaySec: +f.screenOffDelaySec.value,
    pauseOnDeviceIdle: f.pauseOnDeviceIdle.checked,
    pauseWhenLocked: f.pauseWhenLocked.checked,
    pauseOnPowerSave: f.pauseOnPowerSave.checked,
    lowBatteryPercent: +f.lowBatteryPercent.value,
  }), 'Power settings saved');
});

// ---------------------------------------------------------------- start

if (native) {
  try {
    const info = JSON.parse(native.info());
    if (!info.installedArchs.length) {
      const n = $('#qemu-notice');
      n.hidden = false;
      n.textContent = 'No QEMU emulator is bundled in this build, so VMs cannot start yet. ' +
        'Storage, disks and the dashboard work. See the README ("Bundling QEMU") to build an APK with QEMU.';
    }
  } catch (e) { /* ignore */ }
}

loadSettings().catch(e => toast(e.message));
refresh();
setInterval(() => { if (!document.hidden) refresh(); }, 2000);
