'use strict';
// Disposable Playwright context; native snapshots simulate decoded firmware, not real HC-06 I/O.
module.exports = async function firmwareUiFlow(page) {
  const checks = [], errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const ok = (value, label) => { if (!value) throw Error(label); checks.push(label); };
  await page.addInitScript(() => {
    let state; const listeners = {};
    const qa = window.firmwareQA = { commands: [] };
    const snapshot = () => {
      if (!state) {
        state = SmartTrayData.createState(); state.preferences.setupDone = true;
        state.connection = 'connected'; state.protocolReady = true; state.lastReceived = Date.now();
        state.configSync = { status: 'pending', message: '저장한 설정을 장치에 적용하고 있어요.' };
        state.trays.forEach(tray => Object.assign(tray, { present: true, sensor: 'ok', measuredAt: Date.now(), effectUntil: 0 }));
        state.preferences.trays.forEach(tray => { tray.effect.enabled = true; });
        state.preferences.umbrella.effect.enabled = true;
        Object.assign(state.umbrella, { present: true, sensor: 'ok', measuredAt: Date.now(), effectUntil: 0, fanActive: false });
      }
      return structuredClone(state);
    };
    qa.change = action => { action(state); listeners.stateChanged(snapshot()); };
    window.SmartTrayNative = { available: true, app: { async addListener() { return { remove() {} }; } }, plugin: {
      async addListener(name, listener) { listeners[name] = listener; return { remove() {} }; },
      async getState() { return snapshot(); }, async consumeNotificationRoute() { return {}; },
      async refreshWeather() { return snapshot(); },
      async savePreferences({ preferences }) { state.preferences = structuredClone(preferences); return snapshot(); },
      async command(args) {
        qa.commands.push(args);
        return new Promise((resolve, reject) => { qa.ack = success => success ? resolve({ accepted: true, req: 'a123' }) : reject(Error('장치 응답 시간 초과')); });
      }
    } };
  });
  await page.goto('http://127.0.0.1:4173/#detail/1');
  await page.waitForFunction(() => window.SmartTrayApp?.store.get().protocolReady);
  await page.evaluate(() => SmartTrayApp.navigate('detail', 1));
  const start = () => page.getByRole('button', { name: '살균 시작', exact: true });
  ok(await start().isDisabled(), '장치 설정 ACK 대기 중 수동 살균 차단');
  ok(await page.getByRole('switch', { name: '물건을 놓으면 켜기' }).count() === 0, '실제 펌웨어에 없는 거치 일반 점등 옵션 숨김');
  ok(await page.getByRole('button', { name: 'LED 미리보기' }).count() === 0, '실제 펌웨어에 없는 LED 미리보기 숨김');
  await page.evaluate(() => firmwareQA.change(s => { s.configSync.status = 'applied'; }));
  await start().click();
  await page.waitForFunction(() => firmwareQA.commands.length === 1);
  ok(await page.evaluate(() => JSON.stringify(firmwareQA.commands[0]) === JSON.stringify({ command: 'start_steril', space: 1 })), '트레이 살균은 공간 1과 start_steril 전송');
  ok(await page.getByText('살균 OFF', { exact: true }).count() > 0, 'ACK 전 출력 ON 생성 안 함');
  await start().click();
  ok(await page.evaluate(() => firmwareQA.commands.length === 1), 'ACK 대기 중 중복 수동 요청 차단');
  await page.evaluate(() => firmwareQA.ack(true));
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('접수'));
  ok(await page.getByText('살균 OFF', { exact: true }).count() > 0, 'accepted 응답만으로 출력 ON 생성 안 함');
  await page.evaluate(() => firmwareQA.change(s => { s.trays[0].effectUntil = Date.now() + 10000; }));
  ok((await page.locator('.device-state').last().innerText()).includes('살균 ON'), 'state의 실제 살균 남은 시간으로 ON 표시');
  ok(await start().isDisabled(), '실제 살균 중 중복 시작 비활성화');
  await page.evaluate(() => { firmwareQA.change(s => { s.umbrella.fanActive = true; s.umbrella.effectUntil = Date.now() + 10000; }); SmartTrayApp.navigate('trays'); });
  ok(/FAN\s*ON/.test(await page.locator('.umbrella-features').innerText()), '장치가 보고한 우산 팬 ON 표시');
  ok(/살균\s*ON/.test(await page.locator('.umbrella-features').innerText()), '장치가 보고한 우산 살균 ON 표시');
  await page.evaluate(() => { firmwareQA.change(s => { s.umbrella.effectUntil = 0; }); SmartTrayApp.navigate('umbrella'); });
  await start().click(); await page.waitForFunction(() => firmwareQA.commands.length === 2);
  ok(await page.evaluate(() => firmwareQA.commands[1].space === 3), '우산 살균은 공간 3 전송');
  await page.evaluate(() => firmwareQA.ack(false));
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('확인'));
  ok((await page.locator('.umbrella-effect-panel .device-state').innerText()).includes('살균 OFF'), '응답 실패 시 살균 OFF 유지');
  await page.evaluate(() => firmwareQA.change(s => { s.lastReceived = Date.now() - 46000; }));
  ok(await start().isDisabled(), '오래된 상태에서는 수동 시작 비활성화');
  for (const width of [320, 393, 495]) {
    await page.setViewportSize({ width, height: 852 });
    for (const route of ['detail', 'umbrella', 'trays']) {
      await page.evaluate(route => SmartTrayApp.navigate(route, 1), route);
      ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${width}px ${route} 가로 넘침 없음`);
    }
  }
  if (errors.length) throw Error(errors.join(' | '));
  ok(true, '브라우저 실행 오류 없음');
  return { passed: checks.length, checks };
};
