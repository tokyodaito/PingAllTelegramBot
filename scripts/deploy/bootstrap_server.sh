#!/usr/bin/env bash
set -euo pipefail

APP_NAME="botpingall"
APP_ROOT="/opt/${APP_NAME}"
RELEASES_DIR="${APP_ROOT}/releases"
SHARED_DIR="${APP_ROOT}/shared"
DATA_DIR="${SHARED_DIR}/data"
ENV_DIR="/etc/${APP_NAME}"
ENV_FILE="${ENV_DIR}/env"
SERVICE_FILE="/etc/systemd/system/${APP_NAME}.service"
SERVICE_TEMPLATE="${1:-}"

if [[ "$(id -u)" -ne 0 ]]; then
    echo "bootstrap_server.sh must run as root" >&2
    exit 1
fi

if [[ -z "${SERVICE_TEMPLATE}" || ! -f "${SERVICE_TEMPLATE}" ]]; then
    echo "usage: $0 /path/to/botpingall.service" >&2
    exit 1
fi

if ! id -u "${APP_NAME}" >/dev/null 2>&1; then
    useradd \
        --system \
        --home "${APP_ROOT}" \
        --shell /usr/sbin/nologin \
        --user-group \
        "${APP_NAME}"
fi

install -d -o "${APP_NAME}" -g "${APP_NAME}" -m 0750 "${APP_ROOT}"
install -d -o "${APP_NAME}" -g "${APP_NAME}" -m 0750 "${RELEASES_DIR}"
install -d -o "${APP_NAME}" -g "${APP_NAME}" -m 0750 "${SHARED_DIR}"
install -d -o "${APP_NAME}" -g "${APP_NAME}" -m 0750 "${DATA_DIR}"
install -d -o root -g root -m 0755 "${ENV_DIR}"

if [[ ! -f "${ENV_FILE}" ]]; then
    cat > "${ENV_FILE}" <<EOF
BOT_TOKEN=
BOT_DB_PATH=${DATA_DIR}/bot.db
EOF
fi

if ! grep -q '^BOT_DB_PATH=' "${ENV_FILE}"; then
    printf '\nBOT_DB_PATH=%s\n' "${DATA_DIR}/bot.db" >> "${ENV_FILE}"
fi

chown root:"${APP_NAME}" "${ENV_FILE}"
chmod 0640 "${ENV_FILE}"

install -o root -g root -m 0644 "${SERVICE_TEMPLATE}" "${SERVICE_FILE}"

systemctl daemon-reload
systemctl enable "${APP_NAME}.service"

echo "Bootstrap complete for ${APP_NAME}."
echo "Service file: ${SERVICE_FILE}"
echo "Environment file: ${ENV_FILE}"

if grep -Eq '^BOT_TOKEN=.+$' "${ENV_FILE}"; then
    echo "BOT_TOKEN is configured."
else
    echo "BOT_TOKEN is empty. Fill ${ENV_FILE} before the first deploy start."
fi
