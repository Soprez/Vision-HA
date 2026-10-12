#!/bin/bash
# Toute la suite de Vision en un seul pytest, dans l'image du banc (essai/banc/Dockerfile).
#   essai/banc/lancer.sh                      toute la suite
#   essai/banc/lancer.sh quorum_test.py -x    arguments passés à pytest
# Fonctionne depuis Git Bash (Windows) comme sous Linux. Lire la DERNIÈRE ligne en entier : « 1 failed, 8 passed »
# est un échec.
set -e
IMAGE="${VISION_BANC:-vision-banc:0.13.367-2}"
RACINE="$(cd "$(dirname "$0")/../.." && pwd -W 2>/dev/null || cd "$(dirname "$0")/../.." && pwd)"
MSYS_NO_PATHCONV=1 exec docker run --rm -v "$RACINE:/src" -w /src/essai "$IMAGE" \
  python -m pytest -q -p no:cacheprovider -o asyncio_mode=auto "$@"
