#!/usr/bin/env bash
# Що зараз: версія в репо, останній реліз, останні збірки, ключ підпису.
set -u
. "$(dirname -- "$0")/tc-common.sh"
cd "$TC_REPO" || exit 1

echo "Track Catcher — статус"
echo
info "Репо на телефоні: $(git branch --show-current) · $(git rev-parse --short HEAD) · версія v$(tc_version)"
if git fetch --quiet origin "+refs/heads/main:refs/remotes/origin/main" 2>/dev/null; then
  behind="$(git rev-list --count HEAD..refs/remotes/origin/main 2>/dev/null || echo 0)"
  if [ "$behind" = "0" ]; then ok "Меню актуальне"; else warn "На GitHub новіше на $behind коміт(и) — пункт 1"; fi
fi
echo
if tc_gh_ready; then
  rel="$(gh release list -R "$TC_SLUG" -L 1 --json tagName,publishedAt -q '.[0] | "\(.tagName) · \(.publishedAt)"' 2>/dev/null)"
  if [ -n "$rel" ]; then ok "Останній реліз: $rel"; else warn "Релізів ще немає"; fi
  echo
  echo "Останні збірки (Android):"
  gh run list -R "$TC_SLUG" -w android.yml -L 3 \
    --json displayTitle,status,conclusion,headBranch \
    -q '.[] | "   \(.headBranch) · \(.status) \(.conclusion // "") · \(.displayTitle)"' 2>/dev/null || warn "Не вдалося прочитати збірки"
  echo
  secrets="$(gh secret list -R "$TC_SLUG" 2>/dev/null | cut -f1 | tr '\n' ' ')"
  case "$secrets" in
    *TC_KEYSTORE_B64*) ok "Ключ підпису в GitHub: є" ;;
    *) warn "Ключа підпису в GitHub немає — пункт 7" ;;
  esac
else
  warn "gh не увійшов у GitHub — частину статусу не видно"
fi
if [ -f "$TC_KEY" ]; then ok "Ключ підпису на телефоні: є"; else warn "Ключа на телефоні немає — пункт 7"; fi
