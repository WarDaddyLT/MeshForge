// The Android serial bridge pattern was adapted from esp-flash-android (MIT).
const byId = id => document.getElementById(id);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const note = (stage, text) => Phone.diagnostic(stage, String(text));
const directUpdateBoard = 'Elecrow ThinkNode M5';
const detectedRoleNames = {companionUsb: 'USB companion', repeater: 'Repeater', 'room-server': 'Room server'};
let deviceInventory = [];
let catalogue = [];
let manualBoard = '';
let versionList = [];
let versionRequest = 0;
let versionsLoading = false;
let versionPreference = null;
let busy = false;
let downloading = false;
let cachedM5 = null;
let cachedExport = null;
let bootloaderKey = '';
let otaPackageKey = '';
let otaPackages = [];
let downloadName = '';
let downloadChecksumPublished = true;
let downloadScope = '';
let downloadResult = '';
let configWorking = false;
let repositoryLinks = [];
const repositoryLabels = new Map();
let repositoryDownloads = [];
const otaReleases = new Map();
let localFirmware = null;
const repositoryRequests = new Map();
const libraryRepositoryFiles = new Map();
const libraryAccordionState = new Map();
const firmwareSourceNames = {official: 'MeshCore', save: 'SaveMeshcore', solo: 'MeshCore-Solo'};
const canonicalRepositories = {
  'meshcore-dev/meshcore': 'official',
  'aigarslv/savemeshcore': 'save',
  'marekzegare4/meshcore-solo': 'solo'
};
let repositoryRequest = 0;
const pages = {
  configurator: {panel: 'configuratorPage', title: 'Configurator', button: 'showConfigurator'},
  flash: {panel: 'firmwarePage', title: 'Flash Firmware', button: 'showFirmware'},
  library: {panel: 'libraryPage', title: 'Firmware Library', button: 'showLibrary'},
  settings: {panel: 'settingsPage', title: 'Settings', button: 'showSettings'},
  progress: {panel: 'progressPage', title: 'Flash Progress', parent: 'flash'}
};
const pageScroll = new Map();
let activePage = 'configurator';
let flashStage = '';
let repeaterBusy = false;
let repeaterView = null;
let dfuWorkflowStatus = '';
let flashValidationKey = '';
let flashReviewAttempted = false;
const flashInputs = ['board', 'device', 'role', 'version', 'repository'];
const flashActions = ['connectionBanner', 'pickLocal'];

function source() { return byId('repository').value || 'local'; }

function selectedRelease() { return versionList[byId('version').value]; }

function matchesSelection(image) {
  return image && image.board === byId('board').value && image.role === byId('role').value &&
    image.source === source();
}

function isDfuSelection(release = selectedRelease(), board = byId('board').value) {
  if (!catalogue.some(entry => entry.name === board && entry.uf2Dfu)) return false;
  if (source() === 'local') return localFirmware?.board === board && localFirmware.route === 'uf2';
  if (!release?.name?.toLowerCase().endsWith('.uf2')) return false;
  if (source().startsWith('https://')) return /^sha256:[0-9a-f]{64}$/.test(release.digest || '');
  return release.exportOnly === false;
}

function matchesRelease(image, release) {
  return Boolean(image && release && (release.cached && release.cached === image.sha256 ||
    image.tag === release.tag && image.name === release.name));
}

function flashImage() {
  if (!cachedM5?.sha256) return null;
  if (source() === 'local') {
    return localFirmware?.route === 'remote' && localFirmware.board === byId('board').value &&
      localFirmware.sha256 && localFirmware.sha256 === cachedM5?.sha256 ? cachedM5 : null;
  }
  if (source().startsWith('https://')) {
    return cachedM5?.sha256 && cachedM5.board === byId('board').value &&
      selectedRelease()?.digest === 'sha256:' + cachedM5.sha256 ? cachedM5 : null;
  }
  return matchesSelection(cachedM5) && matchesRelease(cachedM5, selectedRelease()) ? cachedM5 : null;
}

function setMotion(id, active) {
  if (active) byId(id).setAttribute('data-working', '');
  else byId(id).removeAttribute?.('data-working');
}

function updateMotion() {
  setMotion('homeMotion', activePage === 'configurator' && configWorking);
  setMotion('titleMotion', downloading && (activePage === 'flash' || activePage === 'library'));
}

function flashRequirements(device, board, release) {
  const issues = [];
  if (!device && !isDfuSelection(release, board)) {
    issues.push(deviceInventory.length ? ['device', 'Select the USB radio you want to flash.'] :
      ['connectionBanner', 'Connect a radio with a USB data cable.']);
  }
  if (!board) issues.push(['board', 'Choose a board.']);
  if (source() === 'local') {
    if (!localFirmware) issues.push(['pickLocal', 'Choose a local firmware file.']);
  } else if (!source().startsWith('https://') && !byId('role').value) {
    if (board) issues.push(['role', 'Choose a firmware role.']);
  } else if (!release) {
    issues.push(versionsLoading ? ['', 'Wait for the firmware release list.'] :
      ['version', 'Choose a firmware release.']);
  }
  return issues;
}

function prepareFlashSelection(device, release) {
  const local = source() === 'local' ? localFirmware : null;
  return {
    device: device?.id ?? -1, board: byId('board').value, source: source(), role: byId('role').value,
    tag: local?.tag || release?.tag || '',
    name: local?.name || release?.name || '',
    sha: local?.sha256 || release?.digest?.replace(/^sha256:/, '') || '',
    assetId: release?.id || ''
  };
}

function openFlashProgress(selection, dfu = false) {
  busy = true;
  flashStage = '';
  for (const id of ['stageDownload', 'stagePrepare', 'stageWrite', 'stageVerify', 'stageReset']) {
    setStageState(byId(id), '');
  }
  byId('progressCard').removeAttribute('data-outcome');
  byId('progressOutcome').hidden = true;
  byId('progressOutcome').textContent = '';
  byId('stageDownload').hidden = true;
  byId('stageWrite').hidden = false;
  byId('stageVerify').hidden = dfu;
  byId('stageReset').hidden = dfu;
  byId('progressTarget').textContent = selection.board;
  byId('progressImage').textContent = [selection.name, selection.tag].filter(Boolean).join(' · ');
  byId('progress').removeAttribute('value');
  byId('progressPercent').textContent = '';
  byId('progressNotice').hidden = false;
  showPage('progress');
  setMotion('forgeScene', true);
  buttons();
  progressStage('stagePrepare', dfu ?
    'Preparing the selected UF2…' : 'Checking for the selected firmware…');
}

function renderFlashValidation(issues) {
  if (!issues.length) flashReviewAttempted = false;
  const shown = flashReviewAttempted ? issues : [];
  const key = JSON.stringify(shown);
  if (key === flashValidationKey) return;
  flashValidationKey = key;
  const ids = new Set(shown.map(([id]) => id));
  for (const id of flashInputs.concat(flashActions)) {
    const field = byId(id);
    const highlighted = ids.has(id);
    if (highlighted) field.setAttribute('data-flash-attention', '');
    else field.removeAttribute('data-flash-attention');
    if (!flashInputs.includes(id)) continue;
    if (highlighted) field.setAttribute('aria-invalid', 'true');
    else field.removeAttribute('aria-invalid');
  }
  const error = byId('flashError');
  error.hidden = !shown.length;
  error.textContent = shown.map(([, message]) => message).join(' ');
}

function buttons() {
  const device = selectedDevice();
  const board = byId('board').value;
  const role = byId('role').value;
  const release = selectedRelease();
  const ready = flashImage();
  const local = source() === 'local';
  const generic = source().startsWith('https://');
  const cached = [cachedM5, cachedExport].find(image => matchesSelection(image) && matchesRelease(image, release));
  const found = release?.digest && repositoryDownloads.find(file => 'sha256:' + file.sha256 === release.digest);
  const offline = cached || found || ready;
  const needsWriteVerification = !generic && !ready &&
    (release?.appTag || release?.types?.includes('flash-update'));
  byId('remoteFirmware').hidden = local;
  byId('localFirmwareField').hidden = !local;
  byId('roleField').hidden = generic;
  const alpha = sourceIsAlpha();
  const channel = local ? '' : releaseChannel(release, alpha);
  const warning = byId('releaseWarning');
  warning.hidden = !channel && !alpha;
  warning.textContent = channel ? channel + ' firmware. Use it for testing.' :
    alpha ? 'Alpha repository. Firmware is for testing.' : '';
  byId('stagedImage').textContent = ready ? 'Ready to flash · ' + ready.name :
    offline ? 'Available in Firmware Library for export.' :
    release && generic ? 'A repository file can be saved for offline use. Direct USB flashing requires a verified board and image match.' : '';
  byId('connect').disabled = !device || busy;
  byId('device').disabled = busy;
  byId('probe').hidden = !device?.flashSerial || board !== directUpdateBoard;
  byId('probe').disabled = byId('probe').hidden || !device.allowed || busy || downloading;
  const issues = flashRequirements(device, board, release);
  byId('flash').disabled = busy || downloading;
  byId('fetch').disabled = busy || downloading || !release || Boolean(offline && !needsWriteVerification) ||
    generic && !release.digest;
  byId('versions').disabled = busy || downloading || local;
  byId('openOfficial').hidden = !board || source() !== 'official';
  byId('openOfficial').disabled = busy;
  byId('viewRelease').hidden = !generic || !release || Boolean(release.digest);
  byId('board').disabled = busy || catalogue.length === 0;
  byId('repository').disabled = busy;
  byId('role').disabled = busy || !byId('role').options.length;
  byId('version').disabled = busy || !versionList.length;
  byId('export').disabled = busy;
  byId('show').disabled = busy;
  byId('readBootloader').disabled = busy || !device?.storage;
  byId('otaInfoHint').hidden = !device?.storage;
  byId('pickLocal').disabled = busy || downloading;
  byId('saveLocal').hidden = localFirmware?.route !== 'uf2' || localFirmware.board !== board;
  for (const {button} of Object.values(pages)) if (button) byId(button).disabled = busy || repeaterBusy;
  byId('parentBack').disabled = busy || repeaterBusy;
  renderFlashValidation(issues);
  renderBootloader(device, board, role);
  repeaterButtons();
  updateMotion();
}

function otaFamily(filename) {
  const prefix = filename.toLowerCase().match(/^(.+_bootloader)-[0-9]/);
  return prefix?.[1] ?? '';
}

function renderBootloader(device, board, role) {
  const entry = catalogue.find(item => item.name === board);
  const info = device?.bootloader;
  const applicable = entry?.type === 'nrf52' || Boolean(device?.storage && !entry);
  byId('bootloaderPanel').hidden = !applicable;
  const release = selectedRelease();
  const selectedUf2 = isDfuSelection(release, board);
  byId('uf2DfuPanel').hidden = entry?.type !== 'nrf52';
  byId('dfuStatus').textContent = dfuWorkflowStatus || (entry?.type !== 'nrf52' ? '' : !entry.uf2Dfu ?
    'UF2/DFU updates are supported by this nRF board, but MeshForge does not have a verified application address for its UF2 files. You can still download and export firmware from the Library.' :
    !selectedUf2 ? 'Select a board-compatible UF2 release above. ZIP DFU packages use a different update procedure.' :
    !device?.storage ? 'Tap Flash to prepare the UF2 and connect to the radio’s bootloader.' :
    !info ? 'UF2 bootloader detected. Tap Flash; MeshForge will verify INFO_UF2.TXT and write the firmware directly over USB.' :
    !info.boards?.includes(board) ? 'The DFU drive identifies a different board. The UF2 will not be installed.' :
    'The selected DFU drive and board match. Select a UF2 release and tap Flash to update.');
  if (selectedUf2 && !device?.storage && entry.dfuEntry && !dfuWorkflowStatus) {
    byId('dfuStatus').textContent += ' ' + entry.dfuEntry;
  }
  const confirmedBoard = Boolean(board && info?.boards.includes(board));
  const installed = confirmedBoard && info.status === 'installed';
  const recommendation = entry?.otaNotices?.[role] ?? entry?.otaNotices?.repeater;
  const officialFiles = entry?.otafix ?? [];
  const families = new Set(officialFiles.map(otaFamily).filter(Boolean));
  if (confirmedBoard && info.family) families.add(info.family);
  const choices = [];
  if (applicable && !installed && (!info || confirmedBoard)) {
    for (const file of officialFiles) {
      if (!confirmedBoard || info.fixes.includes(file)) choices.push({key: 'official:' + file, name: file});
    }
    for (const link of repositoryLinks.filter(item => item.bootloader)) {
      for (const asset of otaReleases.get(link.url)?.files ?? []) {
        if (!asset.digest || !families.has(otaFamily(asset.name)) ||
            !/[-_.]otafix(?=\d|[-_.]|$)/i.test(asset.name) || !/\.(uf2|zip)$/i.test(asset.name)) continue;
        if (confirmedBoard && (!info.family || info.family !== otaFamily(asset.name))) continue;
        choices.push({...asset, key: link.url + ':' + asset.id, url: link.url, sourceLabel: repositoryName(link.url)});
      }
    }
  }
  const context = JSON.stringify([board, device?.id, info]);
  const key = context + '|' + JSON.stringify(choices.map(choice => choice.key));
  if (key !== bootloaderKey) {
    const sameTarget = bootloaderKey.startsWith(context + '|');
    bootloaderKey = key;
    if (!sameTarget || !choices.some(choice => choice.key === otaPackageKey)) otaPackageKey = '';
    otaPackages = choices;
    const select = byId('otaPackage');
    select.replaceChildren();
    addOption(select, '', 'Do not select an OTAFix');
    for (const choice of choices) addOption(select, choice.key,
      choice.name + ' · ' + (choice.sourceLabel || 'MeshCore'));
    select.value = otaPackageKey;
    byId('otaJob').textContent = '';
  }
  byId('otaChoices').hidden = !choices.length;
  const choice = otaPackages.find(item => item.key === otaPackageKey);
  const cached = choice?.digest && repositoryDownloads.some(file => 'sha256:' + file.sha256 === choice.digest);
  byId('downloadOta').disabled = busy || downloading || !choice;
  byId('downloadOta').textContent = cached ? 'Export selected OTAFix' :
    choice?.url ? 'Download selected OTAFix' : 'Open official OTAFix download';
  const otaLinks = repositoryLinks.filter(link => link.bootloader);
  byId('findOtaFixes').hidden = !applicable || !board || installed || !otaLinks.length;
  byId('findOtaFixes').disabled = busy || otaLinks.some(link => otaReleases.get(link.url)?.loading);
  const checked = otaLinks.map(link => otaReleases.get(link.url)).filter(Boolean);
  const loading = checked.some(entry => entry.loading);
  const error = checked.some(entry => entry.error);
  const guidance = recommendation === 'otafixNeeded' || board === 'RAK WisMesh 1W Booster (3401 + 13302)' && officialFiles.length ?
    'MeshCore recommends OTAFix for Bluetooth firmware updates. ' : '';
  byId('otaStatus').textContent = installed ? 'OTAFix is already installed (' + info.version + ').' :
    info && !confirmedBoard ? 'The bootloader does not confirm the selected board. Check the board before choosing a fix.' :
    !board ? 'Select the board to check for an OTAFix.' :
    guidance + (choices.length ? 'OTAFix available. ' : loading ? 'Checking OTAFix repositories… ' :
    error ? 'Some OTAFix repositories could not be checked. ' : 'No matching OTAFix package found. ') +
    (confirmedBoard && info.status === 'absent' ? 'The radio reports a stock bootloader (' + info.version + ').' :
      'Installed status unknown.');
  const badge = installed ? 'OTAFix installed' : choice ? 'OTAFix selected' : choices.length ? 'OTAFix available' : '';
  byId('deviceOtaBadge').textContent = badge;
  byId('deviceOtaBadge').hidden = !badge;
}

function requestOtaRepositories(refresh = false) {
  if (activePage !== 'flash' || byId('bootloaderPanel').hidden || !byId('board').value) return;
  const button = byId('findOtaFixes');
  if (button.hidden) return;
  for (const link of repositoryLinks.filter(item => item.bootloader)) {
    const previous = otaReleases.get(link.url);
    if (previous?.loading || (!refresh && previous)) continue;
    const request = ++repositoryRequest;
    otaReleases.set(link.url, {loading: true, files: previous?.files ?? []});
    repositoryRequests.set(request, {kind: 'ota', url: link.url});
    Phone.repositoryAssets(request, link.url, refresh);
  }
  buttons();
}

function selectedDevice() {
  return deviceInventory.find(device => String(device.id) === byId('device').value);
}

function renderSelectedDevice(forceRefresh = false) {
  const device = selectedDevice();
  const reportedRole = detectedRoleNames[device?.reportedRole];
  byId('deviceInfo').textContent = device ? device.type + ' · ' + device.code +
    [device.reportedModel, reportedRole, device.reportedVersion].filter(Boolean).map(value => ' · ' + value).join('') +
    (device.inspected ? ' · USB connection verified' : device.allowed ? ' · USB access granted' : '') +
    (device.bootloader ? ' · UF2 ' + device.bootloader.version + ' · ' + device.bootloader.id : '') : '';
  const detectedBoard = catalogue.some(board => board.name === device?.board) ? device.board : '';
  const choice = manualBoard || detectedBoard;
  byId('boardInfo').textContent = manualBoard ? 'Using ' + manualBoard + ' for firmware browsing.' :
    detectedBoard ? 'USB reports ' + detectedBoard + '. You can choose a different board.' :
    'You can also choose a board manually.';
  if (forceRefresh || byId('board').value !== choice) {
    byId('board').value = choice;
    showRoles();
  }
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
  renderRepeaterDevices();
  renderSelectedDevice();
  requestOtaRepositories();
};

const repeaterFields = {
  name: ['repeaterName'],
  'owner.info': ['repeaterOwner'],
  repeat: ['repeaterForward'],
  'advert.interval': ['repeaterLocal'],
  'flood.advert.interval': ['repeaterFlood'],
  radio: ['repeaterFreq', 'repeaterBw', 'repeaterSf', 'repeaterCr']
};
const repeaterGroups = ['repeaterNameField', 'repeaterOwnerField', 'repeaterForwardField',
  'repeaterLocalField', 'repeaterFloodField', 'repeaterRadioField'];

function repeaterChanges() {
  const changes = {};
  if (!repeaterView) return changes;
  for (const [key, ids] of Object.entries(repeaterFields)) {
    if (!Object.hasOwn(repeaterView.settings, key)) continue;
    let value = ids.map(id => byId(id).value).join(',');
    if (key === 'owner.info') value = value.replace(/\n/g, '|');
    if (value !== repeaterView.settings[key]) changes[key] = value;
  }
  collectExtraChanges(repeaterView, changes);
  return changes;
}

function repeaterButtons() {
  const selected = byId('repeaterDevice').value;
  const hasDevice = deviceInventory.some(device => device.serial && String(device.id) === selected);
  byId('repeaterDevice').disabled = !deviceInventory.some(device => device.serial) || busy || repeaterBusy;
  byId('readRepeater').disabled = !hasDevice || busy || repeaterBusy;
  byId('repeaterFields').disabled = repeaterBusy;
  byId('repeaterForm').hidden = String(repeaterView?.device) !== selected;
  const dirty = Object.keys(repeaterChanges()).length > 0;
  byId('saveRepeater').disabled = busy || repeaterBusy || byId('repeaterForm').hidden || !dirty;
  byId('repeaterUnsaved').hidden = !dirty || repeaterBusy;
}

function renderRepeaterDevices() {
  const select = byId('repeaterDevice');
  const previous = select.value;
  const serialDevices = deviceInventory.filter(device => device.serial);
  select.replaceChildren();
  if (serialDevices.length !== 1) {
    addOption(select, '', serialDevices.length ? 'Choose a USB radio' : 'No USB radio detected');
  }
  for (const device of serialDevices) {
    addOption(select, String(device.id), device.name + ' · ' + device.code);
  }
  if (serialDevices.some(device => String(device.id) === previous)) select.value = previous;
  if (repeaterView && String(repeaterView.device) !== select.value) {
    repeaterView = null;
    clearConfigSecrets();
    byId('repeaterStatus').textContent = 'The selected radio disconnected.';
  }
}

window.onRepeater = (view, message, keepEdits) => {
  repeaterBusy = false;
  configWorking = false;
  const reading = repeaterView === null;
  repeaterView = view && String(view.device) === byId('repeaterDevice').value ? view : null;
  byId('repeaterStatus').textContent = !repeaterView || reading ? message : '';
  byId('repeaterSaveStatus').textContent = repeaterView && !reading ? message : '';
  if (repeaterView && !keepEdits) {
    const entries = Object.entries(repeaterFields);
    for (const [index, [key, ids]] of entries.entries()) {
      const value = repeaterView.settings[key];
      byId(repeaterGroups[index]).hidden = value === undefined;
      const parts = key === 'radio' ? value?.split(',') : [value];
      ids.forEach((id, part) => {
        byId(id).disabled = value === undefined;
        const partValue = parts?.[part] ?? '';
        byId(id).value = key === 'owner.info' ? partValue.replace(/\|/g, '\n') : partValue;
      });
    }
    showExtraConfig(repeaterView);
    byId('repeaterVersion').textContent = [byId('configRole').textContent, repeaterView.version].filter(Boolean).join(' · ');
  }
  buttons();
};
window.onRepeaterSaving = () => {
  byId('repeaterSaveStatus').textContent = 'Saving and verifying settings…';
  configWorking = true;
  updateMotion();
};
window.onRepeaterReading = () => {
  configWorking = true;
  updateMotion();
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
  const device = selectedDevice();
  const select = byId('role');
  select.replaceChildren();
  const selectedSource = source();
  const roles = selectedSource === 'save' ? [{id: 'repeater', title: 'Repeater'}] :
    selectedSource === 'solo' && choice ? [{id: 'solo', title: 'Solo companion'}] : choice?.roles ?? [];
  for (const role of roles) addOption(select, role.id, role.title);
  select.disabled = roles.length === 0;
  const detectedRole = device?.board === choice?.name ? device?.reportedRole : '';
  const selectedRole = preferredRole || detectedRole;
  if (roles.some(role => role.id === selectedRole)) select.value = selectedRole;
  loadVersions();
}

function matchesSelectedFirmware(release) {
  if (release.exportOnly === false) return true;
  const board = catalogue.find(item => item.name === byId('board').value);
  const role = board?.roles?.find(item => item.id === byId('role').value);
  if (!board || !role) return false;
  return libraryTargets(release).some(target =>
    target.device === board.name + ' (' + board.type + ')' && target.role === role.title);
}

function sourceIsAlpha() {
  return repositoryLinks.some(link => !link.bootloader && link.alpha && (link.source || link.url) === source());
}

function releaseChannel(release, repositoryAlpha = false) {
  if (!release) return '';
  const tag = release.tag || '';
  for (const [pattern, label] of [
    [/(?:^|[-._])alpha(?=$|[-._+0-9])/i, 'Alpha'],
    [/(?:^|[-._])beta(?=$|[-._+0-9])/i, 'Beta'],
    [/(?:^|[-._])rc(?=$|[-._+0-9])/i, 'Release candidate']
  ]) {
    if (pattern.test(tag)) return label;
  }
  return repositoryAlpha ? 'Alpha' : release.prerelease === true ? 'Prerelease' : '';
}

function earlyFirmwareOnly(files) {
  const firmware = files.filter(file => file.downloadable !== false && /\.(?:bin|uf2|zip|hex)$/i.test(file.name || ''));
  return firmware.length > 0 && firmware.every(file => releaseChannel(file));
}

function mergedBinary(release) {
  return release.types?.includes('merged') || /[-_.]merged\.bin$/i.test(release.name || '');
}

function flashableRelease(release) {
  if (mergedBinary(release)) return false;
  if (isDfuSelection(release)) return true;
  if (source().startsWith('https://')) {
    return cachedM5?.board === byId('board').value && release.digest === 'sha256:' + cachedM5.sha256;
  }
  return release.exportOnly !== true &&
    (release.direct === true || Boolean(release.appTag) || release.types?.includes('flash-update'));
}

function renderFirmwareChoices(releases, preferred = selectedRelease()) {
  versionList = [...releases];
  for (const image of [cachedM5, cachedExport]) {
    if (!matchesSelection(image) || versionList.some(release => matchesRelease(image, release))) continue;
    versionList.push({...image, cached: image.sha256, types: [], version: image.tag});
  }
  const select = byId('version');
  const alpha = sourceIsAlpha();
  select.replaceChildren();
  if (!versionList.length) addOption(select, '', 'No firmware selected');
  for (const [index, release] of versionList.entries()) {
    const formats = mergedBinary(release) ? ['Merged BIN (full install)'] : (release.types ?? []).map(type => ({
      'flash-update': 'App update', uf2: 'UF2', bin: 'App BIN',
      zip: 'DFU ZIP', hex: 'HEX', checksums: 'Checksums'
    })[type] || type);
    const name = release.name || release.version;
    const version = release.name && release.version &&
      !release.name.toLowerCase().includes(release.version.toLowerCase()) ? release.version : '';
    const label = [releaseChannel(release, alpha), name, version, formats.join(', '), release.cached ? 'Downloaded' : ''].filter(Boolean).join(' · ');
    addOption(select, String(index), label);
  }
  const previous = preferred && versionList.findIndex(release =>
    release.tag === preferred.tag && release.name === preferred.name && release.id === preferred.id);
  if (previous >= 0 && preferred) select.value = String(previous);
  else {
    const matching = release => !mergedBinary(release) && matchesSelectedFirmware(release);
    for (const eligible of [flashableRelease, matching, release => !mergedBinary(release)]) {
      const stable = versionList.findIndex(release => eligible(release) && !releaseChannel(release, alpha));
      const index = stable >= 0 ? stable : versionList.findIndex(eligible);
      if (index < 0) continue;
      select.value = String(index);
      break;
    }
  }
  select.disabled = !versionList.length;
}

function loadVersions(refresh = false) {
  const current = source();
  const request = ++versionRequest;
  const board = byId('board').value;
  const role = byId('role').value;
  const generic = current.startsWith('https://');
  const ready = current !== 'local';
  versionsLoading = Boolean(ready);
  const previous = refresh ? selectedRelease() : null;
  versionPreference = previous;
  renderFirmwareChoices([], previous);
  byId('versionStatus').textContent = ready ? 'Loading firmware releases…' :
    current === 'local' ? '' : 'Select a board and firmware role.';
  buttons();
  if (!ready) return;
  if (generic) {
    const id = ++repositoryRequest;
    repositoryRequests.set(id, {kind: 'firmware', url: current, versionRequest: request, previous});
    Phone.repositoryAssets(id, current, refresh);
  } else {
    Phone.versions(request, board, role, current);
  }
}

window.onVersions = (request, versions, error) => {
  if (request !== versionRequest) return;
  versionsLoading = false;
  renderFirmwareChoices(versions ?? [], versionPreference);
  versionPreference = null;
  byId('versionStatus').textContent = error || (!versionList.length ?
    'No firmware for the selected board and role.' : '');
  buttons();
  refreshLog();
};
window.onFlashDownload = () => {
  setStageState(byId('stagePrepare'), '');
  flashStage = '';
  byId('stageDownload').hidden = false;
  progressStage('stageDownload', 'Downloading and verifying firmware…');
};
window.onFlashPrepared = message => {
  if (activePage === 'progress') progressStage('stagePrepare', message);
  else byId('job').textContent = message;
};
function completeFlashProgress(success, message, outcome) {
  busy = false;
  buttons();
  byId('job').textContent = message;
  if (activePage === 'progress') {
    const active = flashStage ? byId(flashStage) : null;
    if (active) setStageState(active, success ? 'done' : 'failed');
    byId('progressCard').setAttribute('data-outcome', success ? 'success' : 'failure');
    byId('progressOutcome').textContent = outcome || (success ? 'Flash complete' : 'Flash failed');
    byId('progressOutcome').hidden = false;
    byId('progressStatus').textContent = message;
    byId('forgeScene').removeAttribute?.('data-working');
    byId('progressNotice').hidden = true;
  }
  refreshLog();
}
window.onComplete = completeFlashProgress;
window.onFlashCancelled = () => {
  busy = false;
  setMotion('forgeScene', false);
  buttons();
  showPage('flash');
  byId('flash').focus?.({preventScroll: true});
};
function finishDownload(message) {
  downloading = false;
  downloadName = '';
  downloadScope = '';
  downloadResult = message;
  byId('downloadStatus').textContent = message;
  buttons();
  drawLibrary();
}

window.onDownload = (image, error, restored = false) => {
  if (image) {
    cachedM5 = {board: directUpdateBoard, role: 'companionBle', ...image,
      source: image.source === 'save' ? 'save' : 'official'};
    renderFirmwareChoices(versionList.filter(release => !release.cached));
  }
  if (!restored && downloadScope === 'firmware') {
    finishDownload(error || 'Firmware downloaded and verified.');
  }
  else { buttons(); drawLibrary(); }
  refreshLog();
};
window.onCachedExport = (image, error, restored = false) => {
  cachedExport = image;
  renderFirmwareChoices(versionList.filter(release => !release.cached));
  if (!restored && downloadScope === 'firmware') {
    finishDownload('Firmware downloaded and verified.');
  }
  else { buttons(); drawLibrary(); }
  refreshLog();
};
window.onAssetStatus = message => {
  if (activePage === 'library') byId('libraryStatus').textContent = message;
  else byId('otaJob').textContent = message;
  refreshLog();
};
window.onDfuTransfer = (message, complete = false, success = false) => {
  dfuWorkflowStatus = message;
  byId('dfuStatus').textContent = message;
  if (complete) {
    if (success && activePage === 'progress') progressStage('stageWrite', message);
    completeFlashProgress(success, message, success ? 'UF2 sent' : '');
    return;
  }
  busy = true;
  buttons();
  if (activePage === 'progress') {
    const status = message.toLowerCase();
    const stage = status.startsWith('downloading') ? 'stageDownload' :
      status.startsWith('writing') || status.includes('and writing') ? 'stageWrite' : 'stagePrepare';
    if (stage === 'stageDownload') {
      byId('stageDownload').hidden = false;
      setStageState(byId('stagePrepare'), '');
      flashStage = '';
    }
    const percent = status.match(/(\d+)%/);
    if (stage === 'stageWrite' && percent) {
      byId('progress').value = Number(percent[1]);
      byId('progressPercent').textContent = percent[0];
    }
    progressStage(stage, message);
  }
  refreshLog();
};
window.onLocalFirmware = (image, error, picked = true) => {
  localFirmware = image;
  if (image && picked) {
    byId('repository').value = 'local';
    showRoles();
  }
  if (!image) {
    byId('localStatus').textContent = picked ? error || '' : '';
    buttons();
    drawLibrary();
    return;
  }
  const status = image.route === 'remote' ? 'Matches a verified image for the selected board.' :
    image.route === 'uf2' ? 'Fits the selected board’s UF2 application layout.' :
    'Available for inspection. The app could not verify a direct flash path.';
  byId('localStatus').textContent = image.name + ' · ' + image.size + ' bytes · ' + status +
    ' SHA-256 ' + image.sha256;
  buttons();
  drawLibrary();
  refreshLog();
};

function showPage(page) {
  if (page === activePage) return;
  if (activePage === 'configurator') clearConfigSecrets();
  pageScroll.set(activePage, window.scrollY ?? 0);
  activePage = page;
  const current = pages[page];
  for (const [key, {panel, button, parent}] of Object.entries(pages)) {
    byId(panel).hidden = key !== page;
    if (button && !parent) byId(button).setAttribute('aria-current', key === page ? 'page' : 'false');
  }
  byId('homeHeader').hidden = page !== 'configurator';
  byId('primaryNav').hidden = Boolean(current.parent);
  byId('parentBack').hidden = !current.parent;
  if (current.parent) byId('parentBack').setAttribute('aria-label', 'Back to ' + pages[current.parent].title);
  byId('pageTitle').textContent = current.title;
  if (page === 'library') requestLibraryRepositories();
  if (page === 'settings') refreshLog();
  if (page === 'flash') requestOtaRepositories();
  updateMotion();
  byId('pageTitle').focus?.({preventScroll: true});
  window.scrollTo?.(0, page === 'progress' ? 0 : pageScroll.get(page) ?? 0);
}

window.goBack = () => {
  if (busy || repeaterBusy) return true;
  if (activePage === 'configurator') return false;
  const parent = pages[activePage].parent;
  const button = pages[activePage].button;
  showPage(parent ?? 'configurator');
  if (parent && button) byId(button).focus?.({preventScroll: true});
  return true;
};

function libraryFirmwareType(file) {
  const names = typeof file === 'string' ? [file] : [file?.name, file?.releaseName, file?.tag];
  for (const name of names) {
    const filename = '_' + String(name || '').toLowerCase().replace(/[^a-z0-9]+/g, '_') + '_';
    if (/_room_?server_/.test(filename)) return 'Room Server';
    if (/_repeaters?_/.test(filename)) return 'Repeater';
    if (/_kiss_|_radio_modem_/.test(filename)) return 'KISS Radio Modem';
    if (/_ripple(?:_gui)?_|_gui_/.test(filename)) return 'Ripple GUI';
    if (/_client_|_companion_|_radio_(?:ble|usb)_/.test(filename)) return 'Client';
  }
  return '';
}

function libraryFirmwareVersion(name) {
  return String(name || '').match(/(?:^|[^a-z0-9])(v\d+\.\d+\.\d+)(?=$|[^a-z0-9])/i)?.[1].toLowerCase() || '';
}

function libraryTargets(file) {
  const targets = [];
  const typeNames = {companion: 'Client', repeater: 'Repeater', 'room-server': 'Room Server'};
  const filenameType = libraryFirmwareType(file);
  for (const board of catalogue) {
    for (const role of board.roles ?? []) {
      const rules = Object.values(role.release?.files ?? {});
      if (rules.some(rule => {
        try { return new RegExp('^(?:' + rule + ')$', 'i').test(file.name || ''); }
        catch { return false; }
      })) {
        const type = filenameType || typeNames[role.release?.type] || role.title || role.release?.type ||
          'Unclassified firmware type';
        const target = {device: board.name + ' (' + board.type + ')', type, role: role.title || ''};
        if (!targets.some(item => item.device === target.device && item.type === target.type && item.role === target.role)) {
          targets.push(target);
        }
      }
    }
  }
  return targets;
}

function drawLibrary() {
  const list = byId('libraryResults');
  list.replaceChildren();
  const search = (byId('librarySearch').value || '').trim().toLowerCase();
  const listedDigests = new Set();
  const remoteFiles = [];
  for (const link of repositoryLinks.filter(item => !item.bootloader)) {
    for (const asset of libraryRepositoryFiles.get(link.url)?.files ?? []) {
      const sha = asset.digest?.startsWith('sha256:') ? asset.digest.slice(7) : '';
      const saved = sha ? repositoryDownloads.find(file => file.sha256 === sha) :
        repositoryDownloads.find(file => !file.local && file.name === asset.name && file.tag === asset.tag &&
          file.sourceLabel === asset.sourceLabel);
      if (saved && !saved.local) listedDigests.add(saved.sha256);
      remoteFiles.push({...asset, repository: asset.sourceLabel, url: link.url,
        sha256: saved?.sha256 || '', kind: saved ? 'repository' : 'remote'});
    }
  }
  const files = [
    ...(cachedM5 ? [{...cachedM5, kind: 'direct'}] : []),
    ...(cachedExport ? [{...cachedExport, kind: 'cached'}] : []),
    ...remoteFiles,
    ...repositoryDownloads.filter(file => file.local || !listedDigests.has(file.sha256))
      .map(file => ({...file, kind: file.local ? 'addedLocal' : 'repository'})),
    ...(localFirmware ? [{...localFirmware, kind: 'deviceLocal'}] : [])
  ];
  const groups = new Map();
  let visibleFiles = 0;
  for (const link of repositoryLinks.filter(item => !item.bootloader)) {
    const origin = repositoryName(link.url);
    const sourceKey = canonicalRepositories[origin.toLowerCase()] || link.source;
    const repository = firmwareSourceNames[sourceKey] || origin;
    groups.set(origin.toLowerCase(), {key: origin.toLowerCase(), repository, origin, url: link.url, versions: new Map()});
  }
  for (const file of files) {
    const origin = file.repository || (file.sourceLabel === 'Repository download' ? '' : file.sourceLabel) || '';
    const sourceKey = canonicalRepositories[origin.toLowerCase()] || file.source;
    const local = ['addedLocal', 'deviceLocal'].includes(file.kind);
    const link = file.url ? repositoryLinks.find(item => item.url === file.url) :
      repositoryLinks.find(item => !item.bootloader && item.source === sourceKey);
    const url = file.url || link?.url || '';
    const repository = local ? 'Local files' : firmwareSourceNames[sourceKey] || origin || 'Unknown repository';
    const key = local ? 'local files' : (url ? repositoryName(url) : origin || repository).toLowerCase();
    const version = libraryFirmwareVersion(file.name) ||
      (file.kind === 'addedLocal' ? 'Added locally' : file.tag || file.version || '');
    const targets = libraryTargets(file);
    const format = (file.name || '').split('.').pop().toUpperCase();
    const filenameType = libraryFirmwareType(file);
    const targetSearch = [...targets.flatMap(target => [target.device, target.type, target.role]), filenameType];
    if (search && ![file.name, file.tag, version, file.releaseName, repository, origin, format, ...targetSearch]
      .join(' ').toLowerCase().includes(search)) continue;
    visibleFiles++;
    if (!groups.has(key)) groups.set(key, {key, repository, origin, url, versions: new Map()});
    const group = groups.get(key);
    if (!group.versions.has(version)) group.versions.set(version, []);
    const entries = group.versions.get(version);
    const digest = file.sha256 || file.digest?.replace(/^sha256:/, '');
    const existing = entries.findIndex(other => {
      const otherDigest = other.sha256 || other.digest?.replace(/^sha256:/, '');
      return file.name === other.name && (digest && otherDigest ? digest === otherDigest :
        file.id && other.id ? file.id === other.id : file.tag === other.tag);
    });
    if (existing < 0) entries.push(file);
    else if (entries[existing].kind === 'remote' && file.kind !== 'remote') entries[existing] = file;
  }
  for (const {key, repository, url, versions} of groups.values()) {
    const ordered = [...versions].sort(([a], [b]) => b.localeCompare(a, undefined, {numeric: true}));
    if (search && !ordered.length) continue;
    const root = repositoryElement('details', '', 'library-repository');
    libraryDisclosure(root, 'repository:' + key, true, Boolean(search));
    const state = libraryRepositoryFiles.get(url);
    const alpha = repositoryLinks.some(link => link.url === url && link.alpha);
    const title = alpha ? repository + ' · Alpha' : earlyFirmwareOnly(state?.files ?? []) ?
      repository + ' · Prerelease firmware only' : repository;
    root.append(repositoryElement('summary', title, 'library-repository-title'));
    for (const [index, [version, entries]] of ordered.entries()) {
      const versionRow = repositoryElement('details', '', 'library-version');
      libraryDisclosure(versionRow, 'version:' + key + ':' + version, index === 0, Boolean(search));
      versionRow.append(repositoryElement('summary', version || 'Unknown version'));
      const rows = repositoryElement('ul', '', 'library-version-files');
      for (const file of entries) rows.append(drawLibraryFile(file, alpha));
      versionRow.append(rows);
      root.append(versionRow);
    }
    if (!ordered.length && url && !search) {
      const message = state?.error || (state?.loading || !state ? 'Loading releases…' :
        search ? 'No firmware matches this search.' : 'No release assets in the published releases.');
      root.append(repositoryElement('p', message, 'library-message'));
    }
    list.append(root);
  }
  const repositoryStates = repositoryLinks.filter(link => !link.bootloader)
    .map(link => libraryRepositoryFiles.get(link.url));
  const loadingRepositories = repositoryStates.some(state => !state || state.loading);
  const repositoryError = repositoryStates.find(state => state?.error)?.error;
  byId('libraryStatus').textContent = downloading ? (downloadChecksumPublished ?
    'Downloading and verifying ' + downloadName + '…' : 'Downloading ' + downloadName + ' (no published checksum)…') :
    downloadResult || repositoryError || (loadingRepositories ? 'Loading firmware repository listings…' :
      search && !visibleFiles ? 'No firmware matches your search.' : groups.size ? '' :
      'No firmware listed yet. Refresh repositories or add a local firmware file.');
}

function drawLibraryFile(file, alpha) {
  const row = repositoryElement('li', '', 'library-entry');
  row.append(repositoryElement('span', file.name || file.tag, 'library-filename'));
  const details = file.kind !== 'remote' ? 'Available offline' :
    file.downloadable === false ? 'Download unavailable' : file.digest ? '' : 'No published checksum';
  row.append(repositoryElement('span', [releaseChannel(file, alpha), details].filter(Boolean).join(' · '), 'library-metadata'));
  const actions = repositoryElement('div', '', 'actions');
  const exportFile = file.kind === 'remote' ? null : file.kind === 'repository' || file.kind === 'addedLocal' ?
    () => Phone.saveRepositoryAsset(file.name, file.sha256, file.size) :
    file.kind === 'deviceLocal' ? file.route === 'uf2' ? () => Phone.saveLocal(file.board, file.sha256) : null :
    () => Phone.exportCachedFirmware(file.sha256, file.kind === 'direct');
  if (exportFile) {
    const button = repositoryAction('Export', exportFile);
    button.setAttribute('aria-label', 'Export ' + file.name);
    actions.append(button);
  }
  if (file.kind === 'remote') {
    const download = repositoryAction('Download', () => fetchRepositoryAsset(file, file.url, 'library'));
    download.disabled = downloading || !file.id || !file.tag || file.downloadable === false;
    download.setAttribute('aria-label', 'Download ' + file.name);
    actions.append(download);
  }
  if (file.kind === 'repository' || file.kind === 'addedLocal') {
    const remove = repositoryAction('Remove', () => {
      if (!window.confirm('Remove this offline copy?')) return;
      const id = ++repositoryRequest;
      repositoryRequests.set(id, {kind: 'remove'});
      Phone.removeRepositoryAsset(id, file.sha256);
    });
    remove.setAttribute('aria-label', 'Remove download ' + file.name);
    actions.append(remove);
  }
  row.append(actions);
  return row;
}

function requestLibraryRepositories(refresh = false) {
  const links = repositoryLinks.filter(link => !link.bootloader);
  for (const [url] of libraryRepositoryFiles) {
    if (!links.some(link => link.url === url)) libraryRepositoryFiles.delete(url);
  }
  for (const link of links) {
    const previous = libraryRepositoryFiles.get(link.url);
    if (previous?.loading && !refresh) continue;
    libraryRepositoryFiles.set(link.url, {files: previous?.files ?? [], loading: true, error: ''});
    const id = ++repositoryRequest;
    repositoryRequests.set(id, {kind: 'library', url: link.url});
    Phone.repositoryAssets(id, link.url, refresh);
  }
  drawLibrary();
}

function setStageState(stage, state) {
  stage.setAttribute('data-state', state);
  const label = stage.querySelector?.('span');
  if (label) label.textContent = ({done: '✓ Done', active: 'In progress', failed: 'Failed'})[state] || '';
}

function progressStage(id, status) {
  if (activePage !== 'progress') {
    byId('job').textContent = status;
    return;
  }
  if (flashStage && flashStage !== id) setStageState(byId(flashStage), 'done');
  flashStage = id;
  setStageState(byId(id), 'active');
  byId('progressStatus').textContent = status;
}

function repositoryElement(tag, text, className) {
  const element = document.createElement(tag);
  if (text) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function libraryDisclosure(element, key, initiallyOpen, searchOpen) {
  if (!libraryAccordionState.has(key)) libraryAccordionState.set(key, initiallyOpen);
  element.open = searchOpen || libraryAccordionState.get(key);
  if (!searchOpen) element.ontoggle = () => libraryAccordionState.set(key, element.open);
}

function repositoryAction(label, action, className) {
  const button = repositoryElement('button', label, className);
  button.type = 'button';
  button.onclick = action;
  return button;
}

function repositoryName(url) {
  return url.replace('https://github.com/', '').replace(/\/releases(?=$|\/)/, '');
}

window.onRepositoryStatus = message => { byId('repositoryStatus').textContent = message; };
function repositoryTitle(link) {
  const name = firmwareSourceNames[link.source] || repositoryName(link.url);
  return link.alpha && !link.bootloader ? name + ' · Alpha' : name;
}

window.onRepositoryMaturity = (url, alpha) => {
  const link = repositoryLinks.find(item => item.url === url);
  const labels = repositoryLabels.get(url);
  if (!link || !labels || Boolean(link.alpha) === alpha) return;
  link.alpha = alpha;
  const title = repositoryTitle(link);
  labels.heading.textContent = title;
  labels.remove.setAttribute('aria-label', 'Remove repository ' + title);
  if (labels.option) labels.option.textContent = title;
  if ((link.source || link.url) === source()) {
    renderFirmwareChoices(versionList.filter(release => !release.cached));
    buttons();
  }
  if (activePage === 'library') drawLibrary();
};

window.onRepositories = links => {
  const previous = source();
  const first = !repositoryLinks.length;
  repositoryLinks = links;
  repositoryLabels.clear();
  const list = byId('repositoryList');
  list.replaceChildren();
  const selector = byId('repository');
  selector.replaceChildren();
  for (const link of links) {
    const title = repositoryTitle(link);
    const row = repositoryElement('li', '', 'repository-setting');
    const heading = repositoryElement('strong', title);
    row.append(heading);
    row.append(repositoryElement('p', link.bootloader ? 'OTAFix' : 'Firmware', 'field-note'));
    const remove = repositoryAction('Remove', () => Phone.removeRepository(link.url));
    remove.setAttribute('aria-label', 'Remove repository ' + title);
    row.append(remove);
    list.append(row);
    if (!link.bootloader) addOption(selector, link.source || link.url, title);
    repositoryLabels.set(link.url, {heading, remove, option: !link.bootloader ? selector.options[selector.options.length - 1] : null});
  }
  for (const url of otaReleases.keys()) {
    if (!links.some(link => link.bootloader && link.url === url)) otaReleases.delete(url);
  }
  addOption(selector, 'local', 'Local file');
  if (Array.from(selector.options).some(option => option.value === previous) && !(first && previous === 'local' && !localFirmware)) {
    selector.value = previous;
  }
  if (previous !== source()) {
    renderSelectedDevice(true);
  } else {
    buttons();
  }
  requestOtaRepositories();
  if (activePage === 'library') requestLibraryRepositories();
};

window.onRepositoryInventory = files => {
  repositoryDownloads = files;
  buttons();
  drawLibrary();
};

window.onRepositoryAssets = (id, result, error) => {
  const request = repositoryRequests.get(id);
  repositoryRequests.delete(id);
  if (!request) return;
  if (request.kind === 'ota') {
    if (!repositoryLinks.some(link => link.bootloader && link.url === request.url)) return;
    otaReleases.set(request.url, {files: result?.files ?? otaReleases.get(request.url)?.files ?? [], error});
    buttons();
    return;
  }
  if (request.kind === 'library') {
    if (!repositoryLinks.some(link => !link.bootloader && link.url === request.url)) return;
    const previous = libraryRepositoryFiles.get(request.url);
    libraryRepositoryFiles.set(request.url, {files: result?.files ?? previous?.files ?? [],
      loading: false, error: error || '', notice: result?.notice || ''});
    drawLibrary();
    return;
  }
  if (request.versionRequest !== versionRequest || request.url !== source()) return;
  versionsLoading = false;
  const files = result?.files ?? [];
  renderFirmwareChoices(files, request.previous);
  byId('versionStatus').textContent = error || result?.notice || (!files.length ?
    'No published release files in this repository.' : earlyFirmwareOnly(files) ?
    'This repository currently offers prerelease firmware only.' : '');
  buttons();
};

function beginDownload(file, scope) {
  downloading = true;
  downloadName = file.name || file.version;
  downloadChecksumPublished = scope !== 'library' || Boolean(file.digest);
  downloadScope = scope;
  downloadResult = '';
  byId('downloadStatus').textContent = scope === 'ota' ? '' : scope !== 'library' || file.digest ?
    'Downloading and verifying…' : 'Downloading; no published checksum is available…';
  buttons();
  drawLibrary();
}

function fetchRepositoryAsset(file, url, scope) {
  if (busy || downloading || !file.id || !file.tag) return;
  const id = ++repositoryRequest;
  repositoryRequests.set(id, {kind: scope});
  beginDownload(file, scope);
  Phone.downloadRepositoryAsset(id, url, file.tag, String(file.id), file.digest);
}

window.onRepositoryDownload = (id, image, error) => {
  const request = repositoryRequests.get(id);
  repositoryRequests.delete(id);
  if (!request) return;
  const message = error || (request.kind === 'ota' ? 'OTAFix downloaded. Export the package to install it.' :
    image?.checksumPublished ? 'Firmware downloaded and verified against the published SHA-256.' :
      'Firmware downloaded; SHA-256 calculated locally because the repository did not publish a checksum.');
  finishDownload(message);
  if (request.kind === 'ota') {
    byId('otaJob').textContent = message;
    byId('downloadStatus').textContent = '';
  }
};

window.onRepositoryRemoval = (id, error) => {
  if (!repositoryRequests.delete(id)) return;
  byId('libraryStatus').textContent = error || 'Download removed.';
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
  constructor({usbVendorId = 0x1a86, usbProductId = 0x7522} = {}) {
    this.info = {usbVendorId, usbProductId};
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
  getInfo() { return this.info; }
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
  note('flash.algorithm', 'Preflight start board=' + image.board + ' imageBytes=' + image.size +
    ' partitionLength=' + image.partitionLength);
  note('boot', 'Resetting target and starting ESP32-S3 ROM synchronization');
  await loader.connect('default_reset', 3);
  note('boot', 'ROM synchronization complete chipImageId=' + loader.chip?.IMAGE_CHIP_ID);
  if (loader.chip?.IMAGE_CHIP_ID !== 9) throw new Error('Expected an ESP32-S3.');
  // ESP32-S3 eFuse read addresses and fields from Espressif esptool.
  note('preflight', 'Reading secure-boot and encryption eFuses');
  const cryptCount = (await loader.readReg(0x60007034) >>> 18) & 7;
  const secureBoot = !!((await loader.readReg(0x60007038)) & (1 << 20));
  const encrypted = ((cryptCount ^ (cryptCount >>> 1) ^ (cryptCount >>> 2)) & 1) !== 0;
  note('security', 'ESP32-S3 cryptCount=' + cryptCount + ' secureBoot=' + secureBoot + ' flashEncrypted=' + encrypted);
  if (secureBoot || encrypted) throw new Error('This target has protected flash.');
  if (loader.chip.postConnect) {
    note('preflight', 'Running chip-specific post-connect initialization');
    await loader.chip.postConnect(loader);
  }
  note('preflight', 'Uploading RAM stub');
  await loader.runStub();
  note('preflight', 'RAM stub ready; reading flash JEDEC ID');
  const jedec = await loader.readFlashId();
  note('preflight', 'JEDEC=0x' + jedec.toString(16));
  if (((jedec >>> 16) & 255) !== 0x16) throw new Error('Expected 4 MiB of target flash.');
  note('preflight', 'Reading partition table at 0x8000 length=' + image.partitionLength);
  const partitions = await loader.flashMd5sum(0x8000, image.partitionLength);
  note('layout', 'partitionTableMD5=' + partitions + ' expected=' + image.partitionMd5);
  if (partitions !== image.partitionMd5) throw new Error('The flash partition layout does not match the selected board.');
  note('preflight', 'Reading OTA metadata at 0xe000 length=8192');
  const ota = await loader.flashMd5sum(0xe000, 0x2000);
  note('layout', 'otaMetadataMD5=' + ota);
  if (ota !== image.otaSeedMd5 && ota !== image.otaEmptyMd5) {
    throw new Error('Could not confirm that the target boots from the approved application slot.');
  }
  note('flash.algorithm', 'Preflight passed; application-only write is eligible');
}

async function writeImage(loader, image, data) {
  const address = 0x10000;
  const blockSize = loader.FLASH_WRITE_SIZE;
  if (!blockSize || Math.ceil(data.length / blockSize) * blockSize > 0x140000) {
    throw new Error('Padded write would exceed the application slot.');
  }
  const blockCount = Math.ceil(data.length / blockSize);
  note('write', 'Begin app-only write address=0x10000 size=' + data.length + ' block=' + blockSize +
    ' blockCount=' + blockCount + ' paddedBytes=' + (blockCount * blockSize));
  progressStage('stageWrite', 'Writing firmware…');
  note('write', 'Sending FLASH_BEGIN size=' + data.length + ' address=0x' + address.toString(16));
  await loader.flashBegin(data.length, address);
  note('write', 'FLASH_BEGIN acknowledged');
  for (let offset = 0, sequence = 0; offset < data.length; offset += blockSize, sequence++) {
    const block = new Uint8Array(blockSize).fill(255);
    block.set(data.subarray(offset, offset + blockSize));
    const payloadBytes = Math.min(blockSize, data.length - offset);
    note('write.block', 'Sending sequence=' + sequence + ' offset=' + offset + ' dataBytes=' + payloadBytes +
      ' paddedBytes=' + blockSize);
    await loader.flashBlock(block, sequence, 8000);
    const written = Math.min(data.length, offset + blockSize);
    const percentage = Math.floor(written * 100 / data.length);
    note('write.block', 'Acknowledged sequence=' + sequence + ' confirmedBytes=' + written + ' percent=' + percentage);
    byId('progress').value = percentage;
    byId('progressPercent').textContent = percentage + '%';
    byId('progressStatus').textContent = 'Writing firmware · ' + written + ' / ' + data.length + ' bytes';
  }
  note('verify', 'Starting device-side MD5 over address=0x' + address.toString(16) + ' bytes=' + data.length);
  progressStage('stageVerify', 'Verifying the written application…');
  const observed = await loader.flashMd5sum(address, data.length);
  note('verify', 'observedMD5=' + observed + ' expectedMD5=' + image.md5);
  if (observed !== image.md5) throw new Error('Flash digest mismatch.');
  note('verify', 'Device-side MD5 matches selected image');
  note('write', 'Sending FLASH_END without reboot before explicit reset');
  await loader.flashFinish(false);
  note('write', 'FLASH_END acknowledged');
}

window.startFlash = async (id, image, probeOnly) => {
  let serial;
  let loader;
  let writing = false;
  let verified = false;
  let success = false;
  let result;
  const started = performance.now();
  note('flash.algorithm', 'Flash entry deviceId=' + id + ' board=' + image.board + ' probeOnly=' + probeOnly +
    ' image=' + (image.name || image.tag || 'layout probe') + ' bytes=' + image.size);
  if (!probeOnly) showPage('progress');
  if (probeOnly) flashStage = '';
  byId('stageWrite').hidden = probeOnly;
  byId('stageVerify').hidden = probeOnly;
  byId('progressTarget').textContent = image.board || 'Connected radio';
  byId('progressImage').textContent = probeOnly ? 'Bootloader layout check' :
    [image.name, image.tag].filter(Boolean).join(' · ') || 'Selected application image';
  byId('progress').removeAttribute?.('value');
  byId('progressPercent').textContent = '';
  byId('progressNotice').hidden = false;
  if (!probeOnly) byId('forgeScene').setAttribute('data-working', '');
  progressStage('stagePrepare', 'Checking chip, flash and partition layout…');
  try {
    note('serial', 'Opening approved Android USB serial port');
    const error = Phone.connect(id);
    if (error) throw new Error(error);
    note('serial', 'Android USB serial port open');
    serial = new AndroidSerialPort(image);
    note('loader', 'Creating esptool transport and ESP loader at 115200 baud');
    const transport = new esptool.Transport(serial, false);
    const terminal = {clean() {}, write: message => note('loader', message), writeLine: message => note('loader', message)};
    loader = new esptool.ESPLoader({transport, baudrate: 115200, terminal});
    logCommands(loader);
    await preflight(loader, image);
    if (!probeOnly) {
      note('image', 'Reading exact verified staged image from app storage');
      const bytes = Uint8Array.from(atob(Phone.image()), byte => byte.charCodeAt(0));
      note('image', 'Staged image decoded bytes=' + bytes.length + ' expectedBytes=' + image.size);
      if (bytes.length !== image.size) throw new Error('The approved staged image is unavailable.');
      writing = true;
      note('flash.algorithm', 'Write phase started');
      await writeImage(loader, image, bytes);
      verified = true;
      note('flash.algorithm', 'Write phase and checksum verification passed');
    }
    progressStage('stageReset', 'Resetting device…');
    note('reset', 'Sending hard reset');
    await loader.after('hard_reset');
    note('reset', 'Hard reset command completed');
    result = probeOnly ? 'Probe passed. Chip and layout match. Reset sent.' :
      'Flash checksum verified. Reset sent. Confirm the radio boots into MeshCore.';
    success = true;
  } catch (error) {
    note('flash.algorithm', 'Exception caught writing=' + writing + ' verified=' + verified +
      ' type=' + error.name + ' message=' + error.message);
    note('job.error', error.stack || error.message);
    result = verified ? 'Flash checksum verified, but reset failed. ' + error.message :
      writing ? 'Update interrupted. The radio may need recovery. ' + error.message : error.message;
    if (!writing && loader && !Phone.portError()) {
      try {
        note('reset', 'Attempting recovery reset because no flash write was started');
        await loader.after('hard_reset');
      } catch (resetError) { note('reset.error', resetError.message); }
    }
  } finally {
    note('flash.algorithm', 'Cleanup start success=' + success + ' elapsedMs=' + Math.round(performance.now() - started));
    if (serial) {
      note('serial', 'Closing Android USB serial transport');
      await serial.close();
    }
    note('flash.outcome', 'success=' + success + ' elapsedMs=' + Math.round(performance.now() - started) +
      ' result=' + result);
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
  if (busy || downloading) return;
  const device = selectedDevice();
  const release = selectedRelease();
  const issues = flashRequirements(device, byId('board').value, release);
  byId('job').textContent = '';
  flashReviewAttempted = true;
  renderFlashValidation(issues);
  if (issues.length) return;
  const selection = prepareFlashSelection(device, release);
  if (isDfuSelection(release)) {
    dfuWorkflowStatus = 'Preparing the selected UF2…';
    openFlashProgress(selection, true);
    Phone.installDfu(JSON.stringify(selection));
    return;
  }
  openFlashProgress(selection);
  Phone.flash(JSON.stringify(selection));
};
byId('probe').onclick = () => {
  const device = selectedDevice();
  if (!device?.flashSerial || !device.allowed) return;
  busy = true;
  buttons();
  Phone.probeLayout(device.id);
};
byId('board').onchange = () => {
  dfuWorkflowStatus = '';
  manualBoard = byId('board').value;
  renderSelectedDevice(true);
  requestOtaRepositories();
};
byId('openOfficial').onclick = () => Phone.openOfficial(byId('board').value);
byId('viewRelease').onclick = () => {
  const release = selectedRelease();
  if (!byId('viewRelease').hidden && release) {
    Phone.openRepository(source().split('/tag/')[0] + '/tag/' + release.tag);
  }
};
byId('repository').onchange = () => {
  dfuWorkflowStatus = '';
  renderSelectedDevice(true);
};
byId('role').onchange = () => { dfuWorkflowStatus = ''; loadVersions(); };
byId('version').onchange = () => { dfuWorkflowStatus = ''; buttons(); };
byId('versions').onclick = () => {
  Phone.refreshRepositoryMaturity?.();
  loadVersions(true);
};
byId('fetch').onclick = () => {
  const release = selectedRelease();
  if (!release || byId('fetch').disabled) return;
  if (source().startsWith('https://')) {
    fetchRepositoryAsset(release, source(), 'firmware');
    return;
  }
  beginDownload(release, 'firmware');
  Phone.download(byId('board').value, byId('role').value, source(), release.tag, release.name);
};
byId('readBootloader').onclick = () => {
  const device = selectedDevice();
  if (device?.storage) Phone.readBootloader(device.id);
};
byId('otaPackage').onchange = () => {
  otaPackageKey = byId('otaPackage').value;
  buttons();
};
byId('findOtaFixes').onclick = () => requestOtaRepositories(true);
byId('downloadOta').onclick = () => {
  const choice = otaPackages.find(item => item.key === otaPackageKey);
  if (!choice || byId('downloadOta').disabled) return;
  if (!choice.url) {
    Phone.otaFix(selectedDevice()?.id ?? -1, byId('board').value, choice.name);
    return;
  }
  const saved = repositoryDownloads.find(file => 'sha256:' + file.sha256 === choice.digest);
  if (saved) Phone.saveRepositoryAsset(choice.name, saved.sha256, saved.size);
  else {
    byId('otaJob').textContent = 'Downloading and verifying OTAFix…';
    fetchRepositoryAsset(choice, choice.url, 'ota');
  }
};
for (const [page, {button}] of Object.entries(pages)) {
  if (button) byId(button).onclick = () => showPage(page);
}
byId('parentBack').onclick = window.goBack;
byId('repeaterDevice').onchange = () => {
  clearConfigSecrets();
  repeaterView = null;
  byId('repeaterStatus').textContent = '';
  repeaterButtons();
};
byId('readRepeater').onclick = () => {
  const id = byId('repeaterDevice').value;
  if (byId('readRepeater').disabled || !id) return;
  clearConfigSecrets();
  repeaterView = null;
  repeaterBusy = true;
  byId('repeaterStatus').textContent = 'Reading radio settings over USB…';
  buttons();
  Phone.readRepeater(Number(id));
};
byId('repeaterForm').oninput = event => {
  configCounters();
  if (event?.target && repeaterFields.radio.includes(event.target.id)) updatePresetSelection();
  repeaterButtons();
};
byId('repeaterForm').onsubmit = event => {
  event.preventDefault();
  if (byId('saveRepeater').disabled) return;
  const invalid = byId('repeaterFields').querySelector?.(':invalid');
  if (invalid) {
    const group = invalid.closest('details');
    if (group) group.open = true;
    invalid.reportValidity();
    return;
  }
  const changes = repeaterChanges();
  clearConfigSecrets();
  repeaterBusy = true;
  byId('repeaterSaveStatus').textContent = 'Waiting for confirmation…';
  buttons();
  Phone.saveRepeater(repeaterView.device, JSON.stringify(changes));
};
byId('librarySearch').oninput = drawLibrary;
byId('refreshLibrary').onclick = () => {
  Phone.refreshRepositoryMaturity?.();
  requestLibraryRepositories(true);
};
byId('addLibraryFirmware').onclick = () => Phone.pickLibraryFirmware();
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
if (typeof ResizeObserver !== 'undefined') {
  new ResizeObserver(() => document.documentElement.style.setProperty(
    '--navigation-height', byId('primaryNav').offsetHeight + 'px')).observe(byId('primaryNav'));
}
bindExtraConfig();
Phone.ready();
refreshLog();
