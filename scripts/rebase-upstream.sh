#!/usr/bin/env bash
# mtrix fork: rebase the mtrix patch set onto the latest upstream Element X release and build it.
#
#   scripts/rebase-upstream.sh            # rebase onto the latest vX.Y.Z tag, test, build, copy APK to dist/
#   scripts/rebase-upstream.sh --no-build # rebase only
#   scripts/rebase-upstream.sh --push     # also force-push (with lease) the rebased branch to origin
#   scripts/rebase-upstream.sh --tag v26.10.0   # rebase onto a specific tag
#
# Exit codes: 0 ok (or already up to date), 2 rebase conflict (rebase aborted, branch untouched), 1 other errors.
# See README.mtrix.md.
set -euo pipefail

BRANCH="${MTRIX_BRANCH:-mtrix}"
UPSTREAM="${MTRIX_UPSTREAM_REMOTE:-upstream}"
UPSTREAM_URL="https://github.com/element-hq/element-x-android.git"
BUILD=1
PUSH=0
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --push) PUSH=1 ;;
    --tag) TAG="$2"; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
  shift
done

cd "$(git rev-parse --show-toplevel)"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "working tree has uncommitted changes; commit or stash them first" >&2
  exit 1
fi

git remote get-url "$UPSTREAM" >/dev/null 2>&1 || git remote add "$UPSTREAM" "$UPSTREAM_URL"
echo ">> fetching $UPSTREAM tags"
git fetch --quiet --tags "$UPSTREAM"

# Latest stable release tag: vYY.MM.N (no -rc etc.).
if [ -z "$TAG" ]; then
  TAG="$(git tag -l 'v[0-9]*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n1)"
fi
[ -n "$TAG" ] || { echo "no release tag found" >&2; exit 1; }

git checkout --quiet "$BRANCH"
# The release tag the branch currently sits on.
BASE="$(git describe --tags --abbrev=0 --match 'v[0-9]*' "$BRANCH")"
echo ">> $BRANCH is based on $BASE; latest release is $TAG"

if [ "$BASE" = "$TAG" ]; then
  echo ">> already up to date"
else
  if ! git merge-base --is-ancestor "$BASE" "$TAG"; then
    echo ">> note: $TAG does not contain $BASE (hotfix branch?); rebasing the patches anyway"
  fi
  BACKUP="mtrix-backup/$BASE-$(date +%Y%m%d%H%M%S)"
  git branch "$BACKUP" "$BRANCH"
  echo ">> backup of the old branch: $BACKUP"
  if ! git rebase --onto "$TAG" "$BASE" "$BRANCH"; then
    echo ">> CONFLICT rebasing $BRANCH onto $TAG. Conflicted files:" >&2
    git diff --name-only --diff-filter=U >&2 || true
    git rebase --abort
    echo ">> rebase aborted; $BRANCH is unchanged. Resolve manually with:" >&2
    echo "     git rebase --onto $TAG $BASE $BRANCH" >&2
    exit 2
  fi
  echo ">> rebased $(git rev-list --count "$TAG..$BRANCH") mtrix commits onto $TAG"
fi

if [ "$BUILD" = 1 ]; then
  VERSION="${TAG#v}"
  echo ">> testing and building $VERSION"
  nix develop -c ./gradlew --console=plain \
    :features:mtrix:testDebugUnitTest \
    :app:assembleFdroidRelease
  mkdir -p dist
  OUT=app/build/outputs/apk/fdroid/release
  cp "$OUT/app-fdroid-arm64-v8a-release.apk" "dist/element-x-mtrix-$VERSION.apk"
  cp "$OUT/app-fdroid-universal-release.apk" "dist/element-x-mtrix-$VERSION-universal.apk"
  echo ">> dist/element-x-mtrix-$VERSION.apk (arm64-v8a) and dist/element-x-mtrix-$VERSION-universal.apk"
  APKSIGNER="$(nix develop -c bash -c 'echo $ANDROID_HOME')/build-tools/37.0.0/apksigner"
  nix develop -c "$APKSIGNER" verify --print-certs "dist/element-x-mtrix-$VERSION.apk" | head -n 3 || true
fi

if [ "$PUSH" = 1 ]; then
  git push --force-with-lease origin "$BRANCH"
fi
