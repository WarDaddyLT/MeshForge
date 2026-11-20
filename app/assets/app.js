// The Android serial bridge pattern was adapted from esp-flash-android (MIT).
const byId = id => document.getElementById(id);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const note = (stage, text) => Phone.diagnostic(stage, String(text));
let deviceInventory = [];
let catalogue = [];
let manualBoard = '';
let allSaveAssets = false;
let versionList = [];
let versionRequest = 0;
let busy = false;
let downloading = false;
let cachedM5 = null;
let cachedExport = null;
let bootloaderKey = '';
let localFirmware = null;
const repositoryPanels = new Map();
const repositoryRequests = new Map();
let repositoryRequest = 0;
let activePage = 'flash';
let flashStage = '';

function source() { return byId('repository').value || 'official'; }

function matchesSelection(image) {
  return image && image.board === byId('board').value && image.role === byId('role').value &&
    image.source === source();
}

function buttons() {
  const device = selectedDevice();
  const board = byId('board').value;
  const role = byId('role').value;
  const readyImage = matchesSelection(cachedM5);
  byId('stagedImage').textContent = readyImage ? cachedM5.tag : 'Download a compatible build in Library';
  byId('connect').disabled = !device || busy;
  byId('device').disabled = busy;
  byId('probe').hidden = !device?.m5 || board !== 'Elecrow ThinkNode M5';
  byId('probe').disabled = byId('probe').hidden || !device.allowed || busy || downloading;
  byId('flash').disabled = byId('probe').disabled || !readyImage;
  byId('fetch').disabled = busy || downloading || !versionList[byId('version').value];
  byId('versions').disabled = busy || !role || (!board && source() !== 'save');
  byId('allSaveAssets').hidden = source() !== 'save';
  byId('allSaveAssets').disabled = busy || !board;
  byId('openOfficial').hidden = !board || source() !== 'official';
  byId('openOfficial').disabled = busy;
  byId('board').disabled = busy || catalogue.length === 0;
  byId('repository').disabled = busy;
  byId('export').disabled = busy;
  byId('show').disabled = busy;
  byId('saveImage').hidden = !matchesSelection(cachedExport);
  if (!byId('saveImage').hidden) byId('saveImage').textContent = 'Save ' + cachedExport.name;
  byId('saveImage').disabled = busy || downloading;
  byId('readBootloader').disabled = busy || !device?.storage;
  byId('pickLocal').disabled = busy || downloading;
  byId('saveLocal').hidden = localFirmware?.route !== 'uf2' || localFirmware.board !== board;
  for (const id of ['showFirmware', 'showLibrary', 'showRepos', 'showRepositories', 'backToFlash']) byId(id).disabled = busy;
  byId('stagedField').hidden = board !== 'Elecrow ThinkNode M5';
  const boardLabel = board || 'Choose a board on the Flash screen';
  const sourceLabel = ({official: 'MeshCore Flasher', save: 'SaveMeshcore', solo: 'MeshCore-Solo'})[source()];
  byId('libraryScope').textContent = boardLabel + ' · ' + sourceLabel;
  byId('flashLibraryLabel').textContent = board || 'Browse firmware';
  byId('flashLibraryDetail').textContent = versionList.length ? versionList.length + ' available builds · ' + sourceLabel :
    'Find compatible builds and offline copies';
  renderBootloader(device, board, role);
}

function renderBootloader(device, board, role) {
  const entry = catalogue.find(item => item.name === board);
  byId('bootloaderPanel').hidden = !entry && !device?.storage;
  const info = device?.bootloader;
  const rak1w = board === 'RAK WisMesh 1W Booster (3401 + 13302)';
  const addFixDownloads = fixes => {
    for (const file of fixes) {
      const button = document.createElement('button');
      button.textContent = file.endsWith('.uf2') ? 'Download OTAFix UF2' : 'Download OTAFix DFU ZIP';
      button.onclick = () => Phone.otaFix(device?.id ?? -1, board, file);
      downloads.append(button);
    }
  };
  const key = JSON.stringify([board, role, device?.id, info]);
  if (key === bootloaderKey) return;
  bootloaderKey = key;
  const downloads = byId('otaDownloads');
  downloads.replaceChildren();
  const status = byId('otaStatus');
  const recommendation = entry?.otaNotices?.[role] ?? entry?.otaNotices?.repeater;
  const hasPackage = entry?.otafix?.length > 0;
  const applicable = recommendation || hasPackage;
  if (entry && entry.type !== 'nrf52') {
    status.textContent = 'Not applicable to this board.';
    return;
  }
  if (!info || !board || !info.boards.includes(board)) {
    const recommendationText = recommendation === 'otafixNeeded' ?
      'MeshCore recommends OTAFix for Bluetooth firmware updates. ' :
      applicable ? 'The MeshCore Flasher lists OTAFix for this board. ' :
      entry ? 'The MeshCore Flasher lists no board-specific OTAFix. ' : '';
    if (rak1w && !info) {
      status.textContent = 'MeshCore recommends OTAFix for reliable Bluetooth firmware updates. ' +
        'Installed status unknown. Read INFO_UF2.TXT before installing the fix.';
      addFixDownloads(entry.otafix ?? []);
      return;
    }
    status.textContent = recommendationText + (info ?
      'Bootloader ' + info.version + ' (' + info.id + '). Confirm a matching board to use this result.' :
      device?.storage ? 'Status unknown. Select INFO_UF2.TXT from the radio’s USB drive.' :
      'Status unknown. Connect the radio in UF2 bootloader mode to check.');
    return;
  }
  if (info.status === 'installed') {
    status.textContent = 'OTAFix is already installed (' + info.version + '). Skip this step.';
    return;
  }
  if (info.status !== 'absent') {
    status.textContent = 'OTAFix status unknown. The radio reports bootloader ' + info.version + '.';
    return;
  }
  const fixes = [...new Set(info.fixes)].filter(file => entry?.otafix?.includes(file));
  status.textContent = 'OTAFix is missing (bootloader ' + info.version + '). ' +
    (fixes.length ? 'A matching official download is available.' : 'The MeshCore Flasher lists no matching fix for this board.');
  addFixDownloads(fixes);
}

function selectedDevice() {
  return deviceInventory.find(device => String(device.id) === byId('device').value);
}

function renderSelectedDevice(forceRefresh = false) {
  const device = selectedDevice();
  byId('deviceInfo').textContent = device ? device.type + ' · ' + device.code +
    (device.reportedModel ? ' · ' + device.reportedModel + ' ' + device.reportedVersion : '') +
    (device.inspected ? ' · USB connection verified' : device.allowed ? ' · USB access granted' : '') +
    (device.bootloader ? ' · UF2 ' + device.bootloader.version + ' · ' + device.bootloader.id : '') : '';
  const detectedBoard = catalogue.some(board => board.name === device?.board) ? device.board : '';
  const showingAll = source() === 'save' && allSaveAssets;
  const choice = showingAll ? '' : manualBoard || detectedBoard;
  byId('boardInfo').textContent = showingAll ? 'Browsing all published SaveMeshcore assets.' : manualBoard ? 'Using ' + manualBoard + ' for firmware browsing.' :
    detectedBoard ? 'USB reports ' + detectedBoard + '. You can choose a different board.' :
    'You can also choose a board manually.';
  if (forceRefresh || byId('board').value !== choice) {
    byId('board').value = choice;
    showRoles();
  }
  if (!busy) byId('job').textContent = !device ? 'Connect a device or open the firmware library.' :
    !device.m5 ? 'Choose a board and firmware source. Use Android Files to copy UF2 images to a bootloader drive.' :
    device.allowed ? 'Verify this is your M5 before probing or flashing.' : 'Tap Probe USB to grant USB access.';
  buttons();
}

window.onUsb = (inventory, message) => {
  const select = byId('device');
  const previous = select.value;
  deviceInventory = inventory;
  select.replaceChildren();
  for (const device of inventory) {
    const option = document.createElement('option');
    option.value = String(device.id);
    option.textContent = device.name + ' · ' + device.code;
    select.append(option);
  }
  if (inventory.some(device => String(device.id) === previous)) select.value = previous;
  byId('devices').hidden = inventory.length === 0;
  byId('usb').textContent = message || (inventory.length === 0 ? 'No USB devices detected.' :
    inventory.length === 1 ? '1 USB device detected.' : inventory.length + ' USB devices detected.');
  byId('connectionBanner').setAttribute('data-state', inventory.length ? 'connected' : 'waiting');
  byId('connectionTitle').textContent = inventory.length ? 'Device connected' : 'Waiting for USB';
  renderSelectedDevice();
};

function addOption(select, value, title) {
  const option = document.createElement('option');
  option.value = value;
  option.textContent = title;
  select.append(option);
}

window.onBoards = boards => {
  catalogue = boards;
  const select = byId('board');
  select.replaceChildren();
  addOption(select, '', 'Auto-detect via USB');
  for (const board of [...boards].sort((a, b) => a.name.localeCompare(b.name))) {
    addOption(select, board.name, board.name);
  }
  select.value = manualBoard;
  renderSelectedDevice();
};

function showRoles(preferredRole = '') {
  const choice = catalogue.find(board => board.name === byId('board').value);
  const select = byId('role');
  select.replaceChildren();
  const selectedSource = source();
  const roles = selectedSource === 'save' ? [{id: 'repeater', title: 'Repeater'}] :
    selectedSource === 'solo' && choice ? [{id: 'solo', title: 'Solo companion'}] : choice?.roles ?? [];
  for (const role of roles) addOption(select, role.id, role.title);
  select.disabled = roles.length === 0;
  if (roles.some(role => role.id === preferredRole)) select.value = preferredRole;
  loadVersions();
}

function loadVersions() {
  const request = ++versionRequest;
  const board = byId('board').value;
  const role = byId('role').value;
  versionList = [];
  byId('version').replaceChildren();
  byId('version').disabled = true;
  const ready = role && (board || source() === 'save');
  byId('versionStatus').textContent = ready ? 'Loading compatible builds…' : '';
  byId('downloadStatus').textContent = '';
  byId('saveStatus').textContent = '';
  buttons();
  drawLibrary();
  if (ready) Phone.versions(request, board, role, source());
}

window.onVersions = (request, versions, error) => {
  if (request !== versionRequest) return;
  versionList = versions ?? [];
  for (const [index, release] of versionList.entries()) {
    const formats = release.types.map(type => ({
      'flash-update': 'app update', uf2: 'UF2', bin: 'app BIN', merged: 'merged BIN', zip: 'DFU ZIP', hex: 'HEX', checksums: 'checksums'
    })[type] || type);
    const label = source() === 'save' ? release.name : release.version;
    addOption(byId('version'), String(index), label + ' · ' + formats.join(', '));
  }
  byId('version').disabled = versionList.length === 0;
  byId('versionStatus').textContent = error || (versionList.length === 0 ?
    (source() === 'official' ? 'No in-app download here. Check this board on MeshCore Flasher.' :
      'No compatible build from this source for the selected board and role.') : versionList[0].appTag ?
    'Download an app image for direct USB flashing.' : source() === 'save' ?
    'Choose a file by its exact target name. Published files can be saved for use with the appropriate flasher.' :
    versionList[0].types.includes('uf2') ?
    'Download a UF2 to copy to the radio’s bootloader drive.' : 'Export an app BIN for use with a compatible ESP flasher.');
  buttons();
  drawLibrary();
  refreshLog();
};
window.onPreparing = () => { busy = true; buttons(); byId('job').textContent = 'Checking image…'; };
window.onComplete = (success, message) => {
  busy = false;
  buttons();
  byId('job').textContent = message;
  if (activePage === 'progress') {
    const active = flashStage ? byId(flashStage) : null;
    if (active) setStageState(active, success ? 'done' : 'failed');
    byId('progressStatus').textContent = message;
    byId('forgeScene').setAttribute('data-phase', 'finished');
    byId('progressNotice').hidden = true;
    byId('progressDone').hidden = false;
  }
  refreshLog();
};
window.onDownload = (image, error) => {
  downloading = false;
  if (image) {
    cachedM5 = {board: 'Elecrow ThinkNode M5', role: 'companionBle', ...image,
      source: image.source === 'save' ? 'save' : 'official'};
  }
  byId('downloadStatus').textContent = error || (image ? 'Verified and saved for offline use.' : '');
  buttons();
  drawLibrary();
  refreshLog();
};
window.onCachedExport = image => {
  cachedExport = image;
  downloading = false;
  byId('downloadStatus').textContent = 'Verified firmware cached for offline use.';
  buttons();
  drawLibrary();
  refreshLog();
};
window.onAssetStatus = message => {
  byId('saveStatus').textContent = message;
  if (!byId('repositoryPage').hidden) window.onRepositoryStatus(message);
  refreshLog();
};
window.onLocalFirmware = (image, error) => {
  localFirmware = image;
  if (!image) {
    byId('localStatus').textContent = error || '';
    buttons();
    return;
  }
  const approvedM5 = image.route === 'remote';
  if (approvedM5 && byId('board').value === image.board) {
    byId('repository').value = image.source;
    showRoles(image.role);
  }
  const status = approvedM5 ? 'Matches an approved M5 image.' : image.route === 'uf2' ?
    'Matches the selected application layout. Confirm the board and copy it using Android Files.' :
    'File opened for inspection. Choose a supported board and a compatible image to flash.';
  byId('localStatus').textContent = image.name + ' · ' + image.size + ' bytes · ' + status +
    ' SHA-256 ' + image.sha256;
  buttons();
  refreshLog();
};

function showPage(page) {
  activePage = page;
  const settings = page === 'settings' || page === 'repos';
  byId('firmwarePage').hidden = page !== 'flash';
  byId('libraryPage').hidden = page !== 'library';
  byId('repositoryPage').hidden = !settings;
  byId('diagnostics').hidden = page === 'repos';
  byId('progressPage').hidden = page !== 'progress';
  byId('homeHeader').hidden = page !== 'flash';
  byId('pageHeader').hidden = page === 'flash';
  byId('pageTitle').textContent = ({library: 'Firmware Library', repos: 'Repositories', settings: 'Settings',
    progress: 'Flash Firmware'})[page] ?? '';
  byId('repositoryHeading').textContent = page === 'settings' ? 'Repositories' : 'Firmware sources';
  const pages = {showFirmware: 'flash', showLibrary: 'library', showRepos: 'repos', showRepositories: 'settings'};
  for (const [id, destination] of Object.entries(pages)) {
    byId(id).setAttribute('aria-current', page === destination || page === 'progress' && destination === 'flash' ?
      'page' : 'false');
  }
  if (page === 'library') drawLibrary();
  if (page === 'settings') refreshLog();
  window.scrollTo?.(0, 0);
}

window.goBack = () => {
  if (busy) return true;
  if (activePage === 'flash') return false;
  showPage('flash');
  return true;
};

function drawLibrary() {
  const filters = byId('libraryRoles');
  filters.replaceChildren();
  const roles = byId('role');
  for (const role of roles.options) {
    const selected = role.value === roles.value;
    const chip = repositoryAction(role.textContent, () => {
      roles.value = role.value;
      loadVersions();
    }, selected ? 'gold' : '');
    chip.setAttribute('aria-pressed', String(selected));
    filters.append(chip);
  }
  const list = byId('libraryResults');
  list.replaceChildren();
  const search = (byId('librarySearch').value || '').trim().toLowerCase();
  for (const [index, release] of versionList.entries()) {
    const name = release.name || release.version;
    const searchText = [name, release.version, ...release.types].join(' ').toLowerCase();
    if (search && !searchText.includes(search)) continue;
    const row = repositoryElement('li', '', 'library-entry');
    const heading = repositoryElement('div', '', 'library-entry-heading');
    heading.append(repositoryElement('strong', name));
    heading.append(repositoryElement('p', release.version + ' · ' + release.types.map(format => format === 'flash-update' ? 'ESP app update' : format.toUpperCase()).join(' · ')));
    row.append(heading);
    const local = [cachedM5, cachedExport].find(image => matchesSelection(image) &&
      image.tag === release.tag && image.name === release.name);
    if (local) {
      row.append(repositoryElement('span', '✓ Downloaded', 'file-status is-downloaded'));
      row.append(repositoryAction(local === cachedM5 ? 'Use on Flash' : 'Save copy', () => {
        if (local === cachedM5) {
          showPage('flash');
        } else {
          byId('saveImage').onclick();
        }
      }));
    } else {
      const button = repositoryAction(downloading ? 'Downloading…' : 'Download', () => {
        byId('version').value = String(index);
        byId('fetch').onclick();
      }, 'gold');
      button.disabled = busy || downloading;
      row.append(button);
    }
    list.append(row);
  }
  if (search && list.children?.length === 0 && versionList.length) {
    list.append(repositoryElement('li', 'No firmware matches your search.', 'library-message'));
  }
}

function setStageState(stage, state) {
  stage.setAttribute('data-state', state);
  const label = stage.querySelector?.('span');
  if (label) label.textContent = ({done: '✓ Done', active: 'In progress', failed: 'Failed'})[state] || '';
}

function progressStage(id, status) {
  if (flashStage && flashStage !== id) setStageState(byId(flashStage), 'done');
  flashStage = id;
  byId('forgeScene').setAttribute('data-phase', id);
  setStageState(byId(id), 'active');
  byId('progressStatus').textContent = status;
}

function repositoryElement(tag, text, className) {
  const element = document.createElement(tag);
  if (text) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function repositoryAction(label, action, className) {
  const button = repositoryElement('button', label, className);
  button.type = 'button';
  button.onclick = action;
  return button;
}

function requestRepository(panel, refresh = false) {
  if (panel.loading) return;
  const id = ++repositoryRequest;
  repositoryRequests.set(id, {panel});
  panel.loading = true;
  panel.status.textContent = 'Loading firmware…';
  Phone.repositoryAssets(id, panel.link.url, refresh);
}

function makeRepositoryPanel(link) {
  const panel = {link, loaded: false, loading: false, pending: new Set(), openReleases: new Set()};
  const details = repositoryElement('details', '', 'repository');
  const heading = repositoryElement('summary');
  heading.append(repositoryElement('span', link.url.replace('https://github.com/', '')
    .replace(/\/releases(?=$|\/)/, '')));
  heading.append(repositoryElement('span', link.bootloader ? 'OTA bootloaders' : 'Firmware', 'repository-kind'));
  details.append(heading);
  const actions = repositoryElement('div', '', 'actions repository-actions');
  actions.append(repositoryAction('Refresh', () => requestRepository(panel, true)));
  actions.append(repositoryAction('Open GitHub', () => Phone.openRepository(link.url)));
  if (!link.builtIn) actions.append(repositoryAction('Remove repository', () => Phone.removeRepository(link.url)));
  details.append(actions);
  panel.status = repositoryElement('p', '', 'repository-status');
  panel.status.setAttribute('role', 'status');
  details.append(panel.status);
  panel.items = repositoryElement('ul', '', 'repository-files');
  details.append(panel.items);
  details.ontoggle = () => {
    if (details.open) requestRepository(panel);
  };
  panel.element = details;
  return panel;
}

function drawRepositoryFiles(panel) {
  panel.items.replaceChildren();
  const releases = new Map();
  for (const asset of panel.files ?? []) {
    if (!releases.has(asset.tag)) releases.set(asset.tag, []);
    releases.get(asset.tag).push(asset);
  }
  for (const [tag, assets] of releases) {
    const group = repositoryElement('li', '', 'repository-release');
    const details = repositoryElement('details');
    const summary = repositoryElement('summary');
    summary.append(repositoryElement('strong', tag));
    const downloaded = assets.filter(asset => asset.downloaded).length;
    summary.append(repositoryElement('span', assets.length + ' UF2' +
      (downloaded ? ' · ' + downloaded + ' downloaded' : ''), 'repository-kind'));
    details.append(summary);
    const list = repositoryElement('ul', '', 'release-files');
    for (const asset of assets) list.append(drawRepositoryAsset(panel, asset));
    details.append(list);
    details.open = panel.openReleases.has(tag);
    details.ontoggle = () => {
      if (details.open) panel.openReleases.add(tag);
      else panel.openReleases.delete(tag);
    };
    group.append(details);
    panel.items.append(group);
  }
}

function drawRepositoryAsset(panel, asset) {
  const row = repositoryElement('li');
  row.append(repositoryElement('strong', asset.name));
  const size = asset.size > 0 ? (asset.size / 1024).toFixed(0) + ' KiB' : '';
  if (size) row.append(repositoryElement('p', size, 'repository-meta'));
  const status = asset.downloaded ? 'Downloaded' : asset.digest ? 'Available to download' : 'No published SHA-256';
  row.append(repositoryElement('span', status, asset.downloaded ? 'file-status is-downloaded' : 'file-status'));
  const actions = repositoryElement('div', '', 'actions');
  const working = panel.pending.has(asset.id);
  if (asset.downloaded) {
    actions.append(repositoryAction('Save copy', () => Phone.saveRepositoryAsset(
      asset.name, asset.digest.slice(7), asset.size)));
    if (asset.removable) {
      const remove = repositoryAction(working ? 'Removing…' : 'Remove download', () => {
        if (!window.confirm('Remove this offline copy?')) return;
        const id = ++repositoryRequest;
        panel.pending.add(asset.id);
        repositoryRequests.set(id, {panel, asset});
        drawRepositoryFiles(panel);
        Phone.removeRepositoryAsset(id, asset.digest.slice(7));
      });
      remove.disabled = working;
      actions.append(remove);
    }
  } else if (asset.digest) {
    const download = repositoryAction(working ? 'Downloading…' : 'Download', () => {
      const id = ++repositoryRequest;
      panel.pending.add(asset.id);
      repositoryRequests.set(id, {panel, asset});
      drawRepositoryFiles(panel);
      Phone.downloadRepositoryAsset(id, panel.link.url, asset.tag, String(asset.id), asset.digest);
    }, 'gold');
    download.disabled = working;
    actions.append(download);
  } else {
    const url = panel.link.url.split('/tag/')[0] + '/tag/' + asset.tag;
    actions.append(repositoryAction('Open release', () => Phone.openRepository(url)));
  }
  row.append(actions);
  return row;
}


function takeRepositoryRequest(id) {
  const request = repositoryRequests.get(id);
  repositoryRequests.delete(id);
  if (request && repositoryPanels.get(request.panel.link.url) === request.panel) return request;
  return null;
}

window.onRepositoryStatus = message => { byId('repositoryStatus').textContent = message; };
window.onRepositories = links => {
  const list = byId('repositoryList');
  const active = new Set(links.map(link => link.url));
  for (const [url, panel] of repositoryPanels) {
    if (!active.has(url)) {
      panel.element.remove();
      repositoryPanels.delete(url);
    }
  }
  for (const link of links) {
    if (repositoryPanels.has(link.url)) continue;
    const panel = makeRepositoryPanel(link);
    repositoryPanels.set(link.url, panel);
    list.append(panel.element);
  }
};

window.onRepositoryAssets = (id, result, error) => {
  const request = takeRepositoryRequest(id);
  if (!request) return;
  const {panel} = request;
  panel.loading = false;
  if (result) {
    panel.loaded = true;
    panel.files = result.files;
    drawRepositoryFiles(panel);
  }
  panel.status.textContent = error || [result.files.length + ' UF2 files', result.notice].filter(Boolean).join(' · ');
};

window.onRepositoryDownload = (id, image, error) => {
  const request = takeRepositoryRequest(id);
  if (!request) return;
  const {panel, asset} = request;
  panel.pending.delete(asset.id);
  panel.status.textContent = error || 'Saved for offline use.';
  if (image) {
    for (const other of repositoryPanels.values()) {
      for (const item of other.files ?? []) {
        if (item.digest === asset.digest) {
          item.downloaded = true;
          item.removable = true;
        }
      }
      if (other.loaded) drawRepositoryFiles(other);
    }
  } else {
    drawRepositoryFiles(panel);
  }
};

window.onRepositoryRemoval = (id, error) => {
  const request = takeRepositoryRequest(id);
  if (!request) return;
  const {panel, asset} = request;
  panel.pending.delete(asset.id);
  panel.status.textContent = error || 'Download removed.';
  if (!error) for (const other of repositoryPanels.values()) {
    if (other.loaded) requestRepository(other);
  }
  drawRepositoryFiles(panel);
};

window.onExport = ok => { byId('exportStatus').textContent = ok ? 'Debug log saved.' : 'Could not export log.'; refreshLog(); };
window.onDetach = () => note('usb', 'Active host session lost its device');
window.onerror = (message, source, line) => note('ui.error', 'line=' + line + ' ' + message);
window.onunhandledrejection = event => note('ui.error', String(event.reason));

function refreshLog() {
  const output = byId('log');
  output.textContent = Phone.logTail();
}

class AndroidSerialPort {
  constructor() {
    this.running = true;
    this.readable = new ReadableStream({
      pull: async controller => {
        while (this.running) {
          const data = Phone.readData();
          if (data) { controller.enqueue(Uint8Array.from(atob(data), byte => byte.charCodeAt(0))); return; }
          const error = Phone.portError();
          if (error) { controller.error(new Error(error)); return; }
          await wait(8);
        }
        controller.close();
      },
      cancel: () => { this.running = false; }
    });
    this.writable = new WritableStream({
      write: bytes => {
        let text = '';
        for (const byte of bytes) text += String.fromCharCode(byte);
        this.check(Phone.write(btoa(text)));
      }
    });
  }
  check(error) { if (error) throw new Error(error); }
  async open() {}
  async close() { this.running = false; Phone.close(); }
  getInfo() { return {usbVendorId: 0x1a86, usbProductId: 0x7522}; }
  async setSignals({requestToSend, dataTerminalReady} = {}) {
    this.check(Phone.signals(requestToSend === undefined ? -1 : Number(requestToSend),
                             dataTerminalReady === undefined ? -1 : Number(dataTerminalReady)));
  }
}

function logCommands(loader) {
  const original = loader.command.bind(loader);
  let sequence = 0;
  loader.command = async (opcode, data, checksum, response, timeout) => {
    const id = ++sequence;
    const start = performance.now();
    note('rom.tx', 'seq=' + id + ' op=' + opcode + ' bytes=' + (data?.length ?? 0) + ' timeoutMs=' + timeout);
    try {
      const result = await original(opcode, data, checksum, response, timeout);
      note('rom.rx', 'seq=' + id + ' bytes=' + (result?.[1]?.length ?? 0) +
        ' elapsedMs=' + Math.round(performance.now() - start));
      return result;
    } catch (error) {
      note('rom.error', 'seq=' + id + ' op=' + opcode + ' ' + error.message);
      throw error;
    }
  };
}

async function preflight(loader, image) {
  note('boot', 'Classic DTR/RTS reset and ROM sync');
  await loader.connect('default_reset', 3);
  if (loader.chip?.IMAGE_CHIP_ID !== 9) throw new Error('Expected an ESP32-S3.');
  // ESP32-S3 eFuse read addresses and fields from Espressif esptool.
  const cryptCount = (await loader.readReg(0x60007034) >>> 18) & 7;
  const secureBoot = !!((await loader.readReg(0x60007038)) & (1 << 20));
  const encrypted = ((cryptCount ^ (cryptCount >>> 1) ^ (cryptCount >>> 2)) & 1) !== 0;
  note('security', 'ESP32-S3 secureBoot=' + secureBoot + ' flashEncrypted=' + encrypted);
  if (secureBoot || encrypted) throw new Error('This target has protected flash.');
  if (loader.chip.postConnect) await loader.chip.postConnect(loader);
  await loader.runStub();
  const jedec = await loader.readFlashId();
  note('preflight', 'JEDEC=0x' + jedec.toString(16));
  if (((jedec >>> 16) & 255) !== 0x16) throw new Error('Expected 4 MiB of target flash.');
  const partitions = await loader.flashMd5sum(0x8000, image.partitionLength);
  note('layout', 'partitionTableMD5=' + partitions + ' expected=' + image.partitionMd5);
  if (partitions !== image.partitionMd5) throw new Error('Target partition layout does not match the approved M5 layout.');
  const ota = await loader.flashMd5sum(0xe000, 0x2000);
  note('layout', 'otaMetadataMD5=' + ota);
  if (ota !== image.otaSeedMd5 && ota !== image.otaEmptyMd5) {
    throw new Error('Could not confirm that the target boots from the approved application slot.');
  }
}

async function writeImage(loader, image, data) {
  const address = 0x10000;
  const blockSize = loader.FLASH_WRITE_SIZE;
  if (!blockSize || Math.ceil(data.length / blockSize) * blockSize > 0x140000) {
    throw new Error('Padded write would exceed the application slot.');
  }
  note('write', 'Begin app-only write address=0x10000 size=' + data.length + ' block=' + blockSize);
  progressStage('stageWrite', 'Writing firmware…');
  await loader.flashBegin(data.length, address);
  for (let offset = 0, sequence = 0; offset < data.length; offset += blockSize, sequence++) {
    const block = new Uint8Array(blockSize).fill(255);
    block.set(data.subarray(offset, offset + blockSize));
    await loader.flashBlock(block, sequence, 8000);
    const written = Math.min(data.length, offset + blockSize);
    const percentage = Math.floor(written * 100 / data.length);
    byId('progress').value = percentage;
    byId('progressPercent').textContent = percentage + '%';
    byId('progressStatus').textContent = 'Writing firmware · ' + written + ' / ' + data.length + ' bytes';
  }
  progressStage('stageVerify', 'Verifying the written application…');
  const observed = await loader.flashMd5sum(address, data.length);
  note('verify', 'observedMD5=' + observed + ' expectedMD5=' + image.md5);
  if (observed !== image.md5) throw new Error('Flash digest mismatch.');
  await loader.flashFinish(false);
}

window.startFlash = async (id, image, probeOnly) => {
  let serial;
  let loader;
  let writing = false;
  let verified = false;
  let success = false;
  let result;
  const started = performance.now();
  showPage('progress');
  flashStage = '';
  for (const id of ['stagePrepare', 'stageWrite', 'stageVerify', 'stageReset']) setStageState(byId(id), '');
  byId('stageWrite').hidden = probeOnly;
  byId('stageVerify').hidden = probeOnly;
  byId('progressImage').textContent = probeOnly ? 'Bootloader layout check' : (image.tag || 'Selected application image');
  byId('progress').removeAttribute?.('value');
  byId('progressPercent').textContent = '';
  byId('progressNotice').hidden = false;
  byId('progressDone').hidden = true;
  progressStage('stagePrepare', 'Checking chip, flash and partition layout…');
  try {
    const error = Phone.connect(id);
    if (error) throw new Error(error);
    serial = new AndroidSerialPort();
    const transport = new esptool.Transport(serial, false);
    const terminal = {clean() {}, write: message => note('loader', message), writeLine: message => note('loader', message)};
    loader = new esptool.ESPLoader({transport, baudrate: 115200, terminal});
    logCommands(loader);
    await preflight(loader, image);
    if (!probeOnly) {
      const bytes = Uint8Array.from(atob(Phone.image()), byte => byte.charCodeAt(0));
      if (bytes.length !== image.size) throw new Error('The approved staged image is unavailable.');
      writing = true;
      await writeImage(loader, image, bytes);
      verified = true;
    }
    progressStage('stageReset', 'Resetting device…');
    await loader.after('hard_reset');
    result = probeOnly ? 'Probe passed. Chip and layout match. Reset sent.' :
      'Flash checksum verified. Reset sent. Confirm MeshCore boots on the M5.';
    success = true;
  } catch (error) {
    note('job.error', error.stack || error.message);
    result = verified ? 'Flash checksum verified, but reset failed. ' + error.message :
      writing ? 'Update interrupted. The M5 may need recovery. ' + error.message : error.message;
    if (!writing && loader && !Phone.portError()) {
      try { await loader.after('hard_reset'); } catch (resetError) { note('reset.error', resetError.message); }
    }
  } finally {
    if (serial) await serial.close();
    Phone.finished(success, 'elapsedMs=' + Math.round(performance.now() - started) + ' ' + result);
    window.onComplete(success, result);
  }
};

byId('scan').onclick = () => { Phone.scan(); refreshLog(); };
byId('device').onchange = () => renderSelectedDevice();
byId('connect').onclick = () => {
  const device = selectedDevice();
  if (device) Phone.connectUsb(device.id);
};
byId('flash').onclick = () => {
  const device = selectedDevice();
  if (byId('flash').disabled || !device?.m5 || !device.allowed) return;
  busy = true;
  buttons();
  Phone.confirm(device.id, false, source(), byId('role').value);
};
byId('probe').onclick = () => {
  const device = selectedDevice();
  if (!device?.m5 || !device.allowed) return;
  busy = true;
  buttons();
  Phone.confirm(device.id, true, '', '');
};
byId('board').onchange = () => {
  allSaveAssets = source() === 'save' && !byId('board').value;
  manualBoard = byId('board').value;
  renderSelectedDevice(true);
};
byId('openOfficial').onclick = () => Phone.openOfficial(byId('board').value);
byId('allSaveAssets').onclick = () => {
  allSaveAssets = true;
  renderSelectedDevice(true);
};
byId('repository').onchange = () => {
  allSaveAssets = source() === 'save' && !byId('board').value;
  renderSelectedDevice(true);
};
byId('role').onchange = loadVersions;
byId('version').onchange = buttons;
byId('versions').onclick = loadVersions;
byId('fetch').onclick = () => {
  const release = versionList[byId('version').value];
  if (!release || busy || downloading) return;
  downloading = true;
  buttons();
  byId('downloadStatus').textContent = 'Downloading and verifying…';
  drawLibrary();
  Phone.download(byId('board').value, byId('role').value, source(), release.tag, release.name);
};
byId('saveImage').onclick = () => Phone.saveImage(byId('board').value, byId('role').value, source());
byId('readBootloader').onclick = () => {
  const device = selectedDevice();
  if (device?.storage) Phone.readBootloader(device.id);
};
byId('showFirmware').onclick = () => showPage('flash');
byId('showLibrary').onclick = () => showPage('library');
byId('showRepos').onclick = () => showPage('repos');
byId('showRepositories').onclick = () => showPage('settings');
byId('flashToLibrary').onclick = () => showPage('library');
byId('flashPickLocal').onclick = () => {
  showPage('library');
  byId('pickLocal').onclick();
};
byId('libraryToRepos').onclick = () => showPage('repos');
byId('librarySelection').onclick = () => showPage('flash');
byId('backToFlash').onclick = () => showPage('flash');
byId('progressDone').onclick = () => showPage('flash');
byId('librarySearch').oninput = drawLibrary;
byId('pickLocal').onclick = () => Phone.pickLocal(byId('board').value);
byId('saveLocal').onclick = () => {
  if (localFirmware?.route === 'uf2') Phone.saveLocal(byId('board').value, localFirmware.sha256);
};
byId('addRepository').onclick = () => Phone.addRepository(
  byId('repositoryUrl').value, byId('repositoryKind').value === 'bootloader');
byId('show').onclick = refreshLog;
byId('export').onclick = () => Phone.export();
window.onSystemTopInset = inset => document.documentElement?.style.setProperty('--system-top', inset + 'px');
window.onSystemTopInset(Phone.systemTopInset?.() ?? 0);
Phone.ready();
refreshLog();
