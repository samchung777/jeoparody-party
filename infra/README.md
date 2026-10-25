# Google Cloud setup

Everything the pipeline deploys to, created once with `gcloud` (PowerShell syntax). Region: `us-west1`.

| Resource | Name | Purpose |
|---|---|---|
| Artifact Registry | `jeoparody` (private) | App images. Only the deploy job (OIDC, main branch) can push. |
| Artifact Registry | `ci` (public read) | Custom CI image. Public because CircleCI pulls a job's image before any step can get an OIDC token; it holds only a toolchain and public libraries. |
| Cloud SQL (MySQL 8.4) | `jeoparody-db` / database `jeopardy` | Trivia, keywords, game results. App connects with the Cloud SQL socket factory (IAM). |
| Secret Manager | `db-password`, `admin-key` | Injected into Cloud Run as env vars. Never stored in CircleCI or the repo. |
| Service account | `jeoparody-run` | Cloud Run runtime: `cloudsql.client`, `aiplatform.user` (Gemini), accessor on the two secrets. |
| Service account | `circleci-ci-image` | Push to the `ci` repo only. Any branch of this project. |
| Service account | `circleci-deploy` | Push to `jeoparody`, `run.developer` (deploy revisions, not change access), act as `jeoparody-run`. `main` + `gcp-deploy` context only. |
| Workload Identity pool | `circleci` / provider `circleci-oidc` | Trusts CircleCI OIDC tokens; rules in [`wif-circleci-provider.yaml`](wif-circleci-provider.yaml). |

## 1. Foundations
```powershell
gcloud config set project jeoparody-party
gcloud config set run/region us-west1
gcloud services enable run.googleapis.com artifactregistry.googleapis.com sqladmin.googleapis.com secretmanager.googleapis.com iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com aiplatform.googleapis.com
gcloud artifacts repositories create jeoparody --repository-format=docker --location=us-west1
gcloud artifacts repositories create ci --repository-format=docker --location=us-west1
gcloud artifacts repositories add-iam-policy-binding ci --location=us-west1 --member=allUsers --role=roles/artifactregistry.reader
gcloud sql instances create jeoparody-db --database-version=MYSQL_8_4 --edition=ENTERPRISE --tier=db-f1-micro --region=us-west1 --storage-type=HDD --storage-size=10
```

## 2. Database and secrets
```powershell
gcloud sql databases create jeopardy --instance=jeoparody-db
gcloud sql users create jeopardy --instance=jeoparody-db --host=% --password=<generated>
gcloud secrets create db-password --replication-policy=automatic --data-file=<file with the password, no newline>
gcloud secrets create admin-key   --replication-policy=automatic --data-file=<file with the key, no newline>
```

## 3. Service accounts (least privilege)
```powershell
$P = "jeoparody-party"
gcloud iam service-accounts create jeoparody-run
gcloud iam service-accounts create circleci-ci-image
gcloud iam service-accounts create circleci-deploy
$RUN = "jeoparody-run@$P.iam.gserviceaccount.com"; $CIIMG = "circleci-ci-image@$P.iam.gserviceaccount.com"; $DEPLOY = "circleci-deploy@$P.iam.gserviceaccount.com"
gcloud projects add-iam-policy-binding $P --member="serviceAccount:$RUN" --role=roles/cloudsql.client --condition=None
gcloud projects add-iam-policy-binding $P --member="serviceAccount:$RUN" --role=roles/aiplatform.user --condition=None
gcloud secrets add-iam-policy-binding db-password --member="serviceAccount:$RUN" --role=roles/secretmanager.secretAccessor
gcloud secrets add-iam-policy-binding admin-key   --member="serviceAccount:$RUN" --role=roles/secretmanager.secretAccessor
gcloud artifacts repositories add-iam-policy-binding ci --location=us-west1 --member="serviceAccount:$CIIMG" --role=roles/artifactregistry.writer
gcloud artifacts repositories add-iam-policy-binding jeoparody --location=us-west1 --member="serviceAccount:$DEPLOY" --role=roles/artifactregistry.writer
gcloud projects add-iam-policy-binding $P --member="serviceAccount:$DEPLOY" --role=roles/run.developer --condition=None
gcloud iam service-accounts add-iam-policy-binding $RUN --member="serviceAccount:$DEPLOY" --role=roles/iam.serviceAccountUser
```

## 4. OIDC trust (Workload Identity Federation)
CircleCI context `gcp-deploy` (ID `9f90f75f-ab47-4a66-94e8-c96cf9901c13`) is restricted to this project and to
`pipeline.git.branch == "main"`. Google checks the branch and the context again (defense in depth).
```powershell
$POOL = "projects/20016506594/locations/global/workloadIdentityPools/circleci"
gcloud iam workload-identity-pools create circleci --location=global --display-name="CircleCI"
gcloud iam workload-identity-pools providers create-oidc circleci-oidc --flags-file=infra/wif-circleci-provider.yaml
gcloud iam service-accounts add-iam-policy-binding $CIIMG  --role=roles/iam.workloadIdentityUser --member="principalSet://iam.googleapis.com/$POOL/attribute.project/ccb8edc1-2f1e-4e34-988b-53f915e4b7a0"
gcloud iam service-accounts add-iam-policy-binding $DEPLOY --role=roles/iam.workloadIdentityUser --member="principalSet://iam.googleapis.com/$POOL/attribute.deploy/main"
```
