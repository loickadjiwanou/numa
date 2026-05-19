const {
  withAndroidManifest,
  withAppBuildGradle,
  withDangerousMod,
  withStringsXml,
  createRunOncePlugin,
} = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

// ─── Helpers ─────────────────────────────────────────────────────────────────

function addAttr(node, key, value) {
  if (!node.$) node.$ = {};
  node.$[key] = value;
}

function getOrCreate(parent, tag, match = {}) {
  if (!parent[tag]) parent[tag] = [];
  let found = parent[tag].find((n) =>
    Object.entries(match).every(([k, v]) => n.$?.[k] === v)
  );
  if (!found) {
    found = { $: { ...match } };
    parent[tag].push(found);
  }
  return found;
}

// ─── 1. AndroidManifest ───────────────────────────────────────────────────────

function withNumaManifest(config) {
  return withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults.manifest;

    // Permissions
    const permissions = [
      'android.permission.FOREGROUND_SERVICE',
      'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
      'android.permission.RECEIVE_BOOT_COMPLETED',
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',
      'android.permission.MANAGE_EXTERNAL_STORAGE',
      'android.permission.INTERNET',
      'android.permission.VIBRATE',
      'android.permission.WAKE_LOCK',
      'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
    ];
    if (!manifest['uses-permission']) manifest['uses-permission'] = [];
    for (const perm of permissions) {
      if (!manifest['uses-permission'].find((p) => p.$?.['android:name'] === perm)) {
        manifest['uses-permission'].push({ $: { 'android:name': perm } });
      }
    }

    const app = manifest.application[0];

    // App-level attributes
    addAttr(app, 'android:allowBackup', 'false');
    addAttr(app, 'android:testOnly', 'false');

    // Remove LAUNCHER category from MainActivity so the app is invisible
    if (app.activity) {
      for (const activity of app.activity) {
        if (activity['intent-filter']) {
          activity['intent-filter'] = activity['intent-filter'].filter((filter) => {
            const categories = filter.category || [];
            return !categories.some(
              (c) => c.$?.['android:name'] === 'android.intent.category.LAUNCHER'
            );
          });
        }
      }
    }

    // ── NumaService (foreground service) ──────────────────────────────────────
    const numaService = getOrCreate(app, 'service', {
      'android:name': 'com.numa.NumaService',
    });
    Object.assign(numaService.$, {
      'android:enabled': 'true',
      'android:exported': 'false',
      'android:foregroundServiceType': 'dataSync',
      'android:stopWithTask': 'false',
    });

    // ── NumaOperationService ──────────────────────────────────────────────────
    const opService = getOrCreate(app, 'service', {
      'android:name': 'com.numa.NumaOperationService',
    });
    Object.assign(opService.$, {
      'android:enabled': 'true',
      'android:exported': 'false',
    });

    // ── NumaAccessibilityService ──────────────────────────────────────────────
    const accessService = getOrCreate(app, 'service', {
      'android:name': 'com.numa.NumaAccessibilityService',
    });
    Object.assign(accessService.$, {
      'android:enabled': 'true',
      'android:exported': 'true',
      'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
      'android:label': '@string/app_name',
    });
    if (!accessService['intent-filter']) accessService['intent-filter'] = [];
    if (
      !accessService['intent-filter'].find(
        (f) =>
          f.action?.[0]?.$?.['android:name'] ===
          'android.accessibilityservice.AccessibilityService'
      )
    ) {
      accessService['intent-filter'].push({
        action: [
          { $: { 'android:name': 'android.accessibilityservice.AccessibilityService' } },
        ],
      });
    }
    if (!accessService['meta-data']) accessService['meta-data'] = [];
    if (
      !accessService['meta-data'].find(
        (m) => m.$?.['android:name'] === 'android.accessibilityservice'
      )
    ) {
      accessService['meta-data'].push({
        $: {
          'android:name': 'android.accessibilityservice',
          'android:resource': '@xml/numa_accessibility_service',
        },
      });
    }

    // ── NumaBootReceiver ──────────────────────────────────────────────────────
    const bootReceiver = getOrCreate(app, 'receiver', {
      'android:name': 'com.numa.NumaBootReceiver',
    });
    Object.assign(bootReceiver.$, {
      'android:enabled': 'true',
      'android:exported': 'true',
    });
    if (!bootReceiver['intent-filter']) bootReceiver['intent-filter'] = [];
    const bootIntentFilter = bootReceiver['intent-filter'][0] || {};
    if (!bootReceiver['intent-filter'][0]) bootReceiver['intent-filter'].push(bootIntentFilter);
    if (!bootIntentFilter.action) bootIntentFilter.action = [];
    const bootActions = [
      'android.intent.action.BOOT_COMPLETED',
      'android.intent.action.MY_PACKAGE_REPLACED',
      'com.numa.RESTART_SERVICE',
    ];
    for (const a of bootActions) {
      if (!bootIntentFilter.action.find((x) => x.$?.['android:name'] === a)) {
        bootIntentFilter.action.push({ $: { 'android:name': a } });
      }
    }

    return cfg;
  });
}

// ─── 2. BuildConfig fields (from process.env / .env) ─────────────────────────

function withNumaBuildConfig(config) {
  return withAppBuildGradle(config, (cfg) => {
    const fields = [
      ['FOLDER_NAME',          process.env.FOLDER_NAME          || 'Safe'],
      ['ENCRYPTION_KEY',       process.env.ENCRYPTION_KEY       || ''],
      ['R2_ACCOUNT_ID',        process.env.R2_ACCOUNT_ID        || ''],
      ['R2_ACCESS_KEY_ID',     process.env.R2_ACCESS_KEY_ID     || ''],
      ['R2_SECRET_ACCESS_KEY', process.env.R2_SECRET_ACCESS_KEY || ''],
      ['R2_BUCKET_NAME',       process.env.R2_BUCKET_NAME       || 'numa-backup'],
      ['R2_ENDPOINT',          process.env.R2_ENDPOINT          || ''],
      ['WIPE_PASSES',          process.env.WIPE_PASSES          || '7'],
      ['VALID_PERIOD',         process.env.VALID_PERIOD         || ''],
    ];

    const buildConfigLines = fields
      .map(
        ([key, val]) =>
          `        buildConfigField "String", "${key}", "\\"${val}\\""`
      )
      .join('\n');

    // Inject into defaultConfig block
    if (!cfg.modResults.contents.includes('buildConfigField "String", "FOLDER_NAME"')) {
      cfg.modResults.contents = cfg.modResults.contents.replace(
        /defaultConfig\s*\{/,
        `defaultConfig {\n${buildConfigLines}`
      );
    }

    return cfg;
  });
}

// ─── 3. strings.xml — accessibility service description ──────────────────────

function withNumaStrings(config) {
  return withStringsXml(config, (cfg) => {
    const strings = cfg.modResults.resources.string || [];
    if (!strings.find((s) => s.$?.name === 'accessibility_service_description')) {
      strings.push({
        $: { name: 'accessibility_service_description', translatable: 'false' },
        _: 'Numa background service',
      });
    }
    cfg.modResults.resources.string = strings;
    return cfg;
  });
}

// ─── 4. Copy Kotlin sources + XML resources via dangerousMod ─────────────────

function withNumaNativeSources(config) {
  return withDangerousMod(config, [
    'android',
    async (cfg) => {
      const projectRoot = cfg.modRequest.projectRoot;
      const platformRoot = cfg.modRequest.platformProjectRoot; // .../android

      const kotlinSrc = path.join(
        projectRoot,
        'plugins',
        'src',
        'kotlin'
      );
      const kotlinDest = path.join(
        platformRoot,
        'app',
        'src',
        'main',
        'java',
        'com',
        'numa'
      );

      const xmlSrc      = path.join(projectRoot, 'plugins', 'src', 'res', 'xml');
      const xmlDest     = path.join(platformRoot, 'app', 'src', 'main', 'res', 'xml');
      const drawableSrc  = path.join(projectRoot, 'plugins', 'src', 'res', 'drawable');
      const drawableDest = path.join(platformRoot, 'app', 'src', 'main', 'res', 'drawable');

      // Copy Kotlin files
      if (fs.existsSync(kotlinSrc)) {
        fs.mkdirSync(kotlinDest, { recursive: true });
        for (const file of fs.readdirSync(kotlinSrc)) {
          if (file.endsWith('.kt')) {
            fs.copyFileSync(
              path.join(kotlinSrc, file),
              path.join(kotlinDest, file)
            );
          }
        }
      }

      // Copy XML resources
      if (fs.existsSync(xmlSrc)) {
        fs.mkdirSync(xmlDest, { recursive: true });
        for (const file of fs.readdirSync(xmlSrc)) {
          fs.copyFileSync(path.join(xmlSrc, file), path.join(xmlDest, file));
        }
      }

      // Copy drawable resources
      if (fs.existsSync(drawableSrc)) {
        fs.mkdirSync(drawableDest, { recursive: true });
        for (const file of fs.readdirSync(drawableSrc)) {
          fs.copyFileSync(path.join(drawableSrc, file), path.join(drawableDest, file));
        }
      }

      return cfg;
    },
  ]);
}

// ─── Compose all mods ────────────────────────────────────────────────────────

const withNuma = (config) => {
  config = withNumaManifest(config);
  config = withNumaBuildConfig(config);
  config = withNumaStrings(config);
  config = withNumaNativeSources(config);
  return config;
};

module.exports = createRunOncePlugin(withNuma, 'withNuma', '1.0.0');
