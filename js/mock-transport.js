(function (root) {
  'use strict';
  const { CONFIG, isFresh, trayStatus, umbrellaStatus, notificationForDoor, trayActivityTitle, trayNotificationMessage, umbrellaNotification, doorLeftOpenNotification } = root.SmartTrayData;
  function createMockTransport(store) {
    let connectionAttempt = 0;
    let scanAttempt = 0;
    const effectTimers = new Map();
    const ledTimers = new Map();
    let sequence = 0;
    let doorTimer, umbrellaEffectTimer;
    const id = () => `${Date.now()}-${++sequence}`;
    function log(state, type, title, description = '', trayId = null, trayIds = null) {
      state.activities.unshift({ id: id(), type, title, description, trayId, trayIds, time: Date.now() });
      state.activities = state.activities.slice(0, 200);
    }
    function freshen(state) {
      state.lastReceived = Date.now();
      state.trays.forEach(tray => { if (tray.sensor === 'ok') tray.measuredAt = state.lastReceived; });
      if (state.umbrella.sensor === 'ok') state.umbrella.measuredAt = state.lastReceived;
    }
    function addNotice(state, notification) {
      if (!notification) return;
      state.notifications.unshift({ ...notification, id: id(), time: Date.now(), read: false });
      state.notifications = state.notifications.slice(0, 50);
      log(state, 'notification', notification.title, notification.message, null, notification.trayIds);
    }
    function notify(state) {
      addNotice(state, notificationForDoor(state));
      state.lastNotifiedEvent = state.doorEvent;
      addNotice(state, umbrellaNotification(state));
    }
    function scheduleDoor() {
      clearTimeout(doorTimer);
      const state = store.get();
      if (state.doorOpenedAt == null || state.doorLeftOpenNotified || !state.preferences.notifications.enabled || !state.preferences.notifications.doorLeftOpen) return;
      const remaining = state.doorOpenedAt + Math.max(10, Math.min(300, state.preferences.notifications.doorOpenSeconds)) * 1000 - Date.now();
      doorTimer = setTimeout(() => store.update(s => {
        const notice = doorLeftOpenNotification(s);
        if (notice) { s.doorLeftOpenNotified = true; addNotice(s, notice); }
      }), Math.max(0, remaining));
    }
    function runEffect(state, trayId) {
      const tray = state.trays[trayId - 1];
      const setting = state.preferences.trays[trayId - 1].effect;
      if (!setting.enabled || !CONFIG.effectDurations.includes(setting.duration) || trayStatus(state, tray).key !== 'present' || tray.effectUntil > Date.now()) return false;
      tray.effectUntil = Date.now() + setting.duration * 1000;
      clearTimeout(effectTimers.get(trayId));
      effectTimers.set(trayId, setTimeout(() => store.update(s => { s.trays[trayId - 1].effectUntil = 0; }), setting.duration * 1000));
      return true;
    }
    function stopUmbrellaEffect(state) {
      clearTimeout(umbrellaEffectTimer); state.umbrella.effectUntil = 0;
    }
    function runUmbrellaEffect(state) {
      const setting = state.preferences.umbrella.effect;
      if (!setting.enabled || !CONFIG.umbrellaEffectDurations.includes(setting.duration) || umbrellaStatus(state).key !== 'present' || state.umbrella.effectUntil > Date.now()) return false;
      state.umbrella.effectUntil = Date.now() + setting.duration * 1000;
      clearTimeout(umbrellaEffectTimer);
      umbrellaEffectTimer = setTimeout(() => store.update(stopUmbrellaEffect), setting.duration * 1000);
      return true;
    }
    function normalizeSensors(state) {
      state.trays.forEach(tray => { if (tray.sensor === 'waiting') tray.sensor = 'ok'; });
      if (state.door === 'unknown') state.door = 'closed';
      if (state.umbrella.sensor === 'waiting') state.umbrella.sensor = 'ok';
      freshen(state);
      state.trays.forEach(tray => { tray.ledActive = tray.present && tray.sensor === 'ok' && state.preferences.trays[tray.id - 1].led.enabled && state.preferences.trays[tray.id - 1].led.onDetect; });
    }
    return {
      // These methods manipulate browser domain state. They are not a wire protocol.
      async scan() {
        const attempt = ++scanAttempt;
        store.update(s => { s.scanState = 'scanning'; s.scanResults = []; s.connectionError = ''; });
        await new Promise(resolve => setTimeout(resolve, 950));
        if (attempt !== scanAttempt) return;
        store.update(s => { s.scanState = 'done'; s.scanResults = [{ name: 'AMATTA', signal: '가까움' }]; });
      },
      async connect({ fail = false, demo = false } = {}) {
        const attempt = ++connectionAttempt;
        store.update(s => { s.connection = 'connecting'; s.connectionError = ''; });
        await new Promise(resolve => setTimeout(resolve, demo ? 150 : 1100));
        if (attempt !== connectionAttempt) return false;
        store.update(s => {
          if (fail) { s.connection = 'disconnected'; s.connectionError = '장치와 연결하지 못했어요. 장치 전원을 확인하고 다시 시도해 주세요.'; log(s, 'device', '연결하지 못했어요', '가상 연결 실패'); return; }
          s.connection = 'connected'; s.demo = true; s.paused = false;
          normalizeSensors(s);
          log(s, 'device', `${CONFIG.appName}가 연결되었어요`, '가상 장치 · 현관');
          if (!s.activities.some(a => a.type === 'placed')) {
            log(s, 'removed', trayActivityTitle(s.preferences.trays[1].alias, 'removed'), '트레이 02 · 물건 없음', 2);
            log(s, 'placed', trayActivityTitle(s.preferences.trays[0].alias, 'placed'), '트레이 01 · 128.5 g', 1);
          }
        });
        scheduleDoor();
        return !fail;
      },
      disconnect() {
        ++connectionAttempt; ++scanAttempt; clearTimeout(doorTimer);
        store.update(s => {
          s.connection = 'disconnected'; s.scanState = 'idle'; s.doorOpenedAt = null; s.doorLeftOpenNotified = false; s.door = 'unknown';
          s.trays.forEach(tray => { tray.ledActive = false; tray.effectUntil = 0; });
          stopUmbrellaEffect(s);
          log(s, 'device', '장치 연결이 끊겼어요', '새 데이터 수신이 중단되었어요');
        });
        return true;
      },
      loseConnection() {
        if (store.get().connection !== 'connected') return false;
        ++connectionAttempt; ++scanAttempt;
        store.update(s => {
          s.connection = 'disconnected'; s.scanState = 'idle'; s.lastReceived = null;
          s.connectionError = '거리가 멀어져 연결이 끊긴 상황이에요.';
          s.trays.forEach(tray => { tray.ledActive = false; tray.effectUntil = 0; });
          stopUmbrellaEffect(s);
          log(s, 'device', '장치 연결이 끊겼어요', '거리 이탈 체험 · 열린 문의 확인 대기는 유지돼요');
        });
        scheduleDoor();
        return true;
      },
      refresh() { if (store.get().connection === 'connected' && !store.get().paused) store.update(freshen); },
      setDoor(open) {
        if (!isFresh(store.get())) return false;
        store.update(s => {
          const next = open ? 'open' : 'closed';
          if (s.door === next) return;
          s.door = next; s.doorOpenedAt = open ? Date.now() : null; s.doorLeftOpenNotified = false; freshen(s);
          log(s, 'door', open ? '현관문이 열렸어요' : '현관문이 닫혔어요', '현관 · 문 감지 센서');
          if (open) {
            s.doorEvent++;
            s.trays.forEach(tray => {
              const pref = s.preferences.trays[tray.id - 1];
              if (trayStatus(s, tray).key === 'present') {
                if (pref.led.enabled && pref.led.onDoor) tray.ledActive = true;
                if (pref.effect.enabled && pref.effect.onDoor) runEffect(s, tray.id);
              }
            });
            if (s.preferences.umbrella.effect.onDoor) runUmbrellaEffect(s);
            notify(s);
          }
        });
        scheduleDoor();
        return true;
      },
      setTray(trayId, present, weight) {
        if (store.get().connection !== 'connected' || store.get().paused) return false;
        store.update(s => {
          const tray = s.trays[trayId - 1];
          const pref = s.preferences.trays[trayId - 1];
          const changed = tray.present !== present;
          tray.present = present;
          tray.weight = Number.isFinite(Number(weight)) ? Math.min(1000, Math.max(0, Number(weight))) : (present ? [128.5, 32.8][trayId - 1] : 0);
          if (changed) {
            tray.changedAt = Date.now();
            log(s, present ? 'placed' : 'removed', trayActivityTitle(pref.alias, present ? 'placed' : 'removed'), `트레이 0${trayId}`, trayId);
          }
          tray.ledActive = tray.sensor === 'ok' && present && pref.led.enabled && pref.led.onDetect;
          if (!present) tray.effectUntil = 0;
          freshen(s);
          if (changed && present && pref.effect.enabled && pref.effect.onDetect) runEffect(s, trayId);
        });
        return true;
      },
      setUmbrella(present) {
        if (!isFresh(store.get())) return false;
        store.update(s => {
          const changed = s.umbrella.present !== present;
          s.umbrella.present = present; s.umbrella.sensor = 'ok'; freshen(s);
          if (!present) stopUmbrellaEffect(s);
          else if (changed && s.preferences.umbrella.effect.onDetect) runUmbrellaEffect(s);
        });
        return true;
      },
      setUmbrellaSensor(sensor) { store.update(s => { s.umbrella.sensor = sensor; if (sensor !== 'ok') stopUmbrellaEffect(s); s.umbrella.measuredAt = sensor === 'waiting' ? null : Date.now(); }); },
      setWeather(value) {
        const condition = typeof value === 'boolean' ? value ? 'rain' : 'clear' : value;
        if (!Object.prototype.hasOwnProperty.call(root.SmartTrayData.WEATHER_CONDITIONS, condition)) return false;
        const rain = ['rain', 'rain-snow', 'shower'].includes(condition);
        const now = Date.now(); const end = new Date(now); end.setHours(24, 0, 0, 0);
        const until = Math.min(now + 2 * 3600000, end.getTime());
        store.update(s => { s.weather = { status: 'ready', source: '가상 날씨', regionName: '서울특별시 중구', regionStatus: 'ready', fetchedAt: now, validUntil: end.getTime(), timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          hours: [{ start: now, end: until, condition }],
          windows: rain ? [{ start: now, end: until }] : [] }; });
        return true;
      },
      refreshWeather({ automatic = false } = {}) {
        // Automatic refresh keeps the selected demo weather; manual preview still demonstrates rain.
        const weather = store.get().weather;
        const selected = weather.hours?.[0]?.condition || ((weather.windows || []).length ? 'rain' : 'clear');
        this.setWeather(automatic && weather.status === 'ready' ? selected : 'rain');
      },
      settingsChanged() {
        store.update(s => s.trays.forEach(tray => {
          const setting = s.preferences.trays[tray.id - 1].effect;
          if (!setting.enabled || !CONFIG.effectDurations.includes(setting.duration)) {
            clearTimeout(effectTimers.get(tray.id)); tray.effectUntil = 0;
          }
        }));
        if ((!store.get().preferences.umbrella.effect.enabled || !CONFIG.umbrellaEffectDurations.includes(store.get().preferences.umbrella.effect.duration)) && store.get().umbrella.effectUntil) store.update(stopUmbrellaEffect);
        scheduleDoor();
      },
      playUmbrellaEffect() { let started = false; store.update(s => { started = runUmbrellaEffect(s); }); return started; },
      setSensor(trayId, sensor) {
        if (store.get().connection !== 'connected' || store.get().paused) return false;
        store.update(s => {
          const tray = s.trays[trayId - 1]; tray.sensor = sensor;
          if (sensor !== 'ok') { tray.ledActive = false; tray.effectUntil = 0; }
          if (sensor === 'waiting') tray.measuredAt = null;
          freshen(s);
          log(s, 'device', `트레이 0${trayId} ${sensor === 'ok' ? '센서가 복구되었어요' : sensor === 'error' ? '센서를 확인해 주세요' : '데이터 수신 대기'}`, s.preferences.trays[trayId - 1].alias, trayId);
        });
        return true;
      },
      playLed(trayId) {
        const state = store.get();
        if (!isFresh(state) || !['present', 'empty'].includes(trayStatus(state, state.trays[trayId - 1]).key)) return false;
        store.update(s => { s.trays[trayId - 1].ledActive = true; });
        clearTimeout(ledTimers.get(trayId));
        ledTimers.set(trayId, setTimeout(() => store.update(s => {
          const tray = s.trays[trayId - 1], setting = s.preferences.trays[trayId - 1].led;
          tray.ledActive = isFresh(s) && tray.sensor === 'ok' && tray.present && setting.enabled && setting.onDetect;
        }), 2500));
        return true;
      },
      playEffect(trayId) { let started = false; store.update(s => { started = runEffect(s, trayId); }); return started; },
      previewNotification() {
        store.update(s => {
          s.notifications.unshift({ id: id(), time: Date.now(), read: false, title: '잠깐, 두고 간 물건이 있어요', message: trayNotificationMessage([s.preferences.trays[0].alias, s.preferences.trays[1].alias]), kind: 'preview', items: [], trayIds: [1, 2] });
          s.notifications = s.notifications.slice(0, 50);
          log(s, 'notification', '알림 미리보기를 확인했어요', '실제 알림 설정과 무관한 테스트 알림');
        });
      },
      pause(paused) { store.update(s => { s.paused = paused; if (paused) { s.lastReceived = Date.now() - 46000; stopUmbrellaEffect(s); } else freshen(s); }); scheduleDoor(); },
      async scenario(name) {
        if (store.get().connection !== 'connected') await this.connect({ demo: true });
        this.pause(false);
        store.update(s => {
          Object.assign(s.preferences.notifications, { enabled: true, door: true, belongings: true });
          s.weather = { status: "idle", windows: [], fetchedAt: null };
          s.preferences.trays.forEach(t => { t.notification = true; });
          s.trays.forEach(t => { t.sensor = 'ok'; });
          stopUmbrellaEffect(s);
          s.door = 'closed'; s.doorOpenedAt = null; s.doorLeftOpenNotified = false; s.connectionError = ''; clearTimeout(doorTimer);
        });
        this.setTray(1, name !== 'C', name === 'C' ? 0 : 128.5);
        this.setTray(2, name === 'A' || name === 'D', name === 'A' || name === 'D' ? 32.8 : 0);
        if (name === 'B') store.update(s => { s.preferences.trays[0].notification = false; });
        if (name === 'D') this.disconnect(); else this.setDoor(true);
        store.save();
      },
      dispose() { clearTimeout(doorTimer); clearTimeout(umbrellaEffectTimer); ++connectionAttempt; ++scanAttempt; effectTimers.forEach(clearTimeout); ledTimers.forEach(clearTimeout); }
    };
  }
  root.SmartTrayTransport = { createMockTransport };
})(typeof window !== 'undefined' ? window : globalThis);
