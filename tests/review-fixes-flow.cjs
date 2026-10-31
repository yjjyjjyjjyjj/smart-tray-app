'use strict';
// Run in a fresh disposable browser context; never edit the user's saved preferences.
module.exports = async function reviewFixesFlow(page) {
  const checks = [];
  const ok = (condition, label) => { if (!condition) throw Error(label); checks.push(label); };
  if (await page.evaluate(() => localStorage.getItem('smart-tray.preferences.v1')) !== null) throw Error('Fresh context required');
  await page.reload(); await page.waitForSelector('#location-setup');
  await page.evaluate(async () => { await SmartTrayApp.transport.connect({ demo: true }); SmartTrayApp.navigate('notifications'); });
  const field = page.locator('#door-open-seconds');
  const save = page.locator('#door-time-form button[type="submit"]');
  const saved = () => page.evaluate(() => SmartTrayApp.store.get().preferences.notifications.doorOpenSeconds);
  await field.fill('123');
  const received = await page.evaluate(() => SmartTrayApp.store.get().lastReceived);
  await page.waitForFunction(received => SmartTrayApp.store.get().lastReceived > received, received, { timeout: 12000 });
  ok(await field.inputValue() === '123' && await field.evaluate(el => el === document.activeElement), '실제 주기 갱신 중 시간 초안·포커스 보존');
  ok(await saved() === 30, '입력 중 기존 시간은 적용 상태 유지');
  await page.getByRole('switch', { name: '현관문 열림 알림', exact: true }).uncheck();
  ok(await field.inputValue() === '123' && await saved() === 30, '포커스 이동과 다른 설정 저장도 시간 초안을 확정하지 않음');
  await page.evaluate(() => SmartTrayApp.navigate('settings')); await page.waitForURL('**/#settings');
  await page.evaluate(() => SmartTrayApp.navigate('notifications')); await field.waitFor();
  ok(await field.inputValue() === '123', '화면 전환 뒤 미저장 초안 보존');
  await save.click(); await page.waitForFunction(() => SmartTrayApp.store.get().preferences.notifications.doorOpenSeconds === 123);
  ok(await save.isDisabled(), '저장 버튼으로 확정하고 중복 저장 비활성화');
  for (const value of ['9', '301', '10.5', '']) {
    await field.fill(value); await save.click(); await page.locator('#door-time-error').waitFor();
    ok(await saved() === 123 && await field.inputValue() === value, `잘못된 시간 ${value || '빈 값'}은 저장하지 않고 수정 가능`);
  }
  for (const value of ['10', '300']) {
    await field.fill(value); await save.click();
    await page.waitForFunction(v => SmartTrayApp.store.get().preferences.notifications.doorOpenSeconds === v, Number(value));
    ok(await saved() === Number(value), `${value}초 경계 저장`);
  }
  await field.fill('111'); await page.getByRole('switch', { name: '전체 알림', exact: true }).uncheck();
  ok(await field.isDisabled() && await save.isDisabled(), '전체 알림 OFF일 때 시간 입력·저장 비활성화');
  await page.getByRole('switch', { name: '전체 알림', exact: true }).check();
  ok(await field.inputValue() === '111' && await saved() === 300, '전체 알림 ON으로 돌아와도 초안 유지');
  await page.reload(); await page.waitForSelector('#location-setup');
  await page.evaluate(async () => { await SmartTrayApp.transport.connect({ demo: true }); SmartTrayApp.navigate('notifications'); });
  await field.waitFor(); ok(await field.inputValue() === '300', '재실행은 마지막 저장값 복원');
  for (const width of [320, 393, 495]) {
    await page.setViewportSize({ width, height: 852 });
    ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${width}px에서 저장 행 가로 넘침 없음`);
  }
  await page.evaluate(() => SmartTrayApp.navigate('detail', 1)); await page.waitForURL('**/#detail/1');
  const effect = page.getByRole('button', { name: '살균 시작', exact: true });
  ok(await effect.isDisabled(), '살균 OFF이면 수동 시작 버튼 비활성화');
  ok(!await page.evaluate(() => SmartTrayApp.transport.playEffect(1)), '직접 실행 함수로도 살균 OFF 우회 불가');
  await page.getByRole('switch', { name: '살균 활성화', exact: true }).check();
  await effect.click(); ok(await effect.isDisabled(), '실행 중 살균 중복 시작 차단');
  await page.getByRole('switch', { name: '살균 활성화', exact: true }).uncheck();
  ok(await page.evaluate(() => SmartTrayApp.store.get().trays[0].effectUntil === 0) && await effect.isDisabled(), '살균 OFF 전환 시 즉시 종료');
  await page.evaluate(() => SmartTrayApp.navigate('connect')); await page.waitForURL('**/#connect');
  await page.getByRole('button', { name: '다른 장치 연결하기' }).click();
  await page.getByRole('dialog').waitFor();
  ok(await page.evaluate(() => SmartTrayApp.store.get().connection === 'connected'), '다른 장치 연결도 해제 확인 전 연결 유지');
  await page.getByRole('button', { name: '취소', exact: true }).click();
  ok(await page.evaluate(() => SmartTrayApp.store.get().connection === 'connected'), '확인 취소 시 연결 유지');
  await page.getByRole('button', { name: '다른 장치 연결하기' }).click();
  await page.locator('[data-action="confirm-disconnect"]').click();
  await page.waitForFunction(() => SmartTrayApp.store.get().connection === 'disconnected');
  ok((await page.locator('#toast-root').innerText()).includes('연결과 자동 재연결을 중지했어요'), '확인 후 성공할 때만 해제 완료 안내');
  return { passed: checks.length, checks };
};
