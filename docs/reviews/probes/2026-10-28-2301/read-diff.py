from pathlib import Path
import json,re,hashlib,sys
b=Path('/home/duanjb666/deepseek/Arris-playback-fixes');snap=Path('/tmp/arris-audit-2301-path').read_text();e=b/'docs/reviews/probes/2026-10-28-2301/coverage.json'
d=json.loads(e.read_text());parts=json.loads(Path('/tmp/arris-2301-diffs.json').read_text())
for arg in sys.argv[1:]:
 for idx in ([int(arg)] if '-' not in arg else range(int(arg.split('-')[0]),int(arg.split('-')[1])+1)):
  p=parts[idx];lines=p.splitlines();rel=lines[0].split(' b/')[1];source=Path(snap)/rel
  print(f'\nDELTA {idx}: {rel}')
  ranges=[]
  for line in lines[1:]:
   if line.startswith('@@'):
    m=re.search(r'\+(\d+)(?:,(\d+))?',line);a=int(m[1]);n=int(m[2] or 1)
    if n:ranges.append([a,a+n-1])
    print(line)
   elif line.startswith(('+++','---','index','new file mode')):continue
   elif line[:1] in ('+','-',' '):
    t=line[1:].strip()
    if t and not t.startswith(('//','/*','*','import ')):print(line)
  x=next((f for f in d['files'] if f['path']==rel),None)
  if x is None:x={'path':rel,'regions':[],'mode':'partial_call_chain'};d['files'].append(x)
  x['regions']+=ranges
  x['review_kind']='changed_hunks_and_recorded_ranges'
  x['lines_total']=len(source.read_text().splitlines());x['sha256']=hashlib.sha256(source.read_bytes()).hexdigest()
  x['diff_baseline']='4d3233c';x['diff_head']='e56adcc'
  e.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
