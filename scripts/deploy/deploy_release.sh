#!/usr/bin/env bash
set -euo pipefail

APP_NAME="botpingall"
APP_ROOT="/opt/${APP_NAME}"
RELEASES_DIR="${APP_ROOT}/releases"
CURRENT_LINK="${APP_ROOT}/current"
ENV_FILE="/etc/${APP_NAME}/env"
SERVICE_NAME="${APP_NAME}.service"
APP_ARCHIVE="${APP_ARCHIVE:-}"
KEEP_RELEASES="${KEEP_RELEASES:-3}"
RELEASE_ID="${RELEASE_ID:-}"
TEMP_DIR=""

cleanup() {
    if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
        rm -rf "${TEMP_DIR}"
    fi
}

rollback() {
    local previous_target="${1:-}"

    if [[ -n "${previous_target}" && -d "${previous_target}" ]]; then
        ln -sfn "${previous_target}" "${APP_ROOT}/current.next"
        mv -Tf "${APP_ROOT}/current.next" "${CURRENT_LINK}"
        systemctl restart "${SERVICE_NAME}" || true
    fi
}

wait_for_service() {
    local attempt

    for attempt in {1..10}; do
        if systemctl is-active --quiet "${SERVICE_NAME}"; then
            return 0
        fi
        sleep 2
    done

    return 1
}

prune_old_releases() {
    local keep="${1}"
    local current_target
    local line

    current_target="$(readlink -f "${CURRENT_LINK}")"

    while IFS= read -r line; do
        [[ -z "${line}" ]] && continue
        [[ "${line}" == "${current_target}" ]] && continue
        rm -rf "${line}"
    done < <(
        find "${RELEASES_DIR}" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' \
            | sort -nr \
            | awk -v keep="${keep}" 'NR > keep { print $2 }'
    )
}

trap cleanup EXIT

if [[ "$(id -u)" -ne 0 ]]; then
    echo "deploy_release.sh must run as root" >&2
    exit 1
fi

if [[ -z "${APP_ARCHIVE}" || ! -f "${APP_ARCHIVE}" ]]; then
    echo "APP_ARCHIVE must point to an existing release tarball" >&2
    exit 1
fi

if [[ -z "${RELEASE_ID}" ]]; then
    echo "RELEASE_ID is required" >&2
    exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
    echo "Missing ${ENV_FILE}. Run bootstrap and configure BOT_TOKEN first." >&2
    exit 1
fi

if ! grep -Eq '^BOT_TOKEN=.+$' "${ENV_FILE}"; then
    echo "BOT_TOKEN is empty in ${ENV_FILE}. Refusing to replace the running release." >&2
    exit 1
fi

TEMP_DIR="$(mktemp -d "/tmp/${APP_NAME}-${RELEASE_ID}-XXXXXX")"
mapfile -t ARCHIVE_ENTRIES < <(tar -tf "${APP_ARCHIVE}")
PAYLOAD_ROOT="${ARCHIVE_ENTRIES[0]%%/*}"
RELEASE_DIR="${RELEASES_DIR}/${RELEASE_ID}"
PREVIOUS_TARGET=""

if [[ -z "${PAYLOAD_ROOT}" ]]; then
    echo "Unable to determine archive root in ${APP_ARCHIVE}" >&2
    exit 1
fi

tar -xf "${APP_ARCHIVE}" -C "${TEMP_DIR}"

if [[ ! -d "${TEMP_DIR}/${PAYLOAD_ROOT}" ]]; then
    echo "Archive payload ${PAYLOAD_ROOT} was not extracted" >&2
    exit 1
fi

rm -rf "${RELEASE_DIR}"
mv "${TEMP_DIR}/${PAYLOAD_ROOT}" "${RELEASE_DIR}"
chown -R "${APP_NAME}:${APP_NAME}" "${RELEASE_DIR}"
chmod -R u=rwX,g=rX,o= "${RELEASE_DIR}"

if [[ -L "${CURRENT_LINK}" ]] || [[ -e "${CURRENT_LINK}" ]]; then
    PREVIOUS_TARGET="$(readlink -f "${CURRENT_LINK}" || true)"
fi

ln -sfn "${RELEASE_DIR}" "${APP_ROOT}/current.next"
mv -Tf "${APP_ROOT}/current.next" "${CURRENT_LINK}"

if ! systemctl restart "${SERVICE_NAME}" || ! wait_for_service; then
    echo "Deployment failed for ${SERVICE_NAME}; attempting rollback." >&2
    rollback "${PREVIOUS_TARGET}"
    systemctl status "${SERVICE_NAME}" --no-pager -l || true
    journalctl -u "${SERVICE_NAME}" -n 80 --no-pager || true
    exit 1
fi

prune_old_releases "${KEEP_RELEASES}"

echo "Deployment succeeded: ${SERVICE_NAME} -> ${RELEASE_DIR}"
