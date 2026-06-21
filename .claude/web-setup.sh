#!/usr/bin/env bash
# Claude Code (web) environment setup — MusicResync (Android/Kotlin, AGP 8.7.1, JDK 17).
# Paste into the Claude Code on the web environment "setup script" field.
# Hard-fails if ANY required tool fails to install (set -euo pipefail + explicit die checks).
# Flaky network steps are retried 4x with exponential backoff (2→4→8→16s).
set -euo pipefail

AGENT_HOME="${AGENT_HOME:-/opt/agent}"
mkdir -p "$AGENT_HOME"
log() { echo "[setup] $*"; }
die() { echo "[setup][ERROR] $*" >&2; exit 1; }

retry() {
  local n=0 max=4 delay=2
  until "$@"; do
    n=$((n+1))
    [ "$n" -ge "$max" ] && die "Failed after $max attempts: $*"
    echo "[setup][WARN] retry $n/$max in ${delay}s: $*" >&2
    sleep "$delay"; delay=$((delay*2))
  done
}

ARCH="$(uname -m)"
[ "$ARCH" = "x86_64" ] || die "Expected x86_64; got $ARCH (binary URLs below are amd64-only)"

# ---- Base packages (rg, jq, fd come from apt) ----------------------------
log "Installing base packages…"
retry apt-get update -qq
retry apt-get install -y --no-install-recommends \
  git curl wget unzip xz-utils ca-certificates \
  python3 python3-pip python3-venv \
  jq ripgrep fd-find
# Debian/Ubuntu ship fd as 'fdfind' — expose it under its real name 'fd'.
ln -sf "$(command -v fdfind)" /usr/local/bin/fd
fd --version >/dev/null   || die "fd not available after install"
rg --version >/dev/null   || die "rg not available after install"
log "[OK] rg $(rg --version | head -1) | fd $(fd --version) | jq $(jq --version)"

# ---- Token-efficiency CLI toolkit (yq, sd, tokei) ------------------------
log "Installing yq…"
retry wget -q "https://github.com/mikefarah/yq/releases/download/v4.53.3/yq_linux_amd64" -O /usr/local/bin/yq
chmod +x /usr/local/bin/yq
yq --version >/dev/null || die "yq not available after install"

log "Installing sd…"
retry wget -q "https://github.com/chmln/sd/releases/download/v1.1.0/sd-v1.1.0-x86_64-unknown-linux-musl.tar.gz" -O /tmp/sd.tar.gz
tar -xzf /tmp/sd.tar.gz -C /tmp
install -m 0755 "$(find /tmp -type f -name sd | head -1)" /usr/local/bin/sd
rm -rf /tmp/sd.tar.gz /tmp/sd-v1.1.0-*
sd --version >/dev/null || die "sd not available after install"

log "Installing tokei…"  # latest tokei dropped prebuilt binaries; v12.1.2 is the last with them
retry wget -q "https://github.com/XAMPPRocky/tokei/releases/download/v12.1.2/tokei-x86_64-unknown-linux-musl.tar.gz" -O /tmp/tokei.tar.gz
tar -xzf /tmp/tokei.tar.gz -C /tmp
install -m 0755 "$(find /tmp -maxdepth 2 -type f -name tokei | head -1)" /usr/local/bin/tokei
rm -f /tmp/tokei.tar.gz
tokei --version >/dev/null || die "tokei not available after install"
log "[OK] yq $(yq --version) | sd $(sd --version) | tokei $(tokei --version)"

# ---- Python 3.12 test toolchain ------------------------------------------
log "Installing Python 3.12 test dependencies…"
python3.12 -m pip install --break-system-packages --quiet pytest pytest-cov \
  || die "pytest install failed"
log "[OK] $(python3.12 -m pytest --version 2>/dev/null | head -1)"

# ---- gh (GitHub CLI) ------------------------------------------------------
log "Installing GitHub CLI…"
GH_VERSION="2.67.0"
retry wget -q "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_amd64.tar.gz" -O /tmp/gh.tar.gz
tar -xzf /tmp/gh.tar.gz -C /tmp
mv "/tmp/gh_${GH_VERSION}_linux_amd64/bin/gh" /usr/local/bin/gh
rm -rf /tmp/gh.tar.gz "/tmp/gh_${GH_VERSION}_linux_amd64"
gh --version >/dev/null || die "gh not available after install"
log "[OK] gh $(gh --version | head -1)"

# ---- Node.js 22 (CodeGraph + ast-grep require it) -------------------------
CURRENT_NODE_MAJOR=0
if command -v node >/dev/null 2>&1; then
  CURRENT_NODE_MAJOR=$(node -v 2>/dev/null | cut -dv -f2 | cut -d. -f1 || echo 0)
fi
if [ "$CURRENT_NODE_MAJOR" -lt 20 ]; then
  log "Installing Node.js 22…"
  retry wget -qO /tmp/nodesource_setup.sh https://deb.nodesource.com/setup_22.x
  bash /tmp/nodesource_setup.sh
  rm -f /tmp/nodesource_setup.sh
  retry apt-get install -y nodejs
fi
node --version >/dev/null || die "Node.js not available after install"
log "[OK] node $(node --version)"

# ---- ast-grep (structural search/rewrite; needs Node) --------------------
log "Installing ast-grep…"
retry npm install -g @ast-grep/cli
ast-grep --version >/dev/null || die "ast-grep not available after install"
log "[OK] $(ast-grep --version)"

# ---- JDK 17 + Android SDK (REQUIRED — MusicResync is Android/Kotlin) ------
log "Installing JDK 17 and Android SDK (compileSdk 35, build-tools 35.0.0)…"
retry apt-get install -y --no-install-recommends openjdk-17-jdk-headless
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
[ -x "$JAVA_HOME/bin/javac" ] || die "JAVA_HOME resolution failed ($JAVA_HOME)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
retry wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O /tmp/ct.zip
unzip -q /tmp/ct.zip -d /tmp/ct
mv /tmp/ct/cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/"
rm -rf /tmp/ct.zip /tmp/ct
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
yes | sdkmanager --licenses >/dev/null 2>&1 || true   # 'yes' exits via SIGPIPE; that's fine
retry sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
[ -x "$ANDROID_HOME/platform-tools/adb" ] || die "Android SDK incomplete (adb missing)"
log "[OK] JDK at $JAVA_HOME | Android SDK at $ANDROID_HOME"

# Persist JAVA_HOME / ANDROID_HOME / PATH for every shell the agent spawns later.
cat >/etc/profile.d/10-agent-env.sh <<EOF
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export PATH="\$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
EOF

# ---- CodeGraph — semantic code index -------------------------------------
log "Installing CodeGraph…"
retry npm install -g @colbymchenry/codegraph
codegraph --version >/dev/null 2>&1 || die "CodeGraph --version check failed"
log "[OK] CodeGraph $(codegraph --version 2>/dev/null)"

# ---- Headroom — log/output compression -----------------------------------
log "Installing Headroom…"
export SETUPTOOLS_USE_DISTUTILS=stdlib
pip3 install --upgrade --ignore-installed pip setuptools wheel --break-system-packages --quiet \
  || die "pip bootstrap failed"
python3 -m venv /opt/headroom-venv
retry /opt/headroom-venv/bin/pip install --quiet "headroom-ai[all]"
/opt/headroom-venv/bin/python -c "import headroom; print('[OK] Headroom', headroom.__version__)" \
  || die "Headroom import check failed"
ln -sf /opt/headroom-venv/bin/headroom /usr/local/bin/headroom

log "Setup complete — all required tools verified."
