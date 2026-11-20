import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const script = fs.readFileSync(new URL('../assets/app.js', import.meta.url), 'utf8');
const image = {
  size: 3, partitionLength: 3072, partitionMd5: 'layout',
  otaSeedMd5: 'seed', otaEmptyMd5: 'blank', md5: 'verified',
};

function load({layout = 'layout', ota = 'seed', digest = 'verified', encryption = 0} = {}) {
  const events = [];
  const requests = [];
  const nodes = new Map();
  function node() {
    return {
      value: '', options: [],
      replaceChildren() { this.options = []; this.value = ''; },
      append(child) {
        if (this.options.length === 0) this.value = child.value;
        this.options.push(child);
      },
      setAttribute() {},
    };
  }
  const element = id => {
    if (!nodes.has(id)) nodes.set(id, node());
    return nodes.get(id);
  };
  class Loader {
    chip = {IMAGE_CHIP_ID: 9};
    FLASH_WRITE_SIZE = 16384;
    async connect() { events.push('connect'); }
    async command() {}
    async readReg(address) { return address === 0x60007034 ? encryption << 18 : 0; }
    async runStub() { events.push('stub'); }
    async readFlashId() { return 0x1640c8; }
    async flashMd5sum(address) {
      return {0x8000: layout, 0xe000: ota, 0x10000: digest}[address];
    }
    async flashBegin(size, address) { events.push(['begin', size, address]); }
    async flashBlock(block, sequence) { events.push(['block', block.length, sequence]); }
    async flashFinish() { events.push('finish'); }
    async after() { events.push('reset'); }
  }
  const environment = vm.createContext({
    window: {}, document: {getElementById: element, createElement: node}, setTimeout, performance, atob, btoa,
    ReadableStream, WritableStream,
    esptool: {Transport: class {}, ESPLoader: Loader},
    Phone: {
      ready() {}, logTail: () => '', diagnostic() {}, close() {},
      connect: () => '', readData: () => '', portError: () => '',
      connectUsb: id => events.push(['inspect', id]),
      confirm: (...args) => events.push(['confirm', ...args]),
      versions: (...args) => requests.push(args),
      download: (...args) => events.push(args),
      repositoryAssets: (...args) => requests.push(args),
      downloadRepositoryAsset: (...args) => events.push(args),
      saveRepositoryAsset: (...args) => events.push(args),
      pickLocal: board => events.push(['local', board]),
      image: () => 'AQID', finished: (success, detail) => events.push({success, detail}),
    }
  });
  vm.runInContext(script, environment);
  return {events, element, requests, showUsb: devices => environment.window.onUsb(devices, ''),
    showBoards: boards => environment.window.onBoards(boards),
    showDownload: image => environment.window.onDownload(image, null),
    showVersions: (request, versions) => environment.window.onVersions(request, versions, null),
    showRepositories: links => environment.window.onRepositories(links),
    showRepositoryAssets: (request, files) => environment.window.onRepositoryAssets(request, {files}, null),
    showRepositoryDownload: request => environment.window.onRepositoryDownload(request, {}, null),
    showLocal: image => environment.window.onLocalFirmware(image, null),
    run: probe => environment.window.startFlash(7, image, probe)};
}

test('probe checks the target and resets it without flashing', async () => {
  const {events, run} = load();
  await run(true);
  assert.deepEqual(events.slice(0, -1), ['connect', 'stub', 'reset']);
  assert.equal(events.at(-1).success, true);
});

test('unknown layout or app slot stops before any erase/write', async () => {
  for (const problems of [{layout: 'another'}, {ota: 'app1'}, {encryption: 1}]) {
    const {events, run} = load(problems);
    await run(false);
    assert.equal(events.some(event => Array.isArray(event)), false);
    assert.equal(events.at(-1).success, false);
  }
});

test('verified write stays in the approved app region and resets', async () => {
  const {events, run} = load();
  await run(false);
  assert.deepEqual(events.slice(2, -1), [['begin', 3, 0x10000], ['block', 16384, 0], 'finish', 'reset']);
  assert.equal(events.at(-1).success, true);
});

test('digest mismatch reports failure without resetting the target', async () => {
  const {events, run} = load({digest: 'wrong'});
  await run(false);
  assert.equal(events.includes('reset'), false);
  assert.equal(events.at(-1).success, false);
  assert.match(events.at(-1).detail, /digest mismatch/);
});

test('USB scan lists generic devices and connects only to the selected device', () => {
  const {events, element, showUsb, showBoards, showDownload} = load();
  showBoards([{name: 'Elecrow ThinkNode M5', roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  const native = {id: 4, name: 'XIAO', type: 'Native USB', code: '303A:1001', allowed: true, m5: false};
  const bridge = {id: 7, name: 'WCH', type: 'USB serial', code: '1A86:7522', allowed: true, m5: true,
    board: 'Elecrow ThinkNode M5'};
  showUsb([native, bridge]);
  assert.equal(element('device').options.length, 2);
  assert.equal(element('flash').disabled, true);
  element('connect').onclick();
  assert.deepEqual(events, [['inspect', 4]]);

  element('device').value = '7';
  element('device').onchange();
  assert.equal(element('flash').disabled, true);
  assert.equal(element('probe').disabled, false);
  showDownload({tag: 'companion-v1.18.0', source: 'official'});
  assert.equal(element('flash').disabled, false);
  element('flash').onclick();
  assert.deepEqual(events.at(-1), ['confirm', 7, false, 'official', 'companionBle']);
  showUsb([native]);
  assert.equal(element('flash').disabled, true);
  showUsb([]);
  assert.equal(element('connect').disabled, true);
});

test('a recognized MeshCore board loads official versions and enables eligible downloads', () => {
  const {element, requests, showUsb, showBoards, showVersions} = load();
  const name = 'Elecrow ThinkNode M5';
  showBoards([{name, roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 7, name, allowed: true, m5: true, board: name}]);
  const [request, board, role] = requests[0];
  assert.equal(board, name);
  assert.equal(role, 'companionBle');
  showVersions(request, [{version: 'v1.18.0', types: ['flash-update'], appTag: 'companion-v1.18.0'}]);
  assert.equal(element('version').options[0].textContent, 'v1.18.0 · app update');
  assert.equal(element('fetch').disabled, false);
});

test('manual board selection persists through a generic USB scan and switches to Solo catalogue', () => {
  const {element, requests, showUsb, showBoards} = load();
  const wio = 'Seeed Studio Wio Tracker L1 Pro';
  const m5 = 'Elecrow ThinkNode M5';
  showBoards([
    {name: wio, type: 'nrf52', roles: [{id: 'repeater', title: 'Repeater'}]},
    {name: m5, type: 'esp32', roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]},
  ]);
  element('board').value = wio;
  element('board').onchange();
  showUsb([{id: 4, name: 'Serial', type: 'USB serial', allowed: true}]);
  assert.equal(element('board').value, wio);
  assert.deepEqual(requests.at(-1).slice(1), [wio, 'repeater', 'official']);
  element('repository').value = 'solo';
  element('repository').onchange();
  assert.deepEqual(requests.at(-1).slice(1), [wio, 'solo', 'solo']);
  assert.equal(element('fetch').disabled, true);
  assert.equal(element('openOfficial').hidden, true);

  showUsb([{id: 4, name: 'Serial', type: 'USB serial', allowed: true, board: m5}]);
  assert.equal(element('board').value, wio);
  assert.equal(element('board').options.length, 3);
  element('repository').value = 'official';
  element('repository').onchange();
  assert.equal(element('openOfficial').hidden, false);

  element('board').value = '';
  element('board').onchange();
  assert.equal(element('board').value, m5);
  assert.deepEqual(requests.at(-1).slice(1), [m5, 'companionBle', 'official']);
});

test('OTAFix offers downloads for a matching stock bootloader and skips a verified fix', () => {
  const {element, showUsb, showBoards} = load();
  const wio = 'Seeed Studio Wio Tracker L1 Pro';
  const fix = 'wio_tracker_l1_bootloader-0.9.2-OTAFIX2.2.uf2';
  const radio = {id: 4, name: 'UF2 disk', storage: true, type: 'USB storage'};
  const info = {id: 'TRACKER L1', boards: [wio], fixes: [fix]};
  showBoards([{name: wio, type: 'nrf52', otafix: [fix], roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = wio;
  element('board').onchange();
  showUsb([{...radio, bootloader: {...info, version: '0.9.2', status: 'absent'}}]);
  assert.match(element('otaStatus').textContent, /missing/);
  assert.equal(element('otaDownloads').options.length, 1);
  showUsb([{...radio, bootloader: {...info, version: '0.9.2-OTAFIX2.2', status: 'installed'}}]);
  assert.match(element('otaStatus').textContent, /already installed/);
  assert.equal(element('otaDownloads').options.length, 0);
  showUsb([]);
  assert.match(element('otaStatus').textContent, /Status unknown/);
});

test('SaveMeshcore shows all published assets and downloads the exact file within a release', () => {
  const {element, events, requests, showBoards, showVersions} = load();
  const board = 'Heltec v3';
  showBoards([{name: board, roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = board;
  element('board').onchange();
  element('repository').value = 'save';
  element('repository').onchange();
  element('allSaveAssets').onclick();
  const [request, ...selection] = requests.at(-1);
  assert.deepEqual(selection, ['', 'repeater', 'save']);
  const tag = 'repeater-v1.18.0-savemesh.1';
  const name = 'Heltec_v3_repeater-v1.18.0-savemesh.1-a4cdb56-merged.bin';
  showVersions(request, [
    {tag, name: 'SHA256SUMS.txt', types: ['checksums']},
    {tag, name, types: ['merged'], exportOnly: true},
  ]);
  assert.match(element('version').options[1].textContent, /merged BIN/);
  element('version').value = '1';
  element('fetch').onclick();
  assert.deepEqual(events.at(-1), ['', 'repeater', 'save', tag, name]);
});

test('RAK 1W shows its OTAFix recommendation before a device is attached', () => {
  const {element, showBoards} = load();
  const board = 'RAK WisMesh 1W Booster (3401 + 13302)';
  const fix = 'wiscore_rak3401_board_bootloader-0.9.2-OTAFIX2.3.uf2';
  showBoards([{name: board, type: 'nrf52', otafix: [fix], roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = board;
  element('board').onchange();
  assert.match(element('otaStatus').textContent, /recommends OTAFix/);
  assert.match(element('otaStatus').textContent, /status unknown/);
  assert.equal(element('otaDownloads').options.length, 1);
});

test('settings expands repositories independently and shows offline files with a save action', () => {
  const {element, events, requests, showRepositories, showRepositoryAssets, showRepositoryDownload} = load();
  const url = 'https://github.com/example/firmware/releases';
  const second = 'https://github.com/example/bootloaders/releases';
  showRepositories([{url, builtIn: false}, {url: second, bootloader: true, builtIn: false}]);
  const [firmware, bootloader] = element('repositoryList').options;
  assert.equal(requests.length, 0);
  for (const panel of [firmware, bootloader]) {
    panel.open = true;
    panel.ontoggle();
  }
  assert.deepEqual(requests.map(args => args.slice(1)), [[url, false], [second, false]]);
  const digest = 'sha256:' + 'a'.repeat(64);
  showRepositoryAssets(requests[1][0], [
    {name: 'saved.uf2', id: '3', tag: 'v1', digest, size: 512, downloaded: true},
  ]);
  showRepositoryAssets(requests[0][0], [
    {name: 'available.uf2', id: '1', tag: 'v1', digest, size: 512},
    {name: 'older.uf2', id: '2', tag: 'v0', digest: ''},
  ]);
  const releaseGroups = panel => panel.options[3].options;
  const files = group => group.options[0].options[1].options;
  const [current, previous] = releaseGroups(firmware);
  assert.equal(current.options[0].options[0].options[0].textContent, 'v1');
  assert.equal(previous.options[0].options[0].options[0].textContent, 'v0');
  assert.equal(files(previous)[0].options[2].options[0].textContent, 'Open release');
  const available = files(current)[0];
  assert.equal(available.options[2].textContent, 'Available to download');
  current.options[0].open = true;
  current.options[0].ontoggle();
  available.options[3].options[0].onclick();
  showRepositoryDownload(events.at(-1)[0]);
  assert.equal(files(releaseGroups(firmware)[0])[0].options[2].textContent, 'Downloaded');
  assert.equal(releaseGroups(firmware)[0].options[0].open, true);
  const cached = files(releaseGroups(bootloader)[0])[0];
  assert.equal(cached.options[2].textContent, 'Downloaded');
  cached.options[3].options[0].onclick();
  assert.deepEqual(events.at(-1), ['saved.uf2', digest.slice(7), 512]);
});

test('local UF2 export is bound to its selected board and generic probing stays available', () => {
  const {element, events, showBoards, showUsb, showLocal} = load();
  const wio = 'Seeed Studio Wio Tracker L1 Pro';
  showBoards([{name: wio, type: 'nrf52', roles: []}]);
  showUsb([{id: 14, name: 'USB', type: 'USB serial'}]);
  assert.equal(element('connect').disabled, false);
  assert.equal(element('probe').hidden, true);
  element('board').value = wio;
  element('board').onchange();
  element('pickLocal').onclick();
  assert.deepEqual(events.at(-1), ['local', wio]);
  showLocal({name: 'local.uf2', route: 'uf2', board: wio, size: 512, sha256: 'fixture'});
  assert.equal(element('saveLocal').hidden, false);
  assert.equal(element('flash').disabled, true);
  element('board').value = '';
  element('board').onchange();
  assert.equal(element('saveLocal').hidden, true);
});

test('library is a separate page and renders matching builds as actionable results', () => {
  const {element, events, requests, showBoards, showVersions} = load();
  const board = 'LilyGo T-Echo Lite';
  showBoards([{name: board, roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = board;
  element('board').onchange();
  const tag = 'repeater-v1.18.0';
  const name = 'LilyGo_T-Echo-Lite_repeater-v1.18.0-81dbae5.uf2';
  showVersions(requests.at(-1)[0], [{tag, name, version: 'v1.18.0', types: ['uf2']}]);
  element('showLibrary').onclick();
  assert.equal(element('firmwarePage').hidden, true);
  assert.equal(element('libraryPage').hidden, false);
  assert.equal(element('pageTitle').textContent, 'Firmware Library');
  const row = element('libraryResults').options[0];
  row.options[1].onclick();
  assert.deepEqual(events.at(-1), [board, 'repeater', 'official', tag, name]);
});
