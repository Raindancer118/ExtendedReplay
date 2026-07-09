#!/usr/bin/env bash
# Deploys the freshly built plugin jar to the two ExtendedReplay test servers on
# mango/VM121 (Pelican volumes) and prints what to do next. Requires SSH access
# via the "mango" host alias (ProxyJump into 10.0.0.121).
#
# Usage: scripts/deploy-testservers.sh [--build]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO_ROOT/extendedreplay-paper/build/libs/ExtendedReplay-0.1.0.jar"
VM="root@10.0.0.121"
PRODUCER_VOL="/var/lib/pelican/volumes/b0be2359-350d-4a06-9ce0-6685e6d66493"
REPLAY_VOL="/var/lib/pelican/volumes/b96d595d-6520-4eff-b134-e90060b3442e"

if [[ "${1:-}" == "--build" ]]; then
    (cd "$REPO_ROOT" && ./gradlew :extendedreplay-paper:build)
fi

[[ -f "$JAR" ]] || { echo "Jar fehlt: $JAR (erst bauen: --build)"; exit 1; }

scp -o ProxyJump=mango "$JAR" "$VM:/tmp/ExtendedReplay-0.1.0.jar"
ssh -J mango "$VM" bash -s <<EOF
set -e
for D in "$PRODUCER_VOL" "$REPLAY_VOL"; do
    cp /tmp/ExtendedReplay-0.1.0.jar "\$D/plugins/ExtendedReplay-0.1.0.jar"
    chown 988:988 "\$D/plugins/ExtendedReplay-0.1.0.jar"
done
rm /tmp/ExtendedReplay-0.1.0.jar
echo "deployed to producer + replay volumes"
EOF

echo "Fertig. Jetzt beide Server im Pelican-Panel neu starten:"
echo "  https://pelican.nak-inf.de/server/b0be2359 (ERP-Producer)"
echo "  https://pelican.nak-inf.de/server/b96d595d (ERP-Replay)"
