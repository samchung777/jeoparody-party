#!/usr/bin/env bash
# Setup-workflow step (.circleci/config.yml): decides what this pipeline needs to do, then continues it
# with .circleci/continue_config.yml and these pipeline parameters:
#
#   app-changed   true unless every changed file is documentation (path filtering)
#   ci-image-tag  fingerprint of the custom CI image's inputs (scripts/ci-image-tag.sh)
#
# What counts as "changed":
#   - on main:           files changed by this commit vs. its first parent. With branch protection every
#                        change reaches main as a PR merge, so that is the whole PR.
#   - on other branches: files changed since the branch left main (the whole PR), so a docs-only
#                        follow-up commit can't skip the build of code that hasn't passed yet
# (the same rules as the circleci/path-filtering orb)
#
# Env: CIRCLE_BRANCH, CIRCLE_CONTINUATION_KEY (set by CircleCI), FORCE_BUILD=true to always build. Without CIRCLE_CONTINUATION_KEY it only prints the plan (dry run).
set -euo pipefail
cd "$(dirname "$0")/.."

DEFAULT_BRANCH=main
EMPTY_TREE=4b825dc642cb6eb9a060e54bf8d69288fbee4904   # git's id for "no files", for a first commit
# Changes to these paths alone don't need a build or deploy (Perl regex, matched per file)
DOCS_ONLY='(\.md$|^docs/|^infra/|^LICENSE$|^\.gitignore$)'

out=/tmp/plan
mkdir -p "$out"
log() { printf '[plan] %s\n' "$*"; }

branch="${CIRCLE_BRANCH:-$(git rev-parse --abbrev-ref HEAD)}"
git fetch --quiet origin "$DEFAULT_BRANCH" 2>/dev/null || true

is_commit() { [[ -n "$1" ]] && git cat-file -e "$1^{commit}" 2>/dev/null; }

if [[ "$branch" == "$DEFAULT_BRANCH" ]]; then
  from=$(git rev-parse --quiet --verify HEAD~1 || echo "$EMPTY_TREE"); why="parent commit"
else
  if is_commit "origin/$DEFAULT_BRANCH"; then
    from=$(git merge-base "origin/$DEFAULT_BRANCH" HEAD); why="where $branch left $DEFAULT_BRANCH"
  else
    from="$EMPTY_TREE"; why="no $DEFAULT_BRANCH to compare with"
  fi
fi

git diff --name-only "$from" HEAD > "$out/changed-files.txt"
log "branch $branch: comparing with ${from:0:12} ($why)"
log "$(wc -l < "$out/changed-files.txt" | tr -d ' ') file(s) changed:"
sed 's/^/[plan]   /' "$out/changed-files.txt"

if [[ "${FORCE_BUILD:-false}" == "true" ]]; then
  app_changed=true; log "force-build requested"
elif [[ ! -s "$out/changed-files.txt" ]]; then
  app_changed=true; log "no changes detected (e.g. a rerun): building to be safe"
elif grep -qvP "$DOCS_ONLY" "$out/changed-files.txt"; then
  app_changed=true; log "code/config changed: full build"
else
  app_changed=false; log "documentation only: skipping build and deploy"
fi

ci_image_tag=$(bash scripts/ci-image-tag.sh)
params=$(jq -n --argjson app "$app_changed" --arg tag "$ci_image_tag" '{"app-changed": $app, "ci-image-tag": $tag}')
echo "$params" > "$out/parameters.json"
log "parameters: $(jq -c . <<< "$params")"

if [[ -z "${CIRCLE_CONTINUATION_KEY:-}" ]]; then
  log "dry run (no CIRCLE_CONTINUATION_KEY): not continuing"
  exit 0
fi

# Continue the pipeline: the same API call the circleci/continuation orb makes
jq -n --arg key "$CIRCLE_CONTINUATION_KEY" --rawfile config .circleci/continue_config.yml --argjson params "$params" \
  '{"continuation-key": $key, "configuration": $config, "parameters": $params}' > /tmp/continue-body.json
curl -sS --fail-with-body -X POST "https://circleci.com/api/v2/pipeline/continue" \
  -H 'Content-Type: application/json' --data @/tmp/continue-body.json
echo
log "pipeline continued"
