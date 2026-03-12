import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.railbook.app',
  appName: 'RailBook',
  webDir: 'build',
  server: {
    // Live reload: run npm start, set url to your machine's IP (e.g. http://192.168.1.100:3000), then npx cap run android
    // url: 'http://YOUR_IP:3000',
    // cleartext: true,
  },
  android: {
    allowMixedContent: true,
  },
  ios: {
    contentInset: 'automatic',
  },
};

export default config;
