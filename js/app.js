(function () {
  'use strict';
  const { CONFIG, createStore, isFresh, trayStatus, trayDisplayName, trayActivityTitle, trayNotificationMessage, umbrellaStatus, WEATHER_CONDITIONS, isWeatherFresh, weatherCondition, rainWindows, rainLabel } = window.SmartTrayData;
  let storage;
  try { storage = window.localStorage; } catch (_) { storage = { getItem: () => null, setItem: () => { throw Error('Storage unavailable'); }, removeItem: () => {} }; }
  const store = createStore(storage, { native: !!window.SmartTrayNative?.available });
  const transport = window.SmartTrayRuntime.createTransport(store);
  // Keep development entry buttons visible while building; ?dev=0 hides them for a presentation.
  const showDeveloperMenus = new URLSearchParams(location.search).get('dev') !== '0';
  const app = document.getElementById('app');
  const modalRoot = document.getElementById('modal-root');
  const toastRoot = document.getElementById('toast-root');
  const notificationRoot = document.getElementById('notification-root');
  const icons = {
    'brand-notification': '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M11.5 4C5.8 4 2.6 6.9 2.6 11.6C2.6 14.6 3.7 16.9 5.3 18.1C5.9 18.6 5.1 20 4.8 20.8C4.6 21.4 5.1 21.7 5.8 21.4L9.2 19.6C10 19.7 10.7 19.7 11.5 19.7C17.5 19.7 21 16.7 21 11.7C21 10.7 20.9 9.8 20.6 9C19.6 9.5 18.5 8.9 18.1 7.7L17.8 6.9L17 6.6C15.9 6.2 15.3 5.4 15.5 4.5C14.3 4.2 13 4 11.5 4ZM11.5 7.2C10.5 7.2 9.9 7.9 9.9 8.8L10.1 12.4C10.1 13.3 10.7 13.8 11.5 13.8C12.3 13.8 12.9 13.3 12.9 12.4L13.1 8.8C13.1 7.9 12.5 7.2 11.5 7.2ZM13 16C13 16.8 12.3 17.5 11.5 17.5C10.7 17.5 10 16.8 10 16C10 15.2 10.7 14.5 11.5 14.5C12.3 14.5 13 15.2 13 16Z"/><path fill="currentColor" stroke="none" d="M19 1.5C19.4 1.5 19.5 2 19.7 2.6C20 3.8 20.5 4.2 21.7 4.6C22.4 4.8 22.7 4.9 22.7 5.3C22.7 5.7 22.3 5.9 21.7 6.1C20.5 6.5 20 7 19.7 8.1C19.5 8.8 19.4 9.1 19 9.1C18.6 9.1 18.4 8.7 18.2 8.1C17.8 7 17.4 6.5 16.3 6.1C15.6 5.9 15.3 5.7 15.3 5.3C15.3 4.9 15.7 4.7 16.3 4.6C17.4 4.2 17.9 3.8 18.2 2.6C18.4 1.9 18.6 1.5 19 1.5Z"/>',
    fan: '<circle cx="12" cy="12" r="2"/><path d="M10.3 10.5C7 8 6.9 3 10.2 2.3C13.7 1.6 16 5.8 13 10.2"/><path d="M10.3 10.5C7 8 6.9 3 10.2 2.3C13.7 1.6 16 5.8 13 10.2" transform="rotate(120 12 12)"/><path d="M10.3 10.5C7 8 6.9 3 10.2 2.3C13.7 1.6 16 5.8 13 10.2" transform="rotate(240 12 12)"/>',
    umbrella: '<path d="M3 12a9 9 0 0 1 18 0H3ZM12 3v16a2 2 0 0 0 4 0M8 12c0-5 2-9 4-9s4 4 4 9"/>',
    rain: '<path d="M7 14a4 4 0 1 1 .5-8 5 5 0 0 1 9.5 1A3.5 3.5 0 0 1 18 14H7ZM8 17l-1 3m6-3-1 3m6-3-1 3"/>',
    cloud: '<path d="M6 19a5 5 0 1 1 1-9.9A6 6 0 0 1 19 10a4.5 4.5 0 0 1-.5 9H6Z"/>',
    snow: '<path d="M12 2v20M3.3 7l17.4 10M3.3 17 20.7 7M9 4l3 3 3-3M9 20l3-3 3 3M3.5 10l4-1-1-4M17.5 19l-1-4 4-1M6.5 19l1-4-4-1M20.5 10l-4-1 1-4"/>',
    home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z"/>',
    tray: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16M15 4v16M3 13h18"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    settings: '<path d="m10 3-.6 2.2-2 .9L5.3 5.5 3 9.3l1.6 1.6v2.2L3 14.7l2.3 3.8 2.1-.6 2 .9L10 21h4l.6-2.2 2-.9 2.1.6 2.3-3.8-1.6-1.6v-2.2L21 9.3l-2.3-3.8-2.1.6-2-.9L14 3Z"/><circle cx="12" cy="12" r="3"/>',
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9ZM10 21h4"/>',
    bluetooth: '<path d="m7 7 10 10-5 4V3l5 4L7 17"/>',
    arrow: '<path d="M5 12h14m-5-5 5 5-5 5"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    back: '<path d="m14 5-7 7 7 7"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    light: '<path d="M9 18h6m-5 3h4M8 14a6 6 0 1 1 8 0l-1 2H9Z"/><path d="M12 1v1M3 5l1 1m16-1-1 1M1 11h2m18 0h2"/>',
    sparkle: '<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3ZM20 2v4m-2-2h4"/>',
    door: '<path d="M4 21h16M7 21V4l10-2v19M11 12h.01M17 4h3v17"/>',
    shield: '<path d="m12 3 8 3v6c0 4-5 8-8 9-3-1-8-5-8-9V6Z"/><path d="m8 12 3 3 5-6"/>',
    edit: '<path d="m16 3 5 5-12 12-6 1 1-6L16 3ZM13 6l5 5"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
    device: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M9 5v14m6-14v14M6 16h.01M12 16h.01M18 16h.01"/>',
    signal: '<path d="M5 19v-3m5 3v-7m5 7V8m5 11V4"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 12-2l2 3M4 16l2 3a7 7 0 0 0 12-2"/>',
    code: '<path d="m8 7-5 5 5 5m8-10 5 5-5 5M14 4l-4 16"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    alert: '<path d="m12 3 10 18H2L12 3ZM12 9v5m0 3h.01"/>',
    linkoff: '<path d="m3 3 18 18M10 7l2-2a5 5 0 0 1 7 7l-1 1M14 17l-2 2a5 5 0 0 1-7-7l1-1M8 16l3-3m2-2 3-3"/>',
    filter: '<path d="M4 7h16M7 12h10m-7 5h4"/>',
    moon: '<path d="M20 14a8 8 0 0 1-10-10 9 9 0 1 0 10 10Z"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
    download: '<path d="M12 3v12m-4-4 4 4 4-4M4 17v4h16v-4"/>',
    leaf: '<path d="M20 3c-10-1-17 3-16 10a7 7 0 0 0 12 4c3-4 2-9 4-14ZM4 21l11-12"/>'
  };
  const icon = (name, cls = '') => name === 'logo' ? `<img class="icon brand-symbol ${cls}" src="assets/brand/amatta-symbol.png" width="44" height="44" alt="" aria-hidden="true" decoding="async">` : `<svg class="icon ${cls}" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] || icons.tray}</svg>`;
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
  const pad = n => String(n).padStart(2, '0');
  const time = value => value ? new Date(value).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false }) : '수신 전';
  const dateLabel = value => new Date(value).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'long' });
  const relative = value => !value ? '수신 전' : Date.now() - value < 10000 ? '방금 전' : Date.now() - value < 60000 ? `${Math.floor((Date.now() - value) / 1000)}초 전` : `${Math.floor((Date.now() - value) / 60000)}분 전`;
  const slotIcon = id => `<svg class="icon slot-icon" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="5"/><path d="M6 16v1a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-1"/><text x="12" y="13" text-anchor="middle" fill="currentColor" stroke="none" font-size="7" font-family="inherit" font-weight="500">${pad(id)}</text></svg>`;
  const routes = ['home', 'trays', 'activity', 'settings', 'detail', 'notifications', 'device', 'connect', 'inbox', 'about', 'umbrella'];
  const routeNames = { home: '대시보드', trays: '트레이 관리', activity: '활동 기록', settings: '설정', detail: '트레이 상세', notifications: '알림 설정', device: '장치 관리', connect: '연결과 위치 설정', inbox: '알림함', about: '앱 정보', umbrella: '우산과 날씨' };
  let currentRoute = 'home', trayId = 1, activityFilter = 'all', modal = null, previousFocus = null, toastTimer, notificationTimer, hasNavigated = false, lastNotificationId = null, renderPending = false, initialized = false;
  function getRoute() {
    const parts = location.hash.replace('#', '').split('/');
    currentRoute = routes.includes(parts[0]) ? parts[0] : 'home';
    trayId = CONFIG.trayIds.includes(Number(parts[1])) ? Number(parts[1]) : 1;
    if (currentRoute === 'detail' && !CONFIG.trayIds.includes(Number(parts[1]))) { currentRoute = 'trays'; history.replaceState(null, '', '#trays'); }
  }
  function navigate(route, id) {
    closeModal(); hasNavigated = true;
    const next = `#${route}${id ? '/' + id : ''}`;
    if (location.hash === next) { render(); refreshHomeWeather(); return; }
    location.hash = next;
  }
  function refreshHomeWeather() {
    if (initialized && currentRoute === 'home') void transport.refreshWeather({ automatic: true });
  }
  function back() { if (modal) { closeModal(); return; } if (hasNavigated) history.back(); else navigate(['detail'].includes(currentRoute) ? 'trays' : 'settings'); }
  function toast(message, kind = 'success') {
    clearTimeout(toastTimer);
    toastRoot.innerHTML = `<div class="toast ${kind}" role="status">${icon(kind === 'success' ? 'check' : kind === 'notification' ? 'bell' : 'info')}<span>${esc(message)}</span><button class="icon-button" data-action="dismiss-toast" aria-label="메시지 닫기">${icon('close')}</button></div>`;
    toastTimer = setTimeout(() => { toastRoot.innerHTML = ''; }, kind === 'notification' ? 6500 : 3500);
  }
  function systemNotification(notification, floating = false) {
    const trayIds = (notification.trayIds || []).filter(id => CONFIG.trayIds.includes(id));
    const target = `data-action="open-notification" data-notification-id="${esc(notification.id || '')}" data-tray-ids="${trayIds.join(',')}"`;
    const actionLabel = notification.kind === 'umbrella' ? '우산과 날씨 확인' : trayIds.length ? '트레이 확인' : '홈에서 확인';
    return `<article class="android-notification" aria-label="Android 시스템 알림 미리보기">
      <div class="android-notification-header"><span class="notification-small-icon">${icon('brand-notification')}</span><span>${esc(CONFIG.appName)}</span><span class="notification-meta-dot" aria-hidden="true">·</span><span class="notification-timestamp">지금</span>${floating ? `<button class="notification-dismiss" data-action="dismiss-notification" aria-label="알림 닫기">${icon('close')}</button>` : ''}</div>
      <button class="android-notification-content" ${target}><strong>${esc(notification.title)}</strong><span>${esc(notification.message)}</span></button>
      <button class="android-notification-action" ${target}>${actionLabel}</button>
    </article>`;
  }
  function dismissNotification() {
    clearTimeout(notificationTimer);
    const hadFocus = notificationRoot.contains(document.activeElement);
    notificationRoot.innerHTML = '';
    if (hadFocus) document.getElementById('main-content')?.focus({ preventScroll: true });
  }
  function showSystemNotification(notification) {
    clearTimeout(notificationTimer);
    notificationRoot.innerHTML = `<div class="system-notification-banner"><span class="system-notification-label">시스템 알림 미리보기</span>${systemNotification(notification, true)}</div>`;
    notificationTimer = setTimeout(() => {
      // Keep the preview available while a keyboard user is interacting with it.
      if (!notificationRoot.contains(document.activeElement)) dismissNotification();
    }, 8000);
  }
  async function save(message = '설정을 저장했어요') {
    const saved = await transport.savePreferences(); render();
    if (saved) toast(message, 'success');
    else if (!transport.isNative) toast('브라우저 저장을 사용할 수 없어 이번 세션에만 적용해요.', 'info');
  }
  function brand() { return `<span class="brand-mark">${icon('logo')}</span><span>${esc(CONFIG.appName)}<small>EVERYTHING, IN PLACE.</small></span>`; }
  function connectionBadge(state) {
    const connected = state.connection === 'connected';
    return `<span class="connection-badge ${connected && isFresh(state) ? 'online' : ''}"><i></i>${connected ? transport.isNative ? '연결됨' : isFresh(state) ? '연결됨' : '수신 지연' : state.connection === 'connecting' ? state.connectionPhase === 'pairing' ? '페어링 중' : state.connectionPhase === 'retrying' ? '재연결 대기' : '연결 중' : '연결 끊김'}</span>`;
  }
  function shell(content, state) {
    const active = ['detail', 'umbrella'].includes(currentRoute) ? 'trays' : ['notifications', 'device', 'about', 'connect'].includes(currentRoute) ? 'settings' : currentRoute === 'inbox' ? 'home' : currentRoute;
    const nav = [['home', 'home', '홈'], ['trays', 'tray', '트레이 관리'], ['activity', 'clock', '활동 기록'], ['settings', 'settings', '설정']];
    const unread = state.notifications.filter(n => !n.read).length;
    return `<div class="app-layout">
      <aside class="sidebar">
        <a class="brand" href="#home" aria-label="${esc(CONFIG.appName)} 홈">${brand()}</a>
        <div class="workspace-label">MY SMART HOME</div>
        <nav class="desktop-nav" aria-label="주 메뉴">${nav.map(([route, symbol, label]) => `<a href="#${route}" class="nav-link ${active === route ? 'active' : ''}" ${active === route ? 'aria-current="page"' : ''}>${icon(symbol)}<span>${label}</span>${active === route ? '<span class="nav-active-dot"></span>' : ''}</a>`).join('')}</nav>
        <div class="sidebar-device"><div class="mini-device-icon">${icon('device')}</div><div><strong>우리 집 트레이</strong><span><i class="status-dot ${isFresh(state) ? 'green' : 'gray'}"></i>${isFresh(state) ? '정상적으로 연결되어 있어요' : '장치 연결을 확인해 주세요'}</span></div><button class="icon-button" data-route="device" aria-label="장치 관리">${icon('chevron')}</button></div>
        <div class="sidebar-bottom"><div class="mockup-note"><span class="eyebrow">INTERACTIVE PROTOTYPE</span><p>빠짐없는 하루를 위한<br>작은 습관의 시작.</p><span class="version-label">${transport.isNative ? 'Android 앱' : 'HTML 목업'} · v${CONFIG.version}</span></div>${showDeveloperMenus ? `<button class="dev-launch" data-action="developer">${icon('code')}<span>개발자 시뮬레이션</span><span class="keycap">D</span></button>` : ''}</div>
      </aside>
      <div class="workspace">
        <header class="topbar"><div class="breadcrumb"><span>우리 집</span>${icon('chevron')}<strong>${routeNames[currentRoute]}</strong></div><a href="#home" class="brand mobile-brand">${brand()}</a><div class="topbar-actions">${connectionBadge(state)}<span class="topbar-divider"></span><button class="icon-button notification-button" data-route="inbox" aria-label="알림함${unread ? `, 읽지 않은 알림 ${unread}개` : ''}">${icon('bell')}${unread ? '<i class="unread-dot"></i>' : ''}</button><span class="avatar" aria-label="우리 집 프로필">H</span></div></header>
        <main id="main-content" data-page="${currentRoute}" tabindex="-1">${content}</main>
        <footer class="page-footer"><span>${icon('leaf')}깜빡한 하나까지, 나가기 전 한 번 더.</span><span>아마따 <i>·</i> UI PROTOTYPE</span></footer>
      </div>
      <nav class="bottom-nav" aria-label="모바일 주 메뉴">${nav.map(([route, symbol, label]) => `<a href="#${route}" class="${active === route ? 'active' : ''}" ${active === route ? 'aria-current="page"' : ''}>${icon(symbol)}<span>${route === 'trays' ? '트레이' : route === 'activity' ? '기록' : label}</span></a>`).join('')}</nav>
    </div>`;
  }
  function heading(title, subtitle, action = '', backButton = false) {
    const back = backButton ? '<button class="back-label" data-action="back">' + icon('back') + '뒤로</button>' : '';
    return `<div class="page-heading">${backButton ? `<div class="page-toolbar">${back}${action}</div>` : ''}<div class="page-heading-main"><div><h1>${title}</h1><p>${subtitle}</p></div>${backButton ? '' : action}</div></div>`;
  }
  function button(label, action, symbol, cls = 'button secondary', extra = '') { return `<button class="${cls}" ${action} ${extra}>${symbol ? icon(symbol) : ''}<span>${label}</span></button>`; }
  function statusBadge(status) { return `<span class="state-badge ${status.key}"><i></i>${status.label}</span>`; }
  function freshnessNotice(state) {
    if (transport.isNative && state.connection === 'connected' && !state.protocolReady) return `<div class="notice neutral" role="status">${icon('info')}<div><strong>Bluetooth 연결됨 · 센서 데이터 확인 대기</strong><p>${esc(state.protocolMessage || '프로토콜 미확정 — Arduino 펌웨어 분석 후 확정 예정')}</p></div></div>`;
    if (isFresh(state)) return '';
    const offline = state.connection !== 'connected';
    return `<div class="notice warning" role="status">${icon(offline ? 'linkoff' : 'clock')}<div><strong>${offline ? '트레이와 연결이 끊겼어요' : '새로운 데이터를 기다리고 있어요'}</strong><p>${offline ? '마지막 수신 정보로는 현재 소지품 상태를 확인할 수 없어요.' : '표시된 값은 이전 측정값이에요. 장치와 수신 상태를 확인해 주세요.'}</p></div><button class="text-button" data-route="device">${offline ? '다시 연결' : '장치 확인'}${icon('arrow')}</button></div>`;
  }
  function trayCard(tray, state, management = false) {
    const preference = state.preferences.trays[tray.id - 1], status = trayStatus(state, tray);
    const valid = ['present', 'empty'].includes(status.key);
    const effect = valid && tray.effectUntil > Date.now();
    return `<article class="tray-card ${status.key} ${effect ? 'effect-running' : ''}">
      <div class="tray-card-top"><span class="overline">TRAY ${pad(tray.id)}</span>${statusBadge(status)}</div>
      <div class="item-visual slot ${status.key === 'empty' ? 'empty-visual' : ''}">${slotIcon(tray.id)}<span class="item-shadow"></span>${valid && status.key === 'present' ? '<span class="item-check">' + icon('check') + '</span>' : ''}</div>
      <div class="tray-name"><h3>${esc(preference.alias)}</h3>${management ? `<button class="icon-button small" data-action="rename" data-id="${tray.id}" aria-label="${esc(preference.alias)} 별칭 변경">${icon('edit')}</button>` : ''}</div>
      <p class="tray-caption">${status.key === 'present' ? '트레이에 물건이 놓여 있어요' : status.key === 'empty' ? '트레이가 비어 있어요' : status.key === 'error' ? '센서 연결을 확인해 주세요' : '최신 상태를 확인할 수 없어요'}</p>
      <div class="tray-features"><span class="${valid && tray.ledActive ? 'feature-active' : ''}">${icon('light')}LED <b>${valid ? tray.ledActive ? 'ON' : 'OFF' : '—'}</b></span><span class="${effect ? 'feature-active purple' : ''}">${icon('sparkle')}살균 <b>${valid ? effect ? 'ON' : 'OFF' : '—'}</b></span></div>
      <button class="tray-detail-link" data-route="detail" data-id="${tray.id}" aria-label="${esc(preference.alias)} ${management ? '설정' : '상세 보기'}">${management ? '' : '<span>상세 보기</span>'}${icon(management ? 'chevron' : 'arrow')}</button>
    </article>`;
  }
  function eventView(event, state, compact = false) {
    let title = event.title;
    if (event.trayId && ['placed', 'removed'].includes(event.type)) title = trayActivityTitle(state.preferences.trays[event.trayId - 1].alias, event.type);
    const description = event.trayIds?.length ? trayNotificationMessage(event.trayIds.map(id => state.preferences.trays[id - 1].alias)) : event.description;
    const symbol = { placed: 'plus', removed: 'minus', door: 'door', notification: 'bell', device: 'bluetooth' }[event.type];
    return `<li class="activity-item"><span class="event-icon ${event.type}">${icon(symbol)}</span><div class="event-copy"><strong>${esc(title)}</strong>${compact ? '' : `<span>${esc(description)}</span>`}</div><time datetime="${new Date(event.time).toISOString()}">${time(event.time)}</time></li>`;
  }
  function emptyState(symbol, title, text) { return `<div class="empty-state">${icon(symbol)}<h3>${title}</h3><p>${text}</p></div>`; }
  function forecastSummary(state) {
    const weather = state.weather || {};
    const now = Date.now();
    const recent = isWeatherFresh(state, now);
    const condition = weatherCondition(state, now);
    const rainy = recent && rainWindows(state, now).length > 0;
    const rainHours = rainy ? rainLabel(state, now) : '';
    const message = recent ? rainy ? `오늘 ${rainHours} 비 예보가 있어요.` : `${condition ? `현재 시간대 ${condition.label} · ` : ''}오늘은 비 예보가 없어요`
      : weather.status === 'ready' ? '예보가 오래됐어요. 날씨를 다시 확인해 주세요.'
      : weather.status === 'loading' ? '날씨를 확인하고 있어요.'
      : weather.message || '현재 위치로 오늘의 비 예보를 확인해 주세요.';
    return { recent, rainy, message, rainHours, condition };
  }
  function homeWeatherSummary(state) {
    const { recent, rainy, message, rainHours, condition } = forecastSummary(state);
    const loading = state.weather?.status === 'loading';
    const caption = recent ? rainy ? '오늘' : condition ? '현재 시간대' : '오늘' : '날씨';
    const title = rainy ? '비 예보' : condition?.label || (recent ? '비 예보 없음' : loading ? '확인 중' : '확인 필요');
    const symbol = rainy ? 'rain' : condition?.icon || (loading ? 'refresh' : 'clock');
    const detail = rainy
      ? `<span class="umbrella-weather-times">${rainHours.split(', ').map(hours => `<span>${esc(hours.replace('~', ' – '))}</span>`).join('')}</span>`
      : esc(recent ? '오늘은 비 예보가 없어요' : message);
    return `<span class="umbrella-summary-weather ${recent ? 'ready' : 'pending'}"><span class="umbrella-weather-icon">${icon(symbol)}</span><span class="umbrella-weather-copy"><span class="umbrella-weather-caption">${caption}</span><strong>${esc(title)}</strong></span><span class="umbrella-weather-detail">${detail}</span></span>`;
  }
  function weatherCard(state) {
    const weather = state.weather || {};
    const { recent, rainy, message, rainHours, condition } = forecastSummary(state);
    const status = umbrellaStatus(state);
    const loading = weather.status === 'loading';
    const symbol = rainy ? 'rain' : condition?.icon || (loading ? 'refresh' : 'info');
    const label = rainy ? '비 예보' : condition ? `${condition.label} 예보` : recent ? '비 예보 없음' : loading ? '예보 확인 중' : '예보 확인 필요';
    const advice = status.key === 'present' ? '챙겨 나가세요' : status.key === 'empty' ? '외출 전 우산을 준비해 주세요' : '우산을 확인해 주세요';
    return `<section class="panel weather-card" aria-labelledby="weather-title">
      <div class="weather-heading"><h2 id="weather-title">오늘, 우산이 필요할까요?</h2>${!transport.isNative ? '<span class="mini-tag">가상 날씨</span>' : ''}</div>
      <div class="forecast-panel ${rainy ? 'rainy' : recent ? 'clear' : 'pending'}">
        <div class="forecast-label">${icon(symbol)}<strong>${label}</strong></div>
        ${rainy ? `<div class="weather-rain-hours">${rainHours.split(', ').map(hours => `<span>${esc(hours.replace('~', ' – '))}</span>`).join('')}</div>
          <p class="weather-umbrella-advice"><span>${esc(status.label)}${status.key === 'present' ? icon('check') : ''}</span><strong>${advice}</strong></p>` : `<p class="weather-summary">${esc(message)}</p>`}
      </div>
      <div class="weather-actions">${button(loading ? '확인 중' : '예보 보기', 'data-action="refresh-weather"', '', 'button secondary', loading ? 'disabled' : '')}</div>
      <div class="weather-meta"><span>${weather.fetchedAt ? time(weather.fetchedAt) + ' 업데이트' : '아직 확인한 예보가 없어요'}</span>${transport.isNative ? '<a href="https://www.weather.go.kr/" target="_blank" rel="noopener noreferrer">기상청</a>' : ''}</div>
    </section>`;
  }
  function umbrellaFanSettings(state) {
    const duration = state.preferences.umbrella?.fan?.duration ?? 0;
    return `<section class="panel settings-panel umbrella-fan-panel" aria-labelledby="umbrella-fan-title"><div class="panel-title"><span class="title-icon">${icon('fan')}</span><div><h2 id="umbrella-fan-title">우산 건조 팬</h2><p>우산을 말릴 팬의 동작 시간을 정해요.</p></div>${transport.isNative ? '<span class="mini-tag">장치 연동 대기</span>' : ''}</div><label class="setting-row"><span><strong>팬 동작 시간</strong><small id="fan-duration-help">0초를 선택하면 팬을 사용하지 않아요.</small></span><select data-setting="umbrella.fan.duration" aria-label="팬 동작 시간" aria-describedby="fan-duration-help">${CONFIG.fanDurations.map(value => `<option value="${value}" ${duration === value ? 'selected' : ''}>${value === 60 ? '1분' : value + '초'}</option>`).join('')}</select></label></section>`;
  }
  function umbrellaEffectSettings(state) {
    const setting = state.preferences.umbrella.effect;
    const status = umbrellaStatus(state);
    const known = !transport.isNative && ['present', 'empty'].includes(status.key);
    const running = known && state.umbrella.effectUntil > Date.now();
    const enabled = setting.enabled && setting.duration > 0;
    const canStart = !transport.isNative && enabled && status.key === 'present' && !running;
    return `<section class="panel settings-panel umbrella-effect-panel" aria-labelledby="umbrella-effect-title">
      <div class="panel-title"><span class="title-icon lavender">${icon('sparkle')}</span><div><h2 id="umbrella-effect-title">우산 살균</h2><p>우산 공간의 살균 LED 동작을 설정해요.</p></div><span class="mini-tag">${transport.isNative ? '장치 연동 대기' : '목업'}</span></div>
      <label class="setting-row"><span><strong>살균 시간</strong><small id="umbrella-effect-duration-help">0초를 선택하면 살균을 사용하지 않아요.</small></span><select data-setting="umbrella.effect.duration" aria-label="우산 살균 시간" aria-describedby="umbrella-effect-duration-help">${CONFIG.umbrellaEffectDurations.map(v => `<option value="${v}" ${setting.duration === v ? 'selected' : ''}>${v === 60 ? '1분' : v + '초'}</option>`).join('')}</select></label>
      ${switchRow('살균 활성화', '', enabled, 'umbrella.effect.enabled', setting.duration === 0)}
      ${switchRow('우산을 넣으면 자동 실행', '', setting.onDetect, 'umbrella.effect.onDetect', !enabled)}
      ${switchRow('문이 열리면 실행', '우산이 남아 있을 때 실행해요.', setting.onDoor, 'umbrella.effect.onDoor', !enabled)}
      <div class="panel-action"><span class="device-state"><span class="led-dot ${!known ? 'unknown' : running ? 'on purple' : ''}" aria-hidden="true"></span><span>${known ? running ? '살균 ON' : '살균 OFF' : '동작 상태 확인 불가'}</span></span>${button('살균 시작', 'data-action="umbrella-effect"', 'sparkle', 'button compact secondary', canStart ? '' : 'disabled')}</div>
      ${transport.isNative ? '<p class="inline-note">설정은 휴대폰에 저장돼요. 실제 LED 제어는 장치 연동 후 사용할 수 있어요.</p>' : ''}
    </section>`;
  }
  function umbrella(state) {
    const status = umbrellaStatus(state);
    return `${heading('우산을 위한 한 자리', '세 번째 공간은 접이식 우산을 위해 비워 두었어요.', '', true)}${freshnessNotice(state)}<div class="narrow-content"><section class="panel umbrella-card"><span class="umbrella-symbol">${icon('umbrella')}</span><div><span class="overline">SPACE 03</span><h2>접이식 우산</h2>${statusBadge(status)}</div></section>${umbrellaFanSettings(state)}${umbrellaEffectSettings(state)}${weatherCard(state)}<section class="panel settings-panel">${switchRow('비 예보 우산 알림', '문이 열릴 때 우산이 남아 있고, 오늘 비 예보가 있을 때만 알려드려요.', state.preferences.notifications.umbrella, 'notifications.umbrella', !state.preferences.notifications.enabled)}</section></div>`;
  }
  function home(state) {
    const valid = state.trays.filter(t => ['present', 'empty'].includes(trayStatus(state, t).key));
    const present = valid.filter(t => t.present).length;
    const doorKnown = isFresh(state) && state.door !== 'unknown';
    return `${heading('<span class="greeting-intro">안녕하세요,</span>오늘도 가볍게 떠나요 <span class="greeting-emoji" aria-hidden="true">🌱</span>', '트레이 두 칸과 우산 한 자리, 빠짐없이 챙겨요.', `<div class="today-date">${icon('clock')}${dateLabel(Date.now())}</div>`)}
      ${notificationPermissionNotice(state)}
    ${freshnessNotice(state)}
    <div class="dashboard-grid">
      <section class="readiness-card home-summary" aria-labelledby="readiness-title">
        <div class="readiness-header"><div class="section-heading"><h2 id="readiness-title">외출 전 체크</h2>${icon('shield')}</div>
        <div class="readiness-count"><strong>${valid.length === CONFIG.trayIds.length ? pad(present) : '—'}</strong><span>/ ${pad(CONFIG.trayIds.length)} <small>물건이 남아 있는 트레이</small></span></div>
        <p>${valid.length < CONFIG.trayIds.length ? '확인할 수 없는 트레이가 있어요. 장치 상태를 확인해 주세요.' : present ? '다 챙긴 것 같아도, 남은 물건을 한 번 더 확인해요.' : '트레이가 모두 비어 있어요. 가벼운 발걸음으로 출발하세요.'}</p></div>
        <div class="summary-trays">${state.trays.map(t => { const status = trayStatus(state, t); return `<a class="summary-tray ${status.key}" href="#detail/${t.id}"><span class="summary-tray-number">${pad(t.id)}</span><strong>${esc(state.preferences.trays[t.id - 1].alias)}</strong><span class="summary-tray-state"><i></i>${status.key === 'empty' ? '비어 있음' : status.label}</span></a>`; }).join('')}<a class="summary-tray umbrella-summary" href="#umbrella"><span class="summary-tray-number">03 · UMBRELLA</span><strong>우산 공간</strong><span class="summary-tray-state">${esc(umbrellaStatus(state).label)}</span>${homeWeatherSummary(state)}</a></div>
        <a class="reminder-link" href="#notifications">${icon('bell')} 외출 알림 설정 ${icon('chevron')}</a>
      </section>
      <section class="door-card"><div class="card-eyebrow"><span>우리 집 현관</span>${doorKnown ? '' : '<span class="door-waiting">수신 대기</span>'}</div><div class="door-illustration ${state.door === 'open' && doorKnown ? 'is-open' : ''}"><span class="door-frame"></span><span class="door-leaf"><i></i></span><span class="door-floor"></span>${doorKnown && state.door === 'closed' ? '<span class="door-check">' + icon('check') + '</span>' : ''}</div><h3>${doorKnown ? state.door === 'open' ? '현관문이 열려 있어요' : '현관문이 닫혀 있어요' : '문 상태를 확인할 수 없어요'}</h3><p>${doorKnown ? '문 상태를 실시간으로 확인하고 있어요' : '장치를 연결하면 상태를 확인할 수 있어요'}</p><div class="door-card-footer">${icon('refresh')} 마지막 수신 <strong>${relative(state.lastReceived)}</strong></div></section>
      <section class="recent-section"><div class="section-heading"><h2>최근 활동 <span class="subtle-label">우리 집의 작은 움직임</span></h2><a class="text-button" href="#activity">모든 기록 ${icon('chevron')}</a></div><div class="panel recent-panel">${state.activities.length ? `<ul class="activity-list">${state.activities.slice(0, 3).map(e => eventView(e, state, true)).join('')}</ul>` : emptyState('clock', '아직 활동이 없어요', '장치를 연결하고 물건을 놓으면 이곳에 기록돼요.')}</div></section>
      <section class="quiet-note"><span class="quiet-note-icon">${icon('sparkle')}</span><h3>다 챙긴 줄 알았던 날에도.</h3><p>문을 나서는 순간,<br>두고 가는 물건을 한 번 더 확인해요.</p><span>조금 더 편안한 일상, 아마따</span></section>
    </div>`;
  }
  function umbrellaFeatures(state) {
    // Browser preview state only; configured durations are not hardware status.
    const known = !transport.isNative && ['present', 'empty'].includes(umbrellaStatus(state).key);
    const fan = known && state.umbrella.fanActive === true;
    const effect = known && state.umbrella.effectUntil > Date.now();
    return `<div class="tray-features umbrella-features" aria-label="${known ? '우산 공간 동작 상태' : '우산 공간 동작 상태 확인 불가'}"><span class="${fan ? 'feature-active fan-active' : ''}">${icon('fan')}FAN <b>${known ? fan ? 'ON' : 'OFF' : '—'}</b></span><span class="${effect ? 'feature-active purple' : ''}">${icon('sparkle')}살균 <b>${known ? effect ? 'ON' : 'OFF' : '—'}</b></span></div>`;
  }
  function trays(state) {
    return `${heading('내 보관 공간', '트레이 두 칸과 접이식 우산 한 자리, 총 세 곳을 관리해요.')} ${freshnessNotice(state)}<div class="tray-grid management-grid">${state.trays.map(t => trayCard(t, state, true)).join('')}</div><a class="panel umbrella-card umbrella-list-card" href="#umbrella"><span class="umbrella-symbol">${icon('umbrella')}</span><div class="umbrella-card-copy"><div class="umbrella-card-meta"><span class="overline">SPACE 03</span>${statusBadge(umbrellaStatus(state))}</div><h2>접이식 우산 공간</h2>${umbrellaFeatures(state)}</div>${icon('chevron')}</a><div class="notice neutral">${icon('info')}<div><strong>트레이 이름을 자유롭게 바꿀 수 있어요</strong><p>이름 옆 연필 버튼을 눌러 나만의 별칭을 정해 보세요. 기록과 알림에도 같은 이름이 표시돼요.</p></div></div>`;
  }
  function switchRow(title, description, checked, setting, disabled = false) {
    return `<label class="setting-row ${disabled ? 'disabled' : ''}"><span><strong>${title}</strong>${description ? `<small>${description}</small>` : ''}</span><input type="checkbox" role="switch" data-setting="${setting}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''} aria-label="${title}"><span class="switch" aria-hidden="true"></span></label>`;
  }
  function detail(state) {
    const tray = state.trays[trayId - 1], preference = state.preferences.trays[trayId - 1], status = trayStatus(state, tray);
    const fresh = ['present', 'empty'].includes(status.key), effect = fresh && tray.effectUntil > Date.now();
    return `${heading(esc(preference.alias), `TRAY ${pad(trayId)} · 이 트레이를 위한 나만의 설정`, button('이름 변경', `data-action="rename" data-id="${trayId}"`, 'edit'), true)}${freshnessNotice(state)}
      <div class="detail-grid"><section class="panel detail-summary"><div class="section-heading"><span class="overline">TRAY ${pad(trayId)}</span>${statusBadge(status)}</div><div class="item-visual detail-visual slot ${status.key === 'empty' ? 'empty-visual' : ''} ${effect ? 'effect-preview' : ''}">${slotIcon(trayId)}<span class="item-shadow"></span></div><h2>${esc(preference.alias)}</h2><p>${status.label}</p><div class="measurement-grid"><div><span>${fresh ? '현재 무게' : '이전 무게 · 현재 확인 불가'}</span><strong>${tray.measuredAt ? tray.weight.toFixed(1) : '—'} <small>g</small></strong></div><div><span>마지막 상태 변경</span><strong class="measurement-time">${time(tray.changedAt)}</strong></div></div><div class="summary-caption">${icon('signal')} ${fresh ? transport.isNative ? '센서 정상' : '가상 센서 정상' : status.label} <span>수신 ${relative(tray.measuredAt)}</span></div></section>
      <div class="detail-settings"><section class="panel settings-panel"><div class="panel-title"><span class="title-icon amber">${icon('light')}</span><div><h2>LED 설정</h2><p>두고 가는 물건의 자리를 밝혀요</p></div><span class="mini-tag">${transport.isNative ? '장치 연동 대기' : '목업'}</span></div>${switchRow('LED 활성화', '', preference.led.enabled, `tray.${trayId}.led.enabled`)}${switchRow('물건을 놓으면 켜기', '물건이 감지되면 해당 칸을 밝혀요', preference.led.onDetect, `tray.${trayId}.led.onDetect`, !preference.led.enabled)}${switchRow('문이 열리면 켜기', '챙기지 않은 물건의 자리를 알려줘요', preference.led.onDoor, `tray.${trayId}.led.onDoor`, !preference.led.enabled)}<div class="panel-action"><span class="device-state"><span class="led-dot ${!fresh ? 'unknown' : tray.ledActive ? 'on' : ''}" aria-hidden="true"></span><span>${fresh ? tray.ledActive ? 'LED가 켜져 있어요' : 'LED가 꺼져 있어요' : '동작 상태 확인 불가'}</span></span>${button('LED 미리보기', `data-action="led" data-id="${trayId}"`, 'light', 'button compact secondary', fresh ? '' : 'disabled')}</div></section>
      <section class="panel settings-panel"><div class="panel-title"><span class="title-icon lavender">${icon('sparkle')}</span><div><h2>살균</h2><p>트레이의 살균 동작을 설정해요</p></div><span class="mini-tag">${transport.isNative ? '장치 연동 대기' : '목업'}</span></div>${switchRow('살균 활성화', '', preference.effect.enabled, `tray.${trayId}.effect.enabled`)}${switchRow('물건을 놓으면 자동 실행', '', preference.effect.onDetect, `tray.${trayId}.effect.onDetect`, !preference.effect.enabled)}${switchRow('문이 열리면 실행', '물건이 남아 있는 칸에만 실행해요', preference.effect.onDoor, `tray.${trayId}.effect.onDoor`, !preference.effect.enabled)}<label class="setting-row"><span><strong>살균 시간</strong></span><select data-setting="tray.${trayId}.effect.duration" aria-label="살균 시간" ${!preference.effect.enabled ? 'disabled' : ''}>${CONFIG.effectDurations.map(v => `<option value="${v}" ${preference.effect.duration === v ? 'selected' : ''}>${v}초</option>`).join('')}</select></label><div class="panel-action"><span class="device-state"><span class="led-dot ${!fresh ? 'unknown' : effect ? 'on purple' : ''}" aria-hidden="true"></span><span>${fresh ? effect ? `살균 ON · ${Math.max(1, Math.ceil((tray.effectUntil - Date.now()) / 1000))}초 남음` : '살균 OFF' : '동작 상태 확인 불가'}</span></span>${button('살균 시작', `data-action="effect" data-id="${trayId}"`, 'sparkle', 'button compact secondary', fresh ? '' : 'disabled')}</div></section></div></div><div class="notice neutral">${icon('info')}<p>${transport.isNative ? '설정은 휴대폰에 저장돼요. LED·살균 명령은 펌웨어 분석 후 장치와 연결할 예정이에요.' : '모든 설정은 브라우저 목업에만 적용돼요. 실제 장치의 지원 범위는 Arduino 펌웨어 분석 후 확인할 예정이에요.'}</p></div>`;
  }
  function notificationPermissionNotice(state) {
    if (!transport.isNative || state.notificationsAllowed !== false) return '';
    const settingsRequired = state.notificationSettingsRequired === true;
    const declined = settingsRequired || ['prompt-with-rationale', 'denied'].includes(state.notificationPermission);
    const label = settingsRequired ? '휴대폰 알림 설정 열기' : declined ? '알림 다시 허용하기' : '외출 알림 허용하기';
    return `<section class="notification-permission-card" id="notification-permission-notice" aria-labelledby="notification-permission-title">
      <h2 id="notification-permission-title">${icon('bell')}${declined ? '알림이 꺼져 있어 외출 안내를 받을 수 없어요' : '깜빡한 물건을 챙기도록, 외출 알림을 켜 주세요'}</h2>
      <p>트레이에 남은 물건, 비 예보가 있는 날의 우산, 오래 열린 현관문 안내는 알림을 허용해야 받을 수 있어요.</p>
      ${button(label, `data-action="${settingsRequired ? 'notification-settings' : 'notification-permission'}"`, 'bell', 'button primary full-width')}
      <small>${settingsRequired ? '휴대폰 설정에서 아마따 알림과 ‘외출 전 확인’을 켜 주세요. ' : ''}Bluetooth 연결과 화면 확인은 계속 사용할 수 있어요.</small>
    </section>`;
  }
  function notifications(state) {
    const preferences = state.preferences, flags = preferences.notifications;
    return `${heading('필요한 순간에, 알림', '두고 간 물건, 비 오는 날의 우산, 열린 현관문을 확인해요.', button('알림 미리보기', 'data-action="preview-notification"', 'bell'), true)}<div class="narrow-content">
      <section class="system-notification-preview" aria-labelledby="notification-preview-title"><div class="notification-preview-heading"><h2 id="notification-preview-title">Android 시스템 알림</h2><span>미리보기</span></div>${systemNotification({ title: '잠깐, 두고 간 물건이 있어요', message: trayNotificationMessage([preferences.trays[0].alias, preferences.trays[1].alias]), trayIds: CONFIG.trayIds })}<p class="notification-preview-note">앱을 보고 있지 않아도, 나가기 전 한 번 더.<br>알림창·잠금화면에 표시되는 푸시 메시지예요.</p></section>
      ${notificationPermissionNotice(state)}
      <section class="panel settings-panel">${switchRow('전체 알림', '모든 알림을 한 번에 관리해요', flags.enabled, 'notifications.enabled')}${switchRow('현관문 열림 알림', '현관문이 열리는 순간 알려드려요', flags.door, 'notifications.door', !flags.enabled)}${switchRow('소지품 미수거 알림', '문이 열렸을 때 트레이에 남은 물건을 알려드려요', flags.belongings, 'notifications.belongings', !flags.enabled)}</section>
      <h2 class="standalone-heading">열린 문을 한 번 더 확인해요</h2><section class="panel settings-panel">${switchRow('문 열림 유지 알림', '문이 열린 뒤 설정 시간 안에 닫힘이 확인되지 않으면 알려드려요.', flags.doorLeftOpen, 'notifications.doorLeftOpen', !flags.enabled)}<label class="setting-row"><span><strong>문 열림 판단 시간</strong><small>1~300초 사이에서 직접 지정해 주세요.</small></span><span class="seconds-input"><input type="number" min="1" max="300" step="1" inputmode="numeric" data-setting="notifications.doorOpenSeconds" aria-label="문 열림 판단 시간" value="${flags.doorOpenSeconds}" ${!flags.enabled || !flags.doorLeftOpen ? 'disabled' : ''}>초</span></label><p class="weather-helper">외출 중 연결이 끊겨도, 닫힘 신호를 받지 못했다면 한 번 더 확인을 알려드려요.</p></section>
      <h2 class="standalone-heading">비 오는 날의 우산</h2><section class="panel settings-panel">${switchRow('비 예보 우산 알림', '문이 열릴 때 우산이 남아 있고 오늘 비 예보가 있을 때만 알려드려요.', flags.umbrella, 'notifications.umbrella', !flags.enabled)}<a class="text-button" href="#umbrella">현재 위치의 날씨 확인 ${icon('arrow')}</a><p class="weather-helper">우산을 넣거나 꺼냈다는 알림은 보내지 않아요.</p></section>
      <h2 class="standalone-heading">트레이별 알림</h2><section class="panel settings-panel">${preferences.trays.map(t => switchRow(`${esc(t.alias)} 알림`, `트레이 ${pad(t.id)}`, t.notification, `tray.${t.id}.notification`, !flags.enabled || !flags.belongings)).join('')}</section><div class="notice neutral">${icon('shield')}<div><strong>필요할 때 한 번씩 알려드려요</strong><p>남은 물건은 한 알림으로 묶고, 우산과 문 열림 유지는 각각의 조건을 확인해요. 물건·우산은 최신 센서값과 유효한 예보로 판단하고, 열린 문은 닫힘 확인 여부로 알려드려요.</p></div></div></div>`;
  }
  function activity(state) {
    const filters = [['all', '전체'], ['placed', '물건 거치'], ['removed', '물건 제거'], ['door', '현관문'], ['notification', '알림'], ['device', '장치']];
    const filtered = state.activities.filter(e => activityFilter === 'all' || e.type === activityFilter);
    const groups = new Map();
    filtered.forEach(e => { const date = dateLabel(e.time); if (!groups.has(date)) groups.set(date, []); groups.get(date).push(e); });
    return `<div class="activity-page">${heading('우리 집의 작은 움직임', '물건을 놓고, 챙기고, 나서는 순간들을 모았어요.', `<span class="record-count">총 ${state.activities.length}개의 기록</span>`)}<div class="filter-bar" aria-label="이벤트 필터">${filters.map(([key, label]) => `<button class="filter-chip ${activityFilter === key ? 'active' : ''}" data-filter="${key}" aria-pressed="${activityFilter === key}">${label}</button>`).join('')}</div>${filtered.length ? [...groups].map(([date, events]) => `<section class="timeline-group"><h2>${date}<span>${events.length}개의 활동</span></h2><div class="panel"><ul class="activity-list full-activity">${events.map(e => eventView(e, state)).join('')}</ul></div></section>`).join('') : `<div class="panel">${emptyState('clock', '아직 기록된 활동이 없어요', '다른 필터를 선택하거나 시뮬레이션에서 이벤트를 만들어 보세요.')}</div>`}<p class="footnote">${state.activityStorageError ? '기록을 저장하지 못했어요. 새 기록은 현재 실행 중에만 유지돼요.' : `활동 기록은 ${transport.isNative ? '이 기기' : '이 브라우저'}에 최신 200개까지 저장돼요. 오래된 기록부터 자동으로 지워져요.`}</p></div>`;
  }
  function settingsLink(symbol, title, text, route, badge = '') {
    return `<button class="settings-link" ${route.startsWith('action:') ? `data-action="${route.slice(7)}"` : `data-route="${route}"`}><span class="settings-link-icon">${icon(symbol)}</span><span><strong>${title}</strong><small>${text}</small></span>${badge ? `<span class="link-badge">${badge}</span>` : ''}${icon('chevron')}</button>`;
  }
  function settings(state) {
    return `${heading('설정', '장치와 알림, 그리고 앱을 관리해요.')}<div class="settings-overview"><section class="panel house-profile"><span class="house-profile-icon">${icon('home')}</span><div><span class="eyebrow">MY SMART HOME</span><h2>우리 집의 작은 도우미</h2><p>깜빡한 물건을 한 번 더 챙기는 습관</p></div></section><div class="settings-columns"><div><h2 class="standalone-heading">장치 및 알림</h2><div class="panel">${settingsLink('device', '장치 관리', '연결 상태와 센서 측정값 확인', 'device', isFresh(state) ? '연결됨' : '연결 필요')}${settingsLink('bell', '알림 설정', '외출·우산·문 열림 시간 설정', 'notifications')}</div></div><div><h2 class="standalone-heading">앱</h2><div class="panel">${settingsLink('info', '앱 정보', '앱 정보 및 안내', 'about')}${settingsLink('bluetooth', '연결과 위치 설정', 'Bluetooth 연결과 위치 권한 관리', 'connect')}${showDeveloperMenus ? (transport.isNative ? settingsLink('code', '목업 체험 모드', '실제 연결을 해제하고 가상 데이터로 화면 체험', 'action:demo-mode') : settingsLink('code', '개발자 시뮬레이션', '문, 센서, 연결, 알림 시나리오 테스트', 'action:developer')) : ''}${transport.hasNative && !transport.isNative ? settingsLink('bluetooth', '실제 장치 연결로 돌아가기', 'HC-06 Bluetooth Classic 연결', 'action:native-mode') : ''}</div></div></div><div class="privacy-note">${icon('shield')}앱은 날씨 확인 시 위치를 예보 격자로 변환해 기상청에 전송해요. 설정은 이 휴대폰의 앱에 저장돼요.</div></div>`;
  }
  function device(state) {
    const connected = state.connection === 'connected';
    return `${heading('우리 집 트레이', '장치의 연결과 센서 상태를 한눈에 확인해요.', '', true)}${freshnessNotice(state)}<div class="device-grid"><section class="panel device-info-card"><div class="device-product device-slots" aria-label="트레이 두 칸과 우산 공간">${state.trays.map(t => `<span>${slotIcon(t.id)}</span>`).join('')}<span>${icon('umbrella')}</span></div>${connectionBadge(state)}<h2>${esc(CONFIG.deviceName)}</h2><p>${transport.isNative ? 'Bluetooth Classic · HC-06 SPP' : '가상 Bluetooth 장치 · HC-06 연결 체험'}</p><div class="device-facts"><span>마지막 데이터 수신<strong>${state.lastReceived ? `${time(state.lastReceived)} · ${relative(state.lastReceived)}` : '아직 수신하지 않았어요'}</strong></span><span>데이터 상태<strong>${isFresh(state) ? transport.isNative ? '최신 수신 데이터' : '최신 가상 데이터' : '현재 상태 확인 불가'}</strong></span></div>${connected ? button('연결 해제', 'data-action="disconnect"', 'linkoff', 'button secondary full-width') : button(state.connection === 'connecting' ? '연결 중…' : '다시 연결하기', 'data-action="reconnect"', 'bluetooth', 'button primary full-width', state.connection === 'connecting' ? 'disabled' : '')}<button class="text-button centered" data-route="connect">다른 장치 검색 ${icon('arrow')}</button></section><div><section class="panel settings-panel"><div class="panel-title"><span class="title-icon">${icon('signal')}</span><div><h2>센서 상태</h2><p>트레이별 최신 측정값을 확인해요</p></div></div>${state.trays.map(t => { const status = trayStatus(state, t); return `<div class="sensor-row"><span class="sensor-icon">${slotIcon(t.id)}</span><div><strong>${esc(state.preferences.trays[t.id - 1].alias)}</strong><small>TRAY ${pad(t.id)} · ${relative(t.measuredAt)}</small></div><div>${statusBadge(status)}<small>${t.measuredAt ? `${t.weight.toFixed(1)} g${!['present', 'empty'].includes(status.key) ? ' · 이전 값' : ''}` : '측정 전'}</small></div></div>`; }).join('')}</section></div></div>`;
  }
  function locationSetup(state) {
    const location = state.location || {}, weather = state.weather || {};
    const loading = weather.status === 'loading';
    const denied = transport.isNative && location.permission === 'denied';
    const disabled = transport.isNative && location.enabled === false;
    const ready = forecastSummary(state).recent && (!transport.isNative || location.permission === 'granted' && !disabled);
    const action = denied ? 'app-location-settings' : disabled ? 'location-settings' : 'refresh-weather';
    const label = loading ? '위치와 날씨 확인 중…' : denied ? '앱 위치 권한 설정' : disabled ? '휴대폰 위치 켜기' : transport.isNative ? ready ? '현재 위치로 다시 확인' : '위치 허용하고 날씨 확인' : '가상 날씨로 확인하기';
    const message = denied ? '위치 권한이 꺼져 있어요. 앱 권한에서 위치를 허용해 주세요.' : disabled ? '휴대폰의 위치 기능을 켜면 오늘의 비 예보를 확인할 수 있어요.' : ready ? forecastSummary(state).message : weather.message || '현재 위치로 오늘의 비 예보를 확인해 주세요.';
    return `<section class="panel location-setup" id="location-setup"><div class="setup-card-heading"><span class="title-icon">${icon('umbrella')}</span><div><h2>위치로 비 예보 확인</h2></div><span class="mini-tag">${ready ? '확인 완료' : '선택 설정'}</span></div>
      <p class="setup-description">나가는 순간, 비 소식이 있으면 우산도 함께 챙겨요.</p>
      ${transport.isNative ? `<dl class="location-status"><div><dt>위치 권한</dt><dd>${location.permission === 'granted' ? '허용됨' : denied ? '허용 필요' : '확인 필요'}</dd></div><div><dt>휴대폰 위치</dt><dd>${disabled ? '꺼짐' : location.enabled ? '켜짐' : '확인 중'}</dd></div></dl>` : ''}
      <p class="location-message" role="status">${icon(ready ? 'check' : 'info')}${esc(message)}</p>
      ${button(label, `data-action="${action}"`, loading ? 'refresh' : ready ? 'check' : 'umbrella', 'button secondary full-width', loading ? 'disabled' : '')}
      <p class="setup-privacy">${transport.isNative ? '대략적인 위치를 약 5km 예보 격자로 변환해 기상청 날씨를 확인해요. 앱이 보일 때 위치를 확인하며, 백그라운드에서는 마지막 확인 위치를 사용해요.' : 'Android 앱에서는 위치 권한을 허용한 뒤 내 주변의 실제 날씨를 확인해요.'}</p>
      <p class="setup-optional">위치는 나중에 설정해도 돼요. 위치 확인 전에는 비 예보 우산 알림을 보낼 수 없어요.</p></section>`;
  }
  function connect(state) {
    const scanning = state.scanState === 'scanning', connecting = state.connection === 'connecting', connected = state.connection === 'connected';
    return `${heading('연결하고, 빠짐없이 챙겨요', '트레이 연결과 날씨용 위치를 한곳에서 설정해요.', '', state.preferences.setupDone)}
      <div class="connect-layout setup-layout"><section class="panel connect-card">
        <div class="setup-card-heading"><span class="title-icon">${icon('bluetooth')}</span><div><h2>내 트레이 연결</h2></div>${connectionBadge(state)}</div>
        <p class="setup-description">${connecting ? state.connectionPhase === 'pairing' ? '페어링을 진행하고 있어요.' : state.connectionPhase === 'retrying' ? '트레이 연결을 자동으로 다시 시도하고 있어요.' : 'HC-06에 연결하고 있어요.' : connected ? `${esc(state.deviceName || CONFIG.deviceName)}에 연결되었어요.` : '휴대폰을 설치된 트레이 가까이 가져가 주세요.'}</p>
        ${connected ? notificationPermissionNotice(state) : ''}
        ${!transport.isNative ? `<div class="permission-note">${icon('info')}브라우저에서는 가상 장치로 연결 과정을 체험해요.</div>` : ''}
        ${state.connectionError ? `<div class="notice warning" role="alert">${icon('alert')}<p>${esc(state.connectionError)}</p></div>` : ''}
        ${!connected && state.scanResults.length ? `<div class="scan-results">${state.scanResults.map(d => `<button class="scan-device ${connecting && d.address === state.deviceAddress ? 'selected' : ''}" data-action="connect-device" data-address="${esc(d.address || '')}" ${connecting ? 'disabled' : ''}><span class="settings-link-icon">${icon('device')}</span><span><strong>${esc(d.name)}</strong><small>${transport.isNative ? esc(d.address) + ' · ' + (d.bonded ? '페어링됨' : '페어링 필요') + (d.signal && d.signal.includes('dBm') ? ' · ' + esc(d.signal) : '') : '가상 장치 · ' + esc(d.signal)}</small></span>${connecting && d.address === state.deviceAddress ? '<span class="spinner"></span>' : icon('plus')}</button>`).join('')}</div>` : ''}
        ${transport.isNative && ['done', 'paired'].includes(state.scanState) && !state.scanResults.length && !state.connectionError ? '<div class="inline-note" role="status">HC-06 또는 AMATTA 기기를 찾지 못했어요. 트레이의 전원과 Bluetooth 이름을 확인해 주세요.</div>' : ''}
        ${transport.isNative ? `<div class="bluetooth-help">${state.bluetooth?.enabled === false ? button('Bluetooth 켜기', 'data-action="enable-bluetooth"', 'bluetooth', 'button secondary full-width') : ''}${button('휴대폰 Bluetooth 설정', 'data-action="bluetooth-settings"', 'settings', 'button secondary full-width')}</div>` : ''}
        <div class="connect-actions">${connecting ? button('연결 취소', 'data-action="disconnect"', 'close', 'button secondary full-width') : connected ? button('다른 장치 연결하기', 'data-action="disconnect"', 'bluetooth', 'button secondary full-width') : button(scanning ? '주변 장치를 찾고 있어요…' : state.scanResults.length ? '다시 검색하기' : '주변 장치 찾기', 'data-action="scan"', scanning ? 'refresh' : 'bluetooth', 'button primary full-width', scanning || connecting ? 'disabled' : '')}
        ${transport.isNative && !connected && !connecting ? button('페어링된 장치 불러오기', 'data-action="paired-devices"', 'device', 'button secondary full-width', scanning ? 'disabled' : '') : ''}
        ${!connected && state.scanResults.length && !transport.isNative ? '<label class="failure-toggle"><input type="checkbox" id="fail-next-connection">연결 실패 상황 체험하기</label>' : ''}</div>
      </section>${locationSetup(state)}</div>
      <div class="setup-finish">${button(connected ? '설정 마치고 시작하기' : '나중에 설정하기', 'data-action="finish-setup"', 'arrow', `button ${connected ? 'primary' : 'secondary'} full-width`, connecting || scanning || state.weather?.status === 'loading' ? 'disabled' : '')}<p>설정은 저장되며, 다음 실행부터 홈으로 바로 이동해요.</p>${!connected ? '<button class="text-button centered" data-action="explore">연결 없이 목업 체험하기 '+icon('arrow')+'</button>' : ''}</div>`;
  }
  function inbox(state) {
    return `${heading('놓치지 않도록, 알림함', '이곳에서 최근 알림을 다시 확인할 수 있어요.', button('모두 읽음', 'data-action="read-all"', 'check'), true)}<div class="narrow-content">${state.notifications.length ? state.notifications.map(n => `<article class="panel inbox-item ${n.read ? '' : 'unread'}"><span class="event-icon notification">${icon('bell')}</span><div><div class="inbox-meta"><strong>${n.kind === 'preview' ? '미리보기' : '아마따'}</strong><time>${time(n.time)}</time></div><h2>${esc(n.title)}</h2><p>${esc(n.message)}</p></div></article>`).join('') : `<div class="panel">${emptyState('bell', '아직 도착한 알림이 없어요', '외출 알림이 생기면 이곳에 모아둘게요.')}</div>`}<a class="text-button centered" href="#notifications">알림 설정으로 이동 ${icon('arrow')}</a></div>`;
  }
  function about() {
    return `${heading('빠짐없는 하루의 시작', '아마따의 기능과 이용 방법을 안내해요.', '', true)}<div class="narrow-content"><section class="panel about-card"><div class="brand">${brand()}</div><div class="device-slots" aria-label="트레이 두 칸과 우산 공간">${CONFIG.trayIds.map(id => `<span>${slotIcon(id)}</span>`).join('')}<span>${icon('umbrella')}</span></div><h2>깜빡한 하나 때문에 돌아오지 않도록.</h2><p>나갈 때 한두 개씩 두고 가는 물건.<br>현관문이 열리면 트레이에 남은 물건을 알려드려요.<br>비 오는 날에는 우산도, 오래 열린 문도 한 번 더 확인해요.</p></section><section class="panel about-text"><h2>앱 이용 안내</h2><p>트레이와 휴대폰을 Bluetooth로 연결해 주세요. 위치를 허용하면 내 주변의 비 예보를 확인할 수 있어요.</p><h3>장치 기능 안내</h3><p>Bluetooth 연결과 날씨 확인을 사용할 수 있어요. 센서 상태 확인과 센서 기반 알림, LED·살균·팬 제어는 장치 연동을 준비 중이에요.</p><h3>나의 설정</h3><p>트레이 이름과 설정, 최신 활동 기록 200개는 기기에 저장돼요. 앱을 다시 실행해도 기록이 유지되며, 현재 센서 상태는 장치에 연결한 뒤 확인해요.</p></section></div>`;
  }
  const views = { home, trays, detail, notifications, activity, settings, device, connect, inbox, about, umbrella };
  function rememberFocus(container, update) {
    const active = document.activeElement;
    const key = active && container.contains(active) ? active.getAttribute('data-setting') || active.id : null;
    const start = active?.selectionStart, end = active?.selectionEnd;
    const editingWeight = active?.dataset.dev === 'weight' ? active.value : null;
    update();
    if (key) {
      const replacement = document.getElementById(key) || [...container.querySelectorAll('[data-setting]')].find(e => e.dataset.setting === key);
      if (replacement) { if (editingWeight !== null) replacement.value = editingWeight; replacement.focus({ preventScroll: true }); if (replacement.setSelectionRange && typeof start === 'number' && !['number', 'checkbox'].includes(replacement.type)) replacement.setSelectionRange(start, end); }
    }
  }
  let renderedView = null;
  function render() {
    if (!initialized) return;
    const state = store.get();
    const viewKey = `${currentRoute}/${trayId}`;
    const markup = shell(views[currentRoute](state), state);
    rememberFocus(app, () => {
      // Keep switches mounted through local changes, native save replies and live updates.
      // Replacing them with innerHTML would discard their in-progress CSS transitions.
      if (renderedView === viewKey && app.querySelector('input[role="switch"][data-setting]')) {
        const next = app.cloneNode(false);
        next.innerHTML = markup;
        patchLiveNode(app, next);
      } else app.innerHTML = markup;
    });
    renderedView = viewKey;
    document.title = `${routeNames[currentRoute]} · ${CONFIG.appName}`;
    if (modal === 'developer') renderDeveloper();
  }
  function scheduleRender() {
    if (renderPending) return;
    renderPending = true;
    // A removed, edited number input can emit a native change during blur.
    // Queue updates so that event cannot re-enter the same DOM replacement.
    queueMicrotask(() => { renderPending = false; render(); });
  }
  function openModal(type, content) {
    previousFocus = document.activeElement;
    modal = type; document.body.classList.add('modal-open');
    modalRoot.innerHTML = `<div class="modal-overlay" data-action="overlay-close"><section class="modal-dialog " role="dialog" aria-modal="true" aria-labelledby="modal-title" tabindex="-1">${content}</section></div>`;
    setTimeout(() => modalRoot.querySelector('input:not([type="checkbox"]), .modal-dialog')?.focus(), 20);
  }
  function closeModal() {
    modal = null; modalRoot.innerHTML = ''; document.body.classList.remove('modal-open'); previousFocus?.focus?.({ preventScroll: true });
  }
  function showRename(id) {
    const tray = store.get().preferences.trays[id - 1];
    openModal('rename', `<div class="modal-header"><h2 id="modal-title">트레이 이름 바꾸기</h2><button class="icon-button" data-action="close-modal" aria-label="닫기">${icon('close')}</button></div><p class="modal-description">${id}번 트레이에 사용할 별칭을 정해 주세요.</p><form id="rename-form" data-id="${id}"><label class="input-label" for="tray-alias">트레이 별칭</label><input class="text-input" id="tray-alias" name="alias" maxlength="16" value="${esc(tray.alias)}" required autocomplete="off"><span class="input-helper">1~16자 · 알림과 모든 화면에 함께 적용돼요</span><div class="modal-actions">${button('취소', 'data-action="close-modal" type="button"', '', 'button secondary')}${button('이름 저장', 'type="submit"', 'check', 'button primary')}</div></form>`);
  }
  // Patch live controls in place so updates preserve switch transitions, focus,
  // and the developer drawer's opening animation and unfinished weight edits.
  function patchLiveNode(current, next) {
    if (current.nodeType !== next.nodeType || current.nodeName !== next.nodeName) {
      current.replaceWith(next.cloneNode(true));
      return;
    }
    if (current.nodeType !== Node.ELEMENT_NODE) {
      if (current.nodeValue !== next.nodeValue) current.nodeValue = next.nodeValue;
      return;
    }
    const editingWeight = current === document.activeElement && current.matches('input[data-dev="weight"]');
    for (const attribute of [...current.attributes]) {
      if (editingWeight && attribute.name === 'value') continue;
      if (!next.hasAttribute(attribute.name)) current.removeAttribute(attribute.name);
    }
    for (const attribute of next.attributes) {
      if (editingWeight && attribute.name === 'value') continue;
      if (current.getAttribute(attribute.name) !== attribute.value) current.setAttribute(attribute.name, attribute.value);
    }
    const children = [...current.childNodes];
    const nextChildren = [...next.childNodes];
    for (let index = 0; index < Math.max(children.length, nextChildren.length); index++) {
      if (!nextChildren[index]) children[index].remove();
      else if (!children[index]) current.append(nextChildren[index].cloneNode(true));
      else patchLiveNode(children[index], nextChildren[index]);
    }
    // Form properties can differ from their HTML attributes after user input.
    if (current instanceof HTMLInputElement) {
      if (current.type === 'checkbox') {
        if (current.checked !== next.checked) current.checked = next.checked;
      } else if (!editingWeight && current.value !== next.value) current.value = next.value;
    } else if (current instanceof HTMLSelectElement && current.value !== next.value) {
      current.value = next.value;
    }
  }
  function renderDeveloper() {
    const state = store.get();
    const scroll = modalRoot.querySelector('.developer-body')?.scrollTop || 0;
    const template = document.createElement('template');
    template.innerHTML = `<div class="modal-overlay developer-overlay" data-action="overlay-close"><section class="developer-drawer" role="dialog" aria-modal="true" aria-labelledby="modal-title" tabindex="-1"><div class="developer-header"><div><span class="eyebrow">DEVELOPER TOOLS</span><h2 id="modal-title">시뮬레이션 스튜디오</h2></div><button class="icon-button" data-action="close-modal" aria-label="개발자 패널 닫기">${icon('close')}</button></div><div class="developer-body"><div class="developer-note">${icon('code')}실제 Arduino 연결 없이 앱 동작을 검증해요.<br>이 패널의 값은 가상 센서 데이터예요.</div><section class="dev-section"><h3>연결 &amp; 현관문</h3><div class="dev-connection">${connectionBadge(state)}${button(state.connection === 'connected' ? '연결 해제' : '연결하기', `data-action="${state.connection === 'connected' ? 'disconnect' : 'reconnect'}"`, '', 'button secondary compact', state.connection === 'connecting' ? 'disabled' : '')}</div>${button('거리 이탈 체험', 'data-action="connection-lost"', 'linkoff', 'button secondary compact', state.connection !== 'connected' ? 'disabled' : '')}<div class="dev-segment" aria-label="현관문 상태"><button data-action="door-close" class="${state.door === 'closed' ? 'active' : ''}" ${!isFresh(state) ? 'disabled' : ''}>${icon('door')}문 닫힘</button><button data-action="door-open" class="${state.door === 'open' ? 'active' : ''}" ${!isFresh(state) ? 'disabled' : ''}>${icon('door')}문 열림</button></div><label class="dev-checkbox"><input type="checkbox" id="pause-data" data-dev="pause" ${state.paused ? 'checked' : ''} ${state.connection !== 'connected' ? 'disabled' : ''}>수신 중단 · 오래된 데이터 상태 체험</label><p class="dev-helper">문 열림 → 거리 이탈로 연결 후 끊김을 체험할 수 있어요. 닫힘 신호가 없으면 설정 시간이 지난 뒤 한 번 알려드려요.</p></section><section class="dev-section"><h3>가상 트레이 센서</h3>${state.trays.map(t => `<div class="dev-tray"><div class="dev-tray-heading"><strong>${slotIcon(t.id)}${pad(t.id)} ${esc(state.preferences.trays[t.id - 1].alias)}</strong>${statusBadge(trayStatus(state, t))}</div><label class="dev-checkbox"><input type="checkbox" id="present-${t.id}" data-dev="present" data-id="${t.id}" ${t.present ? 'checked' : ''} ${state.connection !== 'connected' || state.paused ? 'disabled' : ''}>물건 있음</label><div class="dev-fields"><label>가상 무게 (g)<input type="number" min="0" max="1000" step="0.1" value="${t.weight}" id="weight-${t.id}" data-dev="weight" data-id="${t.id}" ${state.connection !== 'connected' || state.paused ? 'disabled' : ''}></label><label>센서 상태<select data-dev="sensor" id="sensor-${t.id}" data-id="${t.id}" ${state.connection !== 'connected' || state.paused ? 'disabled' : ''}><option value="ok" ${t.sensor === 'ok' ? 'selected' : ''}>정상</option><option value="error" ${t.sensor === 'error' ? 'selected' : ''}>오류 발생</option><option value="waiting" ${t.sensor === 'waiting' ? 'selected' : ''}>수신 대기</option></select></label></div><div class="dev-preview-actions">${button('LED 동작', `data-action="led" data-id="${t.id}"`, 'light', 'button compact secondary')}${button('살균', `data-action="effect" data-id="${t.id}"`, 'sparkle', 'button compact secondary')}</div></div>`).join('')}</section><section class="dev-section"><h3>우산 &amp; 날씨</h3><label class="dev-checkbox"><input type="checkbox" data-dev="umbrella" id="umbrella-present" ${state.umbrella.present ? 'checked' : ''} ${!isFresh(state) ? 'disabled' : ''}>우산 공간에 우산 있음</label><label class="setting-row"><span>가상 날씨</span><select data-dev="weather" id="weather-condition"><option value="" disabled ${!weatherCondition(state) ? 'selected' : ''}>날씨 선택</option>${Object.entries(WEATHER_CONDITIONS).map(([key, condition]) => `<option value="${key}" ${weatherCondition(state)?.key === key ? 'selected' : ''}>${condition.label}</option>`).join('')}</select></label><label class="setting-row"><span>적외선 센서 상태</span><select data-dev="umbrella-sensor" id="umbrella-sensor"><option value="ok" ${state.umbrella.sensor === 'ok' ? 'selected' : ''}>정상</option><option value="error" ${state.umbrella.sensor === 'error' ? 'selected' : ''}>오류</option><option value="waiting" ${state.umbrella.sensor === 'waiting' ? 'selected' : ''}>수신 대기</option></select></label><p class="dev-helper">우산과 비 예보를 설정한 뒤 문을 닫았다 열어 보세요. 우산의 단순 거치·제거는 알리지 않아요.</p></section><section class="dev-section"><h3>알림 &amp; 테스트 시나리오</h3>${button('가상 알림 만들기', 'data-action="preview-notification"', 'bell', 'button secondary full-width')}<div class="scenario-list">${[['A', '1·2번 트레이에 물건 있음', '물건이 남은 두 트레이를 한 번에 안내'], ['B', '1번 트레이 알림 비활성화', '1번 트레이의 미수거 알림 제외'], ['C', '모든 트레이 비어 있음', '미수거 알림 없음'], ['D', 'Bluetooth 연결 해제', '이전 값을 현재 상태로 표시하지 않음']].map(([key, title, desc]) => `<button class="scenario-button" data-scenario="${key}"><span>${key}</span><div><strong>${title}</strong><small>${desc}</small></div>${icon('arrow')}</button>`).join('')}</div><p class="dev-helper">시나리오는 알림 설정과 가상 센서값을 재설정해요. B·C에서는 문 열림 알림만 발생할 수 있어요.</p></section><section class="dev-section"><h3>현재 알림 결과 <span class="mini-tag">${state.notifications.length}</span></h3>${state.notifications[0] ? `<div class="dev-notification"><strong>${esc(state.notifications[0].title)}</strong><p>${esc(state.notifications[0].message)}</p><small>${time(state.notifications[0].time)}</small></div>` : '<p class="dev-helper">아직 생성된 알림이 없어요.</p>'}</section></div><footer class="developer-footer">가상 데이터만 변경돼요 · 실제 Bluetooth 통신 없음</footer></section></div>`;
    const current = modalRoot.querySelector('.developer-overlay');
    if (current) patchLiveNode(current, template.content.firstElementChild);
    else modalRoot.replaceChildren(template.content);
    const body = modalRoot.querySelector('.developer-body');
    if (body.scrollTop !== scroll) body.scrollTop = scroll;
  }
  function showDeveloper() {
    if (transport.isNative) { toast('설정에서 목업 체험 모드로 전환하면 시뮬레이션을 사용할 수 있어요.', 'info'); return; }
    previousFocus = document.activeElement; modal = 'developer'; document.body.classList.add('modal-open'); renderDeveloper(); modalRoot.querySelector('.developer-drawer').focus();
  }
  async function changeSetting(element) {
    if (element.dataset.setting === 'notifications.doorOpenSeconds' && (!/^\d+$/.test(element.value) || Number(element.value) < 1 || Number(element.value) > 300)) {
      element.value = store.get().preferences.notifications.doorOpenSeconds;
      toast('문 열림 판단 시간은 1~300초의 정수로 입력해 주세요.', 'info'); return;
    }
    const parts = element.dataset.setting.split('.');
    store.update(state => {
      let target = state.preferences;
      if (parts[0] === 'tray') { target = state.preferences.trays[Number(parts[1]) - 1]; parts.splice(0, 2); }
      for (const key of parts.slice(0, -1)) target = target[key];
      const key = parts[parts.length - 1];
      target[key] = element.type === 'checkbox' ? element.checked : Number(element.value);
      if (element.dataset.setting === 'umbrella.effect.duration') {
        // The time selector stays available at 0 so users can turn sterilization back on.
        target.enabled = target.duration > 0;
      }
      if (!transport.isNative) state.trays.forEach(tray => { const pref = state.preferences.trays[tray.id - 1]; if (!pref.led.enabled) tray.ledActive = false; else tray.ledActive = isFresh(state) && tray.sensor === 'ok' && tray.present && (pref.led.onDetect || (state.door === 'open' && pref.led.onDoor)); if (!pref.effect.enabled) tray.effectUntil = 0; });
    });
    save();
  }
  async function completeSetup() {
    const previous = store.get().preferences.setupDone;
    store.update(s => { s.preferences.setupDone = true; });
    if (!await transport.savePreferences()) {
      store.update(s => { s.preferences.setupDone = previous; });
      toast('설정을 저장하지 못했어요. 다시 시도해 주세요.', 'info'); return;
    }
    closeModal(); navigate('home');
    toast(transport.isNative && store.get().notificationsAllowed === false ? '설정을 저장했어요. 외출 안내를 받으려면 알림을 허용해 주세요.' : '설정을 저장했어요. 빠짐없는 하루를 시작해요.');
  }
  async function finishSetup(demo) {
    if (demo && transport.isNative && await transport.useDemo() === false) return;
    if (await transport.connect({ demo })) await completeSetup();
  }
  document.addEventListener('click', async event => {
    // An anchor to the current hash does not emit hashchange, so handle home re-selection too.
    if (!event.defaultPrevented && event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey && event.target.closest('a[href="#home"]')) {
      event.preventDefault(); navigate('home'); return;
    }
    const element = event.target.closest('[data-route], [data-action], [data-filter], [data-scenario]');
    if (!element || element.disabled) return;
    if (element.dataset.route) { navigate(element.dataset.route, element.dataset.id); return; }
    if (element.dataset.filter) { activityFilter = element.dataset.filter; render(); return; }
    if (element.dataset.scenario) { await transport.scenario(element.dataset.scenario); toast(`시나리오 ${element.dataset.scenario}를 적용했어요. 패널 하단에서 알림 결과를 확인하세요.`); return; }
    const action = element.dataset.action, id = Number(element.dataset.id) || trayId;
    if (action === 'overlay-close') { if (event.target === element) closeModal(); return; }
    if (action === 'explore') { element.disabled = true; element.textContent = '체험을 준비하고 있어요…'; await finishSetup(true); }
    if (action === 'finish-setup') { element.disabled = true; await completeSetup(); }
    if (action === 'location-settings') await transport.openLocationSettings();
    if (action === 'app-location-settings') await transport.openAppLocationSettings();
    if (action === 'refresh-weather') await transport.refreshWeather();
    if (action === 'scan') await transport.scan();
    if (action === 'paired-devices') await transport.listPairedDevices();
    if (action === 'enable-bluetooth') await transport.enableBluetooth();
    if (action === 'bluetooth-settings') await transport.openBluetoothSettings();
    if (action === 'connect-device') {
      const fail = !!document.getElementById('fail-next-connection')?.checked;
      if (await transport.connect({ fail, address: element.dataset.address })) {
        const needsNotifications = transport.isNative && store.get().notificationsAllowed === false;
        toast(needsNotifications ? '연결되었어요. 외출 안내를 받으려면 알림을 허용해 주세요.' : '연결되었어요. 아래에서 날씨용 위치도 설정해 주세요.');
        requestAnimationFrame(() => document.getElementById(needsNotifications ? 'notification-permission-notice' : 'location-setup')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
      }
    }
    if (action === 'reconnect') { if (transport.isNative) { navigate('connect'); await transport.scan(); } else if (await transport.connect({ demo: true })) toast('가상 장치를 다시 연결했어요'); }
    if (action === 'connection-lost') { transport.loseConnection(); toast('연결이 끊겨도 열린 문의 확인 대기는 유지돼요.', 'info'); }
    if (action === 'disconnect') { await transport.disconnect(); toast('연결과 자동 재연결을 중지했어요.', 'info'); }
    if (action === 'rename') showRename(id);
    if (action === 'close-modal') closeModal();
    if (action === 'back') back();
    if (action === 'developer') showDeveloper();
    if (action === 'door-open' || action === 'door-close') { if (!transport.setDoor(action === 'door-open')) toast('장치 연결과 데이터 수신 상태를 먼저 확인해 주세요.', 'info'); }
    if (action === 'led' || action === 'effect') {
      const result = await (action === 'led' ? transport.playLed(id) : transport.playEffect(id));
      toast(result ? `${trayDisplayName(store.get().preferences.trays[id - 1].alias)} ${action === 'led' ? 'LED를 2.5초간 미리 보여드려요' : '살균을 시작했어요'}` : '최신 센서 데이터를 확인한 후 실행해 주세요.', result ? 'success' : 'info');
    }
    if (action === 'umbrella-effect') {
      const started = await transport.playUmbrellaEffect();
      toast(started ? '우산 살균을 시작했어요.' : transport.isNative ? '장치 연동 후 사용할 수 있어요.' : '우산 상태와 살균 설정을 확인해 주세요.', started ? 'success' : 'info');
    }
    if (action === 'preview-notification') await transport.previewNotification();
    if (action === 'notification-permission') {
      element.disabled = true;
      const permission = await transport.requestNotificationPermission();
      if (permission?.granted) toast('알림을 허용했어요. 설정한 조건에 따라 외출 안내를 받을 수 있어요.');
      else render();
    }
    if (action === 'notification-settings') await transport.openNotificationSettings();
    if (action === 'demo-mode') await finishSetup(true);
    if (action === 'native-mode') { await transport.useNative(); navigate('connect'); }
    if (action === 'dismiss-notification') dismissNotification();
    if (action === 'open-notification') {
      const notification = store.get().notifications.find(n => n.id === element.dataset.notificationId);
      const ids = (notification?.trayIds || element.dataset.trayIds.split(',').map(Number)).filter(id => CONFIG.trayIds.includes(id));
      if (notification) { store.update(() => { notification.read = true; }); await transport.markRead([notification.id]); }
      dismissNotification();
      navigate(notification?.kind === 'umbrella' ? 'umbrella' : ids.length === 1 ? 'detail' : ids.length > 1 ? 'trays' : 'home', ids.length === 1 ? ids[0] : undefined);
    }
    if (action === 'read-all') { store.update(s => s.notifications.forEach(n => { n.read = true; })); await transport.markRead(store.get().notifications.map(n => n.id)); toast('모든 알림을 읽음으로 표시했어요'); }
    if (action === 'dismiss-toast') { toastRoot.innerHTML = ''; clearTimeout(toastTimer); }
  });
  document.addEventListener('change', event => {
    const element = event.target;
    if (element.dataset.setting) changeSetting(element);
    if (element.dataset.dev) {
      const id = Number(element.dataset.id), tray = store.get().trays[id - 1];
      if (element.dataset.dev === 'umbrella') transport.setUmbrella(element.checked);
      if (element.dataset.dev === 'umbrella-sensor') transport.setUmbrellaSensor(element.value);
      if (element.dataset.dev === 'weather') transport.setWeather(element.value);
      if (element.dataset.dev === 'pause') transport.pause(element.checked);
      if (element.dataset.dev === 'present') transport.setTray(id, element.checked);
      if (element.dataset.dev === 'weight') {
        if (!Number.isFinite(Number(element.value)) || Number(element.value) < 0 || Number(element.value) > 1000 || element.value === '') { element.value = tray.weight; toast('가상 무게는 0~1,000 g 범위로 입력해 주세요.', 'info'); return; }
        transport.setTray(id, tray.present, Number(element.value));
      }
      if (element.dataset.dev === 'sensor') transport.setSensor(id, element.value);
    }
  });
  document.addEventListener('submit', event => {
    if (event.target.id !== 'rename-form') return;
    event.preventDefault(); const id = Number(event.target.dataset.id), input = document.getElementById('tray-alias');
    const alias = input.value.trim().slice(0, 16);
    if (!alias) { input.setCustomValidity('트레이 별칭을 입력해 주세요.'); input.reportValidity(); return; }
    store.update(state => {
      state.preferences.trays[id - 1].alias = alias;
      state.notifications.forEach(n => {
        if (!n.trayIds?.includes(id)) return;
        const names = n.trayIds.map(tray => state.preferences.trays[tray - 1].alias);
        if (n.kind === 'belongings') { n.items = names; n.message = trayNotificationMessage(names); }
        if (n.kind === 'preview') n.message = trayNotificationMessage(names);
      });
    });
    closeModal(); save('트레이 이름을 바꿨어요');
  });
  document.addEventListener('input', event => { if (event.target.id === 'tray-alias') event.target.setCustomValidity(''); });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { if (modal) { event.preventDefault(); closeModal(); } else if (notificationRoot.firstChild) { event.preventDefault(); dismissNotification(); } return; }
    if (event.key.toLowerCase() === 'd' && !event.metaKey && !event.ctrlKey && !event.altKey && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName) && !modal) { event.preventDefault(); showDeveloper(); }
    if (event.key === 'Tab' && modal) {
      const focusable = [...modalRoot.querySelectorAll('button:not([disabled]), a[href], input:not([disabled]), select:not([disabled])')].filter(e => e.offsetParent !== null);
      const first = focusable[0], last = focusable[focusable.length - 1];
      if (!first) return;
      if (event.shiftKey && (document.activeElement === first || document.activeElement.getAttribute('role') === 'dialog')) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement.getAttribute('role') === 'dialog')) { event.preventDefault(); first.focus(); }
    }
  });
  window.addEventListener('hashchange', () => { hasNavigated = true; getRoute(); closeModal(); render(); refreshHomeWeather(); window.scrollTo({ top: 0 }); document.getElementById('main-content')?.focus({ preventScroll: true }); });
  store.subscribe(state => {
    scheduleRender();
    const latest = state.notifications[0];
    if (!transport.isNative && latest && latest.id !== lastNotificationId) { lastNotificationId = latest.id; showSystemNotification(latest); }
  });
  window.addEventListener('smarttray-error', event => toast(event.detail, 'info'));
  window.addEventListener('smarttray-route', event => { closeModal(); const [route, id] = event.detail.split('/'); navigate(route, id); });
  window.addEventListener('smarttray-app-active', refreshHomeWeather);
  (async () => {
    await transport.initialize();
    if (!transport.openedFromNotification) {
      if (!store.get().preferences.setupDone) history.replaceState(null, '', '#connect');
      else if (transport.hasNative || !location.hash || location.hash === '#connect') history.replaceState(null, '', '#home');
    }
    getRoute(); initialized = true; render(); refreshHomeWeather();
    if (store.get().preferences.setupDone && !transport.isNative) transport.connect({ demo: true });
    if (transport.hasNative) window.SmartTrayNative.app.addListener('backButton', () => {
      if (modal) closeModal();
      else if (currentRoute !== 'home') navigate(currentRoute === 'detail' ? 'trays' : ['connect', 'device', 'notifications', 'about', 'umbrella'].includes(currentRoute) ? 'settings' : 'home');
      else window.SmartTrayNative.app.exitApp();
    });
  })();
  const heartbeat = setInterval(() => { transport.refresh(); if (transport.isNative || store.get().paused) render(); }, 8000);
  window.addEventListener('pagehide', () => { clearInterval(heartbeat); clearTimeout(notificationTimer); clearTimeout(toastTimer); transport.dispose(); });
  // UI-facing state and the selected adapter; native socket ownership stays in the Android service.
  window.SmartTrayApp = { store, transport, navigate, openDeveloper: showDeveloper };
})();
