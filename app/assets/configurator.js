const configGroups = {
  configCoordinates: [
    ['lat', 'Latitude (°)', 'number', 'Positive north, negative south.', -90, 90, .00001],
    ['lon', 'Longitude (°)', 'number', 'Positive east, negative west.', -180, 180, .00001]
  ],
  configAccess: [
    ['password', 'New admin password', 'password', 'Leave blank to keep the current password.'],
    ['guest.password', 'New guest password', 'password', 'Leave blank to keep the current password, or choose Clear guest password.'],
    ['allow.read.only', 'Allow read-only room access', 'toggle', 'Lets people read the room with a blank password.']
  ],
  configFlood: [
    ['flood.max', 'Maximum flood hops', 'number', 'Limits how far the node forwards flooded packets.', 0, 64],
    ['flood.max.unscoped', 'Maximum unscoped flood hops', 'number', 'Limits flooded packets without a region.', 0, 64],
    ['flood.max.advert', 'Maximum announcement hops', 'number', 'Limits how far node announcements are forwarded.', 0, 64]
  ],
  configRadio: [
    ['tx', 'Transmit power (dBm)', 'number', 'LoRa chip power. An external amplifier can increase the transmitted power.', 1, 22],
    ['dutycycle', 'Duty cycle (%)', 'number', 'Limits how much time the radio can spend transmitting.', 1, 100, .1]
  ],
  configAdvanced: [
    ['loop.detect', 'Loop detection', {off: 'Off', minimal: 'Minimal', moderate: 'Moderate', strict: 'Strict'},
      'Drops flood packets whose path suggests a forwarding loop.'],
    ['path.hash.mode', 'Path hash size', {0: '1 byte', 1: '2 bytes', 2: '3 bytes'},
      'Changes how node IDs are encoded in packet paths. Use the size supported by your mesh.'],
    ['int.thresh', 'Interference threshold', 'number', 'Threshold for the radio’s interference detection.', 0, 255],
    ['agc.reset.interval', 'Gain control reset interval (seconds)', 'number', 'Use multiples of 4 seconds. 0 turns periodic resets off.', 0, 1020, 4],
    ['rxdelay', 'Receive processing delay', 'number', 'Experimental delay for processing received packets.', 0, 20, .1],
    ['txdelay', 'Flood retransmit delay factor', 'number', '', 0, 2, .1],
    ['direct.txdelay', 'Direct retransmit delay factor', 'number', '', 0, 2, .1],
    ['adc.multiplier', 'Battery voltage calibration multiplier', 'number', 'Use 0 to restore the board’s default calibration.', 0, 1000000, .001],
    ['radio.rxgain', 'Boosted receive gain', 'toggle', 'Improves reception sensitivity at the cost of extra power.'],
    ['multi.acks', 'Multiple acknowledgments', {0: 'Off', 1: 'On'}, 'Enables multiple acknowledgments for supported traffic.']
  ]
};

const configInputs = new Map();
let guestClear;
let aclEntries = [];
let regionEntries = [];
let regionHome = '';
let regionScope = '';
let nextRegionId = 0;
let radioPresets = [];

function configElement(tag, text) {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  return node;
}

function configInput(parent, id, labelText, type, help = '', min, max, step = 1) {
  const group = configElement('div');
  const label = configElement('label', labelText);
  label.setAttribute('for', id);
  const select = type === 'toggle' || typeof type === 'object';
  const input = configElement(select ? 'select' : 'input');
  input.id = id;
  if (select) {
    const options = type === 'toggle' ? {off: 'Off', on: 'On'} : type;
    for (const [key, title] of Object.entries(options)) addOption(input, key, title);
  } else {
    input.type = type;
    input.autocomplete = type === 'password' ? 'new-password' : 'off';
    if (type === 'number') {
      input.min = min;
      input.max = max;
      input.step = step;
      input.inputMode = step === 1 ? 'numeric' : 'decimal';
    }
    if (type === 'password') input.maxLength = 15;
  }
  group.append(label);
  group.append(input);
  if (help) {
    const hint = configElement('p', help);
    hint.id = id + '-help';
    hint.className = 'field-note';
    input.setAttribute('aria-describedby', hint.id);
    group.append(hint);
  }
  parent.append(group);
  return input;
}

function configButton(parent, title, action) {
  const button = configElement('button', title);
  button.type = 'button';
  button.onclick = action;
  parent.append(button);
  return button;
}

window.onRadioPresets = presets => {
  radioPresets = presets;
  const select = byId('configRadioPreset');
  select.replaceChildren();
  addOption(select, '', 'Custom');
  presets.forEach((preset, index) => addOption(select, String(index), preset.title));
  updatePresetSelection();
};

function updatePresetSelection() {
  const radio = repeaterFields.radio.map(id => byId(id).value);
  const found = radioPresets.findIndex(preset => preset.radio.every((value, i) =>
    radio[i] !== '' && Number(value) === Number(radio[i])));
  byId('configRadioPreset').value = found < 0 ? '' : String(found);
}

function configCounters() {
  const encoder = new TextEncoder();
  const located = Number(configInputs.get('lat')?.value || 0) !== 0 ||
    Number(configInputs.get('lon')?.value || 0) !== 0;
  const name = encoder.encode(byId('repeaterName').value).length;
  const owner = encoder.encode(byId('repeaterOwner').value).length;
  byId('configNameBytes').textContent = name + ' of ' + (located ? 24 : 31) + ' UTF-8 bytes';
  byId('configOwnerBytes').textContent = owner + ' of 119 UTF-8 bytes';
}

function showExtraConfig(view) {
  configInputs.clear();
  guestClear = null;
  byId('configVersion').textContent = view.version;
  byId('configRole').textContent = view.role === 'room-server' ? 'Room server' : 'Repeater';
  byId('configClock').textContent = view.clock || 'Unavailable';
  byId('configPublicKey').textContent = view.publicKey || 'Unavailable';
  for (const [id, fields] of Object.entries(configGroups)) {
    const group = byId(id);
    group.replaceChildren();
    for (const [key, label, ...spec] of fields) {
      if (!Object.hasOwn(view.settings, key)) continue;
      const input = configInput(group, 'config-' + key, label, ...spec);
      input.value = view.settings[key];
      configInputs.set(key, input);
      if (key === 'guest.password' && view.guestSet) {
        const label = configElement('label', 'Clear guest password');
        guestClear = configElement('input');
        guestClear.type = 'checkbox';
        label.append(guestClear);
        group.append(label);
        guestClear.onchange = () => {
          input.disabled = guestClear.checked;
          if (guestClear.checked) input.value = '';
          repeaterButtons();
        };
      }
    }
    const wrapper = {configAccess: 'configAccessGroup', configAdvanced: 'configAdvancedGroup'}[id];
    if (wrapper) byId(wrapper).hidden = !fields.some(([key]) => Object.hasOwn(view.settings, key));
  }

  byId('configAclGroup').hidden = view.acl == null;
  aclEntries = readAclEntries(view.acl);
  drawAcl();

  byId('configRegionGroup').hidden = view.regions == null;
  byId('configRegionControls').hidden = !view.regions?.editable;
  byId('configRegionStatus').textContent = view.regions && !view.regions.editable ?
    'This radio returned an incomplete region list. Region editing requires the full list.' : '';
  byId('configDefaultRegion').hidden = view.regions?.scope == null;
  const list = view.regions?.list ?? [];
  const names = new Map(list.map((row, i) => [row.name, String(i)]));
  regionEntries = list.map((row, i) => ({...row, id: String(i), parent: names.get(row.parent) ?? ''}));
  nextRegionId = list.length;
  regionHome = names.get(view.regions?.home) ?? '0';
  regionScope = names.get(view.regions?.scope) ?? '';
  drawRegions();
  configCounters();
  updatePresetSelection();
}

function clearConfigSecrets() {
  for (const key of ['guest.password', 'password']) {
    if (configInputs.has(key)) configInputs.get(key).value = '';
  }
  if (guestClear) {
    guestClear.checked = false;
    configInputs.get('guest.password').disabled = false;
  }
}

function readAclEntries(acl) {
  return Object.entries(acl ?? {}).map(([key, permissions]) => ({key, role: permissions & 3}));
}

function drawAcl() {
  const rows = byId('configAclRows');
  rows.replaceChildren();
  aclEntries.forEach((row, index) => {
    const card = configElement('div');
    card.className = 'config-row';
    const key = configInput(card, 'acl-key-' + index, 'Public key', 'text');
    key.value = row.key;
    key.maxLength = 64;
    key.spellcheck = false;
    key.oninput = () => { row.key = key.value; };
    const roles = {1: 'Read only', 2: 'Read and write', 3: 'Admin'};
    if (row.role === 0) roles[0] = 'Other access';
    const access = configInput(card, 'acl-role-' + index, 'Access level', roles);
    access.value = String(row.role);
    access.onchange = () => { row.role = Number(access.value); repeaterButtons(); };
    const remove = configButton(card, 'Remove node', () => {
      aclEntries.splice(index, 1);
      drawAcl();
      repeaterButtons();
    });
    remove.setAttribute('aria-label', 'Remove access entry ' + (index + 1));
    rows.append(card);
  });
}

function regionSelect(select, value, includeNone, exclude = '') {
  select.replaceChildren();
  if (includeNone) addOption(select, '', 'None');
  for (const row of regionEntries) {
    if (row.id === exclude || includeNone && row.id === '0') continue;
    addOption(select, row.id, row.id === '0' ? 'Unscoped traffic' : row.name || 'Unnamed region');
  }
  select.value = value;
}

function drawRegions() {
  const rows = byId('configRegionRows');
  rows.replaceChildren();
  regionSelect(byId('configHome'), regionHome, false);
  regionSelect(byId('configScope'), regionScope, true);
  regionEntries.forEach(row => {
    const card = configElement('div');
    card.className = 'config-row';
    if (row.id === '0') {
      card.append(configElement('strong', 'Unscoped traffic'));
    } else {
      const name = configInput(card, 'region-name-' + row.id, 'Region name', 'text');
      name.value = row.name;
      name.maxLength = 30;
      name.oninput = () => { row.name = name.value; };
      name.onchange = () => {
        regionSelect(byId('configHome'), regionHome, false);
        regionSelect(byId('configScope'), regionScope, true);
        for (const other of regionEntries) {
          if (other.id !== '0') regionSelect(byId('region-parent-' + other.id), other.parent, false, other.id);
        }
      };
      const parent = configInput(card, 'region-parent-' + row.id, 'Parent region', {});
      regionSelect(parent, row.parent, false, row.id);
      parent.onchange = () => { row.parent = parent.value; repeaterButtons(); };
    }
    const flood = configInput(card, 'region-flood-' + row.id, 'Forward flood traffic', 'toggle');
    flood.value = row.flood ? 'on' : 'off';
    flood.onchange = () => { row.flood = flood.value === 'on'; repeaterButtons(); };
    if (row.id !== '0') {
      const remove = configButton(card, 'Remove region', () => {
        for (const child of regionEntries) if (child.parent === row.id) child.parent = row.parent;
        regionEntries = regionEntries.filter(item => item !== row);
        if (regionHome === row.id) regionHome = '0';
        if (regionScope === row.id) regionScope = '';
        drawRegions();
        repeaterButtons();
      });
      remove.setAttribute('aria-label', 'Remove region ' + (row.name || row.id));
    }
    rows.append(card);
  });
}

function collectExtraChanges(view, changes) {
  for (const [key, input] of configInputs) {
    if (input.type === 'password' && !input.value) continue;
    if (input.value !== view.settings[key]) changes[key] = input.value;
  }
  if (guestClear?.checked) changes['guest.password'] = '';
  if (view.acl != null && JSON.stringify(aclEntries) !== JSON.stringify(readAclEntries(view.acl))) {
    changes.acl = aclEntries;
  }
  if (view.regions?.editable) {
    const name = id => regionEntries.find(row => row.id === id)?.name ?? '';
    const regions = {
      list: regionEntries.map(row => ({name: row.name, parent: name(row.parent), flood: row.flood})),
      home: name(regionHome), scope: view.regions.scope == null ? null : name(regionScope)
    };
    const previous = {list: view.regions.list, home: view.regions.home, scope: view.regions.scope};
    if (JSON.stringify(regions) !== JSON.stringify(previous)) changes.regions = regions;
  }
}

function bindExtraConfig() {
  byId('configRadioPreset').onchange = () => {
    const selected = byId('configRadioPreset').value;
    if (selected === '') return;
    const p = radioPresets[Number(selected)];
    repeaterFields.radio.forEach((id, index) => { byId(id).value = p.radio[index]; });
    if (p.hash != null && configInputs.has('path.hash.mode')) configInputs.get('path.hash.mode').value = String(p.hash);
    repeaterButtons();
  };
  byId('configAclAdd').onclick = () => {
    aclEntries.push({key: '', role: 2});
    drawAcl();
    repeaterButtons();
  };
  byId('configRegionAdd').onclick = () => {
    regionEntries.push({id: String(nextRegionId++), name: '', parent: '0', flood: true});
    drawRegions();
    repeaterButtons();
  };
  byId('configHome').onchange = () => {
    regionHome = byId('configHome').value;
    repeaterButtons();
  };
  byId('configScope').onchange = () => {
    regionScope = byId('configScope').value;
    repeaterButtons();
  };
  byId('radioPrivateKey').onclick = () => {
    if (!repeaterView || repeaterBusy) return;
    repeaterBusy = true;
    buttons();
    Phone.revealRadioKey(repeaterView.device);
  };
}
