import { Capacitor, registerPlugin } from '@capacitor/core';
import { App } from '@capacitor/app';

// Loaded only in the packaged build. The original HTML remains a plain browser preview.
window.SmartTrayNative = {
  available: Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android',
  plugin: registerPlugin('SmartTray'),
  app: App
};
