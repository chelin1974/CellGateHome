#!/usr/bin/env bash
#
# Creates (or updates) the Kubernetes Secret this bridge reads its configuration from,
# using the cellgate.config.json you already have for running it locally.
#
#   ./deploy/k8s/create-secret.sh
#   ./deploy/k8s/create-secret.sh --config ~/cellgate.config.json --dry-run
#   ./deploy/k8s/create-secret.sh --bind-address 192.168.7.10
#
# Unlike the Daikin bridge, this one reads a config file rather than environment
# variables, so the Secret holds the whole file and the pod mounts it at
# /config/cellgate.config.json. Two values have to differ inside the cluster, and the
# script rewrites them rather than leaving you to remember:
#
#   storagePath  -> /data/cellgate-home.state.json, the mounted volume. Left pointing at a
#                   path on your laptop, HomeKit pairing state would be written to the
#                   container's ephemeral filesystem and lost on every restart, re-pairing
#                   every controller.
#   bindAddress  -> the node's LAN IP, if you pass --bind-address. The pod is pinned to one
#                   node, so a fixed value is safe and avoids a bad interface guess.
#
# Idempotent: run it again after editing the config and it updates the Secret in place.
# The bridge reads it once at startup, so follow an update with:
#
#   kubectl -n cellgate-home rollout restart deploy/cellgate-home
#
# The rewritten config is handed to kubectl in a mode-600 temporary file, removed on exit.
set -euo pipefail

CONFIG="cellgate.config.json"
NAMESPACE="cellgate-home"
NAME="cellgate-home-config"
BIND_ADDRESS=""
DRY_RUN=false

usage() {
  cat <<'USAGE'
usage: create-secret.sh [options]

  --config <path>         configuration file to read (default: cellgate.config.json)
  --namespace <name>      namespace to create the Secret in (default: cellgate-home)
  --name <name>           Secret name (default: cellgate-home-config)
  --bind-address <ip>     LAN IP to advertise HomeKit on; set this if the Home app cannot
                          find the bridge
  --dry-run               show what would be set, send nothing, and exit
  --help                  show this message
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)       CONFIG="${2:?--config needs a path}"; shift 2 ;;
    --namespace)    NAMESPACE="${2:?--namespace needs a name}"; shift 2 ;;
    --name)         NAME="${2:?--name needs a name}"; shift 2 ;;
    --bind-address) BIND_ADDRESS="${2:?--bind-address needs an IP}"; shift 2 ;;
    --dry-run)      DRY_RUN=true; shift ;;
    --help|-h)      usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v python3 >/dev/null 2>&1 || {
  echo "error: python3 is required to read $CONFIG" >&2
  exit 1
}
$DRY_RUN || command -v kubectl >/dev/null 2>&1 || {
  echo "error: kubectl is not on PATH" >&2
  exit 1
}
[[ -r "$CONFIG" ]] || {
  echo "error: cannot read $CONFIG" >&2
  echo "Copy cellgate.config.k8s.example.json and fill in your CellGate login, or pass --config." >&2
  exit 1
}

OUT_FILE="$(mktemp "${TMPDIR:-/tmp}/cellgate-config.XXXXXX")"
chmod 600 "$OUT_FILE"
trap 'rm -f "$OUT_FILE"' EXIT INT TERM

python3 - "$CONFIG" "$OUT_FILE" "$BIND_ADDRESS" <<'PYTHON'
import json, sys

config_path, out_path, bind_address = sys.argv[1], sys.argv[2], sys.argv[3]

try:
    with open(config_path, encoding="utf-8") as handle:
        config = json.load(handle)
except json.JSONDecodeError as error:
    sys.exit(f"error: {config_path} is not valid JSON: {error}")

if not isinstance(config, dict):
    sys.exit(f"error: {config_path} should contain a JSON object")

# Keys beginning with an underscore are the example file's own commentary; the bridge
# ignores them, and there is no reason to carry them into the cluster.
config = {key: value for key, value in config.items() if not key.startswith("_")}

for required in ("email", "password"):
    value = config.get(required)
    if not isinstance(value, str) or not value.strip():
        sys.exit(f"error: {required} is required in {config_path}")

STATE_PATH = "/data/cellgate-home.state.json"
notes = []

if config.get("storagePath") != STATE_PATH:
    was = config.get("storagePath")
    config["storagePath"] = STATE_PATH
    notes.append(f"storagePath -> {STATE_PATH}" + (f" (was {was})" if was else " (was unset)"))

if bind_address:
    config["bindAddress"] = bind_address
    notes.append(f"bindAddress -> {bind_address}")
elif not str(config.get("bindAddress") or "").strip():
    notes.append("bindAddress left unset: the bridge picks an interface. Pass "
                 "--bind-address <node-ip> if the Home app cannot find it.")

with open(out_path, "w", encoding="utf-8") as handle:
    json.dump(config, handle, indent=2)
    handle.write("\n")

redacted = {"password", "dashboardPasscode"}
print(f"settings read from {config_path}:")
for key in config:
    print(f"  {key}" + ("  (secret)" if key in redacted else ""))
if notes:
    print("rewritten for the cluster:")
    for note in notes:
        print(f"  {note}")
PYTHON

if $DRY_RUN; then
  echo
  echo "--dry-run: nothing was sent to the cluster."
  exit 0
fi

echo
# --dry-run=client plus apply, rather than a bare create, so re-running updates the Secret
# instead of failing with AlreadyExists. The YAML goes through a pipe, never the terminal.
kubectl create secret generic "$NAME" \
  --namespace "$NAMESPACE" \
  --from-file=cellgate.config.json="$OUT_FILE" \
  --dry-run=client -o yaml \
  | kubectl apply -f -

echo
echo "The bridge reads this once at startup. If it is already running:"
echo "  kubectl -n $NAMESPACE rollout restart deploy/cellgate-home"
