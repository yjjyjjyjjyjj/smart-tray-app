'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../js/mock-data.js'); require('../js/mock-transport.js'); require('../js/native-transport.js');
const D = global.SmartTrayData;
const event = n => ({ id: `event-${n}`, time: 1700000000000 + n, type: 'placed', title: '1번 트레이에 물건이 놓였어요', description: '트레이 01', trayId: 1 });
function storage() {
  const values = new Map();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
}
test('활동은 재실행 후 최신 200개를 복원하고 센서는 수신 대기로 시작한다', () => {
  const disk = storage(), store = D.createStore(disk);
  store.update(s => { s.preferences.trays[0].alias = '현관'; }); store.save();
  for (let i = 0; i < 205; i++) store.update(s => s.activities.unshift(event(i)));
  const restored = D.createStore(disk).get();
  assert.equal(JSON.parse(disk.getItem(D.CONFIG.activityStorageKey)).length, 200);
  assert.equal(restored.activities.length, 200); assert.equal(restored.activities[0].id, 'event-204');
  assert.equal(restored.activities[199].id, 'event-5'); assert.equal(restored.activities[0].time, 1700000000204);
  assert.equal(restored.preferences.trays[0].alias, '현관'); assert.equal(restored.trays[0].sensor, 'waiting');
  assert.equal(restored.lastReceived, null); assert.equal(restored.connection, 'disconnected');
  assert.equal(restored.notifications.length, 0);
});
test('손상된 기록과 오래된 트레이 ID는 화면을 깨뜨리지 않고 저장 한도를 적용한다', () => {
  const disk = storage(); disk.setItem(D.CONFIG.activityStorageKey, '{bad');
  assert.deepEqual(D.createStore(disk).get().activities, []);
  disk.setItem(D.CONFIG.activityStorageKey, JSON.stringify([null, {type:'unknown'}, ...Array.from({length:205},(_,i)=>event(i))]));
  assert.equal(D.createStore(disk).get().activities.length, 200);
  disk.setItem(D.CONFIG.activityStorageKey, JSON.stringify([{...event(1),trayId:999,trayIds:[1,999]}]));
  const restored = D.createStore(disk).get().activities[0];
  assert.equal(restored.trayId,null); assert.deepEqual(restored.trayIds,[1]);
});
test('기록 저장 실패는 현재 기록을 유지하고 다음 저장 성공 시 복구한다', () => {
  const disk = storage(); let blocked = true; const write = disk.setItem;
  disk.setItem = (key,value) => { if(blocked) throw Error('blocked'); write(key,value); };
  const store = D.createStore(disk); store.update(s=>s.activities.unshift(event(1)));
  assert.equal(store.get().activityStorageError,true); assert.equal(store.get().activities.length,1);
  blocked = false; store.update(s=>s.activities.unshift(event(2)));
  assert.equal(store.get().activityStorageError,false); assert.equal(D.createStore(disk).get().activities.length,2);
});
test('실제 Android 기록과 브라우저 체험 기록은 모드 전환 후에도 섞이지 않는다', async () => {
  const disk = storage(), demo = D.createStore(disk);
  demo.update(s=>s.activities.unshift(event(1)));
  const store = D.createStore(disk,{native:true}); assert.equal(store.get().activities.length,0);
  const nativeState = D.createState(); nativeState.activities = [{...event(2),id:'native-record'}];
  const runtime = {available:true,plugin:{
    async addListener(){return {remove(){}};}, async getState(){return structuredClone(nativeState);},
    async consumeNotificationRoute(){return {};}, async disconnect(){return {stopped:true};}
  }};
  const transport = SmartTrayRuntime.createTransport(store,runtime);
  try {
    await transport.initialize(); assert.equal(store.get().activities[0].id,'native-record');
    assert.equal(D.createStore(disk).get().activities[0].id,'event-1');
    await transport.useDemo(); assert.equal(store.get().activities[0].id,'event-1');
    store.update(s=>s.activities.unshift(event(3)));
    await transport.useNative(); assert.equal(store.get().activities[0].id,'native-record');
    assert.equal(D.createStore(disk).get().activities[0].id,'event-3');
  } finally {transport.dispose();}
});
