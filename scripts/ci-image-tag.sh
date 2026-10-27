#!/usr/bin/env bash
# Prints the tag for the custom CI image: a fingerprint of everything that goes into it.
# Same inputs -> same tag, so the pipeline can skip rebuilding when the image already exists.
#
# Usage: scripts/ci-image-tag.sh            -> e.g. deps-3f9c1a2b7d4e
# Used by: scripts/ci-plan.sh, which passes the tag to the pipeline as the ci-image-tag parameter.
# Why: the CI image only changes when its inputs change, so the ci-image job can skip most runs.
set -euo pipefail
cd "$(dirname "$0")/.."

inputs=(ci/Dockerfile pom.xml)
hash=$(cat "${inputs[@]}" | sha256sum | cut -c1-12)
echo "deps-${hash}"
