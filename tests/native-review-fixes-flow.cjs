'use strict';
// Disposable context only. Native bridge replies are fixtures, not physical Bluetooth tests.
module.exports = async function nativeReviewFixesFlow(page) {
  await page.addInitScript(() => {
    let state; const listeners = {};
    const qa = window.nativeReviewQA = { saves: 0, disconnects: 0 };
    const snapshot = () => {
      if (!state) { state = SmartTrayData.createState(); state.preferences.setupDone = true; state.connection = 'connected'; }
      return structuredClone(state);
    };
    qa.publish = () => listeners.stateChanged(snapshot());
    qa.setConnection = value => { state.connection = value; qa.publish(); };
    window.SmartTrayNative = { available: true, app: { async addListener() { return { remove() {} }; } }, plugin: {
      async addListener(name, listener) { listeners[name] = listener; return { remove() {} }; },
      async getState() { return snapshot(); }, async consumeNotificationRoute() { return {}; },
      async refreshWeather() { return snapshot(); },
      async savePreferences({ preferences }) {
        qa.saves++; const saved = structuredClone(preferences);
        return new Promise((resolve, reject) => {
          qa.finishSave = success => {
            if (!success) { reject(Error('설정 저장 실패')); return; }
            state.preferences = saved; resolve(snapshot());
          };
        });
      },
      async disconnect() {
        qa.disconnects++;
        return new Promise((resolve, reject) => {
          qa.finishDisconnect = success => {
            if (!success) { reject(Error('연결 해제 실패')); return; }
            state.connection = 'disconnected'; state.connectionError = ''; qa.publish(); resolve({ stopped: true });
          };
        });
      }
    } };
  });
  await page.goto('http://127.0.0.1:4173/#home');
  await page.waitForFunction(() => !!window.SmartTrayApp && SmartTrayApp.store.get().connection === 'connected');
  const checks = [], ok = (value, name) => { if (!value) throw Error(name); checks.push(name); };
  await page.evaluate(() => SmartTrayApp.navigate('notifications'));
  const field = page.locator('#door-open-seconds'), save = page.locator('#door-time-form button');
  await field.fill('99'); await save.click();
  await page.waitForFunction(() => nativeReviewQA.saves === 1);
  ok(await field.isDisabled() && await save.isDisabled(), '네이티브 저장 응답 대기 중 중복 조작 차단');
  await page.evaluate(() => { nativeReviewQA.publish(); document.getElementById('door-time-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  ok(await field.inputValue() === '99' && await page.evaluate(() => nativeReviewQA.saves === 1), '저장 중 이전 네이티브 스냅샷이 와도 초안 보존·중복 요청 없음');
  await page.evaluate(() => nativeReviewQA.finishSave(false));
  await page.locator('#door-time-error').waitFor();
  ok(await field.inputValue() === '99' && await page.evaluate(() => SmartTrayApp.store.get().preferences.notifications.doorOpenSeconds === 30), '저장 실패 시 기존 적용값과 수정 초안 보존');
  await save.click(); await page.waitForFunction(() => nativeReviewQA.saves === 2);
  await page.evaluate(() => nativeReviewQA.finishSave(true));
  await page.waitForFunction(() => SmartTrayApp.store.get().preferences.notifications.doorOpenSeconds === 99);
  ok(await save.isDisabled() && await field.inputValue() === '99', '저장 재시도 성공 후 확정');
  for (const route of ['connect', 'device']) {
    await page.evaluate(route => { nativeReviewQA.setConnection('connected'); SmartTrayApp.navigate(route); }, route);
    await page.waitForURL('**/#' + route);
    const action = page.locator(route === 'connect' ? '#change-device' : '#device-disconnect');
    const before = await page.evaluate(() => nativeReviewQA.disconnects);
    await action.click(); await page.getByRole('dialog').waitFor();
    await page.getByRole('button', { name: '취소', exact: true }).click();
    ok(await page.evaluate(() => nativeReviewQA.disconnects) === before, route + ': 확인 취소 시 해제 호출 없음');
    await action.click(); await page.locator('[data-action="confirm-disconnect"]').click();
    await page.waitForFunction(n => nativeReviewQA.disconnects === n, before + 1);
    ok(await action.isDisabled(), route + ': 해제 중 중복 요청 차단');
    await page.evaluate(() => nativeReviewQA.finishDisconnect(false));
    await page.waitForFunction(() => document.querySelector('#toast-root').textContent.includes('연결 해제 실패'));
    ok(await page.evaluate(() => SmartTrayApp.store.get().connection === 'connected') && await action.isEnabled(), route + ': 해제 실패 시 연결 유지·재시도 허용');
    ok(!(await page.locator('#toast-root').innerText()).includes('중지했어요'), route + ': 실패를 성공으로 안내하지 않음');
    await action.click(); await page.locator('[data-action="confirm-disconnect"]').click();
    await page.waitForFunction(n => nativeReviewQA.disconnects === n, before + 2);
    await page.evaluate(() => nativeReviewQA.finishDisconnect(true));
    await page.waitForFunction(() => SmartTrayApp.store.get().connection === 'disconnected');
    ok((await page.locator('#toast-root').innerText()).includes('연결과 자동 재연결을 중지했어요'), route + ': 재시도 성공 안내');
  }
  await page.evaluate(() => { nativeReviewQA.setConnection('connecting'); SmartTrayApp.navigate('connect'); });
  const cancel = page.locator('[data-action="cancel-connect"]');
  await cancel.click();
  ok(await page.getByRole('dialog').count() === 0, '연결 중 취소는 즉시 요청');
  await page.evaluate(() => nativeReviewQA.finishDisconnect(true));
  await page.waitForFunction(() => SmartTrayApp.store.get().connection === 'disconnected');
  ok((await page.locator('#toast-root').innerText()).includes('연결 시도를 취소했어요'), '연결 취소 완료 안내');
  return { passed: checks.length, checks };
};
