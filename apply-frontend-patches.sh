#!/usr/bin/env bash
# Patch Metabase frontend constants so the StarRocks driver can expose
# UI toggles that are otherwise gated by a hard-coded engine allowlist.
# Pattern-based (Perl) so it survives upstream line-number drift.
#
# Run from the Metabase repo root:
#   bash apply-frontend-patches.sh
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

# Add "starrocks" to ALLOWED_ENGINES_FOR_TABLE_EDITING so the
# "Editable table data" section appears on the admin Database page.
# See frontend/src/metabase/databases/constants.tsx and
# enterprise/frontend/src/metabase-enterprise/table-editing/admin/AdminDatabaseTableEditingSection.tsx.
perl -0777 -i -pe '
  s{
    ALLOWED_ENGINES_FOR_TABLE_EDITING\s*=\s*\[
    (?<list>[^\]]*?)
    \]
  }{
    my $list = $+{list};
    if ($list !~ /"starrocks"/) {
        $list =~ s/\s*$//;
        $list .= qq{, "starrocks"};
    }
    qq{ALLOWED_ENGINES_FOR_TABLE_EDITING = [$list]}
  }esx;
' frontend/src/metabase/databases/constants.tsx
require_change frontend/src/metabase/databases/constants.tsx "ALLOWED_ENGINES_FOR_TABLE_EDITING += starrocks"

echo ""
echo "Frontend patches applied:"
git diff --cached --stat
