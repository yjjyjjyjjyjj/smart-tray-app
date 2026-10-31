"use strict";
const test = require('node:test');
const assert = require('node:assert/strict');
require('../js/mock-data.js'); require('../js/mock-transport.js');
const D = SmartTrayData;

test('우산 살균의 이전 설정 이관은 팬·별칭·날씨 알림 설정을 보존한다', () => {
  const old = D.defaults(); delete old.umbrella.effect;
  old.umbrella.fan.duration=60; old.trays[0].alias='차키'; old.notifications.umbrella=false;
  const p=D.loadPreferences({getItem:()=>JSON.stringify(old)});
  assert.deepEqual(p.umbrella.effect,{enabled:false,onDetect:false,onDoor:false,duration:0});
  assert.equal(p.umbrella.fan.duration,60); assert.equal(p.trays[0].alias,'차키'); assert.equal(p.notifications.umbrella,false);
});
test('우산 살균 스위치와 네 가지 시간은 저장·복원하고 잘못된 값은 기본값을 사용한다', () => {
  for(const duration of [0,15,30,60]) {
    const input=D.defaults(); Object.assign(input.umbrella.effect,{enabled:duration>0,onDetect:true,onDoor:true,duration}); input.umbrella.fan.duration=15;
    const saved=D.loadPreferences({getItem:()=>JSON.stringify(input)});
    assert.deepEqual(saved.umbrella,input.umbrella);
  }
  for(const duration of [5,10,20,5.5,61,'15',true,null]) {
    const input={umbrella:{effect:{enabled:'true',onDetect:1,onDoor:null,duration}}};
    assert.deepEqual(D.loadPreferences({getItem:()=>JSON.stringify(input)}).umbrella.effect,D.defaults().umbrella.effect);
  }
});
async function setup(context,fn) {
  const store=D.createStore({getItem:()=>null,setItem(){}}),t=SmartTrayTransport.createMockTransport(store);
  await t.connect({demo:true});
  context.mock.timers.enable({apis:['Date','setTimeout'],now:Date.now()});
  try { await fn(store,t,ms=>context.mock.timers.tick(ms)); }
  finally {t.dispose();context.mock.timers.reset();}
}
test('우산 거치 자동 실행은 설정 시간을 지키고 같은 센서값으로 재시작하거나 알리지 않는다', async c=>setup(c,(store,t,tick)=>{
  Object.assign(store.get().preferences.umbrella.effect,{enabled:true,onDetect:true,duration:15});
  t.setUmbrella(false); t.setUmbrella(true); const until=store.get().umbrella.effectUntil;
  assert.equal(until,Date.now()+15000); tick(2000); t.setUmbrella(true);
  assert.equal(store.get().umbrella.effectUntil,until);
  tick(12999); assert.ok(store.get().umbrella.effectUntil>Date.now());
  tick(1); assert.equal(store.get().umbrella.effectUntil,0);
  assert.equal(store.get().notifications.length,0);
  assert.ok(store.get().trays.every(tray=>tray.effectUntil===0));
}));
test('문 열림 자동 실행은 우산이 있고 기능이 켜진 경우만 실행한다', async c=>setup(c,(store,t,tick)=>{
  Object.assign(store.get().preferences.umbrella.effect,{enabled:true,onDoor:true,duration:15});
  t.setDoor(true); const until=store.get().umbrella.effectUntil; assert.ok(until>Date.now());
  tick(1000); t.setDoor(true); assert.equal(store.get().umbrella.effectUntil,until);
  t.setUmbrella(false); assert.equal(store.get().umbrella.effectUntil,0);
  t.setDoor(false); t.setDoor(true); assert.equal(store.get().umbrella.effectUntil,0);
  t.setDoor(false); t.setUmbrella(true); store.get().preferences.umbrella.effect.enabled=false;
  t.setDoor(true); assert.equal(store.get().umbrella.effectUntil,0);
}));
test('살균 OFF·제거·센서 오류·수신 지연·연결 유실은 가상 살균을 종료한다', async c=>setup(c,(store,t)=>{
  const setting=store.get().preferences.umbrella.effect;
  assert.equal(t.playUmbrellaEffect(),false); setting.enabled=true; setting.duration=15;
  for(const stop of [()=>{setting.enabled=false;t.settingsChanged();},()=>t.setUmbrella(false),()=>t.setUmbrellaSensor('error'),()=>t.pause(true)]) {
    setting.enabled=true; setting.duration=15; t.pause(false); t.setUmbrellaSensor('ok'); t.setUmbrella(true);
    assert.equal(t.playUmbrellaEffect(),true); stop(); assert.equal(store.get().umbrella.effectUntil,0);
  }
  t.pause(false); t.setUmbrellaSensor('ok'); t.setUmbrella(true);
  assert.equal(t.playUmbrellaEffect(),true); t.loseConnection();
  assert.equal(store.get().umbrella.effectUntil,0); assert.equal(t.playUmbrellaEffect(),false);
}));
test('센서 대기·오류·빈 공간·직접 연결 해제에서는 살균 시작을 허용하지 않는다', async c=>setup(c,(store,t)=>{
  Object.assign(store.get().preferences.umbrella.effect,{enabled:true,duration:15});
  for(const sensor of ['waiting','error']) {t.setUmbrellaSensor(sensor);assert.equal(t.playUmbrellaEffect(),false);}
  t.setUmbrellaSensor('ok'); t.setUmbrella(false); assert.equal(t.playUmbrellaEffect(),false);
  t.setUmbrella(true); assert.equal(t.playUmbrellaEffect(),true);
  t.disconnect(); assert.equal(store.get().umbrella.effectUntil,0);assert.equal(t.playUmbrellaEffect(),false);
}));

test('0초는 저장된 활성화를 끄고 실행 중인 살균·수동·자동 시작을 모두 차단한다', async c=>setup(c,(store,t,tick)=>{
  const effect=store.get().preferences.umbrella.effect;
  Object.assign(effect,{enabled:true,onDetect:true,onDoor:true,duration:15});
  assert.equal(t.playUmbrellaEffect(),true);
  tick(1000); effect.duration=0; t.settingsChanged();
  assert.equal(store.get().umbrella.effectUntil,0);
  assert.equal(t.playUmbrellaEffect(),false);
  t.setUmbrella(false); t.setUmbrella(true); t.setDoor(false); t.setDoor(true);
  assert.equal(store.get().umbrella.effectUntil,0);
  tick(60000); assert.equal(store.get().umbrella.effectUntil,0);
  const restored=D.loadPreferences({getItem:()=>JSON.stringify(store.get().preferences)});
  assert.equal(restored.umbrella.effect.duration,0); assert.equal(restored.umbrella.effect.enabled,false);
  assert.equal(restored.umbrella.effect.onDetect,true); assert.equal(restored.umbrella.effect.onDoor,true);
}));
test('이전 5·10·20초는 사용 안 함으로 이전하고 일반 트레이 시간은 유지한다',()=>{
  for(const duration of [5,10,20]) {
    const saved=D.defaults(); Object.assign(saved.umbrella.effect,{enabled:true,onDetect:true,duration});
    saved.umbrella.fan.duration=60; saved.trays[0].effect.duration=duration;
    const next=D.loadPreferences({getItem:()=>JSON.stringify(saved)});
    assert.equal(next.umbrella.effect.duration,0); assert.equal(next.umbrella.effect.enabled,false);
    assert.equal(next.umbrella.fan.duration,60); assert.equal(next.trays[0].effect.duration,duration);
  }
});
