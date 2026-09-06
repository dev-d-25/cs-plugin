#!/usr/bin/env bash
# Verify a generated .cs3 plugin package (plan Phase 1).
#
# Usage: scripts/verify-cs3.sh [path-to.cs3 ...]
# Defaults to every .cs3 under */build/.
# Checks: valid zip, manifest.json present, version + pluginClassName sane.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

files=()
if [ "$#" -gt 0 ]; then
  files=("$@")
else
  while IFS= read -r f; do files+=("$f"); done < <(find "$ROOT" -maxdepth 3 -name "*.cs3" -path "*/build/*")
fi
if [ "${#files[@]}" -eq 0 ]; then
  echo "verify-cs3: no .cs3 files found" >&2
  exit 1
fi

fail=0
for f in "${files[@]}"; do
  echo "== $f"
  manifest="$(python3 -c "import zipfile,sys; print(zipfile.ZipFile(sys.argv[1]).read('manifest.json').decode())" "$f")" \
    || { echo "  FAIL: not a zip or manifest.json missing"; fail=1; continue; }
  echo "  manifest: $manifest"
  python3 - "$manifest" <<'EOF' || fail=1
import json, sys
m = json.loads(sys.argv[1])
assert isinstance(m.get("version"), int) and m["version"] > 0, "bad version"
assert m.get("pluginClassName", "").count(".") >= 1, "bad pluginClassName"
assert m.get("name"), "missing name"
print("  OK: version=%s name=%s entry=%s" % (m["version"], m["name"], m["pluginClassName"]))
EOF
done
exit "$fail"
