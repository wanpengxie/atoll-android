#!/usr/bin/env bash
# check-scope.sh BASE PATTERN... — fail if the branch changed a file outside
# the task's allowed patterns (shell globs, matched against repo paths).
# Example: scripts/check-scope.sh main 'app/src/main/java/dev/atoll/app/net/*' 'app/src/test/java/dev/atoll/app/net/*'
set -euo pipefail
base=$1; shift
bad=0
while IFS= read -r file; do
  ok=0
  for pattern in "$@"; do
    # shellcheck disable=SC2053
    [[ $file == $pattern ]] && { ok=1; break; }
  done
  if [ $ok = 0 ]; then echo "out of scope: $file"; bad=1; fi
done < <(git diff --name-only "$base"...HEAD)
[ $bad = 0 ] && echo "scope ok"
exit $bad
