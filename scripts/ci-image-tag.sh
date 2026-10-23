#!/usr/bin/env bash
# Prints the tag for the custom CI image: a fingerprint of everything that goes into it.
# Same inputs -> same tag, so the pipeline can skip rebuilding when the image already exists.
#
# Usage: scripts/ci-image-tag.sh            -> e.g. deps-3f9c1a2b7d4e
set -euo pipefail
cd "$(dirname "$0")/.."

inputs=(ci/Dockerfile pom.xml)
hash=$(cat "${inputs[@]}" | sha256sum | cut -c1-12)
echo "deps-${hash}"
