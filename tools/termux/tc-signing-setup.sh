#!/usr/bin/env bash
# Один раз: створити власний ключ підпису Track Catcher на телефоні і передати його в секрети GitHub.
# Ключ потрібен, щоб кожна нова версія ставилась поверх старої (без видалення застосунку).
set -u
. "$(dirname -- "$0")/tc-common.sh"

if ! command -v keytool >/dev/null 2>&1; then
  fail "Немає Java (keytool)."
  info "Java 17 уже ставилась для збірки Dorama; якщо ні — напиши Claude."
  exit 1
fi
if ! tc_gh_ready; then
  fail "gh не увійшов у GitHub — без цього ключ не передати."
  exit 1
fi
mkdir -p "$HOME/.android" && chmod 700 "$HOME/.android"

if [ -f "$TC_KEY" ] && [ -f "$TC_PROPS" ]; then
  ok "Ключ уже є на телефоні — новий не створюю (інакше нові версії не встановляться поверх)."
else
  if [ -f "$TC_KEY" ] || [ -f "$TC_PROPS" ]; then
    fail "Знайдено лише частину ключа ($TC_KEY / $TC_PROPS). Нічого не чіпаю — напиши Claude."
    exit 1
  fi
  pass="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 28)"
  if ! keytool -genkeypair -keystore "$TC_KEY" -storetype PKCS12 -storepass "$pass" -keypass "$pass" \
    -alias "$TC_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Track Catcher, O=faric-ua, C=UA" >/dev/null 2>&1; then
    fail "keytool не створив ключ."
    exit 1
  fi
  umask 077
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$TC_KEY" "$pass" "$TC_ALIAS" "$pass" > "$TC_PROPS"
  chmod 600 "$TC_KEY" "$TC_PROPS"
  ok "Ключ створено: ~/.android/track-catcher.jks"
fi

pass="$(tc_prop storePassword)"
alias_name="$(tc_prop keyAlias)"
cert="$(keytool -list -v -keystore "$TC_KEY" -storepass "$pass" -alias "$alias_name" 2>/dev/null \
  | sed -n 's/^.*SHA256: *//p' | head -n 1 | tr -d ': ' | tr 'A-F' 'a-f')"
if [ -z "$cert" ]; then
  fail "Не вдалося прочитати сертифікат ключа."
  exit 1
fi
info "Сертифікат: ${cert:0:16}…"

echo "Передаю ключ у секрети GitHub…"
base64 -w0 "$TC_KEY" | gh secret set TC_KEYSTORE_B64 -R "$TC_SLUG" >/dev/null || { fail "Не вдалося записати TC_KEYSTORE_B64"; exit 1; }
gh secret set TC_STORE_PASSWORD -R "$TC_SLUG" --body "$pass" >/dev/null || { fail "TC_STORE_PASSWORD"; exit 1; }
gh secret set TC_KEY_ALIAS -R "$TC_SLUG" --body "$alias_name" >/dev/null || { fail "TC_KEY_ALIAS"; exit 1; }
gh secret set TC_KEY_PASSWORD -R "$TC_SLUG" --body "$pass" >/dev/null || { fail "TC_KEY_PASSWORD"; exit 1; }
gh secret set TC_CERT_SHA256 -R "$TC_SLUG" --body "$cert" >/dev/null || { fail "TC_CERT_SHA256"; exit 1; }
ok "Ключ у GitHub (5 секретів)."

echo "Запускаю збірку релізу…"
if gh workflow run android.yml -R "$TC_SLUG" --ref main >/dev/null 2>&1; then
  ok "Збірка запущена. Через 5–7 хв — пункт 2 «Встановити останній реліз»."
else
  warn "Збірку не запущено автоматично — напиши Claude, він запустить."
fi
echo
info "Порада: пункт 8 — зробити резервну копію ключа."
