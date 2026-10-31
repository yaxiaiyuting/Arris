from pathlib import Path
import sys,json,hashlib
base=Path('/home/duanjb666/deepseek/.arris-audit-20261028-2301-8kc4uzvg')
src=base/'app/src/main/java/com/takahashirinta/ncrust'
ledger=Path('/home/duanjb666/deepseek/Arris-playback-fixes/docs/reviews/probes/2026-10-28-2301/coverage.json')
data=json.loads(ledger.read_text())
for spec in sys.argv[1:]:
    name,_,region=spec.partition(':')
    p=src/name
    if not p.exists():
        print('MISSING',name);continue
    lines=p.read_text().splitlines()
    a,b=(map(int,region.split('-')) if region else (1,len(lines)))
    b=min(b,len(lines))
    print('\n'+name)
    for n,s in enumerate(lines,1):
        t=s.strip()
        if a<=n<=b and t and not t.startswith(('//','*','/*','import ')):
            print(f'{n}: {s}')
    rel=str(p.relative_to(base));old=next((x for x in data['files'] if x['path']==rel),None)
    item=old or {'path':rel,'regions':[]}
    item['regions'].append([a,b]);item['mode']='full_executable_code' if any(x==[1,len(lines)] for x in item['regions']) else 'partial_call_chain'
    item['lines_total']=len(lines);item['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
    if old is None:data['files'].append(item)
    ledger.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
