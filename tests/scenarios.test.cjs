'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
require('../js/mock-data.js');
require('../js/mock-transport.js');
const { CONFIG, createStore, createState, defaults, isFresh, trayStatus, notificationForDoor, loadPreferences } = global.SmartTrayData;
const { createMockTransport } = global.SmartTrayTransport;
function setup() {
  const memory = new Map();
  const storage = { getItem: key => memory.get(key) || null, setItem: (key, value) => memory.set(key, value), removeItem: key => memory.delete(key) };
  const store = createStore(storage);
  return { store, transport: createMockTransport(store), storage };
}
test('A: 1·2번 트레이를 하나의 미수거 알림으로 묶고 같은 문 열림을 중복 처리하지 않는다', async () => {
  const { store, transport } = setup();
  await transport.scenario('A');
  assert.equal(store.get().notifications.length, 1);
  assert.deepEqual(store.get().notifications[0].items, ['1번 트레이', '2번 트레이']);
  assert.equal(store.get().notifications[0].kind, 'belongings');
  assert.equal(store.get().notifications[0].message, '1번 트레이, 2번 트레이에 물건이 남아있어요. 외출 전에 확인해주세요.');
  transport.setDoor(true); transport.setDoor(true);
  assert.equal(store.get().notifications.length, 1);
  transport.setDoor(false); transport.setDoor(true);
  assert.equal(store.get().notifications.length, 2);
  transport.dispose();
});
test('B: 알림이 꺼진 1번 트레이는 미수거 알림에서 제외한다', async () => {
  const { store, transport } = setup(); await transport.scenario('B');
  assert.equal(store.get().preferences.trays[0].notification, false);
  assert.equal(store.get().notifications.filter(n => n.kind === 'belongings').length, 0);
  assert.equal(store.get().notifications[0].kind, 'door');
  transport.dispose();
});
test('C: 모든 칸이 비면 미수거 알림이 없으며 문 알림 설정을 따로 적용한다', async () => {
  const { store, transport } = setup(); await transport.scenario('C');
  assert.equal(store.get().notifications.filter(n => n.kind === 'belongings').length, 0);
  store.update(s => { s.preferences.notifications.door = false; });
  transport.setDoor(false); transport.setDoor(true);
  assert.equal(store.get().notifications.length, 1);
  for (let i = 0; i < 55; i++) transport.previewNotification();
  assert.equal(store.get().notifications.length, 50);
  transport.dispose();
});
test('D: 연결이 끊기면 이전 측정값을 보존하되 현재 상태와 알림 판단에서 제외한다', async () => {
  const { store, transport } = setup(); await transport.scenario('D');
  assert.equal(store.get().connection, 'disconnected');
  assert.equal(store.get().trays[0].weight, 128.5);
  assert.equal(trayStatus(store.get(), store.get().trays[0]).key, 'offline');
  assert.equal(isFresh(store.get()), false);
  assert.equal(transport.setDoor(true), false);
  assert.equal(store.get().notifications.length, 0);
  assert.equal(transport.playLed(1), false);
  transport.dispose();
});
test('오류·수신 대기·오래된 칸은 알림에서 제외하고 복구 후 다시 평가한다', async () => {
  const { store, transport } = setup(); await transport.connect({ demo: true });
  transport.setTray(2, true, 32.8);
  transport.setSensor(1, 'error'); transport.setSensor(2, 'waiting');
  transport.setDoor(true);
  assert.equal(store.get().notifications[0].kind, 'door');
  assert.equal(transport.playEffect(2), false);
  assert.equal(transport.playLed(1), false);
  transport.setSensor(1, 'ok'); transport.setSensor(2, 'ok');
  transport.setDoor(false); transport.setDoor(true);
  assert.deepEqual(store.get().notifications[0].items, ['1번 트레이', '2번 트레이']);
  transport.pause(true);
  assert.equal(trayStatus(store.get(), store.get().trays[0]).key, 'stale');
  assert.equal(transport.setDoor(false), false);
  transport.pause(false);
  assert.equal(isFresh(store.get()), true);
  transport.dispose();
});
test('전체·미수거·개별 알림 스위치는 각각 조건에 반영한다', async () => {
  const { store, transport } = setup(); await transport.connect({ demo: true });
  store.update(s => { s.preferences.notifications.enabled = false; }); transport.setDoor(true);
  assert.equal(store.get().notifications.length, 0);
  store.update(s => { s.preferences.notifications.enabled = true; s.preferences.notifications.belongings = false; });
  transport.setDoor(false); transport.setDoor(true);
  assert.equal(store.get().notifications[0].kind, 'door');
  store.update(s => { s.preferences.notifications.door = false; });
  transport.setDoor(false); transport.setDoor(true);
  assert.equal(store.get().notifications.length, 1);
  transport.dispose();
});
test('이름·설정은 복원하고 새 세션의 센서값은 수신 대기로 시작한다', () => {
  const { store, storage } = setup();
  store.update(s => { s.preferences.trays[0].alias = '사원증'; s.preferences.trays[0].effect.duration = 20; s.preferences.notifications.door = false; s.preferences.setupDone = true; s.trays[0].weight = 777; }); store.save();
  const restored = createStore(storage).get();
  assert.equal(restored.preferences.trays[0].alias, '사원증'); assert.equal(restored.preferences.trays[0].effect.duration, 20);
  assert.equal(restored.preferences.notifications.door, false); assert.equal(restored.preferences.setupDone, true);
  assert.equal(restored.trays[0].weight, 128.5); assert.equal(restored.trays[0].sensor, 'waiting'); assert.equal(restored.lastReceived, null);
});
test('손상·부정확한 저장값과 저장 차단은 앱 상태를 깨뜨리지 않는다', () => {
  assert.deepEqual(loadPreferences({ getItem: () => '{invalid' }), defaults());
  const p = loadPreferences({ getItem: () => JSON.stringify({ trays: [{ alias: ' ', led: { enabled: 'yes' }, effect: { duration: 999 } }] }) });
  assert.equal(p.trays[0].alias, '1번 트레이'); assert.equal(p.trays[0].led.enabled, true); assert.equal(p.trays[0].effect.duration, 10);
  const blocked = createStore({ getItem() { throw Error('denied'); }, setItem() { throw Error('denied'); } });
  blocked.save(); assert.equal(blocked.get().storageError, true);
});
test('연결 실패 후 재시도하고 연결 중 취소 시 늦은 성공이 상태를 덮어쓰지 않는다', async () => {
  const { store, transport } = setup();
  assert.equal(await transport.connect({ fail: true, demo: true }), false);
  assert.equal(store.get().connection, 'disconnected'); assert.ok(store.get().connectionError);
  const pending = transport.connect({ demo: true }); transport.disconnect();
  assert.equal(await pending, false); assert.equal(store.get().connection, 'disconnected');
  assert.equal(await transport.connect({ demo: true }), true); assert.equal(store.get().connectionError, '');
  transport.dispose();
});
test('수신한 무게를 유지하고 LED·살균 자동 조건을 적용한다', async () => {
  const { store, transport } = setup(); await transport.connect({ demo: true });
  store.update(s => { s.preferences.trays[1].effect.enabled = true; s.preferences.trays[1].effect.onDetect = true; });
  transport.setTray(2, true, 32.8);
  assert.equal(store.get().trays[1].weight, 32.8);
  assert.equal(store.get().trays[1].ledActive, true); assert.ok(store.get().trays[1].effectUntil > Date.now());
  transport.setTray(2, false, 0); assert.equal(store.get().trays[1].ledActive, false); assert.equal(store.get().trays[1].effectUntil, 0);
  transport.dispose();
});
test('연결·전체 수신·개별 측정 시각으로 상태 신선도를 평가한다', () => {
  const state = createState(); assert.equal(isFresh(state), false);
  state.connection = 'connected'; state.lastReceived = Date.now();
  state.trays[0].sensor = 'ok'; state.trays[0].measuredAt = Date.now() - CONFIG.staleAfterMs - 100;
  assert.equal(trayStatus(state, state.trays[0]).key, 'stale');
  state.door = 'open'; state.preferences.notifications.door = false;
  assert.equal(notificationForDoor(state), null);
});

test('이전 기본 이름만 번호로 전환하고 사용자 별칭과 설정은 유지한다', () => {
  const legacy = defaults();
  delete legacy.schemaVersion;
  legacy.setupDone = true;
  legacy.trays[0].alias = '지갑';
  legacy.trays[1].alias = '현관 오른쪽';
  legacy.trays[1].notification = false;
  legacy.trays[1].effect.duration = 20;
  legacy.trays.push({ id: 3, alias: '이어폰', notification: true, effect: { duration: 30 } });
  legacy.notifications.door = false;
  const migrated = loadPreferences({ getItem: () => JSON.stringify(legacy) });
  assert.deepEqual(migrated.trays.map(t => t.alias), ['1번 트레이', '현관 오른쪽']);
  assert.equal(migrated.schemaVersion, 2);
  assert.equal(migrated.setupDone, true);
  assert.equal(migrated.trays[1].notification, false);
  assert.equal(migrated.trays[1].effect.duration, 20);
  assert.equal(migrated.notifications.door, false);
  migrated.trays[0].alias = '지갑';
  assert.equal(loadPreferences({ getItem: () => JSON.stringify(migrated) }).trays[0].alias, '지갑');
});
test('거치·제거 기록과 미리보기는 물건 종류 대신 트레이 별칭을 사용한다', async () => {
  const { store, transport } = setup();
  store.update(s => { s.preferences.trays[0].alias = '현관 왼쪽'; });
  await transport.connect({ demo: true });
  assert.equal(store.get().activities[0].title, '현관 왼쪽 트레이에 물건이 놓였어요');
  transport.setTray(1, false, 0);
  assert.equal(store.get().activities[0].title, '현관 왼쪽 트레이에서 물건이 없어졌어요');
  transport.setTray(1, true, 120);
  assert.equal(store.get().activities[0].title, '현관 왼쪽 트레이에 물건이 놓였어요');
  transport.previewNotification();
  assert.equal(store.get().notifications[0].message, '현관 왼쪽 트레이, 2번 트레이에 물건이 남아있어요. 외출 전에 확인해주세요.');
  store.update(s => { s.preferences.trays[0].alias = '출근 트레이'; });
  transport.setTray(1, false, 0);
  assert.equal(store.get().activities[0].title, '출근 트레이에서 물건이 없어졌어요');
  transport.setTray(1, true, 120);
  assert.equal(store.get().activities[0].title, '출근 트레이에 물건이 놓였어요');
  transport.dispose();
});

test('우산 팬 시간은 기존 설정을 보존하며 저장·복원하고 잘못된 값은 0초로 복구한다', () => {
  const { store, storage } = setup();
  store.update(s => { s.preferences.trays[0].alias = '출근 트레이'; s.preferences.notifications.umbrella = false; });
  for (const duration of [0, 15, 30, 60]) {
    store.update(s => { s.preferences.umbrella.fan.duration = duration; });
    store.save();
    const restored = createStore(storage).get().preferences;
    assert.equal(restored.umbrella.fan.duration, duration);
    assert.equal(restored.trays[0].alias, '출근 트레이');
    assert.equal(restored.notifications.umbrella, false);
  }
  for (const duration of [null, -1, 1, 15.5, 61, '15', true]) {
    const saved = { umbrella: { fan: { duration } }, trays: [{ alias: '내 트레이' }] };
    const restored = loadPreferences({ getItem: () => JSON.stringify(saved) });
    assert.equal(restored.umbrella.fan.duration, 0);
    assert.equal(restored.trays[0].alias, '내 트레이');
  }
  const legacy = defaults(); delete legacy.umbrella;
  legacy.trays[1].alias = '차키'; legacy.trays[1].effect.duration = 30;
  const migrated = loadPreferences({ getItem: () => JSON.stringify(legacy) });
  assert.equal(migrated.umbrella.fan.duration, 0);
  assert.equal(migrated.trays[1].alias, '차키');
  assert.equal(migrated.trays[1].effect.duration, 30);
});
