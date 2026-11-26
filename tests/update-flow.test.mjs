import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const script = ['configurator.js', 'app.js'].map(file =>
  fs.readFileSync(new URL('../assets/' + file, import.meta.url), 'utf8')).join('\n');
const image = {
  size: 3, partitionLength: 3072, partitionMd5: 'layout',
  otaSeedMd5: 'seed', otaEmptyMd5: 'blank', md5: 'verified',
};

function load({layout = 'layout', ota = 'seed', digest = 'verified', encryption = 0} = {}) {
  const events = [];
  const traces = [];
  const requests = [];
  const nodes = new Map();
  function node(tag = '') {
    return {
      tagName: tag.toUpperCase(),
      value: '', options: [], attributes: new Map(),
      replaceChildren() { this.options = []; this.value = ''; },
      append(child) {
        if (this.options.length === 0) this.value = child.value;
        this.options.push(child);
      },
      setAttribute(key, value) { this.attributes.set(key, value); },
      removeAttribute(key) { this.attributes.delete(key); },
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
    ReadableStream, WritableStream, TextEncoder,
    esptool: {Transport: class {}, ESPLoader: Loader},
    Phone: {
      ready() {}, logTail: () => '', diagnostic: (stage, message) => traces.push({stage, message}), close() {},
      connect: () => '', readData: () => '', portError: () => '',
      connectUsb: id => events.push(['inspect', id]),
      readRepeater: id => events.push(['readRepeater', id]),
      saveRepeater: (id, settings) => events.push(['saveRepeater', id, JSON.parse(settings)]),
      flash: selection => events.push(['flash', JSON.parse(selection)]),
      installDfu: selection => events.push(['installDfu', JSON.parse(selection)]),
      probeLayout: id => events.push(['probeLayout', id]),
      versions: (...args) => requests.push(args),
      download: (...args) => events.push(args),
      repositoryAssets: (...args) => requests.push(args),
      downloadRepositoryAsset: (...args) => events.push(args),
      saveRepositoryAsset: (...args) => events.push(args),
      pickLibraryFirmware: () => events.push(['pickLibraryFirmware']),
      exportCachedFirmware: (...args) => events.push(['exportCached', ...args]),
      removeRepository: url => events.push(['removeRepository', url]),
      otaFix: (...args) => events.push(['otaFix', ...args]),
      saveLocal: (...args) => events.push(['saveLocal', ...args]),
      pickLocal: board => events.push(['local', board]),
      image: () => 'AQID', finished: (success, detail) => events.push({success, detail}),
    }
  });
  vm.runInContext(script, environment);
  environment.window.onRepositories([
    {url: 'https://github.com/meshcore-dev/MeshCore/releases', source: 'official'},
    {url: 'https://github.com/aigarslv/SaveMeshcore/releases', source: 'save'},
    {url: 'https://github.com/MarekZegare4/MeshCore-Solo/releases', source: 'solo'}
  ]);
  return {events, traces, element, requests, back: environment.window.goBack,
    showUsb: devices => environment.window.onUsb(devices, ''),
    showDfuTransfer: (...args) => environment.window.onDfuTransfer(...args),
    showRuntimeVerification: environment.window.onRuntimeVerification,
    showFlashRecovery: environment.window.onFlashRecovery,
    showRepeater: (...args) => environment.window.onRepeater(...args),
    showPresets: presets => environment.window.onRadioPresets(presets),
    showBoards: boards => environment.window.onBoards(boards),
    showDownload: image => environment.window.onDownload(image, null),
    showCachedExport: image => environment.window.onCachedExport(image),
    flashDownload: environment.window.onFlashDownload,
    flashPrepared: environment.window.onFlashPrepared,
    flashFinished: environment.window.onComplete,
    showVersions: (request, versions) => environment.window.onVersions(request, versions, null),
    showRepositories: links => environment.window.onRepositories(links),
    showRepositoryMaturity: environment.window.onRepositoryMaturity,
    showRepositoryAssets: (request, files) => environment.window.onRepositoryAssets(request, {files}, null),
    showInventory: files => environment.window.onRepositoryInventory(files),
    showCuratedInventory: environment.window.onCuratedInventory,
    onReading: environment.window.onRepeaterReading, onSaving: environment.window.onRepeaterSaving,
    showRepositoryDownload: request => environment.window.onRepositoryDownload(request, {}, null),
    showLocal: image => environment.window.onLocalFirmware(image, null),
    run: (probe, target = image) => environment.window.startFlash(7, target, probe)};
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
  const {events, traces, element, run} = load();
  await run(false, {...image, board: 'Selected board'});
  assert.equal(element('progressTarget').textContent, 'Selected board');
  assert.deepEqual(events.slice(2, -1), [['begin', 3, 0x10000], ['block', 16384, 0], 'finish', 'reset']);
  assert.equal(events.at(-1).success, true);
  const stages = traces.map(entry => entry.stage);
  for (const stage of ['flash.algorithm', 'boot', 'security', 'preflight', 'write', 'write.block', 'verify', 'reset', 'flash.outcome']) {
    assert.ok(stages.includes(stage), `missing trace stage ${stage}`);
  }
  assert.ok(traces.some(entry => entry.stage === 'write.block' && /Sending sequence=0/.test(entry.message)));
  assert.ok(traces.some(entry => entry.stage === 'write.block' && /Acknowledged sequence=0/.test(entry.message)));
  assert.ok(traces.some(entry => entry.stage === 'flash.outcome' && /success=true/.test(entry.message)));
});

test('digest mismatch reports failure without resetting the target', async () => {
  const {events, traces, run} = load({digest: 'wrong'});
  await run(false);
  assert.equal(events.includes('reset'), false);
  assert.equal(events.at(-1).success, false);
  assert.match(events.at(-1).detail, /digest mismatch/);
  assert.ok(traces.some(entry => entry.stage === 'flash.algorithm' && /Exception caught writing=true/.test(entry.message)));
  assert.ok(traces.some(entry => entry.stage === 'flash.outcome' && /success=false/.test(entry.message)));
});

test('USB scan lists generic devices and connects only to the selected device', () => {
  const {events, element, showUsb, showBoards, showDownload} = load();
  showBoards([{name: 'Elecrow ThinkNode M5', roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  const native = {id: 4, name: 'XIAO', type: 'Native USB', code: '303A:1001', allowed: true, flashSerial: false};
  const bridge = {id: 7, name: 'WCH', type: 'USB serial', code: '1A86:7522', allowed: true, flashSerial: true,
    board: 'Elecrow ThinkNode M5'};
  showUsb([native, bridge]);
  assert.equal(element('device').options.length, 2);
  assert.equal(element('flash').disabled, false);
  element('flash').onclick();
  assert.equal(element('board').attributes.has('data-flash-attention'), true);
  assert.deepEqual(events, []);
  element('connect').onclick();
  assert.deepEqual(events, [['inspect', 4]]);

  element('device').value = '7';
  element('device').onchange();
  assert.equal(element('flashError').hidden, false);
  assert.equal(element('probe').disabled, false);
  showDownload({tag: 'companion-v1.18.0', name: 'approved.bin', sha256: 'fixture', source: 'official', direct: true});
  assert.equal(element('flash').disabled, false);
  assert.equal(element('flashError').hidden, true);
  element('flash').onclick();
  assert.equal(events.at(-1)[0], 'flash');
  assert.equal(events.at(-1)[1].device, 7);
  assert.equal(events.at(-1)[1].name, 'approved.bin');
  showUsb([native]);
  assert.equal(element('flash').disabled, true);
  showUsb([]);
  assert.equal(element('connect').disabled, true);
});

test('a recognized MeshCore board loads official versions and enables eligible downloads', () => {
  const {element, requests, showUsb, showBoards, showVersions} = load();
  const name = 'Elecrow ThinkNode M5';
  showBoards([{name, roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 7, name, allowed: true, flashSerial: true, board: name}]);
  const [request, board, role] = requests.filter(([, selectedBoard, , selectedSource]) =>
    selectedBoard === name && selectedSource === 'official').at(-1);
  assert.equal(board, name);
  assert.equal(role, 'companionBle');
  showVersions(request, [{version: 'v1.18.0', name: 'thinknode-m5-companion-v1.18.0.bin',
    types: ['flash-update'], appTag: 'companion-v1.18.0'}]);
  assert.equal(element('version').options[0].textContent,
    'thinknode-m5-companion-v1.18.0.bin · App update');
  assert.equal(element('fetch').disabled, false);
});

test('USB discovery uses the reported MeshCore role for companions and repeaters', () => {
  const board = 'Elecrow ThinkNode M5';
  for (const [reportedRole, label] of [['companionUsb', 'USB companion'], ['repeater', 'Repeater']]) {
    const {element, requests, events, showUsb, showBoards, showVersions, showDownload} = load();
    showBoards([{name: board, roles: [
      {id: 'companionBle', title: 'Companion Bluetooth'},
      {id: 'companionUsb', title: 'Companion USB'},
      {id: 'repeater', title: 'Repeater'}
    ]}]);
    const radio = {id: 7, name: 'USB radio', board, flashSerial: true, allowed: true, code: '1A86:7522',
      reportedModel: board, reportedRole, reportedVersion: 'v1.18.0'};
    showUsb([radio]);
    assert.match(element('deviceInfo').textContent, new RegExp(label));
    assert.equal(element('role').value, reportedRole);
    const [request, selectedBoard, role, source] = requests.at(-1);
    assert.deepEqual([selectedBoard, role, source], [board, reportedRole, 'official']);
    const release = {tag: 'v1.18.0', name: 'approved.bin', version: 'v1.18.0', types: ['flash-update']};
    showVersions(request, [release]);
    showDownload({...release, board, role, source, sha256: 'fixture'});
    element('flash').onclick();
    assert.equal(events.at(-1)[0], 'flash');
    assert.equal(events.at(-1)[1].role, reportedRole);
  }
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

test('built-in firmware can be browsed without selecting a target board', () => {
  const {element, requests, showBoards, showVersions} = load();
  showBoards([{name: 'Heltec v3', roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = '';
  element('board').onchange();
  element('repository').value = 'official';
  element('repository').onchange();
  const [request, board, role, source] = requests.at(-1);
  assert.deepEqual([board, role, source], ['', '', 'official']);
  showVersions(request, [{tag: 'companion-v1.18.0', version: 'v1.18.0', name: 'other-board.uf2',
    types: ['uf2']}]);
  assert.equal(element('version').options.length, 1);
  assert.equal(element('fetch').disabled, false);
});

test('off-target built-in firmware stays selectable for Library download only', () => {
  const {element, events, requests, showBoards, showVersions} = load();
  const board = 'Heltec v3';
  const name = 'other-board-v1.18.0.uf2';
  showBoards([{name: board, roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = board;
  element('board').onchange();
  const [request] = requests.at(-1);
  showVersions(request, [{tag: 'companion-v1.18.0', version: 'v1.18.0', name, types: ['uf2'], exportOnly: true}]);
  assert.equal(element('fetch').disabled, false);
  element('fetch').onclick();
  assert.deepEqual(events.at(-1), [board, 'repeater', 'official', 'companion-v1.18.0', name]);
});

test('selecting a recognized device defaults to matching firmware without hiding other choices', () => {
  const {element, requests, showUsb, showBoards, showVersions} = load();
  const board = 'Elecrow ThinkNode M5';
  showBoards([{name: board, roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 7, name: board, allowed: true, flashSerial: true, board}]);
  const [request] = requests.filter(([, selectedBoard, , selectedSource]) =>
    selectedBoard === board && selectedSource === 'official').at(-1);
  showVersions(request, [
    {tag: 'other-v1.18.0', version: 'v1.18.0', name: 'other-board-v1.18.0.uf2',
      types: ['uf2'], exportOnly: true},
    {tag: 'companion-v1.18.0', version: 'v1.18.0', name: 'thinknode-m5-companion-v1.18.0.bin',
      types: ['flash-update'], exportOnly: false}
  ]);

  assert.equal(element('version').options.length, 2);
  assert.equal(element('version').value, '1');
  element('version').value = '0';
  assert.equal(element('version').value, '0');
  assert.equal(element('version').options.length, 2);
});

test('M5 defaults to the app update over a matching merged BIN', () => {
  const {element, requests, events, showUsb, showBoards, showVersions} = load();
  const board = 'Elecrow ThinkNode M5';
  const tag = 'repeater-v1.18.0';
  const app = 'ThinkNode_M5_repeater-v1.18.0-abc1234.bin';
  const merged = app.replace('.bin', '-merged.bin');
  showBoards([{name: board, type: 'esp32', roles: [{id: 'repeater', title: 'Repeater',
    release: {files: {'flash-wipe': 'ThinkNode_M5_repeater.*-merged[.]bin'}}}]}]);
  showUsb([{id: 7, name: board, allowed: true, flashSerial: true, board}]);
  const [request] = requests.at(-1);
  showVersions(request, [
    {tag, name: merged, types: ['bin'], exportOnly: true},
    {tag, name: app, types: ['flash-update'], appTag: tag, exportOnly: false}
  ]);
  assert.equal(element('version').value, '1');
  assert.match(element('version').options[0].textContent, /Merged BIN \(full install\)/);
  element('flash').onclick();
  assert.equal(events.at(-1)[1].name, app);
});

test('Flash sends an unsupported UF2 selection to native preparation', () => {
  const {element, events, requests, showUsb, showBoards, showVersions} = load();
  const board = 'LilyGo T-Echo Lite';
  showBoards([{name: board, type: 'nrf52', roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 7, name: board, board, allowed: true, flashSerial: false}]);
  const [request] = requests.filter(([, selectedBoard, , selectedSource]) =>
    selectedBoard === board && selectedSource === 'official').at(-1);
  showVersions(request, [{tag: 'companion-v1.18.0', version: 'v1.18.0',
    name: 'lilygo-t-echo-lite-companion-v1.18.0.uf2', types: ['uf2'], exportOnly: false}]);

  assert.equal(element('version').options.length, 1);
  element('flash').onclick();

  assert.equal(events.at(-1)[0], 'flash');
  assert.equal(element('flashError').hidden, true);
  assert.equal(element('pageTitle').textContent, 'Flash Progress');
});

test('configured GitHub firmware is handed to Flash Progress with its exact asset ID', () => {
  const {element, events, requests, showUsb, showBoards, showRepositories, showRepositoryAssets} = load();
  const board = 'Elecrow ThinkNode M5';
  const repository = 'https://github.com/example/firmware/releases';
  showBoards([{name: board, type: 'esp32', roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 7, name: board, board, allowed: true, flashSerial: true}]);
  showRepositories([{url: repository}]);
  element('repository').value = repository;
  element('repository').onchange();
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [{name: 'thinknode-m5-companion-v1.18.0.bin', id: '108',
    tag: 'companion-v1.18.0', sourceLabel: 'example/firmware', size: 512}]);

  assert.equal(element('version').options.length, 1);
  element('flash').onclick();

  assert.equal(events.at(-1)[0], 'flash');
  assert.equal(events.at(-1)[1].assetId, '108');
  assert.equal(element('flashError').hidden, true);
  assert.equal(element('pageTitle').textContent, 'Flash Progress');
});

test('a custom UF2 with a published digest starts the selected board’s DFU flow', () => {
  const {element, events, requests, showUsb, showBoards, showRepositories, showRepositoryAssets} = load();
  const board = 'LilyGo T-Echo Lite';
  const repository = 'https://github.com/example/firmware/releases';
  const sha = 'a'.repeat(64);
  showBoards([{name: board, type: 'nrf52', uf2Dfu: true, roles: []}]);
  showUsb([{id: 7, name: board, board, storage: true}]);
  showRepositories([{url: repository}]);
  element('board').value = board;
  element('board').onchange();
  element('repository').value = repository;
  element('repository').onchange();
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [{name: 'custom.uf2', id: '109', tag: 'v2',
    sourceLabel: 'example/firmware', size: 512, digest: 'sha256:' + sha}]);
  element('flash').onclick();

  const [method, selection] = events.at(-1);
  assert.equal(method, 'installDfu');
  assert.equal(selection.board, board);
  assert.equal(selection.assetId, '109');
  assert.equal(selection.sha, sha);
});

test('Flash uses release metadata for early firmware warnings and prefers stable files', () => {
  const {element, requests, showRepositories, showRepositoryAssets} = load();
  const url = 'https://github.com/example/firmware/releases';
  const file = (tag, prerelease = false) => ({tag, prerelease, id: tag, name: 'radio-' + tag + '.uf2', size: 512});
  const latestRequest = () => requests.filter(([, source]) => source === url).at(-1)[0];
  showRepositories([{url, builtIn: true}]);

  showRepositoryAssets(latestRequest(), []);
  assert.match(element('versionStatus').textContent, /No published release files/);
  assert.equal(element('releaseWarning').hidden, true);

  element('versions').onclick();
  const alpha = file('v1.0.0-alpha.1');
  showRepositoryAssets(latestRequest(), [alpha]);
  assert.match(element('versionStatus').textContent, /prerelease firmware only/);
  assert.match(element('version').options[0].textContent, /^Alpha ·/);
  assert.equal(element('releaseWarning').textContent, 'Alpha firmware. Use it for testing.');

  element('repository').onchange();
  showRepositoryAssets(latestRequest(), [alpha, file('preview', true), file('v1.0.0')]);
  assert.equal(element('version').value, '2');
  assert.equal(element('releaseWarning').hidden, true);
  assert.equal(element('versionStatus').textContent, '');
  element('version').value = '1';
  element('version').onchange();
  assert.match(element('releaseWarning').textContent, /^Prerelease firmware/);
});

test('An Alpha repository marks empty and unqualified releases while explicit release tags still win', () => {
  const {element, requests, showRepositories, showRepositoryMaturity, showRepositoryAssets} = load();
  const url = 'https://github.com/example/firmware/releases';
  const latestRequest = () => requests.filter(([, source]) => source === url).at(-1)[0];
  showRepositories([{url}]);
  showRepositoryAssets(latestRequest(), []);
  showRepositoryMaturity(url, true);

  assert.match(element('repository').options[0].textContent, /Alpha$/);
  assert.match(element('repositoryList').options[0].options[0].textContent, /Alpha$/);
  assert.match(element('releaseWarning').textContent, /Alpha repository/);
  assert.match(element('versionStatus').textContent, /No published release files/);

  element('versions').onclick();
  showRepositoryAssets(latestRequest(), [
    {tag: 'v0.1.0', name: 'radio.uf2', id: '1'},
    {tag: 'v0.1.0-rc1', name: 'radio-rc.uf2', id: '2'}
  ]);
  assert.match(element('version').options[0].textContent, /^Alpha ·/);
  assert.match(element('version').options[1].textContent, /^Release candidate ·/);
  assert.match(element('releaseWarning').textContent, /^Alpha firmware/);

  showRepositoryMaturity(url, false);
  assert.equal(element('version').value, '0');
  assert.equal(element('releaseWarning').hidden, true);
  assert.equal(element('repository').options[0].textContent, 'example/firmware');
});

test('Library warns when a repository only offers early firmware and clears the warning for mixed releases', () => {
  const {element, requests, showRepositories, showRepositoryAssets} = load();
  const url = 'https://github.com/example/firmware/releases';
  const latestRequest = () => requests.filter(([, source]) => source === url).at(-1)[0];
  const beta = {tag: 'v1.0.0-beta2', id: '101', name: 'radio-beta.uf2', size: 512,
    sourceLabel: 'example/firmware'};
  const stable = {tag: 'v1.0.0', id: '102', name: 'radio.uf2', size: 512,
    sourceLabel: 'example/firmware'};
  showRepositories([{url}]);
  element('showLibrary').onclick();
  showRepositoryAssets(latestRequest(), [beta]);
  assert.match(element('libraryResults').options[0].options[0].textContent, /Prerelease firmware only/);
  element('refreshLibrary').onclick();
  showRepositoryAssets(latestRequest(), [beta, stable]);
  const repository = element('libraryResults').options[0];
  assert.equal(repository.options[0].textContent, 'example/firmware');
  const betaVersion = repository.options.find(row => row.options[0]?.textContent === beta.tag);
  assert.match(betaVersion.options[1].options[0].options[1].textContent, /^Beta ·/);
});

test('configured repository defaults to catalogue-matched firmware but keeps off-target assets selectable', () => {
  const {element, requests, showBoards, showRepositories, showRepositoryAssets} = load();
  const board = 'Demo Board';
  const repository = 'https://github.com/example/firmware/releases';
  showBoards([{name: board, type: 'nrf52', roles: [{id: 'repeater', title: 'Repeater',
    release: {files: {download: 'demo_board_repeater.*\\.uf2'}}}]}]);
  element('board').value = board;
  element('board').onchange();
  showRepositories([{url: repository}]);
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [
    {name: 'other_board_repeater-v1.uf2', id: '1', tag: 'v1', sourceLabel: 'example/firmware', size: 512},
    {name: 'demo_board_repeater-v1.uf2', id: '2', tag: 'v1', sourceLabel: 'example/firmware', size: 512}
  ]);

  assert.equal(element('version').options.length, 2);
  assert.equal(element('version').value, '1');
  element('version').value = '0';
  assert.equal(element('version').value, '0');
  assert.equal(element('version').options.length, 2);
});

test('Flash opens progress for nRF UF2 and reports the terminal transfer outcome', () => {
  const {element, events, requests, showUsb, showBoards, showVersions, showDfuTransfer} = load();
  const board = 'LilyGo T-Echo Lite';
  showBoards([{name: board, type: 'nrf52', uf2Dfu: true,
    dfuEntry: 'Enter UF2 mode.', roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 42, name: 'nRF radio', code: '239A:8029', serial: true}]);
  element('board').value = board;
  element('board').onchange();
  const [request] = requests.filter(([, selectedBoard, , selectedSource]) =>
    selectedBoard === board && selectedSource === 'official').at(-1);
  const name = 'lilygo-t-echo-lite-companion-v1.18.0.uf2';
  showVersions(request, [{tag: 'companion-v1.18.0', version: 'v1.18.0', name,
    types: ['uf2'], exportOnly: false}]);

  element('flash').onclick();
  assert.equal(element('pageTitle').textContent, 'Flash Progress');
  assert.equal(events.some(event => Array.isArray(event) && event[0] === 'flash'), false);
  assert.deepEqual(events.at(-1), ['installDfu', {
    device: 42, board, source: 'official', role: 'companionBle',
    tag: 'companion-v1.18.0', name, sha: '', assetId: ''
  }]);
  assert.equal(element('flash').disabled, true);
  assert.equal(element('stageDownload').hidden, true);
  showDfuTransfer('Downloading and verifying the board-compatible UF2…');
  assert.equal(element('stageDownload').hidden, false);
  const progress = 'Firmware downloaded and verified. Checking for the radio and requesting DFU mode over USB…';
  showDfuTransfer(progress, false);
  assert.equal(element('flash').disabled, true);
  assert.equal(element('progressStatus').textContent, progress);
  showUsb([{id: 42, name: 'UF2 Bootloader', code: '1209:5BF0', storage: true}]);
  const detected = 'Verifying the radio bootloader and writing UF2 directly over USB…';
  showDfuTransfer(detected, false);
  assert.equal(element('flash').disabled, true);
  assert.equal(element('progressStatus').textContent, detected);
  showDfuTransfer('Writing verified UF2 directly over USB… 50%');
  assert.equal(element('progressPercent').textContent, '50%');
  const complete = 'UF2 transfer completed directly over USB.';
  showDfuTransfer(complete, true, true);
  assert.equal(element('flash').disabled, false);
  assert.equal(element('progressStatus').textContent, complete);
  assert.equal(element('progressOutcome').textContent, 'UF2 sent');
  assert.equal(element('progressCard').attributes.get('data-outcome'), 'success');
  const failed = 'The radio bootloader drive disappeared before installation.';
  showDfuTransfer(failed, true, false);
  assert.equal(element('progressOutcome').textContent, 'Flash failed');
  assert.equal(element('progressCard').attributes.get('data-outcome'), 'failure');
  assert.equal(element('progressStatus').textContent, failed);
});

test('DFU delivery and startup verification have separate outcomes', () => {
  const {element, showDfuTransfer, showRuntimeVerification} = load();
  showDfuTransfer('UF2 sent.', true, true, true);
  assert.equal(element('flash').disabled, true);
  assert.match(element('runtimeStatus').textContent, /waiting for the radio/);
  showRuntimeVerification('mismatch', 'Unexpected firmware version.');
  assert.equal(element('flash').disabled, false);
  assert.match(element('runtimeStatus').textContent, /^Startup mismatch/);
});

test('Recovery distinguishes interruption before writing, during writing and after delivery', () => {
  const {element, showFlashRecovery} = load();
  const record = {board: 'T-Echo Lite', name: 'radio.uf2', sha: 'abc', uf2: true};
  showFlashRecovery({...record, stage: 'STAGED'});
  assert.match(element('recoveryMessage').textContent, /Writing had not started/);
  showFlashRecovery({...record, stage: 'WRITING'});
  assert.match(element('recoveryMessage').textContent, /Enter the radio’s UF2 bootloader again/);
  assert.match(element('recoveryImage').textContent, /SHA-256 abc/);
  showFlashRecovery({...record, stage: 'DELIVERED'});
  assert.match(element('recoveryMessage').textContent, /Firmware was sent/);
  showFlashRecovery(null);
  assert.equal(element('recoveryCard').hidden, true);
});

test('Flash skips DFU-entry guidance and folder-picker handoff when a UF2 drive is already connected', () => {
  const {element, events, requests, showUsb, showBoards, showVersions, showDfuTransfer} = load();
  const board = 'LilyGo T-Echo Lite';
  showBoards([{name: board, type: 'nrf52', uf2Dfu: true,
    roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 42, name: 'UF2 Bootloader', code: '1209:5BF0', storage: true}]);
  element('board').value = board;
  element('board').onchange();
  const [request] = requests.filter(([, selectedBoard, , selectedSource]) =>
    selectedBoard === board && selectedSource === 'official').at(-1);
  const name = 'lilygo-t-echo-lite-companion-v1.18.0.uf2';
  showVersions(request, [{tag: 'companion-v1.18.0', version: 'v1.18.0', name,
    types: ['uf2'], exportOnly: false}]);

  element('flash').onclick();
  assert.equal(element('pageTitle').textContent, 'Flash Progress');
  assert.equal(events.at(-1)[0], 'installDfu');
  const status = 'Verifying the radio bootloader and writing UF2 directly over USB…';
  showDfuTransfer(status, false);
  assert.match(element('progressStatus').textContent, /directly over USB/);
  assert.doesNotMatch(element('progressStatus').textContent, /Android Files|Use this folder/i);
});

test('off-target UF2 cannot start the nRF DFU flow', () => {
  const {element, requests, showUsb, showBoards, showVersions} = load();
  const board = 'LilyGo T-Echo Lite';
  showBoards([{name: board, type: 'nrf52', uf2Dfu: true,
    roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 42, name: 'UF2 Bootloader', code: '1209:5BF0', storage: true,
    bootloader: {boards: [], version: '0.6.1', id: 'UNKNOWN', status: 'unknown'}}]);
  element('board').value = board;
  element('board').onchange();
  const [request] = requests.filter(([, selectedBoard, , selectedSource]) =>
    selectedBoard === board && selectedSource === 'official').at(-1);
  showVersions(request, [{tag: 'other-v1.18.0', version: 'v1.18.0',
    name: 'other-board-companion-v1.18.0.uf2', types: ['uf2'], exportOnly: true}]);

  element('flash').onclick();
  assert.equal(element('flashError').hidden, true);
  assert.equal(element('pageTitle').textContent, 'Flash Progress');
});

test('Library browses configured firmware repositories before download and offers local import', () => {
  const {element, events, requests, showRepositories, showRepositoryAssets} = load();
  const first = 'https://github.com/example/firmware/releases';
  const second = 'https://github.com/example/other-firmware/releases';
  showRepositories([{url: first}, {url: second}]);
  element('showLibrary').onclick();
  const queries = [...new Map(requests.filter(([, url]) => url === first || url === second)
    .map(request => [request[1], request])).values()];
  assert.deepEqual(queries.map(([, url]) => url), [first, second]);
  const digest = 'sha256:' + 'b'.repeat(64);
  for (const [request, url] of queries) {
    showRepositoryAssets(request, [{name: url === first ? 'firmware.uf2' : 'device.bin', id: url,
      sourceLabel: url === first ? 'example/firmware' : 'example/other-firmware',
      tag: 'v1', digest, size: 512}]);
  }
  assert.equal(element('libraryResults').options.length, 2);
  const firstFile = element('libraryResults').options[0].options[1].options[1].options[0];
  assert.equal(firstFile.options[2].options[0].textContent, 'Download');
  firstFile.options[2].options[0].onclick();
  assert.deepEqual(events.at(-1).slice(1), [first, 'v1', first, digest]);
  element('addLibraryFirmware').onclick();
  assert.deepEqual(events.at(-1), ['pickLibraryFirmware']);
});

test('Library lists remote releases and identifies downloads without a published digest', () => {
  const {element, events, requests, showBoards, showRepositories, showRepositoryAssets} = load();
  const first = 'https://github.com/example/firmware/releases';
  const empty = 'https://github.com/example/empty/releases';
  showBoards([{name: 'Demo Board', type: 'nrf52', roles: [{id: 'repeater', title: 'Repeater',
    release: {files: {download: 'demo_board_repeater.*\\.uf2'}}}]}]);
  showRepositories([{url: first}, {url: empty}]);
  element('showLibrary').onclick();
  const queries = [...new Map(requests.filter(([, url]) => url === first || url === empty)
    .map(request => [request[1], request])).values()];
  const firstRequest = queries.find(([, url]) => url === first)[0];
  const emptyRequest = queries.find(([, url]) => url === empty)[0];
  showRepositoryAssets(firstRequest, [{name: 'demo_board_repeater-v1.uf2', id: '42', tag: 'v1.2.0',
    releaseName: 'Demo release', prerelease: true, sourceLabel: 'example/firmware', size: 512, digest: ''}]);
  showRepositoryAssets(emptyRequest, []);

  const groups = element('libraryResults').options;
  assert.equal(groups.length, 2);
  assert.match(groups[0].options[0].textContent, /^example\/firmware/);
  assert.match(groups[0].options[1].options[0].textContent, /v1\.2\.0/);
  const row = groups[0].options[1].options[1].options[0];
  assert.equal(row.options[0].textContent, 'demo_board_repeater-v1.uf2');
  assert.equal(row.options[1].textContent, 'Prerelease · No published checksum');
  assert.equal(row.options[2].options[0].textContent, 'Download');
  assert.equal(row.options[2].options[0].disabled, false);
  row.options[2].options[0].onclick();
  assert.deepEqual(events.at(-1).slice(1), [first, 'v1.2.0', '42', '']);
  assert.match(groups[1].options[0].textContent, /^example\/empty/);
  assert.match(groups[1].options[1].textContent, /No release assets/);
});

test('Library lists each file once and can search by a supported board', () => {
  const {element, requests, showBoards, showRepositories, showRepositoryAssets} = load();
  const repository = 'https://github.com/example/firmware/releases';
  showBoards([
    {name: 'Demo Board', type: 'nrf52', roles: [
      {id: 'repeater', title: 'Repeater', release: {type: 'repeater', files: {download: 'radio_repeater.*\\.uf2'}}},
      {id: 'client', title: 'Client', release: {type: 'companion', files: {download: 'radio_client.*\\.bin'}}}
    ]},
    {name: 'Second Board', type: 'esp32', roles: [
      {id: 'repeater', title: 'Repeater', release: {type: 'repeater', files: {download: 'radio_repeater.*\\.uf2'}}}
    ]}
  ]);
  showRepositories([{url: repository}]);
  element('showLibrary').onclick();
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [
    {name: 'radio_repeater-v3.uf2', id: '84', tag: 'v3', sourceLabel: 'example/firmware', size: 512, digest: ''},
    {name: 'radio_client-v3.bin', id: '85', tag: 'v3', sourceLabel: 'example/firmware', size: 512, digest: ''}
  ]);

  const [repositoryRow] = element('libraryResults').options;
  assert.equal(repositoryRow.tagName, 'DETAILS');
  assert.equal(repositoryRow.className, 'library-repository');
  assert.match(repositoryRow.options[0].textContent, /example\/firmware/);
  const [versionRow] = repositoryRow.options.slice(1);
  assert.equal(versionRow.tagName, 'DETAILS');
  assert.equal(versionRow.className, 'library-version');
  assert.match(versionRow.options[0].textContent, /v3/);
  assert.deepEqual(versionRow.options[1].options.map(row => row.options[0].textContent),
    ['radio_repeater-v3.uf2', 'radio_client-v3.bin']);
  element('librarySearch').value = 'Second Board';
  element('librarySearch').oninput();
  const matchingVersion = element('libraryResults').options[0].options[1];
  assert.equal(matchingVersion.open, true);
  assert.deepEqual(matchingVersion.options[1].options.map(row => row.options[0].textContent),
    ['radio_repeater-v3.uf2']);
});

test('Library groups repository assets by the version in the filename', () => {
  const {element, requests, showRepositories, showRepositoryAssets} = load();
  const repository = 'https://github.com/example/firmware/releases';
  showRepositories([{url: repository}]);
  element('showLibrary').onclick();
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [
    {name: 'mesh_repeater_v1.18.0.uf2', id: '201', tag: 'release-one', sourceLabel: 'example/firmware', size: 512},
    {name: 'mesh_room-server-v1.18.0.uf2', id: '202', tag: 'release-two', sourceLabel: 'example/firmware', size: 512},
    {name: 'mesh_room-server-v1.9.0.uf2', id: '203', tag: 'legacy-tag', sourceLabel: 'example/firmware', size: 512}
  ]);

  const [, matchingVersion, legacyVersion] = element('libraryResults').options[0].options;
  assert.match(matchingVersion.options[0].textContent, /v1\.18\.0/);
  assert.deepEqual(matchingVersion.options[1].options.map(row => row.options[0].textContent), [
    'mesh_repeater_v1.18.0.uf2', 'mesh_room-server-v1.18.0.uf2'
  ]);
  assert.equal(legacyVersion.options[0].textContent, 'v1.9.0');
});

test('Library classifies firmware by role keywords in filenames, even without a catalogue match', () => {
  const {element, requests, showBoards, showRepositories, showRepositoryAssets} = load();
  const repository = 'https://github.com/example/firmware/releases';
  showBoards([{name: 'Legacy Radio', type: 'esp32', roles: [{id: 'repeater', title: 'Repeater',
    release: {type: 'companion', files: {download: 'legacy_repeater.*\\.uf2'}}}]}]);
  showRepositories([{url: repository}]);
  element('showLibrary').onclick();
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [
    {name: 'Legacy_repeater-v1.uf2', id: '101', tag: 'v1', sourceLabel: 'example/firmware', size: 512},
    {name: 'Board_room_server-v1.bin', id: '102', tag: 'v1', sourceLabel: 'example/firmware', size: 512},
    {name: 'Board_companion_radio_ble-v1.bin', id: '103', tag: 'v1', sourceLabel: 'example/firmware', size: 512},
    {name: 'Board_radio_usb-v1.uf2', id: '104', tag: 'v1', sourceLabel: 'example/firmware', size: 512},
    {name: 'Board_kiss_radio_modem-v1.bin', id: '105', tag: 'v1', sourceLabel: 'example/firmware', size: 512},
    {name: 'Board_ripple_gui-v1.bin', id: '106', tag: 'v1', sourceLabel: 'example/firmware', size: 512},
    {name: 'Board_firmware-v1.bin', id: '107', tag: 'v1', sourceLabel: 'example/firmware', size: 512}
  ]);

  for (const [query, names] of [
    ['repeater', ['Legacy_repeater-v1.uf2']],
    ['client', ['Board_companion_radio_ble-v1.bin', 'Board_radio_usb-v1.uf2']],
    ['KISS Radio Modem', ['Board_kiss_radio_modem-v1.bin']],
    ['Ripple GUI', ['Board_ripple_gui-v1.bin']]
  ]) {
    element('librarySearch').value = query;
    element('librarySearch').oninput();
    const [, version] = element('libraryResults').options[0].options;
    assert.deepEqual(version.options[1].options.map(row => row.options[0].textContent), names);
  }
});

test('Library uses GitHub release metadata to classify assets whose filenames omit the firmware role', () => {
  const {element, requests, showRepositories, showRepositoryAssets} = load();
  const repository = 'https://github.com/example/firmware/releases';
  showRepositories([{url: repository}]);
  element('showLibrary').onclick();
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [
    {name: 'generic_board_v1.18.0.bin', id: '108', tag: 'companion-v1.18.0',
      releaseName: 'Companion Firmware v1.18.0', sourceLabel: 'example/firmware', size: 512},
    {name: 'generic_board_v1.18.0.uf2', id: '109', tag: 'room-server-v1.18.0',
      releaseName: 'Room Server Firmware v1.18.0', sourceLabel: 'example/firmware', size: 512}
  ]);

  element('librarySearch').value = 'Room Server';
  element('librarySearch').oninput();
  const [, version] = element('libraryResults').options[0].options;
  assert.deepEqual(version.options[1].options.map(row => row.options[0].textContent),
    ['generic_board_v1.18.0.uf2']);
});

test('Library lists every GitHub release asset and disables downloads that exceed the size limit', () => {
  const {element, events, requests, showRepositories, showRepositoryAssets} = load();
  const repository = 'https://github.com/example/firmware/releases';
  showRepositories([{url: repository}]);
  element('showLibrary').onclick();
  const [request] = requests.filter(([, url]) => url === repository).at(-1);
  showRepositoryAssets(request, [
    {name: 'firmware.elf', id: '90', tag: 'v4', sourceLabel: 'example/firmware', size: 5 * 1024 * 1024,
      digest: '', downloadable: false},
    {name: 'checksums.txt', id: '91', tag: 'v4', sourceLabel: 'example/firmware', size: 128,
      digest: '', downloadable: true}
  ]);

  const [repositoryRow] = element('libraryResults').options;
  const [versionRow] = repositoryRow.options.slice(1);
  const [largeAsset, smallAsset] = versionRow.options[1].options;
  assert.equal(largeAsset.options[0].textContent, 'firmware.elf');
  assert.match(largeAsset.options[1].textContent, /Download unavailable/);
  assert.equal(largeAsset.options[2].options[0].disabled, true);
  assert.equal(smallAsset.options[0].textContent, 'checksums.txt');
  assert.equal(smallAsset.options[2].options[0].disabled, false);
  smallAsset.options[2].options[0].onclick();
  assert.deepEqual(events.at(-1).slice(1), [repository, 'v4', '91', '']);
});

test('Library places explicitly imported files in a separate local category', () => {
  const {element, showInventory} = load();
  element('showLibrary').onclick();
  showInventory([{name: 'from-downloads.uf2', sourceLabel: 'Local files', local: true,
    sha256: 'c'.repeat(64), size: 512}]);
  const group = element('libraryResults').options.find(item => item.options[0].textContent.startsWith('Local files'));
  assert.match(group.options[0].textContent, /Local files/);
  assert.match(group.options[1].options[0].textContent, /Added locally/);
  assert.equal(group.options[1].options[1].options[0].options[0].textContent, 'from-downloads.uf2');
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
  assert.match(element('otaStatus').textContent, /stock bootloader/);
  assert.equal(element('otaPackage').options.length, 2);
  showUsb([{...radio, bootloader: {...info, version: '0.9.2-OTAFIX2.2', status: 'installed'}}]);
  assert.match(element('otaStatus').textContent, /already installed/);
  assert.equal(element('otaChoices').hidden, true);
  showUsb([]);
  assert.match(element('otaStatus').textContent, /status unknown/);
});

test('SaveMeshcore shows all published assets and downloads the exact file within a release', () => {
  const {element, events, requests, showBoards, showVersions} = load();
  const board = 'Heltec v3';
  showBoards([{name: board, roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = board;
  element('board').onchange();
  element('repository').value = 'save';
  element('repository').onchange();
  const [request, ...selection] = requests.at(-1);
  assert.deepEqual(selection, [board, 'repeater', 'save']);
  const tag = 'repeater-v1.18.0-savemesh.1';
  const name = 'Heltec_v3_repeater-v1.18.0-savemesh.1-a4cdb56-merged.bin';
  showVersions(request, [
    {tag, name: 'SHA256SUMS.txt', types: ['checksums']},
    {tag, name, types: ['merged'], exportOnly: true},
  ]);
  assert.match(element('version').options[1].textContent, /Merged BIN/);
  element('version').value = '1';
  element('fetch').onclick();
  assert.deepEqual(events.at(-1), [board, 'repeater', 'save', tag, name]);
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
  assert.equal(element('otaPackage').options.length, 2);
});

test('configured repositories appear on Flash while Settings manages the links', () => {
  const {element, events, requests, showRepositories, showRepositoryAssets, showRepositoryDownload, showInventory} = load();
  const url = 'https://github.com/example/firmware/releases';
  const secondFirmware = 'https://github.com/example/another-firmware/releases';
  const second = 'https://github.com/example/bootloaders/releases';
  showRepositories([{url, builtIn: false}, {url: secondFirmware, builtIn: false},
    {url: second, bootloader: true, builtIn: false}]);
  assert.deepEqual(element('repository').options.map(option => option.value), [url, secondFirmware, 'local']);
  assert.equal(element('repositoryList').options.length, 3);
  element('repository').value = url;
  element('repository').onchange();
  const [request, selected] = requests.at(-1);
  assert.equal(selected, url);
  const digest = 'sha256:' + 'a'.repeat(64);
  showRepositoryAssets(request, [{name: 'available.uf2', id: '1', tag: 'v1', digest, size: 512}]);
  assert.equal(element('fetch').disabled, false);
  element('fetch').onclick();
  const [download, ...args] = events.at(-1);
  assert.deepEqual(args, [url, 'v1', '1', digest]);
  showInventory([{name: 'available.uf2', sourceLabel: 'example/firmware', tag: 'v1',
    sha256: digest.slice(7), size: 512}]);
  showRepositoryDownload(download);
  const group = element('libraryResults').options[0];
  assert.match(group.options[0].textContent, /^example\/firmware/);
  assert.match(group.options[1].options[0].textContent, /v1/);
  const row = group.options[1].options[1].options[0];
  assert.equal(row.options[0].textContent, 'available.uf2');
  row.options[2].options[0].onclick();
  assert.deepEqual(events.at(-1), ['available.uf2', digest.slice(7), 512]);
  element('repository').value = secondFirmware;
  element('repository').onchange();
  const [secondRequest, secondSelected] = requests.at(-1);
  assert.equal(secondSelected, secondFirmware);
  const secondDigest = 'sha256:' + 'b'.repeat(64);
  showRepositoryAssets(secondRequest, [{name: 'other-board.bin', id: '2', tag: 'v2', digest: secondDigest, size: 512}]);
  assert.equal(element('version').options.length, 1);
  assert.equal(element('fetch').disabled, false);
  element('repositoryList').options[0].options[2].onclick();
  assert.deepEqual(events.at(-1), ['removeRepository', url]);
});

test('Flash matches configured OTAFix packages to a board and marks selection separately from install', () => {
  const {element, events, requests, showBoards, showRepositories, showRepositoryAssets, showUsb} = load();
  const url = 'https://github.com/example/fixes/releases';
  const board = 'Seeed Studio Wio Tracker L1 Pro';
  const family = 'wio_tracker_l1_bootloader';
  const official = family + '-0.9.2-OTAFIX2.2.uf2';
  const name = family + '-0.9.2-OTAFIX2.4.uf2';
  const digest = 'sha256:' + 'a'.repeat(64);
  showBoards([{name: board, type: 'nrf52', otafix: [official], roles: [{id: 'repeater', title: 'Repeater'}]}]);
  showRepositories([{url, bootloader: true}]);
  element('showFirmware').onclick();
  element('board').value = board;
  element('board').onchange();
  const [request, source] = requests.at(-1);
  assert.equal(source, url);
  showRepositoryAssets(request, [
    {name, tag: 'v1', id: '42', digest, size: 512},
    {name: 'lilygo_techo_bootloader-0.9.2-OTAFIX2.4.uf2', tag: 'v1', id: '43', digest, size: 512}
  ]);
  assert.equal(element('deviceOtaBadge').textContent, 'OTAFix available');
  assert.equal(element('otaPackage').options.length, 3);
  element('otaPackage').value = url + ':42';
  element('otaPackage').onchange();
  assert.equal(element('deviceOtaBadge').textContent, 'OTAFix selected');
  element('downloadOta').onclick();
  assert.deepEqual(events.at(-1).slice(1), [url, 'v1', '42', digest]);
  showUsb([{id: 3, storage: true, bootloader: {boards: [board], family, fixes: [official],
    status: 'installed', version: '0.9.2-OTAFIX2.4'}}]);
  assert.equal(element('deviceOtaBadge').textContent, 'OTAFix installed');
  assert.equal(element('otaChoices').hidden, true);
});

test('local UF2 can be flashed for its selected board and generic probing stays available', () => {
  const {element, events, showBoards, showUsb, showLocal} = load();
  const wio = 'Seeed Studio Wio Tracker L1 Pro';
  showBoards([{name: wio, type: 'nrf52', uf2Dfu: true, roles: []}]);
  showUsb([{id: 14, name: 'USB', type: 'USB serial'}]);
  assert.equal(element('connect').disabled, false);
  assert.equal(element('probe').hidden, true);
  element('board').value = wio;
  element('board').onchange();
  element('pickLocal').onclick();
  assert.deepEqual(events.at(-1), ['local', wio]);
  showLocal({name: 'local.uf2', route: 'uf2', board: wio, size: 512, sha256: 'fixture'});
  assert.equal(element('saveLocal').hidden, false);
  element('flash').onclick();
  assert.equal(events.at(-1)[0], 'installDfu');
  assert.equal(events.at(-1)[1].board, wio);
  assert.equal(events.at(-1)[1].source, 'local');
  assert.equal(events.at(-1)[1].sha, 'fixture');
  assert.equal(element('pageTitle').textContent, 'Flash Progress');
});

test('Flash owns release selection and Library shows the download and the saved file', () => {
  const {element, events, requests, showBoards, showVersions, showCachedExport} = load();
  const board = 'LilyGo T-Echo Lite';
  showBoards([{name: board, roles: [{id: 'repeater', title: 'Repeater'}]}]);
  element('board').value = board;
  element('board').onchange();
  const tag = 'repeater-v1.18.0';
  const name = 'LilyGo_T-Echo-Lite_repeater-v1.18.0-81dbae5.uf2';
  showVersions(requests.at(-1)[0], [{tag, name, version: 'v1.18.0', types: ['uf2']}]);
  element('showFirmware').onclick();
  element('fetch').onclick();
  assert.deepEqual(events.at(-1), [board, 'repeater', 'official', tag, name]);
  element('showLibrary').onclick();
  assert.equal(element('firmwarePage').hidden, true);
  assert.equal(element('libraryPage').hidden, false);
  assert.equal(element('pageTitle').textContent, 'Firmware Library');
  assert.equal(element('libraryResults').options.length, 3);
  assert.equal(element('titleMotion').attributes.has('data-working'), true);
  assert.match(element('libraryStatus').textContent, /Downloading and verifying/);
  showCachedExport({name, board, role: 'repeater', source: 'official', tag, sha256: 'verified'});
  assert.equal(element('titleMotion').attributes.has('data-working'), false);
  const group = element('libraryResults').options[0];
  assert.match(group.options[0].textContent, /MeshCore/);
  assert.match(group.options[1].options[0].textContent, /^v1\.18\.0$/);
  assert.equal(group.options[1].options[1].options[0].options[0].textContent, name);
});

test('Curated inventory keeps older offline releases selectable by their saved digest', () => {
  const {element, requests, events, showBoards, showUsb, showVersions, showCuratedInventory} = load();
  const board = 'Elecrow ThinkNode M5';
  showBoards([{name: board, roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 7, name: board, board, flashSerial: true}]);
  const [request] = requests.at(-1);
  showVersions(request, []);
  const details = {board, source: 'official', role: 'companionBle', direct: true, exportOnly: false};
  showCuratedInventory([
    {...details, name: 'new.bin', tag: 'v2', sha256: 'new-sha'},
    {...details, name: 'old.bin', tag: 'v1', sha256: 'old-sha'}
  ]);
  assert.equal(element('version').options.length, 2);
  element('version').value = '1';
  element('flash').onclick();
  assert.equal(events.at(-1)[1].name, 'old.bin');
  assert.equal(events.at(-1)[1].sha, 'old-sha');
});

test('Library groups files by repository and version and uses compact file rows', () => {
  const {element, showDownload, showInventory, showLocal} = load();
  showDownload({name: 'one.bin', source: 'official', repository: 'meshcore-dev/MeshCore',
    tag: 'v2', sha256: 'fixture', size: 1234});
  showInventory([
    {name: 'older.uf2', sourceLabel: 'meshcore-dev/MeshCore', tag: 'v1'},
    {name: 'two.uf2', sourceLabel: 'MeshCore-dev/MeshCore', tag: 'v2'},
    {name: 'other.uf2', sourceLabel: 'example/other', tag: 'v2'}
  ]);
  showLocal({name: 'local.bin', sha256: 'local-digest', size: 512, route: 'inspect'});
  const list = element('libraryResults');
  assert.deepEqual(list.options.map(group => group.options[0].textContent.split(' · ')[0]),
    ['MeshCore', 'SaveMeshcore', 'MeshCore-Solo', 'example/other', 'Local files']);
  const [title, newest, older] = list.options[0].options;
  assert.match(title.textContent, /MeshCore/);
  assert.match(newest.options[0].textContent, /v2/);
  assert.match(older.options[0].textContent, /v1/);
  const sharedFiles = newest.options[1];
  assert.deepEqual(sharedFiles.options.map(row => row.options[0].textContent), ['one.bin', 'two.uf2']);
  assert.equal(sharedFiles.options[0].options.length, 3);
  assert.match(list.options[4].options[1].options[0].textContent, /Unknown version/);
  element('librarySearch').value = 'example/other';
  element('librarySearch').oninput();
  assert.equal(list.options.length, 1);
  assert.match(list.options[0].options[0].textContent, /^example\/other/);
});

test('Flash starts with the exact selection and tracks download status', () => {
  const {element, events, requests, showBoards, showUsb, showVersions, showDownload, flashDownload, flashPrepared,
    flashFinished} = load();
  const board = 'Elecrow ThinkNode M5';
  showBoards([{name: board, roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  showUsb([{id: 7, name: 'Radio', flashSerial: true, allowed: true, board}]);
  const available = [
    {tag: 'v2', name: 'two.bin', version: 'v2', types: ['flash-update']},
    {tag: 'v1', name: 'one.bin', version: 'v1', types: ['flash-update']}
  ];
  showVersions(requests.at(-1)[0], available);
  showDownload({...available[1], source: 'official', sha256: 'sha'});
  element('flash').onclick();
  assert.equal(element('pageTitle').textContent, 'Flash Progress');
  assert.deepEqual(events, [['flash', {device: 7, board, role: 'companionBle', source: 'official',
    tag: 'v2', name: 'two.bin', sha: '', assetId: ''}]]);
  assert.equal(element('stageDownload').hidden, true);
  flashDownload();
  assert.equal(element('stageDownload').hidden, false);
  showDownload({...available[0], source: 'official', sha256: 'two-sha'});
  flashPrepared('Checking the connected radio and starting firmware update…');
  assert.equal(element('stageDownload').attributes.get('data-state'), 'done');
  assert.match(element('progressStatus').textContent, /starting firmware update/i);
  assert.equal(element('libraryResults').options[0].options[1].options[1]
    .options[0].options[0].textContent, 'two.bin');
  flashFinished(true, 'Flash checksum verified. Reset sent.');
  assert.equal(element('progressOutcome').textContent, 'Flash complete');
  assert.equal(element('progressCard').attributes.get('data-outcome'), 'success');
  assert.equal(element('progressStatus').textContent, 'Flash checksum verified. Reset sent.');
});

test('Flash validates on tap and clears highlights as inputs are fixed', () => {
  const {element, events, requests, showBoards, showUsb, showVersions, showDownload} = load();
  const highlighted = id => element(id).attributes.has('data-flash-attention');
  const board = 'Elecrow ThinkNode M5';
  showBoards([{name: board, roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  assert.equal(element('flashError').hidden, true);
  assert.equal(highlighted('board'), false);
  assert.equal(element('flash').disabled, false);

  element('flash').onclick();
  assert.match(element('flashError').textContent, /Connect a radio.*Choose a board/);
  assert.equal(highlighted('connectionBanner'), true);
  assert.equal(element('board').attributes.get('aria-invalid'), 'true');
  assert.deepEqual(events, []);

  const device = {id: 7, name: 'Radio', flashSerial: true, allowed: false, board};
  showUsb([device]);
  const release = {tag: 'v1', name: 'one.bin', version: 'v1', types: ['flash-update']};
  showVersions(requests.at(-1)[0], [release]);
  assert.equal(element('flashError').hidden, true);
  assert.equal(highlighted('board'), false);
  assert.equal(highlighted('connect'), false);
  showDownload({...release, source: 'official', sha256: 'sha'});
  assert.equal(element('flashError').hidden, true);
  assert.equal(highlighted('fetch'), false);
  assert.deepEqual(events, []);
  element('flash').onclick();
  assert.equal(events.length, 1);
  assert.equal(events[0][0], 'flash');
  assert.equal(events[0][1].device, 7);
});

test('Review uses the manually selected board even when USB reports a different model', () => {
  const {element, events, requests, showBoards, showUsb, showVersions, showDownload} = load();
  const chosen = 'Elecrow ThinkNode M5';
  const reported = 'Another USB radio';
  const roles = [{id: 'companionBle', title: 'Companion Bluetooth'}];
  showBoards([{name: reported, roles}, {name: chosen, roles}]);
  showUsb([{id: 16, name: reported, board: reported, flashSerial: true, allowed: true}]);
  element('board').value = chosen;
  element('board').onchange();
  const release = {tag: 'v1', name: 'one.bin', version: 'v1', types: ['flash-update']};
  showVersions(requests.at(-1)[0], [release]);
  showDownload({...release, source: 'official', sha256: 'sha'});
  element('flash').onclick();
  assert.equal(element('board').value, chosen);
  assert.equal(element('flashError').hidden, true);
  assert.equal(events.length, 1);
  assert.equal(events[0][0], 'flash');
  assert.equal(events[0][1].board, chosen);
});

test('Review can verify a previously exported file for its new direct-write profile', () => {
  const {element, events, requests, showBoards, showUsb, showVersions, showCachedExport} = load();
  const board = 'Elecrow ThinkNode M5';
  const role = 'companionUsb';
  showBoards([{name: board, roles: [{id: role, title: 'USB companion'}]}]);
  showUsb([{id: 7, name: 'Radio', flashSerial: true, allowed: true, board}]);
  const release = {tag: 'v1', name: 'one.bin', version: 'v1', types: ['flash-update']};
  showCachedExport({...release, board, role, source: 'official', sha256: 'sha'});
  showVersions(requests.at(-1)[0], [release]);
  element('flash').onclick();
  assert.equal(events.at(-1)[0], 'flash');
  assert.equal(events.at(-1)[1].name, 'one.bin');
});

test('Review caches a completed download without retargeting after USB disconnection', () => {
  const {element, events, requests, showBoards, showUsb, showVersions, showDownload, flashFinished} = load();
  const board = 'Elecrow ThinkNode M5';
  showBoards([{name: board, roles: [{id: 'companionBle', title: 'Companion Bluetooth'}]}]);
  element('board').value = board;
  element('board').onchange();
  showUsb([{id: 16, name: 'Selected radio', allowed: true}]);
  const release = {tag: 'v1', name: 'one.bin', version: 'v1', types: ['flash-update']};
  showVersions(requests.at(-1)[0], [release]);
  element('flash').onclick();
  showUsb([{id: 17, name: 'Different radio', allowed: true}]);
  showDownload({...release, source: 'official', sha256: 'sha'});
  flashFinished(false, 'The selected USB device disconnected or changed.');
  assert.equal(events.length, 1);
  assert.equal(events[0][1].device, 16);
  assert.match(element('progressStatus').textContent, /disconnected/);
  assert.equal(element('progressOutcome').textContent, 'Flash failed');
  assert.equal(element('progressCard').attributes.get('data-outcome'), 'failure');
  assert.equal(element('board').value, board);
});

test('Configurator is the launch destination and Settings keeps its navigation bar', () => {
  const {element, back} = load();
  assert.equal(back(), false);
  element('showSettings').onclick();
  assert.equal(element('homeHeader').hidden, true);
  assert.equal(element('settingsPage').hidden, false);
  assert.equal(element('primaryNav').hidden, false);
  assert.equal(element('parentBack').hidden, true);
  assert.equal(back(), true);
  assert.equal(element('pageTitle').textContent, 'Configurator');
  assert.equal(element('homeHeader').hidden, false);
  element('showFirmware').onclick();
  assert.equal(element('homeHeader').hidden, true);
});

test('the Configurator billboard animates while the radio is reading or saving', () => {
  const {element, showUsb, showRepeater, onReading, onSaving} = load();
  showUsb([{id: 9, serial: true, name: 'USB radio'}]);
  const moving = () => element('homeMotion').attributes.has('data-working');
  assert.equal(moving(), false);
  element('readRepeater').onclick();
  onReading();
  assert.equal(moving(), true);
  const view = {device: 9, role: 'repeater', version: 'v1.18.0', settings: {name: 'Relay'}};
  showRepeater(view, 'Settings read');
  assert.equal(moving(), false);
  onSaving();
  assert.equal(moving(), true);
  showRepeater(view, 'Saved');
  assert.equal(moving(), false);
});

test('Configurator reads an existing repeater and submits only changed fields', () => {
  const {element, events, showUsb, showRepeater, back} = load();
  const radio = {id: 4, serial: true, name: 'Repeater USB', code: '1A86:7522'};
  showUsb([radio, {...radio, id: 5}]);
  assert.equal(element('repeaterDevice').disabled, false);
  assert.equal(element('readRepeater').disabled, true);
  element('repeaterDevice').value = '4';
  element('repeaterDevice').onchange();
  element('readRepeater').onclick();
  assert.deepEqual(events.at(-1), ['readRepeater', 4]);
  assert.equal(back(), true);
  const view = {device: 4, version: 'v1.18.0', settings: {name: 'Relay', repeat: 'on', radio: '869.525,62.5,7,5'}};
  showRepeater(view, 'Settings read');
  assert.equal(element('repeaterForm').hidden, false);
  assert.equal(element('repeaterFreq').value, '869.525');
  assert.equal(element('repeaterFlood').disabled, true);
  assert.equal(element('saveRepeater').disabled, true);
  element('repeaterName').value = 'Hill relay';
  element('repeaterForm').oninput();
  element('repeaterForm').onsubmit({preventDefault() {}});
  assert.deepEqual(events.at(-1), ['saveRepeater', 4, {name: 'Hill relay'}]);
  showRepeater(view, 'Save canceled', true);
  assert.equal(element('repeaterName').value, 'Hill relay');
  showUsb([]);
  assert.equal(element('repeaterForm').hidden, true);
  assert.equal(element('saveRepeater').disabled, true);
});

test('Configurator edits room access, LoRa presets and structured region and access settings', () => {
  const {element, events, showUsb, showRepeater, showPresets} = load();
  const key = 'aa'.repeat(32);
  showUsb([{id: 9, serial: true, name: 'USB radio', code: '1A86:7522'}]);
  showPresets([{title: 'Test mesh', radio: ['869.525', '62.5', '7', '5'], hash: 1}]);
  showRepeater({device: 9, role: 'room-server', version: 'v1.18.0',
    settings: {name: 'Room', repeat: 'off', 'allow.read.only': 'off', 'guest.password': '',
      password: '', radio: '916.5,125,8,5', 'path.hash.mode': '0'},
    guestSet: true, acl: {[key]: 3},
    regions: {list: [{name: '*', parent: '', flood: true}, {name: 'EU', parent: '*', flood: true}],
      home: '*', scope: '', editable: true}}, 'Settings read');
  assert.equal(element('configRole').textContent, 'Room server');
  assert.equal(element('saveRepeater').disabled, true);
  assert.equal(element('configAccess').options.at(-1).options[1].value, 'off');
  element('configAccess').options.at(-1).options[1].value = 'on';
  element('configRadioPreset').value = '0';
  element('configRadioPreset').onchange();
  element('configScope').value = '1';
  element('configScope').onchange();
  const row = element('configAclRows').options[0];
  const role = row.options[1].options[1];
  role.value = '1';
  role.onchange();
  element('repeaterForm').onsubmit({preventDefault() {}});
  const [, device, changes] = events.at(-1);
  assert.equal(device, 9);
  assert.deepEqual(changes, {'allow.read.only': 'on', radio: '869.525,62.5,7,5',
    'path.hash.mode': '1', acl: [{key, role: 1}],
    regions: {list: [{name: '*', parent: '', flood: true}, {name: 'EU', parent: '*', flood: true}],
      home: '*', scope: 'EU'}});
});

test('Configurator displays a warning for incomplete regions and keeps their editing controls hidden', () => {
  const {element, showUsb, showRepeater} = load();
  showUsb([{id: 9, serial: true, name: 'USB radio'}]);
  showRepeater({device: 9, role: 'repeater', version: 'v1.18.0', settings: {name: 'Relay'},
    regions: {list: [{name: '*', parent: '', flood: false}], home: '*', scope: null, editable: false}},
    'Settings read');
  assert.equal(element('configRegionGroup').hidden, false);
  assert.equal(element('configRegionControls').hidden, true);
  assert.match(element('configRegionStatus').textContent, /incomplete region list/);
  assert.equal(element('configAccessGroup').hidden, true);
});
