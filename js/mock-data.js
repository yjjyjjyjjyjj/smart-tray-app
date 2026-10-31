(function (root) {
  'use strict';
  const CONFIG = Object.freeze({ appName: '아마따', storageKey: 'smart-tray.preferences.v1', activityStorageKey: 'smart-tray.activities.v1', activityLimit: 200, staleAfterMs: 45000, deviceName: '아마따 · 현관', version: '0.2.0', trayIds: Object.freeze([1, 2]), fanDurations: Object.freeze([0, 15, 30, 60]), effectDurations: Object.freeze([5, 10, 20, 30]), umbrellaEffectDurations: Object.freeze([0, 15, 30, 60]) });
  const defaults = () => ({
    schemaVersion: 2,
    setupDone: false,
    umbrella: { fan: { duration: 0 }, effect: { enabled: false, onDetect: false, onDoor: false, duration: 0 } },
    notifications: { enabled: true, door: true, belongings: true, doorLeftOpen: true, doorOpenSeconds: 30, umbrella: true },
    trays: CONFIG.trayIds.map(id => ({
      id, alias: `${id}번 트레이`, notification: true,
      led: { enabled: true, onDetect: true, onDoor: true },
      effect: { enabled: false, onDetect: false, onDoor: false, duration: 10 }
    }))
  });
  function loadPreferences(storage) {
    const value = defaults();
    try {
      const saved = JSON.parse(storage.getItem(CONFIG.storageKey));
      if (!saved || typeof saved !== 'object') return value;
      value.setupDone = saved.setupDone === true;
      if (CONFIG.fanDurations.includes(saved.umbrella?.fan?.duration)) value.umbrella.fan.duration = saved.umbrella.fan.duration;
      for (const key of ['enabled', 'door', 'belongings', 'doorLeftOpen', 'umbrella']) if (typeof saved.notifications?.[key] === 'boolean') value.notifications[key] = saved.notifications[key];
      if (Number.isInteger(saved.notifications?.doorOpenSeconds)) value.notifications.doorOpenSeconds = Math.max(1, Math.min(300, saved.notifications.doorOpenSeconds));
      const umbrellaEffect = saved.umbrella?.effect;
      for (const key of ['enabled', 'onDetect', 'onDoor']) {
        if (typeof umbrellaEffect?.[key] === 'boolean') value.umbrella.effect[key] = umbrellaEffect[key];
      }
      if (CONFIG.umbrellaEffectDurations.includes(umbrellaEffect?.duration)) value.umbrella.effect.duration = umbrellaEffect.duration;
      if (value.umbrella.effect.duration === 0) value.umbrella.effect.enabled = false;
      value.trays.forEach((tray, i) => {
        const previous = saved.trays?.[i];
        if (!previous) return;
        if (typeof previous.alias === 'string' && previous.alias.trim()) {
          const alias = previous.alias.trim().slice(0, 16);
          // Migrate the original demo labels once; keep custom names and all settings.
          const legacyDefault = saved.schemaVersion !== 2 && alias === ['지갑', '자동차 키', '이어폰'][i];
          if (!legacyDefault) tray.alias = alias;
        }
        if (typeof previous.notification === 'boolean') tray.notification = previous.notification;
        for (const domain of ['led', 'effect']) for (const key of Object.keys(tray[domain])) {
          if (typeof tray[domain][key] === 'boolean' && typeof previous[domain]?.[key] === 'boolean') tray[domain][key] = previous[domain][key];
        }
        if (CONFIG.effectDurations.includes(previous.effect?.duration)) tray.effect.duration = previous.effect.duration;
      });
    } catch (_) { /* A corrupt or unavailable browser store falls back to safe defaults. */ }
    return value;
  }
  function createState(preferences = defaults()) {
    const now = Date.now();
    return {
      preferences, connection: 'disconnected', demo: false, lastReceived: null, door: 'unknown', doorEvent: 0,
      scanResults: [], scanState: 'idle', connectionError: '', paused: false, storageError: false,
      trays: [true, false].map((present, index) => ({ id: index + 1, present, weight: [128.5, 0][index], sensor: 'waiting', ledActive: false, effectUntil: 0, changedAt: now, measuredAt: null })),
      umbrella: { present: true, sensor: 'waiting', measuredAt: null, fanActive: false, effectUntil: 0 },
      weather: { status: 'idle', message: '현재 위치로 날씨를 확인해 주세요.', windows: [], fetchedAt: null },
      doorOpenedAt: null, doorLeftOpenNotified: false,
      activities: [], activityStorageError: false, notifications: [], lastNotifiedEvent: -1
    };
  }
  function isFresh(state, now = Date.now()) {
    return state.connection === 'connected' && state.lastReceived !== null && now - state.lastReceived <= CONFIG.staleAfterMs;
  }
  function trayStatus(state, tray, now = Date.now()) {
    if (state.connection !== 'connected') return { key: 'offline', label: '연결 끊김', short: '확인 불가' };
    if (!isFresh(state, now)) return { key: state.lastReceived ? 'stale' : 'waiting', label: state.lastReceived ? '업데이트 지연' : '데이터 수신 대기', short: '확인 대기' };
    if (tray.sensor === 'error') return { key: 'error', label: '센서 확인 필요', short: '센서 오류' };
    if (tray.sensor === 'waiting' || tray.measuredAt === null) return { key: 'waiting', label: '데이터 수신 대기', short: '확인 대기' };
    if (now - tray.measuredAt > CONFIG.staleAfterMs) return { key: 'stale', label: '업데이트 지연', short: '확인 대기' };
    return tray.present ? { key: 'present', label: '물건 있음', short: '보관 중' } : { key: 'empty', label: '물건 없음', short: '비어 있음' };
  }
  function umbrellaStatus(state, now = Date.now()) {
    const status = trayStatus(state, state.umbrella, now);
    return { ...status, label: status.key === 'present' ? '우산 있음' : status.key === 'empty' ? '우산 없음' : status.label };
  }
  const WEATHER_CONDITIONS = Object.freeze({
    clear: { label: '맑음', icon: 'sun' },
    'partly-cloudy': { label: '구름 많음', icon: 'cloud' },
    cloudy: { label: '흐림', icon: 'cloud' },
    rain: { label: '비', icon: 'rain' },
    'rain-snow': { label: '비/눈', icon: 'snow' },
    snow: { label: '눈', icon: 'snow' },
    shower: { label: '소나기', icon: 'rain' }
  });
  function isWeatherFresh(state, now = Date.now()) {
    const weather = state.weather;
    return !!(weather?.status === 'ready' && weather.fetchedAt && now >= weather.fetchedAt && now - weather.fetchedAt <= 3600000 && (!weather.validUntil || now < weather.validUntil));
  }
  function weatherCondition(state, now = Date.now()) {
    if (!isWeatherFresh(state, now)) return null;
    const hour = (state.weather.hours || []).find(h => h.start <= now && now < h.end);
    const condition = hour && Object.prototype.hasOwnProperty.call(WEATHER_CONDITIONS, hour.condition) ? WEATHER_CONDITIONS[hour.condition] : null;
    return condition ? { key: hour.condition, ...condition } : null;
  }
  function rainWindows(state, now = Date.now()) {
    if (!isWeatherFresh(state, now)) return [];
    return (state.weather.windows || []).filter(w => w.end > now);
  }
  function rainLabel(state, now = Date.now()) {
    const format = ms => new Date(ms).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: state.weather.timezone || undefined });
    return rainWindows(state, now).map(w => `${format(w.start)}~${format(w.end)}`).join(', ');
  }
  function umbrellaNotification(state, now = Date.now()) {
    if (!state.preferences.notifications.enabled || !state.preferences.notifications.umbrella || state.door !== 'open' || umbrellaStatus(state, now).key !== 'present') return null;
    const hours = rainLabel(state, now);
    return hours ? { title: '오늘은 우산도 챙겨요', message: `오늘 ${hours} 비 예보가 있어요. 우산 공간에 있는 접이식 우산을 챙겨 주세요.`, kind: 'umbrella', items: [], trayIds: [] } : null;
  }
  function doorLeftOpenNotification(state, now = Date.now()) {
    const settings = state.preferences.notifications;
    const seconds = Math.max(1, Math.min(300, settings.doorOpenSeconds));
    const fresh = isFresh(state, now);
    if (!settings.enabled || !settings.doorLeftOpen || (fresh && state.door === 'closed') || state.doorOpenedAt == null || state.doorLeftOpenNotified || now - state.doorOpenedAt < seconds * 1000) return null;
    const confirmedOpen = fresh && state.door === 'open';
    return { title: confirmedOpen ? '현관문이 계속 열려 있어요' : '현관문이 닫혔는지 확인해 주세요',
      message: confirmedOpen ? `현관문이 ${seconds}초 이상 열려 있어요. 문이 잘 닫혔는지 확인해 주세요.` : `문이 열린 뒤 ${seconds}초 동안 닫힘 신호를 받지 못했어요. 외출하셨다면 현관문을 확인해 주세요.`,
      kind: 'door-left-open', items: [], trayIds: [] };
  }
  function particle(text, consonant, vowel) {
    const ending = String(text).charCodeAt(String(text).length - 1) - 0xac00;
    return text + (ending >= 0 && ending <= 11171 && ending % 28 !== 0 ? consonant : vowel);
  }
  function trayDisplayName(alias) {
    const name = alias.trim();
    return name.endsWith('트레이') ? name : `${name} 트레이`;
  }
  function trayActivityTitle(alias, type) {
    const name = trayDisplayName(alias);
    return type === 'placed' ? `${name}에 물건이 놓였어요` : `${name}에서 물건이 없어졌어요`;
  }
  function trayNotificationMessage(names) {
    return `${names.map(trayDisplayName).join(', ')}에 물건이 남아있어요. 외출 전에 확인해주세요.`;
  }
  function notificationForDoor(state) {
    const settings = state.preferences.notifications;
    if (!settings.enabled || !isFresh(state) || state.door !== 'open' || state.lastNotifiedEvent === state.doorEvent) return null;
    const leftTrays = settings.belongings ? state.trays.filter(t => trayStatus(state, t).key === 'present' && state.preferences.trays[t.id - 1].notification) : [];
    const left = leftTrays.map(t => state.preferences.trays[t.id - 1].alias);
    if (left.length) return { title: '잠깐, 두고 간 물건이 있어요', message: trayNotificationMessage(left), items: left, trayIds: leftTrays.map(t => t.id), kind: 'belongings' };
    if (settings.door) return { title: '현관문이 열렸어요', message: '트레이 상태를 확인하고 외출을 준비하세요.', items: [], kind: 'door' };
    return null;
  }
  function loadActivities(storage) {
    try {
      const saved = JSON.parse(storage.getItem(CONFIG.activityStorageKey));
      if (!Array.isArray(saved)) return [];
      return saved.filter(e => e && typeof e.id === 'string' && e.id.trim() && typeof e.title === 'string' && e.title.trim()
        && ['placed', 'removed', 'door', 'notification', 'device'].includes(e.type)
        && Number.isFinite(e.time) && e.time > 0 && e.time <= 8640000000000000).slice(0, CONFIG.activityLimit).map(e => ({
          id: e.id, time: e.time, type: e.type, title: e.title,
          description: typeof e.description === 'string' ? e.description : '',
          trayId: CONFIG.trayIds.includes(e.trayId) ? e.trayId : null,
          trayIds: Array.isArray(e.trayIds) ? e.trayIds.filter(id => CONFIG.trayIds.includes(id)) : null
        }));
    } catch (_) { return []; }
  }
  function createStore(storage, { native = false } = {}) {
    let state = createState(loadPreferences(storage));
    if (native) state.runtime = 'android';
    else state.activities = loadActivities(storage);
    let savedActivities = JSON.stringify(state.activities);
    const listeners = new Set();
    function persistActivities() {
      // Actual device history belongs to Kotlin; keep browser/demo records separate.
      if (state.runtime === 'android') return;
      state.activities = state.activities.slice(0, CONFIG.activityLimit);
      const serialized = JSON.stringify(state.activities);
      if (serialized === savedActivities && !state.activityStorageError) return;
      try {
        storage.setItem(CONFIG.activityStorageKey, serialized);
        savedActivities = serialized; state.activityStorageError = false;
      } catch (_) { state.activityStorageError = true; }
    }
    return {
      get: () => state,
      loadActivities: () => loadActivities(storage),
      update(fn) { fn(state); persistActivities(); listeners.forEach(listener => listener(state)); },
      subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
      save() { try { storage.setItem(CONFIG.storageKey, JSON.stringify(state.preferences)); state.storageError = false; } catch (_) { state.storageError = true; } },
      reset() {
        state = createState(); if (native) state.runtime = 'android'; savedActivities = '[]';
        try { storage.removeItem(CONFIG.storageKey); storage.removeItem(CONFIG.activityStorageKey); }
        catch (_) { state.storageError = true; state.activityStorageError = true; }
        listeners.forEach(listener => listener(state));
      }
    };
  }
  root.SmartTrayData = { CONFIG, defaults, createState, createStore, isFresh, trayStatus, umbrellaStatus, WEATHER_CONDITIONS, isWeatherFresh, weatherCondition, rainWindows, rainLabel, umbrellaNotification, doorLeftOpenNotification, notificationForDoor, loadPreferences, particle, trayDisplayName, trayActivityTitle, trayNotificationMessage };
})(typeof window !== 'undefined' ? window : globalThis);
