# Sourced by every script here. Reads what is particular to this machine and its owner from local.env,
# a file beside the README that is never committed: where the toolchain is, and where updates are published.
# See local.env.example.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$ROOT/local.env" ] && . "$ROOT/local.env"
[ -n "${JAVA_HOME:-}" ] && export JAVA_HOME && export PATH="$JAVA_HOME/bin:$PATH"
[ -n "${ANDROID_HOME:-}" ] && export ANDROID_HOME && export ANDROID_SDK_ROOT="$ANDROID_HOME"
# Gradle: the one named in local.env, else the wrapper that comes with the sources.
GRADLE="${GRADLE:-$ROOT/gradlew}"
# Where the phone app looks for updates; empty means it does not look.
export REDUCTION_UPDATE_MANIFEST="${REDUCTION_UPDATE_MANIFEST:-}"
