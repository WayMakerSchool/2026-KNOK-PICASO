"""Read-only comparison of source snapshot, protected runtime code, and model bytes."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'evidence/baseline-source-manifest.json').read_text())
original = Path(manifest['source'])
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
source_changes = []
protected_changes = []
protected_count = 0
models = []
for row in manifest['files']:
    rel = row['path']
    path = original / rel
    if not path.is_file() or sha(path) != row['sha256']:
        source_changes.append(rel)
    protected = rel.startswith(('app/src/main/java/com/example/data/', 'app/src/main/java/com/example/ml/',
        'app/src/main/java/com/example/auth/', 'app/src/main/assets/')) or rel.endswith(('EspRecorderViewModel.kt','NoiseViewModel.kt'))
    if protected:
        protected_count += 1
        copied = root / rel
        if not copied.is_file() or sha(copied) != row['sha256']:
            protected_changes.append(rel)
    if rel.startswith('app/src/main/assets/'):
        models.append({'path':rel,'sha256':row['sha256']})
result = {'original_source_changes':source_changes,'v3_protected_runtime_changes':protected_changes,
    'protected_file_count':protected_count,'model_assets':models}
(root / 'evidence/preservation-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'original_source_changes':len(source_changes),'protected_runtime_changes':len(protected_changes),
    'protected_files':protected_count,'model_assets':len(models)}))
if source_changes or protected_changes:
    raise SystemExit(1)
