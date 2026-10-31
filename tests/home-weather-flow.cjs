'use strict';
// Run in a disposable browser context. Native bridge responses are fixtures; no coordinates leave it.
module.exports = async function homeWeatherFlow(page) {
  await page.addInitScript(() => {
    let state;
    const listeners = {};
    const qa = window.homeWeatherQA = { requests: [], appActive: null, complete: null };
    const snapshot = () => {
      if (!state) {
        state = SmartTrayData.createState(); state.preferences.setupDone = true;
        state.location = { permission: 'granted', enabled: true };
      }
      return structuredClone(state);
    };
    window.SmartTrayNative = { available: true, app: {
      async addListener(name, listener) { if (name === 'appStateChange') qa.appActive = listener; return { remove() {} }; }
    }, plugin: {
      async addListener(name, listener) { listeners[name] = listener; return { remove() {} }; },
      async getState() { return snapshot(); },
      async consumeNotificationRoute() { return {}; },
      async refreshWeather(options) {
        qa.requests.push(options); state.weather = { status: 'loading', windows: [] }; listeners.stateChanged(snapshot());
        return new Promise(resolve => { qa.complete = () => {
          state.weather = { status: 'ready', fetchedAt: Date.now(), windows: [] };
          listeners.stateChanged(snapshot()); resolve(snapshot());
        }; });
      }
    } };
  });
  await page.goto('http://127.0.0.1:4173/#home');
  const checks = [];
  const count = async (n, label) => {
    await page.waitForFunction(n => homeWeatherQA.requests.length === n, n);
    checks.push(label);
  };
  const complete = async () => {
    await page.evaluate(() => homeWeatherQA.complete());
    await page.waitForFunction(() => SmartTrayApp.store.get().weather.status === 'ready');
  };
  const home = () => page.locator('.bottom-nav a[href="#home"]');
  await count(1, 'initial home entry refreshes');
  await home().click(); await home().click();
  await count(1, 'home clicks during lookup share one request'); await complete();
  await home().click(); await count(2, 'home re-selection refreshes'); await complete();
  await page.locator('.bottom-nav a[href="#trays"]').click();
  await page.waitForURL('**/#trays');
  if (await page.evaluate(() => homeWeatherQA.requests.length) !== 2) throw Error('Non-home navigation refreshed weather');
  checks.push('other tabs do not refresh');
  await home().click(); await count(3, 'return to home refreshes'); await complete();
  await page.evaluate(() => SmartTrayApp.navigate('umbrella')); await page.waitForURL('**/#umbrella');
  await page.goBack(); await count(4, 'history back to home refreshes'); await complete();
  await page.evaluate(() => homeWeatherQA.appActive({ isActive: true }));
  await count(5, 'foreground return while on home refreshes'); await complete();
  await page.evaluate(() => SmartTrayApp.store.update(s => { s.preferences.trays[0].alias = '검증용 별칭'; }));
  await page.getByText('검증용 별칭', { exact: true }).waitFor();
  if (await page.evaluate(() => homeWeatherQA.requests.length) !== 5) throw Error('State rendering caused another lookup');
  checks.push('state re-render does not refresh');
  await page.setViewportSize({ width: 1280, height: 954 });
  await page.locator('.desktop-nav a[href="#home"]').click(); await count(6, 'desktop home re-selection refreshes'); await complete();
  if (!await page.evaluate(() => homeWeatherQA.requests.every(r => r.automatic === true))) throw Error('Automatic permission intent missing');
  checks.push('all home refreshes are automatic');
  return { passed: checks.length, checks };
};
