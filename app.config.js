import 'dotenv/config';

export default {
  name: "Numa",
  slug: "numa",
  owner: "alck",
  version: "1.0.0",
  ios: {
    bundleIdentifier: "com.numa",
  },
  android: {
    package: "com.numa",
    versionCode: 2,
  },
  plugins: [
    "./plugins/withNuma",
  ],
  extra: {
    eas: {
      projectId: "e2280b12-1755-4227-8c76-b967c157fbcd",
    },
  },
  updates: {
    url: "https://u.expo.dev/e2280b12-1755-4227-8c76-b967c157fbcd",
  },
  runtimeVersion: "1.0.0",
};
