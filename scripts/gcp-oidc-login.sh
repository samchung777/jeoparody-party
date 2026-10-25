#!/usr/bin/env bash
# Signs gcloud in as a service account using this job's CircleCI OIDC token (Workload Identity Federation).
# gcloud re-reads the token file and refreshes Google tokens itself, so this suits longer jobs like deploys.
# Same trust rules as scripts/gcp-oidc-token.sh.
#
# Usage: scripts/gcp-oidc-login.sh SERVICE_ACCOUNT_EMAIL
set -euo pipefail

sa="${1:?usage: scripts/gcp-oidc-login.sh SERVICE_ACCOUNT_EMAIL}"
: "${CIRCLE_OIDC_TOKEN:?no CircleCI OIDC token in this job}"
: "${GCP_PROJECT_NUMBER:?set GCP_PROJECT_NUMBER}"
: "${GCP_PROJECT_ID:?set GCP_PROJECT_ID}"

token_file=/tmp/circleci-oidc-token
cred_file=/tmp/gcp-wif-credentials.json
printf '%s' "$CIRCLE_OIDC_TOKEN" > "$token_file"

gcloud iam workload-identity-pools create-cred-config \
  "projects/${GCP_PROJECT_NUMBER}/locations/global/workloadIdentityPools/circleci/providers/circleci-oidc" \
  --service-account="$sa" \
  --credential-source-file="$token_file" \
  --output-file="$cred_file"
gcloud auth login --cred-file="$cred_file" --quiet
gcloud config set project "$GCP_PROJECT_ID" --quiet
echo "[gcp-oidc-login] gcloud is signed in as $sa"
