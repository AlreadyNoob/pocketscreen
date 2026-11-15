#!/usr/bin/env bash
# Works out the next version (major.minor.patch) from the commit messages since the last release tag,
# and writes the release notes. Prints: VERSION=<x.y.z> and writes notes.md in the current directory.
#
#   feat: ...            -> minor
#   fix: ... (or anything else) -> patch
#   feat!: ... / BREAKING CHANGE in the message -> major
#
# The first release (no v1+ tag yet) uses appVersion from gradle.properties.
set -euo pipefail

LAST="$(git tag -l 'v[1-9]*.*.*' --sort=-v:refname | head -1 || true)"

if [ -z "$LAST" ]; then
  VERSION="$(grep '^appVersion=' gradle.properties | cut -d= -f2)"
  printf 'First release under the new versioning. Earlier versions are listed in CHANGELOG.md.\n' > notes.md
else
  SUBJECTS="$(git log "$LAST..HEAD" --no-merges --format=%s)"
  BODIES="$(git log "$LAST..HEAD" --no-merges --format=%B)"
  IFS=. read -r MAJ MIN PAT <<< "${LAST#v}"
  if echo "$BODIES" | grep -q 'BREAKING CHANGE' || echo "$SUBJECTS" | grep -qE '^[a-z]+(\([^)]*\))?!:'; then
    MAJ=$((MAJ + 1)); MIN=0; PAT=0
  elif echo "$SUBJECTS" | grep -qE '^feat(\([^)]*\))?:'; then
    MIN=$((MIN + 1)); PAT=0
  else
    PAT=$((PAT + 1))
  fi
  VERSION="$MAJ.$MIN.$PAT"

  {
    FEATS="$(echo "$SUBJECTS" | grep -E '^feat(\([^)]*\))?!?:' | sed -E 's/^feat(\([^)]*\))?!?: */- /' || true)"
    FIXES="$(echo "$SUBJECTS" | grep -E '^fix(\([^)]*\))?!?:' | sed -E 's/^fix(\([^)]*\))?!?: */- /' || true)"
    OTHER="$(echo "$SUBJECTS" | grep -vE '^(feat|fix)(\([^)]*\))?!?:' | sed -E 's/^[a-z]+(\([^)]*\))?!?: */- /; t; s/^/- /' || true)"
    [ -n "$FEATS" ] && printf '### New\n%s\n\n' "$FEATS"
    [ -n "$FIXES" ] && printf '### Fixed\n%s\n\n' "$FIXES"
    [ -n "$OTHER" ] && printf '### Other\n%s\n\n' "$OTHER"
    printf 'Changes since %s.\n' "$LAST"
  } > notes.md
fi

echo "VERSION=$VERSION"
