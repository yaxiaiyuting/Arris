from pathlib import Path
import json, hashlib, shutil, subprocess, xml.etree.ElementTree as ET
b=Path('/home/duanjb666/deepseek/Arris-playback-fixes')
e=b/'docs/reviews/probes/2026-10-27-2052'
c=json.loads((e/'coverage.json').read_text())
for name,ranges in {
 'MainActivity.kt':[[1355,1415],[2018,2044],[2093,2110]],
 'ui/player/PlayerCard.kt':[[485,585]],
 'ui/player/PlayerCardOverlays.kt':[[245,302]],
}.items():
 p=b/'app/src/main/java/com/takahashirinta/ncrust'/name
 rel=str(p.relative_to(b)); item=next((x for x in c['files'] if x['path']==rel),None)
 if item is None:
  item={'path':rel,'regions':[]};c['files'].append(item)
 item['regions']=item.get('regions',[])+ranges
 item.update(lines_total=len(p.read_text().splitlines()),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
def normalize(x):
 ranges=[]
 for lo,hi in sorted(x.get('regions',[])):
  hi=min(hi,x['lines_total'])
  if lo>hi:continue
  if ranges and lo<=ranges[-1][1]+1:ranges[-1][1]=max(hi,ranges[-1][1])
  else:ranges.append([lo,hi])
 x['regions']=ranges
 x['mode']='full_executable_code' if x.get('mode')=='full_executable_code' or ranges==[[1,x['lines_total']]] else 'partial_call_chain'
for x in c['files']:normalize(x)
c['counts']={k:sum(x['mode']==k for x in c['files']) for k in ['full_executable_code','partial_call_chain']}
c['counts']['recorded']=len(c['files'])
c['counts']['without_this_round_record']=382-len(c['files'])
(e/'coverage.json').write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n')
old=json.loads((b/'docs/reviews/probes/2026-10-27-1150/cumulative-coverage.json').read_text())
merged={x['path']:dict(x) for x in old['files']}
for x in c['files']:
 if x['path'] in merged:
  o=merged[x['path']];assert o['sha256']==x['sha256'],x['path']
  o['regions']=o.get('regions',[])+x.get('regions',[])
  if x['mode']=='full_executable_code':o['mode']='full_executable_code'
 else:merged[x['path']]=dict(x)
for x in merged.values():
 normalize(x)
 assert hashlib.sha256((b/x['path']).read_bytes()).hexdigest()==x['sha256'],x['path']
allfiles=sorted(b.glob('app/src/main/java/**/*.kt'))
assert len(allfiles)==382
remaining=[{'path':x['path'],'regions':x['regions'],'lines_total':x['lines_total']} for x in merged.values() if x['mode']=='partial_call_chain']
counts={k:sum(x['mode']==k for x in merged.values()) for k in ['full_executable_code','partial_call_chain']}
counts.update(recorded=len(merged),without_record=len(allfiles)-len(merged))
combined={'definition':'06:43 + 11:50 + 20:52 合并，仅 SHA 一致时归并连续行段。通读跳过注释/import/空行，不代表行为动态验证或项目总体完成。','baseline':c['application_baseline'],'counts':counts,'files':sorted(merged.values(),key=lambda x:x['path']),'normalization_note':'历史 QqPlaylistDetailScreen 带有无效逆序行段；本轮已实际重新通读 1–262，规范化移除无效段，不修改历史证据。'}
(e/'cumulative-coverage.json').write_text(json.dumps(combined,ensure_ascii=False,indent=2)+'\n')
(e/'remaining-partial.json').write_text(json.dumps(remaining,ensure_ascii=False,indent=2)+'\n')
final=e/'phase-final';final.mkdir(exist_ok=True)
sources=sorted((b/'app/src/test/java/com/takahashirinta/ncrust/audit').glob('Evening*AuditProbe.kt'))
temporary_sources=bool(sources)
if not sources:sources=sorted(final.glob('Evening*AuditProbe.kt'))
assert len(sources)==5
summary=[]
for source in sources:
 xml=b/'app/build/test-results/testDebugUnitTest'/('TEST-com.takahashirinta.ncrust.audit.'+source.stem+'.xml')
 root=ET.parse(xml).getroot();countsxml={k:int(root.attrib[k]) for k in ['tests','failures','errors','skipped']}
 assert not any(countsxml[k] for k in ['failures','errors','skipped'])
 if source != final/source.name:shutil.copy2(source,final/source.name)
 shutil.copy2(xml,final/xml.name)
 summary.append({'class':source.stem,**countsxml})
assert sum(x['tests'] for x in summary)==8
shutil.copy2('/home/duanjb666/deepseek/dist/audit-2052-20261027-build.txt',final/'gradle.log')
shutil.copy2('/tmp/arris-review-2052-read.py',e/'read-ranges.py')
shutil.copy2(__file__,e/'finalize-evidence.py')
(final/'test-summary.json').write_text(json.dumps({'meaning':'通过断言表示错误存在，不代表修复通过；5 类、8 条缺陷断言，无 Android UI 或真实网络。','suites':summary},ensure_ascii=False,indent=2)+'\n')
if temporary_sources:
 for source in sources:source.unlink()
auditdir=b/'app/src/test/java/com/takahashirinta/ncrust/audit'
if auditdir.exists() and not any(auditdir.iterdir()):auditdir.rmdir()
status=subprocess.check_output(['git','status','--porcelain'],cwd=b,text=True)
assert all(line[3:].startswith('docs/reviews/') for line in status.splitlines()),status
assert subprocess.check_output(['git','diff',c['application_baseline'],'--','app'],cwd=b,text=True)==''
print(json.dumps({'this_round':c['counts'],'cumulative':counts,'remaining':remaining,'tests':summary,'business_diff_empty':True},ensure_ascii=False,indent=2))
