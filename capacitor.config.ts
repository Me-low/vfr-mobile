import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'fr.vfr.briefing',
  appName: 'VFR Nav',
  webDir: 'www',
  server: {
    androidScheme: 'https',
    hostname: 'appassets.androidplatform.net'
  },
  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: true
  }
};

export default config;
