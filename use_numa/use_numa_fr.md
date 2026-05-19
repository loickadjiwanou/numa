# Numa — Guide d'activation et d'utilisation

Ce guide décrit toutes les étapes pour rendre Numa pleinement opérationnel après l'installation de l'APK, depuis les permissions jusqu'à la première utilisation.

---

## Prérequis avant de commencer

- L'APK Numa est installé sur l'appareil
- Le débogage USB est activé (si vous utilisez les commandes adb)
- L'appareil tourne sous Android 8.0 (API 26) minimum

---

## Étape 1 — Autoriser l'accès à tous les fichiers

Numa doit pouvoir lire et effacer le dossier protégé sur le stockage externe.

### Via adb
```bash
adb shell appops set com.numa MANAGE_EXTERNAL_STORAGE allow
```

### Via les paramètres de l'appareil
**Paramètres → Applications → Numa → Autorisations → Fichiers et médias → Autoriser la gestion de tous les fichiers**

> Sur certains appareils : **Paramètres → Confidentialité → Gestionnaire de fichiers spéciaux → Numa → Activer**

---

## Étape 2 — Activer le service d'accessibilité

C'est la permission la plus importante. Elle permet à Numa d'intercepter les touches volume sans aucune interface visible.

### Via adb
```bash
adb shell settings put secure enabled_accessibility_services com.numa/.NumaAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Via les paramètres de l'appareil
**Paramètres → Accessibilité → Services installés → Numa → Activer**

Un message de confirmation système apparaîtra — accepter.

> **Note :** L'application peut se fermer brièvement lors de l'activation. C'est normal — Android redémarre le processus.

---

## Étape 3 — Désactiver l'optimisation de batterie

Sans cette étape, Android peut tuer le service Numa en arrière-plan, surtout quand l'écran est éteint.

### Via adb
```bash
adb shell dumpsys deviceidle whitelist +com.numa
```

### Via les paramètres de l'appareil
**Paramètres → Batterie → Optimisation de la batterie → Toutes les applications → Numa → Ne pas optimiser**

> Sur certains appareils (Samsung, Xiaomi, Huawei) : **Paramètres → Batterie → Démarrage automatique → Numa → Autoriser**

---

## Étape 4 — Vérifier que les services tournent

### Via adb
```bash
adb shell dumpsys activity services com.numa
```

Dans la sortie, vous devez voir :

```
User 0 active services:
  * ServiceRecord{...} com.numa/.NumaAccessibilityService
```

Le champ `crashCount` doit être à `0` et le service doit être dans **"active services"**, pas dans **"Restarting services"**.

---

## Étape 5 — Créer le dossier protégé

Créez manuellement le dossier sur le stockage interne de l'appareil. Par défaut son nom est `Safe` (configurable via `FOLDER_NAME` dans le `.env`).

```
/storage/emulated/0/Safe/
```

Placez-y les fichiers à protéger. Numa utilisera ce dossier comme source pour le PANIC.

---

## Étape 6 — Ouvrir l'app une première fois

Numa doit être ouvert au moins une fois pour démarrer `NumaService`.

- Ouvrez l'app depuis le launcher (elle apparaît brièvement puis ne montre rien)
- Ou via adb :

```bash
adb shell am start -n com.numa/.MainActivity
```

Après ce premier démarrage, Numa survit aux redémarrages du téléphone grâce au `NumaBootReceiver`.

---

## Utilisation — Séquences de touches

Les deux séquences doivent être effectuées en **moins de 3 secondes**, avec un minimum de **200ms entre chaque pression**.

### PANIC — Sauvegarde + effacement sécurisé
**Vol+ → Vol− → Vol+ → Vol+**

1. Confirmation : double vibration courte (100ms · pause 50ms · 100ms)
2. Opération en cours (quelques secondes selon la taille du dossier)
3. Succès : 3 vibrations courtes + toast `"numa panic ok"`
4. Échec : 1 vibration longue forte (dossier local préservé si upload échoué)

### RESTORE — Téléchargement + déchiffrement
**Vol− → Vol+ → Vol− → Vol−**

1. Confirmation : 1 vibration longue (300ms)
2. Téléchargement + déchiffrement en cours
3. Succès : 3 vibrations courtes + toast `"numa restore ok"`
4. Échec : 1 vibration longue forte

---

## Période de validité (VALID_PERIOD)

Si une période de validité a été configurée dans le `.env`, le compte à rebours démarre au **premier PANIC réussi**.

Après expiration :
- Toute tentative PANIC ou RESTORE affiche le toast `"numa expiré"` + vibration erreur
- Aucune opération n'est exécutée

| Valeur | Durée |
|--------|-------|
| `5mins` | 5 minutes |
| `3hrs` | 3 heures |
| `7jrs` | 7 jours |
| `2smns` | 2 semaines |
| `6mois` | 6 mois |
| `1ans` | 1 an |
| *(vide)* | Pas d'expiration |

---

## Surveillance des logs (débogage)

Pour voir ce que Numa fait en temps réel :

```bash
# Opérations PANIC / RESTORE
adb logcat -s NumaOp:D

# Tous les tags Numa
adb logcat -s NumaOp:D NumaService:D CryptoManager:D R2Uploader:D ZipManager:D SecureWipe:D

# Crashes uniquement
adb logcat -b crash
```

---

## Récapitulatif — Checklist d'activation

| Étape | Via adb | Via paramètres | Obligatoire |
|-------|---------|----------------|-------------|
| Accès tous les fichiers | `appops set com.numa MANAGE_EXTERNAL_STORAGE allow` | Paramètres → Applications → Numa → Autorisations | Oui |
| Service d'accessibilité | `settings put secure enabled_accessibility_services ...` | Paramètres → Accessibilité → Numa | Oui |
| Batterie non optimisée | `dumpsys deviceidle whitelist +com.numa` | Paramètres → Batterie → Numa → Ne pas optimiser | Recommandé |
| Créer dossier Safe | `adb shell mkdir /storage/emulated/0/Safe` | Gestionnaire de fichiers | Oui |
| Premier lancement | `adb shell am start -n com.numa/.MainActivity` | Ouvrir l'app | Oui |

---

## Problèmes courants

**L'app ne répond pas aux séquences :**
- Vérifiez que le service d'accessibilité est bien actif (Étape 2)
- Relancez `dumpsys activity services com.numa` et vérifiez `crashCount=0`

**Toast "numa expiré" affiché :**
- La période `VALID_PERIOD` est dépassée
- L'app doit être recompilée avec une nouvelle période ou sans expiration

**PANIC réussi mais dossier Safe toujours présent :**
- L'effacement multi-passes peut prendre plusieurs secondes sur les gros dossiers
- Vérifiez les logs avec `adb logcat -s NumaOp:D`

**R2 reçoit un fichier de 50 octets :**
- `MANAGE_EXTERNAL_STORAGE` n'est pas accordée (Étape 1)
- Relancez les commandes d'Étape 1 et retestez

**Le service est dans "Restarting services" :**
- Un crash s'est produit — consultez `adb logcat -b crash`
- Réinstallez l'APK et reprenez depuis l'Étape 2
