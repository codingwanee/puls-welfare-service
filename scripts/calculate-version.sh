#!/usr/bin/env bash
set -euo pipefail

ref="${1:?source ref is required}"
build_id="${2:?build ID is required}"
base="${3:?base version is required}"
pr_id="${4:-0}"
semver='^[0-9]+\.[0-9]+\.[0-9]+$'

if [[ ! "$base" =~ $semver ]]; then
  echo "VERSION must contain MAJOR.MINOR.PATCH: $base" >&2
  exit 1
fi

case "$ref" in
  refs/tags/v*)
    tag_version="${ref#refs/tags/v}"
    if [[ ! "$tag_version" =~ $semver ]]; then
      echo "Invalid release tag: $ref" >&2
      exit 1
    fi
    if [[ "$tag_version" != "$base" ]]; then
      echo "Tag $tag_version does not match VERSION $base" >&2
      exit 1
    fi
    version="$tag_version"
    channel="release"
    ;;

  refs/heads/release/*)
    release_version="${ref#refs/heads/release/}"
    if [[ "$release_version" != "$base" ]]; then
      echo "Release branch $release_version does not match VERSION $base" >&2
      exit 1
    fi
    version="${base}-rc.${build_id}"
    channel="rc"
    ;;

  refs/heads/develop)
    version="${base}-dev.${build_id}"
    channel="develop"
    ;;

  refs/heads/main)
    version="${base}-main.${build_id}"
    channel="main"
    ;;

  refs/pull/*)
    version="${base}-pr.${pr_id}.${build_id}"
    channel="pr"
    ;;

  *)
    branch="${ref#refs/heads/}"
    slug="$(printf '%s' "$branch" | tr '/_' '--' | tr -cd '[:alnum:].-')"
    [[ -n "$slug" ]] || slug="branch"
    version="${base}-feature.${slug}.${build_id}"
    channel="feature"
    ;;
esac

printf '%s|%s\n' "$version" "$channel"
