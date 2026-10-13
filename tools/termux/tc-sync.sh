#!/usr/bin/env bash
# Оновити меню з GitHub: лише fast-forward, нічого не стирає й не скидає.
set -u
. "$(dirname -- "$0")/tc-common.sh"
quiet=0
[ "${1:-}" = "--quiet" ] && quiet=1
cd "$TC_REPO" || exit 1

branch="$(git branch --show-current 2>/dev/null || true)"
if [ "$branch" != "main" ]; then
  fail "Зараз гілка «${branch:-без гілки}», а меню працює з main. Нічого не змінюю."
  exit 3
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  fail "У папці меню є локальні зміни — оновлення зупинено, нічого не стерто."
  git status --short | head -n 10
  exit 4
fi
if ! git fetch --quiet --prune origin "+refs/heads/main:refs/remotes/origin/main"; then
  warn "GitHub недоступний — працюю з тим, що є."
  exit 5
fi
local_sha="$(git rev-parse HEAD)"
remote_sha="$(git rev-parse refs/remotes/origin/main)"
if [ "$local_sha" = "$remote_sha" ]; then
  [ "$quiet" = 1 ] || ok "Меню вже актуальне: $(git rev-parse --short HEAD)"
  exit 10
fi
if git merge-base --is-ancestor "$local_sha" "$remote_sha"; then
  if git merge --quiet --ff-only "$remote_sha"; then
    ok "Меню оновлено: $(git rev-parse --short "$local_sha") → $(git rev-parse --short HEAD)"
    exit 0
  fi
  fail "Git зупинив оновлення — локальні файли не перезаписані."
  exit 6
fi
fail "Копія на телефоні розійшлась з GitHub. Нічого не скидаю — напиши Claude."
info "Телефон: $(git rev-parse --short "$local_sha")  GitHub: $(git rev-parse --short "$remote_sha")"
exit 7
