import 'dotenv/config';

export default {
  name: "Numa",
  slug: "numa",
  version: "1.0.0",
  ios: {
    bundleIdentifier: "com.numa",
  },
  android: {
    package: "com.numa",
    versionCode: 1,
  },
  plugins: [
    "./plugins/withNuma",
  ],
};
