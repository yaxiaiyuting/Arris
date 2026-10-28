"""Replay the production deletion predicate against an in-memory SQL fixture.
Not a ContentResolver/Android/real gallery execution. It proves predicate overlap only.
"""
from pathlib import Path
import json,re,sqlite3
source=Path('app/src/main/java/com/takahashirinta/ncrust/share/LyricShareActions.kt').read_text()
share_path=re.search(r'SHARE_RELATIVE_PATH = "([^"]+)"',source).group(1)+'/'
ttl=int(re.search(r'SHARE_MEDIA_TTL_SECONDS = (\d+)L',source).group(1))
# DIRECTORY_PICTURES == Pictures (Android SDK constant); source gallery-save suffix is /Arris.
assert '"${Environment.DIRECTORY_PICTURES}/Arris"' in source
assert 'RELATIVE_PATH} = ? AND ' in source and 'DATE_ADDED} < ?' in source
permanent_path='Pictures/Arris/'
assert permanent_path==share_path
now=2_000_000_000
c=sqlite3.connect(':memory:')
c.execute('CREATE TABLE images (_id INTEGER, relative_path TEXT, date_added INTEGER, purpose TEXT)')
c.executemany('INSERT INTO images VALUES (?,?,?,?)',[(1,permanent_path,now-900,'user saved to gallery'),(2,share_path,now-900,'old temporary share'),(3,permanent_path,now-60,'recent user save'),(4,'Pictures/Other/',now-900,'unrelated')])
predicate='relative_path = ? AND date_added < ?'
matched=c.execute('SELECT _id,purpose FROM images WHERE '+predicate,(share_path,now-ttl)).fetchall()
assert matched==[(1,'user saved to gallery'),(2,'old temporary share')]
print(json.dumps({'evidence_kind':'production-predicate replay, not Android execution','ttl_seconds':ttl,'path_shared_by_temporary_and_permanent_exports':share_path,'matched':matched,'source':'LyricShareActions.kt:190-239,296-323'},ensure_ascii=False,indent=2))
