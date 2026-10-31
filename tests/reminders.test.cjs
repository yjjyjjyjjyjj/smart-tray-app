'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../js/mock-data.js'); require('../js/mock-transport.js');
const D = global.SmartTrayData;
function state() {
  const s = D.createState(); s.connection = 'connected'; s.lastReceived = Date.now(); s.door = 'open';
  s.umbrella = { present: true, sensor: 'ok', measuredAt: Date.now() };
  s.weather = { status: 'ready', fetchedAt: Date.now(), windows: [{ start: Date.now(), end: Date.now()+3600000 }] };
  return s;
}
test('우산 알림은 오늘 남은 비 예보·열린 문·보관 중인 정상 우산 센서가 모두 필요하다', () => {
  assert.equal(D.umbrellaNotification(state()).kind, 'umbrella');
  for (const mutate of [s=>s.weather.windows=[], s=>s.umbrella.present=false, s=>s.umbrella.sensor='error', s=>s.umbrella.sensor='waiting', s=>s.door='closed', s=>s.connection='disconnected', s=>s.preferences.notifications.umbrella=false, s=>s.preferences.notifications.enabled=false, s=>s.weather.fetchedAt-=3600001, s=>s.weather.validUntil=Date.now(), s=>s.weather.windows[0].end=Date.now()-1]) {
    const s = state(); mutate(s); assert.equal(D.umbrellaNotification(s), null);
  }
});
test('날씨는 현재 시간대의 예보를 표시하고 누락·오래된 예보를 맑음으로 만들지 않는다', () => {
  const s = state(), now = Date.now();
  s.weather.fetchedAt = now;
  s.weather.hours = [{ start: now - 1000, end: now + 1000, condition: 'cloudy' }, { start: now + 1000, end: now + 2000, condition: 'snow' }];
  assert.equal(D.weatherCondition(s, now).label, '흐림');
  assert.equal(D.weatherCondition(s, now + 1000).label, '눈');
  assert.equal(D.weatherCondition(s, now + 2000), null);
  s.weather.hours[0].condition = null; assert.equal(D.weatherCondition(s, now), null);
  s.weather.hours[0].condition = 'clear'; s.weather.fetchedAt = now - 3600001;
  assert.equal(D.weatherCondition(s, now), null);
});
test('맑음·구름·눈은 우산 알림을 만들지 않고 자동 새로고침 뒤에도 가상 날씨가 유지된다', async () => {
  const store = D.createStore({getItem:()=>null,setItem(){}}), t = SmartTrayTransport.createMockTransport(store);
  try {
    await t.connect({demo:true});
    for (const condition of ['clear', 'partly-cloudy', 'cloudy', 'snow']) {
      t.setDoor(false); t.setWeather(condition); t.setDoor(true);
      assert.equal(D.weatherCondition(store.get()).key, condition);
      assert.equal(store.get().notifications.filter(n=>n.kind==='umbrella').length, 0);
      t.refreshWeather({automatic:true});
      assert.equal(D.weatherCondition(store.get()).key, condition);
    }
    t.setDoor(false); t.setWeather('rain-snow'); t.setDoor(true);
    assert.equal(store.get().notifications.filter(n=>n.kind==='umbrella').length, 1);
  } finally { t.dispose(); }
});
test('문 열림 유지 시간의 경계·중복·설정 저장과 이전 설정 이관을 확인한다', () => {
  const s = state(); const now = Date.now(); s.doorOpenedAt = now-30000;
  assert.equal(D.doorLeftOpenNotification(s, now-1), null);
  assert.equal(D.doorLeftOpenNotification(s, now).kind, 'door-left-open');
  s.doorLeftOpenNotified=true; assert.equal(D.doorLeftOpenNotification(s, now), null);
  const old=D.loadPreferences({getItem:()=>JSON.stringify({schemaVersion:2,trays:[{alias:'내 자리'}],notifications:{door:false}})});
  assert.equal(old.trays[0].alias,'내 자리'); assert.equal(old.notifications.door,false); assert.equal(old.notifications.doorOpenSeconds,30);
  const edited=D.loadPreferences({getItem:()=>JSON.stringify({...old,notifications:{...old.notifications,doorOpenSeconds:123,umbrella:false}})});
  assert.equal(edited.notifications.doorOpenSeconds,123); assert.equal(edited.notifications.umbrella,false);
});
test('가상 우산 거치·제거는 알리지 않으며 비 예보가 있는 문 열림에서만 한 번 알린다', async () => {
  const store = D.createStore({getItem:()=>null,setItem(){}}), t=SmartTrayTransport.createMockTransport(store);
  try {
    await t.connect({demo:true}); t.setWeather(false); t.setUmbrella(false); t.setUmbrella(true); t.setDoor(true);
    assert.equal(store.get().notifications.filter(n=>n.kind==='umbrella').length,0);
    t.setDoor(false); t.setWeather(true); t.setDoor(true); t.setDoor(true);
    assert.equal(store.get().notifications.filter(n=>n.kind==='umbrella').length,1);
    t.setUmbrella(false); t.setUmbrella(true);
    assert.equal(store.get().notifications.filter(n=>n.kind==='umbrella').length,1);
  } finally { t.dispose(); }
});

test('문 닫힘 미확인 알림은 연결 유실·오래된 수신에서도 원래 기한을 사용한다', () => {
  const now = Date.now();
  for (const mutate of [s => { s.connection='disconnected'; s.door='unknown'; s.lastReceived=null; }, s => { s.lastReceived=now-46000; }]) {
    const s=state(); s.doorOpenedAt=now-30000; mutate(s);
    assert.equal(D.doorLeftOpenNotification(s,now-1),null);
    assert.equal(D.doorLeftOpenNotification(s,now).title,'현관문이 닫혔는지 확인해 주세요');
    s.doorLeftOpenNotified=true; assert.equal(D.doorLeftOpenNotification(s,now),null);
    s.doorLeftOpenNotified=false; s.doorOpenedAt=null; assert.equal(D.doorLeftOpenNotification(s,now),null);
  }
  const s=state(); s.doorOpenedAt=now-30000; s.door='closed';
  assert.equal(D.doorLeftOpenNotification(s,now),null);
});
test('기존 600초 설정은 300초로 제한하고 기본값·별칭·다른 알림 설정은 보존한다', () => {
  for (const [input,expected] of [[0,1],[1,1],[300,300],[301,300],[600,300]]) {
    const p=D.loadPreferences({getItem:()=>JSON.stringify({notifications:{doorOpenSeconds:input,umbrella:false},trays:[{alias:'출근'}]})});
    assert.equal(p.notifications.doorOpenSeconds,expected); assert.equal(p.notifications.umbrella,false); assert.equal(p.trays[0].alias,'출근');
  }
});
test('거리 이탈 뒤 원래 기한에 한 번 알리고 재연결은 기한을 바꾸지 않는다', async (context) => {
  const store=D.createStore({getItem:()=>null,setItem(){}}), t=SmartTrayTransport.createMockTransport(store);
  await t.connect({demo:true});
  context.mock.timers.enable({apis:['Date','setTimeout'],now:Date.now()});
  try {
    store.update(s=>{s.preferences.notifications.doorOpenSeconds=30;});
    t.setDoor(true); const opened=store.get().doorOpenedAt;
    context.mock.timers.tick(10000); t.loseConnection();
    assert.equal(store.get().doorOpenedAt,opened);
    context.mock.timers.tick(19999);
    assert.equal(store.get().notifications.filter(n=>n.kind==='door-left-open').length,0);
    context.mock.timers.tick(1);
    assert.equal(store.get().notifications.filter(n=>n.kind==='door-left-open').length,1);
    assert.equal(store.get().notifications[0].title,'현관문이 닫혔는지 확인해 주세요');
    const reconnect=t.connect({demo:true}); context.mock.timers.tick(150); await reconnect;
    assert.equal(store.get().doorOpenedAt,opened);
    context.mock.timers.tick(30000);
    assert.equal(store.get().notifications.filter(n=>n.kind==='door-left-open').length,1);
  } finally { t.dispose(); context.mock.timers.reset(); }
});
test('재연결 후 닫힘·직접 연결 해제는 대기를 취소하고 수신 중단은 취소하지 않는다', async (context) => {
  const store=D.createStore({getItem:()=>null,setItem(){}}), t=SmartTrayTransport.createMockTransport(store);
  await t.connect({demo:true});
  context.mock.timers.enable({apis:['Date','setTimeout'],now:Date.now()});
  const reconnect=async()=>{const p=t.connect({demo:true});context.mock.timers.tick(150);await p;};
  const count=()=>store.get().notifications.filter(n=>n.kind==='door-left-open').length;
  try {
    t.setDoor(true); context.mock.timers.tick(10000); t.loseConnection(); await reconnect();
    t.setDoor(false); context.mock.timers.tick(30000); assert.equal(count(),0);
    t.setDoor(true); t.disconnect(); context.mock.timers.tick(30000); assert.equal(count(),0);
    await reconnect(); t.setDoor(true); const opened=store.get().doorOpenedAt;
    t.pause(true); assert.equal(store.get().doorOpenedAt,opened);
    context.mock.timers.tick(30000); assert.equal(count(),1);
    assert.equal(store.get().notifications[0].title,'현관문이 닫혔는지 확인해 주세요');
  } finally { t.dispose(); context.mock.timers.reset(); }
});
