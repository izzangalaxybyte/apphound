#!/usr/bin/env bash
# One-time: create the scoped `apphound-deployer` IAM user and the `apphound` CLI profile.
# Run with admin credentials (the default profile). Re-running updates the policies; it only
# creates a new access key if the profile doesn't have one yet.
#
# - apphound-deployer may only create/change apphound-* resources in eu-central-1.
# - Any role it creates must carry the apphound-boundary permissions boundary, which caps
#   the Lambda role to apphound's own resources (no privilege escalation via roles).
set -euo pipefail
cd "$(dirname "$0")"

ADMIN_PROFILE="${ADMIN_PROFILE:-default}"
PROFILE=apphound
USER=apphound-deployer
REGION=eu-central-1
aws_admin() { aws --profile "$ADMIN_PROFILE" "$@"; }

ACCOUNT_ID=$(aws_admin sts get-caller-identity --query Account --output text)
render() { sed "s/\${ACCOUNT_ID}/$ACCOUNT_ID/g" "$1"; }

upsert_policy() {  # name file
  local arn="arn:aws:iam::$ACCOUNT_ID:policy/$1"
  if aws_admin iam get-policy --policy-arn "$arn" >/dev/null 2>&1; then
    # Keep at most 5 versions: drop the oldest non-default one before adding a new version.
    local old
    old=$(aws_admin iam list-policy-versions --policy-arn "$arn" \
      --query 'reverse(sort_by(Versions[?!IsDefaultVersion], &CreateDate))[3:].VersionId' --output text)
    for v in $old; do aws_admin iam delete-policy-version --policy-arn "$arn" --version-id "$v"; done
    aws_admin iam create-policy-version --policy-arn "$arn" --policy-document "$(render "$2")" --set-as-default >/dev/null
    echo "updated policy $1"
  else
    aws_admin iam create-policy --policy-name "$1" --policy-document "$(render "$2")" \
      --tags Key=app,Value=apphound >/dev/null
    echo "created policy $1"
  fi
}

upsert_policy apphound-boundary boundary-policy.json
upsert_policy apphound-deployer deployer-policy.json

if ! aws_admin iam get-user --user-name "$USER" >/dev/null 2>&1; then
  aws_admin iam create-user --user-name "$USER" --tags Key=app,Value=apphound >/dev/null
  echo "created user $USER"
fi
aws_admin iam attach-user-policy --user-name "$USER" --policy-arn "arn:aws:iam::$ACCOUNT_ID:policy/apphound-deployer"

if aws configure get aws_access_key_id --profile "$PROFILE" >/dev/null 2>&1; then
  echo "profile $PROFILE already has a key; not creating another"
else
  # The secret goes straight into ~/.aws/credentials and is never printed.
  read -r KEY_ID SECRET < <(aws_admin iam create-access-key --user-name "$USER" \
    --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text)
  aws configure set aws_access_key_id "$KEY_ID" --profile "$PROFILE"
  aws configure set aws_secret_access_key "$SECRET" --profile "$PROFILE"
  unset SECRET
  echo "created access key for $USER and stored it in profile $PROFILE"
fi
aws configure set region "$REGION" --profile "$PROFILE"
echo "done: use AWS_PROFILE=$PROFILE (samconfig.toml and scripts/ already do)"
