(function (root) {
  'use strict';
  function createTransport(store, runtime = root.SmartTrayNative) {
    let nativeMode = !!runtime?.available;
    let mock = nativeMode ? null : root.SmartTrayTransport.createMockTransport(store);
    let listener, openedListener, appListener, finishPendingConnection;
    let connecting = false, connectionOperation = 0;
    let disposed = false, weatherRequest, notificationRequest;
    let openedFromNotification = false;
    const plugin = runtime?.plugin;
    const report = error => {
      const message = error?.message || '장치 작업을 완료하지 못했어요. 다시 확인해 주세요.';
      store.update(s => { s.connectionError = message; });
      root.dispatchEvent?.(new CustomEvent('smarttray-error', { detail: message }));
    };
    function apply(snapshot) {
      if (!snapshot || !nativeMode || disposed) return;
      store.update(s => {
        for (const key of ['preferences', 'connection', 'connectionError', 'scanState', 'scanResults',
          'bluetooth', 'connectionPhase', 'retryAttempt', 'deviceAddress', 'transportBytesReceived', 'lastTransportReceived', 'lastReceived', 'door', 'trays', 'umbrella', 'weather', 'location', 'notifications', 'activities', 'activityStorageError', 'protocolReady', 'protocolMessage', 'deviceName', 'notificationsAllowed', 'notificationPermission', 'notificationSettingsRequired']) {
          if (snapshot[key] !== undefined) s[key] = snapshot[key];
        }
        s.demo = false;
        s.runtime = 'android';
      });
      store.save(); // A UI cache only. Android SharedPreferences is authoritative.
    }
    async function call(method, args = {}) {
      try { return await plugin[method](args); } catch (error) { report(error); return false; }
    }
    async function requestBluetoothAccess(discovery, cancelled = () => false) {
      if (disposed || !nativeMode || cancelled()) return false;
      const permission = await call('requestBluetoothPermissions', { discovery });
      if (disposed || !nativeMode || cancelled()) return false;
      if (permission?.granted === true) return true;
      // Only a new user action with a permanently denied permission opens Settings.
      if (permission?.settingsRequired === true) {
        if (permission.message) report(new Error(permission.message));
        await call('openBluetoothPermissions');
      }
      return false;
    }
    async function initialize() {
      if (!nativeMode) return;
      listener = await plugin.addListener('stateChanged', apply);
      openedListener = await plugin.addListener('notificationOpened', event => {
        if (nativeMode) root.dispatchEvent(new CustomEvent('smarttray-route', { detail: event.route }));
      });
      appListener = await runtime.app?.addListener('appStateChange', async ({ isActive }) => {
        if (isActive && nativeMode && !disposed) {
          apply(await call('getState'));
          if (nativeMode && !disposed) root.dispatchEvent?.(new CustomEvent('smarttray-app-active'));
        }
      });
      const snapshot = await call('getState');
      if (snapshot) apply(snapshot);
      const launch = await call('consumeNotificationRoute');
      if (launch?.route) { openedFromNotification = true; root.location.hash = launch.route; }
    }
    const adapter = {
      get isNative() { return nativeMode; },
      get hasNative() { return !!runtime?.available; },
      get openedFromNotification() { return openedFromNotification; },
      async initialize() { try { await initialize(); } catch (error) { report(error); } },
      async useDemo() {
        if (!nativeMode) return;
        if (!await adapter.disconnect()) return false;
        nativeMode = false;
        mock = root.SmartTrayTransport.createMockTransport(store);
        store.update(s => Object.assign(s, root.SmartTrayData.createState(s.preferences), { runtime: 'demo', activities: store.loadActivities() }));
        return true;
      },
      async useNative() {
        if (!runtime?.available) return;
        mock?.dispose(); mock = null; nativeMode = true;
        const snapshot = await call('getState');
        if (snapshot) apply(snapshot);
      },
      async scan() {
        if (!nativeMode) return mock.scan();
        if (await requestBluetoothAccess(true)) await call('scan');
      },
      async listPairedDevices() {
        if (!nativeMode) return mock.scan();
        if (await requestBluetoothAccess(false)) await call('listPairedDevices');
      },
      async enableBluetooth() {
        if (!nativeMode) return;
        if (await requestBluetoothAccess(false)) await call('enableBluetooth');
        apply(await call('getState'));
      },
      async openBluetoothSettings() { if (nativeMode) return call('openBluetoothSettings'); },
      async connect(options = {}) {
        if (!nativeMode) return mock.connect(options);
        if (connecting || disposed) return false;
        if (!options.address) { report(new Error('주변 장치를 검색한 뒤 내 트레이를 선택해 주세요.')); return false; }
        connecting = true;
        const operation = ++connectionOperation;
        const cancelled = () => disposed || !nativeMode || operation !== connectionOperation;
        try {
          if (!await requestBluetoothAccess(false, cancelled) || cancelled()) return false;
          if (!await call('connect', { address: options.address }) || cancelled()) return false;
          const snapshot = await call('getState');
          if (cancelled()) return false;
          apply(snapshot);
          // Native pairing/connection/retry deadlines own the operation. A JS timeout must
          // never abandon a still-running service connection. Cancel explicitly instead.
          return await new Promise(resolve => {
            let cancel;
            const finish = result => { cancel?.(); finishPendingConnection = undefined; resolve(result); };
            finishPendingConnection = finish;
            const inspect = s => {
              if (s.connection === 'connected' && (!s.deviceAddress || s.deviceAddress === options.address)) finish(true);
              else if (s.connection === 'disconnected' || disposed || !nativeMode) finish(false);
            };
            cancel = store.subscribe(inspect);
            inspect(store.get());
          });
        } finally { connecting = false; }
      },
      async disconnect() {
        if (!nativeMode) return mock.disconnect();
        connectionOperation++; finishPendingConnection?.(false);
        const result = await call('disconnect');
        if (result) { finishPendingConnection?.(false); apply(await call('getState')); }
        return result;
      },
      refresh() { if (!nativeMode) mock.refresh(); },
      async savePreferences() {
        if (!nativeMode) { store.save(); mock.settingsChanged(); return !store.get().storageError; }
        const result = await call('savePreferences', { preferences: store.get().preferences });
        if (result) apply(result);
        return !!result;
      },
      async markRead(ids) { if (nativeMode) { const result = await call('markRead', { ids }); if (result) apply(result); } },
      async requestNotificationPermission() {
        if (!nativeMode || disposed) return false;
        if (notificationRequest) return notificationRequest;
        const request = Promise.resolve().then(async () => {
          const result = await call('requestNotificationPermission');
          apply(await call('getState'));
          if (result?.granted === false && !disposed && nativeMode) {
            // A declined notification permission is not a Bluetooth connection failure.
            root.dispatchEvent?.(new CustomEvent('smarttray-error', { detail: '알림이 꺼져 있어 물건·우산·열린 문 안내를 받을 수 없어요. 알림을 허용해 주세요.' }));
          }
          return result;
        });
        notificationRequest = request;
        try { return await request; }
        finally { if (notificationRequest === request) notificationRequest = undefined; }
      },
      async openNotificationSettings() { if (nativeMode) return call('openNotificationSettings'); },
      async previewNotification() {
        if (!nativeMode) return mock.previewNotification();
        const permission = await adapter.requestNotificationPermission();
        if (!permission?.granted) return false;
        return call('previewNotification');
      },
      async playLed(id) { return nativeMode ? call('command', { command: 'led', trayId: id }) : mock.playLed(id); },
      async playUmbrellaEffect() {
        // No umbrella command or successful device state is invented before firmware agreement.
        return !nativeMode && !disposed ? mock.playUmbrellaEffect() : false;
      },
      async playEffect(id) { return !nativeMode && !disposed && root.SmartTrayData.CONFIG.trayIds.includes(id) ? mock.playEffect(id) : false; },
      async refreshWeather({ automatic = false } = {}) {
        if (disposed) return false;
        if (weatherRequest) return weatherRequest;
        // Share an in-flight request across home re-selection, app resume and the manual button.
        // Once settled, the next home visit starts a fresh lookup, even with a valid cached forecast.
        const request = Promise.resolve().then(async () => {
          if (disposed) return false;
          if (!nativeMode) return mock.refreshWeather({ automatic });
          const result = await call('refreshWeather', { automatic });
          if (result) apply(result);
          return result;
        });
        weatherRequest = request;
        try { return await request; }
        finally { if (weatherRequest === request) weatherRequest = undefined; }
      },
      async openLocationSettings() { if (nativeMode) return call('openLocationSettings'); },
      async openAppLocationSettings() { if (nativeMode) return call('openAppLocationSettings'); },
      setUmbrella(...args) { return !nativeMode && mock.setUmbrella(...args); },
      setUmbrellaSensor(...args) { return !nativeMode && mock.setUmbrellaSensor(...args); },
      setWeather(...args) { return !nativeMode && mock.setWeather(...args); },
      setDoor(...args) { return !nativeMode && mock.setDoor(...args); },
      setTray(...args) { return !nativeMode && mock.setTray(...args); },
      setSensor(...args) { return !nativeMode && mock.setSensor(...args); },
      loseConnection() { return !nativeMode && mock.loseConnection(); },
      pause(...args) { return !nativeMode && mock.pause(...args); },
      scenario(...args) { return !nativeMode && mock.scenario(...args); },
      dispose() { disposed = true; connectionOperation++; finishPendingConnection?.(false); mock?.dispose(); listener?.remove(); openedListener?.remove(); appListener?.remove(); }
    };
    return adapter;
  }
  root.SmartTrayRuntime = { createTransport };
})(typeof window !== 'undefined' ? window : globalThis);
