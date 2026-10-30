"""Read-only verification of the original source and original Android sandbox."""
from pathlib import Path
import hashlib,io,json,tarfile
from v2_adb_ui import run
ROOT=Path(__file__).resolve().parents[2]
manifest=json.loads((ROOT/'private/source-snapshot-manifest.json').read_text())
base=Path(manifest['source']);changed=[]
for rel,expected in manifest['source_sha256'].items():
    file=base/rel
    if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest()!=expected:changed.append(rel)
before=ROOT/'private/original-app-before-v2.tar'
current=run('exec-out','run-as','com.aistudio.quietneighbors.gspqzy','tar','-cf','-','.',raw=True)
(ROOT/'private/original-app-after-v2.tar').write_bytes(current)
def files(data):
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        return {m.name.removeprefix('./'):t.extractfile(m).read() for m in t.getmembers() if m.isfile() and m.name.removeprefix('./').startswith(('databases/','files/','shared_prefs/'))}
old=files(before.read_bytes());new=files(current)
different=[name for name in sorted(set(old)|set(new)) if old.get(name)!=new.get(name)]
record_changes=[name for name in different if name.startswith('databases/') or name.lower().endswith(('.wav','.m4a','.mp3','.aac'))]
report={'original_source_file_count':len(manifest['source_sha256']),'source_changed_files':changed,'original_app_compared_file_count':len(old),'original_app_changed_paths':different,'record_database_or_audio_changed_paths':record_changes,'all_app_files_byte_identical':not different,'auth_change_cause_confirmed':False,'note':'Authentication/heartbeat files can update while networking is active; do not restore stale tokens. Original DB and recording bytes are checked separately.','original_package':'com.aistudio.quietneighbors.gspqzy','source_and_recordings_preserved':not changed and not record_changes}
(ROOT/'evidence/original-preservation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False,indent=2))
if not report['source_and_recordings_preserved']:raise SystemExit(1)
