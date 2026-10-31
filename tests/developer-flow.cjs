'use strict';
// Run only in a freshly created, disposable Playwright browser context.
module.exports = async function developerFlow(page) {
  const checks = [];
  const ok = (condition, label) => { if (!condition) throw Error(label); checks.push(label); };
  if (await page.evaluate(() => localStorage.getItem('smart-tray.preferences.v1')) !== null) {
    throw Error('Use a fresh QA browser context; never reset an existing user profile.');
  }
  await page.reload();
  await page.waitForSelector('#location-setup');
  await page.evaluate(async () => {
    await SmartTrayApp.transport.connect({ demo: true });
    SmartTrayApp.navigate('settings');
  });
  await page.locator('.settings-link[data-action="developer"]').click();
  await page.locator('#weight-1').fill('171.3');
  const before = await page.evaluate(async () => {
    const drawer = document.querySelector('.developer-drawer');
    await Promise.all(drawer.getAnimations().map(animation => animation.finished));
    const body = drawer.querySelector('.developer-body');
    body.scrollTop = 340;
    const fixture = window.studioQa = {
      drawer, body, input: document.querySelector('#weight-1'),
      sensor: document.querySelector('#sensor-2'), weather: document.querySelector('#weather-condition'),
      replacements: 0, animations: 0, changes: 0
    };
    fixture.observer = new MutationObserver(records => { fixture.replacements += records.length; });
    fixture.observer.observe(document.querySelector('#modal-root'), { childList: true });
    drawer.addEventListener('animationstart', () => { fixture.animations++; });
    fixture.input.addEventListener('change', () => { fixture.changes++; });
    return { received: SmartTrayApp.store.get().lastReceived, weight: SmartTrayApp.store.get().trays[0].weight, scroll: body.scrollTop };
  });
  // Wait for the actual periodic refresh instead of manufacturing its state change.
  await page.waitForFunction(received => SmartTrayApp.store.get().lastReceived > received, before.received, { timeout: 12000 });
  const after = await page.evaluate(() => {
    const fixture = window.studioQa;
    return {
      sameDrawer: fixture.drawer === document.querySelector('.developer-drawer'),
      sameInput: fixture.input === document.querySelector('#weight-1'),
      focused: document.activeElement === fixture.input,
      value: fixture.input.value, scroll: fixture.body.scrollTop,
      weight: SmartTrayApp.store.get().trays[0].weight,
      replacements: fixture.replacements, animations: fixture.animations, changes: fixture.changes
    };
  });
  ok(after.sameDrawer && after.sameInput && after.replacements === 0, '주기 수신 중 드로어와 입력 DOM 유지');
  ok(after.animations === 0, '주기 수신 중 열기 애니메이션 재실행 없음');
  ok(after.focused && after.value === '171.3' && after.scroll === before.scroll, '입력 초안·포커스·스크롤 유지');
  ok(after.weight === before.weight && after.changes === 0, '주기 수신으로 미완성 무게 입력을 확정하지 않음');

  await page.evaluate(() => {
    SmartTrayApp.transport.setTray(2, true, 52.4);
    SmartTrayApp.transport.setSensor(2, 'error');
    SmartTrayApp.transport.setWeather('clear');
  });
  await page.waitForFunction(() => document.querySelector('#sensor-2').value === 'error' && document.querySelector('#weather-condition').value === 'clear');
  ok(await page.locator('#present-2').isChecked() && await page.locator('#weight-2').inputValue() === '52.4', '다른 센서와 무게 변경 즉시 반영');
  ok(await page.evaluate(() => studioQa.sensor === document.querySelector('#sensor-2') && studioQa.weather === document.querySelector('#weather-condition')), '선택 컨트롤 DOM 유지');
  ok(await page.evaluate(() => document.activeElement === studioQa.input && studioQa.input.value === '171.3' && studioQa.changes === 0), '다른 상태 변경 중에도 입력 초안 유지');
  await page.evaluate(() => SmartTrayApp.transport.setWeather('rain'));
  await page.waitForFunction(() => document.querySelector('#weather-condition').value === 'rain');
  ok(await page.evaluate(() => studioQa.animations === 0 && studioQa.replacements === 0), '실제 상태 변경에도 드로어 재생성 없음');

  await page.locator('#sensor-1').focus();
  await page.waitForFunction(() => SmartTrayApp.store.get().trays[0].weight === 171.3);
  ok(await page.evaluate(() => studioQa.changes === 1), '사용자가 입력을 마치면 무게를 한 번만 확정');
  await page.locator('#pause-data').check();
  await page.waitForFunction(() => document.querySelector('[data-action="door-open"]').disabled);
  ok(await page.locator('#present-1').isDisabled() && (await page.locator('.dev-connection').innerText()).includes('수신 지연'), '수신 지연 상태와 조작 제한 반영');
  await page.locator('#pause-data').uncheck();
  await page.waitForFunction(() => !document.querySelector('#present-1').disabled);
  ok((await page.locator('.dev-connection').innerText()).includes('연결됨'), '수신 재개 상태 반영');
  await page.locator('.dev-connection [data-action="disconnect"]').click();
  await page.waitForFunction(() => SmartTrayApp.store.get().connection === 'disconnected');
  ok(await page.locator('#weight-1').isDisabled(), '연결 해제 시 입력 비활성화');
  await page.locator('.dev-connection [data-action="reconnect"]').click();
  await page.waitForFunction(() => SmartTrayApp.store.get().connection === 'connected');
  ok(await page.locator('#weight-1').isEnabled(), '재연결 시 입력 활성화');
  ok(await page.evaluate(() => studioQa.drawer === document.querySelector('.developer-drawer') && studioQa.animations === 0), '연결 상태 전환 중 드로어 유지');
  await page.keyboard.press('Escape');
  ok(await page.locator('.developer-drawer').count() === 0, '닫기 동작 유지');
  await page.evaluate(() => SmartTrayApp.openDeveloper());
  await page.locator('.developer-drawer').waitFor();
  ok(await page.evaluate(() => studioQa.drawer !== document.querySelector('.developer-drawer')), '다시 열 때만 새 드로어 생성');
  await page.evaluate(() => studioQa.observer.disconnect());
  return { passed: checks.length, checks };
};
