#!/usr/bin/env bash
# Deploy the backend with the scoped `apphound` profile and record what the store app and
# publish script need in the local, git-ignored .env.
#
# First run also creates the API token (SSM SecureString /apphound/api-token). The token is
# written to .env and never printed.
set -euo pipefail
cd "$(dirname "$0")/.."

export AWS_PROFILE="${AWS_PROFILE:-apphound}"
REGION=eu-central-1
STACK=apphound
TOKEN_PARAM=/apphound/api-token
ENV_FILE=.env

touch "$ENV_FILE"
set_env() {  # KEY VALUE: replace or append KEY=VALUE in .env
  local tmp
  tmp=$(mktemp)
  grep -v "^$1=" "$ENV_FILE" > "$tmp" || true
  printf '%s=%s\n' "$1" "$2" >> "$tmp"
  mv "$tmp" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
}

if ! TOKEN=$(aws ssm get-parameter --region "$REGION" --name "$TOKEN_PARAM" --with-decryption \
    --query Parameter.Value --output text 2>/dev/null); then
  TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
  aws ssm put-parameter --region "$REGION" --name "$TOKEN_PARAM" --type SecureString \
    --value "$TOKEN" --tags Key=app,Value=apphound >/dev/null
  echo "created API token in SSM $TOKEN_PARAM"
fi

sam build
sam deploy --no-fail-on-empty-changeset

output() {
  aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

set_env AWS_PROFILE "$AWS_PROFILE"
set_env AWS_REGION "$REGION"
set_env APPHOUND_BUCKET "$(output BucketName)"
set_env APPHOUND_API_URL "$(output ApiUrl)"
set_env APPHOUND_API_TOKEN "$TOKEN"
unset TOKEN
echo "wrote $ENV_FILE (bucket, API URL, token)"
