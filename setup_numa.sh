#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# setup_numa.sh — Configure Numa sur un device Android connecté en USB
#
# Usage :
#   chmod +x setup_numa.sh
#   ./setup_numa.sh
#
# Prérequis :
#   - adb installé et dans le PATH
#   - Débogage USB activé sur le device
#   - L'APK Numa déjà installé sur le device
#   - Un fichier .env présent à la racine du projet (ou FOLDER_NAME en variable d'env)
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

# ─── Couleurs ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

ok()   { echo -e "${GREEN}  ✓${RESET} $*"; }
info() { echo -e "${CYAN}  →${RESET} $*"; }
warn() { echo -e "${YELLOW}  !${RESET} $*"; }
fail() { echo -e "${RED}  ✗${RESET} $*"; exit 1; }

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}   Numa — Setup automatique               ${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""

# ─── Lecture du FOLDER_NAME depuis .env ───────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
FOLDER_NAME="Safe"  # valeur par défaut

if [[ -f "$ENV_FILE" ]]; then
    val=$(grep -E '^FOLDER_NAME=' "$ENV_FILE" | cut -d'=' -f2- | tr -d '[:space:]')
    [[ -n "$val" ]] && FOLDER_NAME="$val"
    info "Dossier protégé lu depuis .env : $FOLDER_NAME"
else
    warn ".env non trouvé — dossier par défaut : $FOLDER_NAME"
fi

PACKAGE="com.numa"
SAFE_PATH="/storage/emulated/0/$FOLDER_NAME"

# ─── Vérification adb ─────────────────────────────────────────────────────────
echo -e "\n${BOLD}[0/6] Vérification de adb${RESET}"
command -v adb &>/dev/null || fail "adb non trouvé. Installez Android SDK Platform Tools."
ok "adb disponible"

# ─── Attente du device ────────────────────────────────────────────────────────
echo -e "\n${BOLD}[1/6] Détection du device${RESET}"
info "Attente d'un device Android connecté..."
adb wait-for-device

DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
[[ -z "$DEVICE" ]] && fail "Aucun device autorisé détecté. Vérifiez le débogage USB et acceptez la demande sur l'écran."
ok "Device connecté : $DEVICE"

# ─── Vérification que l'app est installée ─────────────────────────────────────
echo -e "\n${BOLD}[2/6] Vérification de l'installation${RESET}"
adb -s "$DEVICE" shell pm list packages | grep -q "$PACKAGE" \
    || fail "Package $PACKAGE non trouvé. Installez l'APK d'abord."
ok "$PACKAGE est installé"

# ─── Étape 1 : Accès stockage externe ─────────────────────────────────────────
echo -e "\n${BOLD}[3/6] Accès à tous les fichiers (MANAGE_EXTERNAL_STORAGE)${RESET}"
adb -s "$DEVICE" shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow
ok "Permission stockage accordée"

# ─── Étape 2 : Service d'accessibilité ────────────────────────────────────────
echo -e "\n${BOLD}[4/6] Service d'accessibilité${RESET}"
adb -s "$DEVICE" shell settings put secure \
    enabled_accessibility_services "${PACKAGE}/.NumaAccessibilityService"
adb -s "$DEVICE" shell settings put secure accessibility_enabled 1
ok "NumaAccessibilityService activé"

# ─── Étape 3 : Optimisation batterie ──────────────────────────────────────────
echo -e "\n${BOLD}[5/6] Désactivation de l'optimisation batterie${RESET}"
adb -s "$DEVICE" shell dumpsys deviceidle whitelist +"$PACKAGE" 2>/dev/null || true
ok "$PACKAGE ajouté à la whitelist Doze"

# ─── Étape 4 : Création du dossier Safe ───────────────────────────────────────
echo -e "\n${BOLD}[6/6] Création du dossier protégé${RESET}"
EXISTS=$(adb -s "$DEVICE" shell "[ -d '$SAFE_PATH' ] && echo yes || echo no" | tr -d '\r')
if [[ "$EXISTS" == "yes" ]]; then
    warn "Dossier '$FOLDER_NAME' déjà présent — ignoré"
else
    adb -s "$DEVICE" shell mkdir -p "$SAFE_PATH"
    ok "Dossier créé : $SAFE_PATH"
fi

# ─── Lancement de l'app (démarrage NumaService) ───────────────────────────────
echo -e "\n${BOLD}[+] Premier lancement (démarrage NumaService)${RESET}"
adb -s "$DEVICE" shell am start -n "${PACKAGE}/.MainActivity" &>/dev/null || true
sleep 2
# Retour à l'écran d'accueil — l'app ne figure pas dans les récentes
# grâce à android:excludeFromRecents="true" dans le manifest
adb -s "$DEVICE" shell input keyevent KEYCODE_HOME
ok "MainActivity lancée → retour à l'écran d'accueil (invisible dans les récentes)"

# ─── Vérification finale ──────────────────────────────────────────────────────
echo -e "\n${BOLD}[✓] Vérification des services${RESET}"
SERVICES=$(adb -s "$DEVICE" shell dumpsys activity services "$PACKAGE" 2>/dev/null)

if echo "$SERVICES" | grep -q "NumaAccessibilityService"; then
    ok "NumaAccessibilityService : actif"
else
    warn "NumaAccessibilityService non détecté — activez-le manuellement :"
    echo "    Paramètres → Accessibilité → Services installés → Numa → Activer"
fi

if echo "$SERVICES" | grep -q "NumaService"; then
    ok "NumaService : actif"
else
    warn "NumaService non détecté — rouvrez l'app une fois"
fi

# ─── Résumé ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${GREEN}${BOLD}   Setup terminé !${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
echo -e "  Dossier protégé  : ${BOLD}$SAFE_PATH${RESET}"
echo -e "  PANIC            : ${BOLD}Vol+ → Vol− → Vol+ → Vol+${RESET}"
echo -e "  RESTORE          : ${BOLD}Vol− → Vol+ → Vol− → Vol−${RESET}"
echo -e "  (séquence en < 3s, min 200ms entre chaque pression)"
echo ""
echo -e "  ${YELLOW}Placez vos fichiers dans $SAFE_PATH avant le premier PANIC.${RESET}"
echo ""
