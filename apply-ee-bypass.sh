#!/usr/bin/env bash
# Force-enable all EE/premium features in a fresh Metabase checkout.
# Pattern-based (Perl) so it survives upstream line-number drift.
#
# Run from the Metabase repo root:
#   bash apply-ee-bypass.sh
#
# Verifies each replacement actually changed the file; aborts on mismatch.

set -euo pipefail

require_change() {
    local file="$1" tag="$2"
    if git diff --quiet -- "$file"; then
        echo "[FAIL] $file: '$tag' replacement did not change file" >&2
        exit 1
    fi
    git add -- "$file"
    echo "[OK]   $file: $tag"
}

# 1) ee-available? → always true
perl -0777 -i -pe '
  s{
    \(def\s+\^\{:doc\s+"Indicates\s+whether\s+Enterprise\s+Edition\s+extensions\s+are\s+available".*?\}\s+ee-available\?\s+
    \(try\s+.*?\(catch\s+Throwable\s+_\s+false\)\)\)
  }{(def ^\{:doc "Indicates whether Enterprise Edition extensions are available" :added "0.39.0"\} ee-available?
  ;; Force EE to be treated as available.
  true)}sx;
' src/metabase/config/core.clj
require_change src/metabase/config/core.clj "ee-available?"

# 2) check-feature → always true
perl -0777 -i -pe '
  s{
    \(defn-\s+check-feature\s+\[feature\]\s+
    \(or\s+\(=\s+feature\s+:none\)\s+
    \(do\s+;;[^\n]*\n\s+
    \(classloader/require[^\n]*\n\s+
    \(\(resolve[^\n]*\)\s*feature\)\)\)\)
  }{(defn- check-feature
  [feature]
  true)}sx;
' src/metabase/premium_features/defenterprise.clj
require_change src/metabase/premium_features/defenterprise.clj "check-feature"

# 3) has-feature? → always true (replace the body return expression)
perl -0777 -i -pe '
  s{
    \(contains\?\s+\(\*token-features\*\)\s+\(name\s+feature\)\)
  }{;; Force all features to be treated as enabled.\n  true}sx;
' src/metabase/premium_features/token_check.clj
require_change src/metabase/premium_features/token_check.clj "has-feature?"

# 4) token-status getter → static valid blob
perl -0777 -i -pe '
  s{
    :getter\s+\(fn\s+\[\]\s+
    \(\(requiring-resolve\s+\x27metabase\.premium-features\.token-check/-token-status\)\)\)
  }{:getter (fn []
                \{:valid true
                 :status "Token is valid."
                 :trial false
                 :valid-thru "2099-12-31T00:00:00Z"
                 :error-details nil\})}sx;
' src/metabase/premium_features/settings.clj
require_change src/metabase/premium_features/settings.clj "token-status getter"

# 5) development-mode? → always false (otherwise the forced has-feature? = true
#    above turns this on and Metabase shows a watermark + warning banner)
perl -0777 -i -pe '
  s{
    \(define-premium-feature\s+\^\{:added\s+"0\.55\.0"\}\s+development-mode\?\s+
    "Is\s+this\s+a\s+development\s+instance\s+that\s+should\s+have\s+watermarks\?"\s+
    :development-mode\)
  }{(define-premium-feature ^\{:added "0.55.0"\} development-mode?
  "Is this a development instance that should have watermarks?"
  :development-mode
  ;; Force dev mode off so the UI banner/watermark does not appear.
  :getter (constantly false))}sx;
' src/metabase/premium_features/settings.clj
require_change src/metabase/premium_features/settings.clj "development-mode? override"

echo ""
echo "EE bypass applied:"
git diff --cached --stat
