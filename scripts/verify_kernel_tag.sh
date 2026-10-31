#!/usr/bin/env bash
# Verify the published kernel tag resolves to the exact checked-out pinned commit.
set -euo pipefail
props=${1:?version properties path required}
kernel=${2:?kernel checkout path required}
read_prop() {
  local key="$1" count
  count=$(grep -c "^${key}=" "$props" || true)
  test "$count" = 1 || { echo "Expected exactly one $key in $props" >&2; exit 1; }
  grep "^${key}=" "$props" | cut -d= -f2- | tr -d '\r'
}
tag=$(read_prop KERNEL_TAG)
commit=$(read_prop KERNEL_COMMIT)
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-chain\.[0-9]+$ ]] || { echo 'Invalid kernel tag' >&2; exit 1; }
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid kernel commit' >&2; exit 1; }
test "$(git -C "$kernel" rev-parse HEAD)" = "$commit" || { echo 'Kernel checkout does not match pin' >&2; exit 1; }
# No force: an existing local tag pointing elsewhere must not be silently replaced.
git -C "$kernel" fetch --no-tags origin "refs/tags/$tag:refs/tags/$tag"
resolved=$(git -C "$kernel" rev-parse "refs/tags/$tag^{commit}")
test "$resolved" = "$commit" || { echo "Remote kernel tag $tag does not match pinned commit" >&2; exit 1; }
echo "Verified kernel $tag at $commit"
