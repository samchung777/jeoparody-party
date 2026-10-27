#!/usr/bin/env bash
# Trades this job's CircleCI OIDC token for a 15-minute Google access token for one service account,
# using Workload Identity Federation. No Google key or password is stored anywhere.
#
#   1. Google STS checks the CircleCI token against the circleci-oidc provider's rules
#      (infra/wif-circleci-provider.yaml: this project only, no SSH reruns, and which jobs count as "deploy")
#      and returns a federated token.
#   2. IAM Credentials turns that into an access token for the service account, which only works if the
#      service account's workloadIdentityUser binding admits this job (infra/README.md section 4).
#
# Prints the token on stdout (pipe it, don't echo it), e.g.:
#   scripts/gcp-oidc-token.sh circleci-ci-image@jeoparody-party.iam.gserviceaccount.com \
#     | docker login -u oauth2accesstoken --password-stdin https://us-west1-docker.pkg.dev
# Used by: ci-image (as circleci-ci-image) and publish (as circleci-deploy), piped into docker login.
# Needs only curl and jq, so it works in the small cimg/base image.
set -euo pipefail

sa="${1:?usage: scripts/gcp-oidc-token.sh SERVICE_ACCOUNT_EMAIL}"
: "${CIRCLE_OIDC_TOKEN:?no CircleCI OIDC token in this job}"
: "${GCP_PROJECT_NUMBER:?set GCP_PROJECT_NUMBER}"
provider="//iam.googleapis.com/projects/${GCP_PROJECT_NUMBER}/locations/global/workloadIdentityPools/circleci/providers/circleci-oidc"

federated=$(jq -n --arg aud "$provider" --arg tok "$CIRCLE_OIDC_TOKEN" '{
    audience: $aud,
    grantType: "urn:ietf:params:oauth:grant-type:token-exchange",
    requestedTokenType: "urn:ietf:params:oauth:token-type:access_token",
    subjectTokenType: "urn:ietf:params:oauth:token-type:jwt",
    subjectToken: $tok,
    scope: "https://www.googleapis.com/auth/cloud-platform"
  }' | curl -sS --fail-with-body https://sts.googleapis.com/v1/token \
         -H 'Content-Type: application/json' --data @- | jq -r .access_token)

curl -sS --fail-with-body -X POST \
  "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${sa}:generateAccessToken" \
  -H "Authorization: Bearer ${federated}" -H 'Content-Type: application/json' \
  --data '{"scope": ["https://www.googleapis.com/auth/cloud-platform"], "lifetime": "900s"}' \
  | jq -r .accessToken
