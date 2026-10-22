import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const source = readFileSync(new URL('../web-portals/src/wasmJsMain/resources/portal-boot.js', import.meta.url), 'utf8');
function harness(hostname, online = true) {
  const elements = Object.fromEntries(['portal-boot','portal','boot-brand','boot-role','boot-owner','boot-message','boot-status','boot-retry'].map(id => [id, { textContent: '', hidden: false, addEventListener() {} }]));
  elements['boot-retry'].hidden = true;
  let label = 'Competency workspace';
  let canvas = null;
  let mutation;
  let disconnected = false;
  let reloaded = false;
  const frames = [], timers = [], events = new Map();
  elements.portal.querySelector = () => canvas;
  elements.portal.getAttribute = () => label;
  elements.portal.removeAttribute = name => { assert.equal(name, 'aria-busy'); };
  elements['boot-retry'].addEventListener = (_, callback) => events.set('retry', callback);
  vm.runInNewContext(source, {
    document: { getElementById: id => elements[id], documentElement: { style: { setProperty() {} } }, querySelector: () => ({}) },
    location: { hostname, reload: () => { reloaded = true; } }, navigator: { onLine: online },
    MutationObserver: class { constructor(callback) { mutation = callback; } observe() {} disconnect() { disconnected = true; } },
    requestAnimationFrame: callback => frames.push(callback),
    setTimeout: callback => { timers.push(callback); return timers.length; }, clearTimeout() {},
    window: { addEventListener: (name, callback) => events.set(name, callback), removeEventListener: name => events.delete(name) }
  });
  return { elements, frames, timers, events, setCanvas: () => { canvas = { width: 800 }; mutation(); },
    setReadyLabel: () => { label = 'Independent organization — Workspace'; mutation(); },
    disconnected: () => disconnected, reloaded: () => reloaded };
}
for (const [host, brand] of [['lsp-nusantara', 'LSP Nusantara'], ['karya-instalasi', 'Karya Instalasi'], ['dompet-identitas', 'NusaCred Wallet']]) {
  const h = harness(host + '.demo.inoerawan.id');
  assert.equal(h.elements['boot-brand'].textContent, brand);
  h.setCanvas();
  assert.equal(h.frames.length, 0, 'Canvas creation alone must not reveal an uninitialized renderer');
  h.setReadyLabel();
  h.frames.shift()();
  assert.equal(h.elements['portal-boot'].hidden, false, 'Keep loading UI through the first frame');
  h.frames.shift()();
  assert.equal(h.elements['portal-boot'].hidden, true);
  assert.equal(h.disconnected(), true);
  assert.equal(h.events.has('unhandledrejection'), false);
}
// Compose may render through a surface outside the portal's ordinary DOM tree.
const separateSurface = harness('karya-instalasi.demo.inoerawan.id');
separateSurface.setReadyLabel();
separateSurface.frames.shift()();
separateSurface.frames.shift()();
assert.equal(separateSurface.elements['portal-boot'].hidden, true, 'Mounted identity must work without a descendant canvas');
const slow = harness('karya-instalasi.demo.inoerawan.id');
slow.timers[0]();
assert.equal(slow.elements['boot-retry'].hidden, false);
slow.events.get('retry')();
assert.equal(slow.reloaded(), true);
const offline = harness('dompet-identitas.demo.inoerawan.id', false);
assert.match(offline.elements['boot-message'].textContent, /offline/);
offline.events.get('unhandledrejection')({ reason: 'private error details must not be displayed' });
assert.doesNotMatch(offline.elements['boot-message'].textContent, /private error details/);
console.log('PASS: three entity brands, renderer readiness, slow/offline failure, retry and error privacy');
