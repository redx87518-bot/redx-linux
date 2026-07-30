#!/usr/bin/env bash
##############################################################################
# Self-bootstrapping Gradle wrapper (no gradle-wrapper.jar required).
# Downloads Gradle 8.7 on first run and caches it in ~/.gradle/wrapper.
##############################################################################
set -euo pipefail

GRADLE_VERSION=8.7
GRADLE_DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_HOME="${HOME}/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_DIR="${GRADLE_HOME}/gradle-${GRADLE_VERSION}"
GRADLE_ZIP="${GRADLE_HOME}/gradle-${GRADLE_VERSION}-bin.zip"

if [ ! -d "${GRADLE_DIR}" ]; then
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  mkdir -p "${GRADLE_HOME}"
  if command -v curl &>/dev/null; then
    curl -fsSL "${GRADLE_DIST_URL}" -o "${GRADLE_ZIP}"
  else
    wget -q "${GRADLE_DIST_URL}" -O "${GRADLE_ZIP}"
  fi
  unzip -q "${GRADLE_ZIP}" -d "${GRADLE_HOME}"
  rm -f "${GRADLE_ZIP}"
fi

exec "${GRADLE_DIR}/bin/gradle" "$@"
