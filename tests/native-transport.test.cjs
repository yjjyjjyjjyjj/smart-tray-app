'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../js/mock-data.js');
require('../js/mock-transport.js');
require('../js/native-transport.js');

function setup() {
  const store = SmartTrayData.createStore({ getItem: () => null, setItem() {}, removeItem() {} });
  const listeners = {};
  const calls = [];
  const state = SmartTrayData.createState();
  Object.assign(state, { connection: 'disconnected', lastReceived: null, door: 'unknown', protocolReady: false });
  state.trays.forEach(t => Object.assign(t, { present: false, weight: 0, sensor: 'waiting', measuredAt: null }));
  const snapshot = () => JSON.parse(JSON.stringify(state));
  const plugin = {
    async addListener(name, listener) { listeners[name] = listener; return { remove() { delete listeners[name]; } }; },
    async getState() { return snapshot(); },
    async consumeNotificationRoute() { return {}; },
    async requestBluetoothPermissions() { return { granted: true }; },
    async requestNotificationPermission() { return { granted: true }; },
    async scan() { calls.push('scan'); },
    async listPairedDevices() { calls.push('paired'); },
    async enableBluetooth() { calls.push('enable-bluetooth'); },
    async openBluetoothPermissions() { calls.push('app-permission-settings'); },
    async connect() { calls.push('connect'); state.connection = 'connecting'; setTimeout(() => { state.connection = 'connected'; listeners.stateChanged(snapshot()); }, 5); return { accepted: true }; },
    async disconnect() { calls.push('disconnect'); state.connection = 'disconnected'; return { stopped: true }; },
    async savePreferences({ preferences }) { calls.push('save'); state.preferences = structuredClone(preferences); return snapshot(); },
    async command() { calls.push('command'); throw Error('PROTOCOL_NOT_CONFIGURED'); },
    async previewNotification() { calls.push('preview'); return { posted: true }; }
  };
  const transport = SmartTrayRuntime.createTransport(store, { available: true, plugin });
  return { store, transport, state, calls, listeners, plugin, snapshot };
}

test('Android hydration and refresh never manufacture sensor data or start a mock connection', async () => {
  const { store, transport, calls } = setup();
  await transport.initialize(); transport.refresh();
  assert.equal(transport.isNative, true);
  assert.equal(store.get().lastReceived, null);
  assert.equal(store.get().trays[0].sensor, 'waiting');
  assert.equal(store.get().demo, false);
  assert.deepEqual(calls, []);
  transport.dispose();
});
test('Native connect awaits RFCOMM socket state, and does not mark unresolved sensors as fresh', async () => {
  const { store, transport } = setup(); await transport.initialize();
  assert.equal(await transport.connect({ address: 'AA:BB:CC:DD:EE:FF' }), true);
  assert.equal(store.get().connection, 'connected');
  assert.equal(SmartTrayData.isFresh(store.get()), false);
  assert.equal(await transport.playLed(1), false);
  assert.equal(store.get().trays[0].ledActive, false);
  transport.dispose();
});
test('Bluetooth connects without requesting notification permission, even when notifications are blocked', async () => {
  const { plugin, transport, state, store } = setup();
  state.notificationsAllowed = false; state.notificationPermission = 'denied'; state.notificationSettingsRequired = true;
  let prompts = 0; plugin.requestNotificationPermission = async () => { prompts++; return { granted: false }; };
  await transport.initialize();
  assert.equal(await transport.connect({ address: 'AA:BB:CC:DD:EE:FF' }), true);
  assert.equal(prompts, 0); assert.equal(store.get().notificationsAllowed, false);
  assert.equal(store.get().notificationSettingsRequired, true); transport.dispose();
});
test('A declined notification request keeps the connection healthy and publishes how to retry', async () => {
  const { plugin, transport, state, store, calls } = setup();
  state.connection = 'connected'; state.connectionError = ''; state.notificationsAllowed = false;
  plugin.requestNotificationPermission = async () => {
    state.notificationPermission = 'prompt-with-rationale'; state.notificationSettingsRequired = false;
    return { granted: false, settingsRequired: false };
  };
  await transport.initialize(); await transport.requestNotificationPermission();
  assert.equal(store.get().connection, 'connected'); assert.equal(store.get().connectionError, '');
  assert.equal(store.get().notificationPermission, 'prompt-with-rationale'); assert.deepEqual(calls, []);
  transport.dispose();
});
test('Blocked notifications open Settings only through the explicit action and refresh after enabling', async () => {
  const { plugin, transport, state, store, calls, listeners, snapshot } = setup();
  state.notificationsAllowed = false; state.notificationPermission = 'denied'; state.notificationSettingsRequired = true;
  plugin.requestNotificationPermission = async () => ({ granted: false, settingsRequired: true });
  plugin.openNotificationSettings = async () => { calls.push('notification-settings'); };
  await transport.initialize(); await transport.requestNotificationPermission(); assert.deepEqual(calls, []);
  await transport.openNotificationSettings(); assert.deepEqual(calls, ['notification-settings']);
  state.notificationsAllowed = true; state.notificationPermission = 'granted'; state.notificationSettingsRequired = false;
  listeners.stateChanged(snapshot());
  assert.equal(store.get().notificationsAllowed, true); assert.equal(store.get().notificationSettingsRequired, false);
  transport.dispose();
});
test('Repeated permission taps share one OS request and a denial cannot post a preview', async () => {
  const { plugin, transport, calls } = setup(); await transport.initialize();
  let finish, prompts = 0;
  plugin.requestNotificationPermission = () => { prompts++; return new Promise(resolve => { finish = resolve; }); };
  const first = transport.requestNotificationPermission(), second = transport.requestNotificationPermission();
  await new Promise(setImmediate); assert.equal(prompts, 1);
  finish({ granted: false }); await Promise.all([first, second]);
  plugin.requestNotificationPermission = async () => ({ granted: false, settingsRequired: true });
  assert.equal(await transport.previewNotification(), false); assert.equal(calls.includes('preview'), false);
  transport.dispose();
});
test('Denied Bluetooth permission cannot trigger a native scan', async () => {
  const { plugin, transport, calls } = setup(); await transport.initialize();
  plugin.requestBluetoothPermissions = async () => { throw Error('Permission denied'); };
  await transport.scan(); assert.deepEqual(calls, []); transport.dispose();
});
test('A retryable denial stays in the app; the next search can request and grant permission', async () => {
  const { plugin, transport, calls } = setup(); await transport.initialize();
  let attempts = 0;
  plugin.requestBluetoothPermissions = async () => ({ granted: ++attempts > 1 });
  await transport.scan(); assert.deepEqual(calls, []);
  await transport.scan(); assert.equal(attempts, 2); assert.deepEqual(calls, ['scan']);
  transport.dispose();
});
test('Only a permanent denial opens app settings and no Bluetooth operation runs without permission', async () => {
  for (const settingsRequired of [false, true]) {
    for (const action of ['scan', 'listPairedDevices', 'enableBluetooth', 'connect']) {
      const { plugin, transport, calls } = setup();
      plugin.requestBluetoothPermissions = async () => ({ granted: false, settingsRequired });
      await transport.initialize(); assert.deepEqual(calls, []);
      await transport[action]({ address: 'AA:BB:CC:DD:EE:FF' });
      assert.deepEqual(calls, settingsRequired ? ['app-permission-settings'] : [], `${action}: ${settingsRequired}`);
      transport.dispose();
    }
  }
});
test('Permission granted in Settings allows the next search without reopening Settings', async () => {
  const { plugin, transport, calls } = setup(); await transport.initialize();
  plugin.requestBluetoothPermissions = async () => ({ granted: false, settingsRequired: true });
  await transport.scan();
  plugin.requestBluetoothPermissions = async () => ({ granted: true });
  await transport.scan();
  assert.deepEqual(calls, ['app-permission-settings', 'scan']); transport.dispose();
});
test('Cancelled or disposed requests cannot open Settings after the permission result arrives', async () => {
  for (const dispose of [false, true]) {
    const { plugin, transport, calls } = setup(); await transport.initialize();
    let resolve; plugin.requestBluetoothPermissions = () => new Promise(done => { resolve = done; });
    const pending = dispose ? transport.scan() : transport.connect({ address: 'AA:BB:CC:DD:EE:FF' });
    if (dispose) transport.dispose(); else await transport.disconnect();
    resolve({ granted: false, settingsRequired: true }); await pending;
    assert.equal(calls.includes('app-permission-settings'), false); transport.dispose();
  }
});
test('Native settings and preview use Kotlin; WebView disposal does not disconnect Bluetooth', async () => {
  const { store, transport, calls } = setup(); await transport.initialize();
  store.update(s => { s.preferences.trays[0].alias = '현관'; s.preferences.umbrella.fan.duration = 60; });
  assert.equal(await transport.savePreferences(), true);
  assert.equal(store.get().preferences.trays[0].alias, '현관');
  assert.equal(store.get().preferences.umbrella.fan.duration, 60);
  await transport.previewNotification();
  assert.deepEqual(calls, ['save', 'preview']);
  transport.dispose(); assert.equal(calls.includes('disconnect'), false);
});
test('Demo mode explicitly stops real Bluetooth and ignores native data until switching back', async () => {
  const { store, transport, calls, listeners, state, snapshot } = setup(); await transport.initialize();
  await transport.useDemo(); assert.equal(transport.isNative, false); assert.deepEqual(calls, ['disconnect']);
  await transport.connect({ demo: true }); assert.equal(store.get().demo, true);
  state.preferences.trays[0].alias = '실제 설정'; listeners.stateChanged(snapshot());
  assert.notEqual(store.get().preferences.trays[0].alias, '실제 설정');
  await transport.useNative(); assert.equal(store.get().demo, false);
  assert.equal(store.get().preferences.trays[0].alias, '실제 설정'); transport.dispose();
});

test('Native location status and settings actions stay on the Kotlin bridge', async () => {
  const { store, transport, plugin, state, calls } = setup();
  state.location = { permission: 'denied', enabled: false };
  plugin.openLocationSettings = async () => { calls.push('location-settings'); return {}; };
  plugin.openAppLocationSettings = async () => { calls.push('app-location-settings'); return {}; };
  plugin.refreshWeather = async () => {
    state.location = { permission: 'granted', enabled: true };
    state.weather = { status: 'error', message: '날씨 조회 실패', windows: [] };
    return structuredClone(state);
  };
  await transport.initialize();
  assert.deepEqual(store.get().location, { permission: 'denied', enabled: false });
  await transport.openLocationSettings(); await transport.openAppLocationSettings();
  assert.deepEqual(calls, ['location-settings', 'app-location-settings']);
  await transport.refreshWeather();
  assert.deepEqual(store.get().location, { permission: 'granted', enabled: true });
  assert.equal(store.get().weather.status, 'error');
  assert.equal(store.get().preferences.setupDone, false);
  transport.dispose();
});

test('Pairing and retry states never resolve as connected; explicit cancel settles pending connect', async () => {
  const { plugin, transport, state, store, listeners, snapshot, calls } = setup(); await transport.initialize();
  plugin.connect = async ({ address }) => { calls.push('connect'); state.deviceAddress = address; state.connection = 'connecting'; state.connectionPhase = 'pairing'; return {accepted:true}; };
  const pending = transport.connect({address:'AA:BB:CC:DD:EE:FF'});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(store.get().connection, 'connecting'); assert.equal(store.get().connectionPhase,'pairing');
  assert.equal(await transport.connect({address:'00:11:22:33:44:55'}),false);
  state.connectionPhase='retrying'; state.retryAttempt=3; listeners.stateChanged(snapshot());
  assert.equal(store.get().retryAttempt,3);
  await transport.disconnect(); assert.equal(await pending,false);
  assert.equal(calls.filter(c=>c==='connect').length,1); transport.dispose();
});
test('Disposing the WebView settles its wait without disconnecting the service', async () => {
  const { plugin, transport, state, calls }=setup(); await transport.initialize();
  plugin.connect=async()=>{state.connection='connecting';return {accepted:true};};
  const pending=transport.connect({address:'AA:BB:CC:DD:EE:FF'});
  await new Promise(resolve=>setImmediate(resolve)); transport.dispose();
  assert.equal(await pending,false); assert.equal(calls.includes('disconnect'),false);
});
test('Paired list requests connection permission separately from discovery', async () => {
  const {plugin,transport,calls}=setup(); await transport.initialize();
  plugin.requestBluetoothPermissions=async options=>{calls.push(options.discovery?'scan-permission':'connect-permission');return {granted:true};};
  plugin.listPairedDevices=async()=>{calls.push('paired');};
  await transport.listPairedDevices(); await transport.scan();
  assert.deepEqual(calls,['connect-permission','paired','scan-permission','scan']); transport.dispose();
});

test('Cancel during the OS permission prompt cannot start a late socket connection', async () => {
  const {plugin,transport,calls}=setup(); await transport.initialize();
  let grant; plugin.requestBluetoothPermissions=()=>new Promise(resolve=>{grant=resolve;});
  const pending=transport.connect({address:'AA:BB:CC:DD:EE:FF'});
  await transport.disconnect(); grant({granted:true});
  assert.equal(await pending,false); assert.equal(calls.includes('connect'),false); transport.dispose();
});

test('Weather refresh coalesces in-flight calls, forwards automatic intent, and retries after failure', async () => {
  const { plugin, transport, state, store } = setup(); await transport.initialize();
  const requests = []; let resolve, reject;
  plugin.refreshWeather = options => { requests.push(options); return new Promise((yes, no) => { resolve = yes; reject = no; }); };
  const first = transport.refreshWeather({ automatic: true });
  const duplicate = transport.refreshWeather({ automatic: true });
  await new Promise(setImmediate);
  assert.deepEqual(requests, [{ automatic: true }]);
  state.weather = { status: 'ready', fetchedAt: 100, windows: [] }; resolve(structuredClone(state));
  await Promise.all([first, duplicate]); assert.equal(store.get().weather.fetchedAt, 100);
  const failed = transport.refreshWeather({ automatic: true });
  await new Promise(setImmediate); reject(Error('Forecast unavailable')); assert.equal(await failed, false);
  const retry = transport.refreshWeather();
  await new Promise(setImmediate);
  assert.deepEqual(requests, [{ automatic: true }, { automatic: true }, { automatic: false }]);
  state.weather.fetchedAt = 200; resolve(structuredClone(state)); await retry;
  assert.equal(store.get().weather.fetchedAt, 200); transport.dispose();
  assert.equal(await transport.refreshWeather({ automatic: true }), false); assert.equal(requests.length, 3);
});

test('Automatic browser weather refresh preserves a selected dry forecast', async () => {
  const store = SmartTrayData.createStore({ getItem: () => null, setItem() {}, removeItem() {} });
  const transport = SmartTrayRuntime.createTransport(store, { available: false });
  transport.setWeather(false);
  store.update(s => { s.weather.fetchedAt = 1; });
  await transport.refreshWeather({ automatic: true });
  assert.equal(store.get().weather.status, 'ready'); assert.equal(store.get().weather.windows.length, 0);
  assert.ok(store.get().weather.fetchedAt > 1);
  await transport.refreshWeather(); assert.equal(store.get().weather.windows.length, 1);
  transport.dispose();
});

test('Umbrella sterilization settings persist through Kotlin without inventing a native command', async()=>{
  const {store,transport,calls}=setup();await transport.initialize();
  store.update(s=>{s.preferences.umbrella.fan.duration=60;Object.assign(s.preferences.umbrella.effect,{enabled:true,onDetect:true,onDoor:true,duration:30});});
  assert.equal(await transport.savePreferences(),true);
  assert.deepEqual(store.get().preferences.umbrella.effect,{enabled:true,onDetect:true,onDoor:true,duration:30});
  assert.equal(store.get().preferences.umbrella.fan.duration,60);
  assert.equal(await transport.playUmbrellaEffect(),false);
  assert.deepEqual(calls,['save']); assert.equal(store.get().umbrella.effectUntil,0);
  transport.dispose();
});

test('Native region updates preserve forecasts and clear the previous location name', async () => {
  const { transport, store, state, listeners, snapshot } = setup(); await transport.initialize();
  state.weather = { status: 'ready', source: '기상청', regionName: '경기도 성남시 수정구', regionStatus: 'ready', windows: [{ start: 1, end: 2 }] };
  listeners.stateChanged(snapshot());
  assert.equal(store.get().weather.regionName, '경기도 성남시 수정구');
  state.weather = { status: 'loading', regionName: null, regionStatus: 'loading', windows: [] };
  listeners.stateChanged(snapshot());
  assert.equal(store.get().weather.regionName, null);
  state.weather = { status: 'ready', regionName: null, regionStatus: 'unavailable', windows: [{ start: 3, end: 4 }] };
  listeners.stateChanged(snapshot());
  assert.equal(store.get().weather.status, 'ready');
  assert.deepEqual(store.get().weather.windows, [{ start: 3, end: 4 }]);
  transport.dispose();
});

test('Native disconnect failure leaves connection state intact and supports retry', async () => {
  const { store, transport, state, calls, plugin } = setup();
  state.connection = 'connected'; await transport.initialize();
  const disconnect = plugin.disconnect;
  plugin.disconnect = async () => { calls.push('disconnect-failed'); throw Error('연결 해제 실패'); };
  assert.equal(await transport.disconnect(), false);
  assert.equal(store.get().connection, 'connected');
  assert.equal(store.get().connectionError, '연결 해제 실패');
  plugin.disconnect = disconnect;
  assert.deepEqual(await transport.disconnect(), { stopped: true });
  assert.equal(store.get().connection, 'disconnected');
  assert.deepEqual(calls, ['disconnect-failed', 'disconnect']);
  transport.dispose();
});
test('Native tray sterilization never sends an unimplemented command', async () => {
  const { store, transport, calls } = setup(); await transport.initialize();
  for (const enabled of [false, true]) {
    store.update(s => { s.preferences.trays[0].effect.enabled = enabled; });
    assert.equal(await transport.playEffect(1), false);
  }
  assert.deepEqual(calls, []); transport.dispose();
});
