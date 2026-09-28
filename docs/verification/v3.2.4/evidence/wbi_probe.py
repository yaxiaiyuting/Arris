import json,time,hashlib,urllib.parse,subprocess,sys
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
REF='https://www.bilibili.com/'
def curl(url, extra=None):
    cmd=['curl','-s','--max-time','25','-H','User-Agent: '+UA,'-H','Referer: '+REF]
    if extra:
        for k,v in extra.items(): cmd+=['-H',f'{k}: {v}']
    cmd.append(url)
    return subprocess.run(cmd,capture_output=True,text=True).stdout
nav=json.loads(curl('https://api.bilibili.com/x/web-interface/nav'))
img=nav['data']['wbi_img']['img_url'].rsplit('/',1)[-1].split('.')[0]
sub=nav['data']['wbi_img']['sub_url'].rsplit('/',1)[-1].split('.')[0]
MIXIN=[46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52]
raw=img+sub
mixin=''.join(raw[i] for i in MIXIN)[:32]
def sign(params):
    params=dict(params); params['wts']=str(int(time.time()))
    items=sorted(params.items())
    q=urllib.parse.urlencode(items)
    params['w_rid']=hashlib.md5((q+mixin).encode()).hexdigest()
    return urllib.parse.urlencode(sorted(params.items()))
q=sign({'search_type':'video','keyword':'成都 赵雷','page':'1'})
body=curl('https://api.bilibili.com/x/web-interface/wbi/search/type?'+q)
open('search-wbi-raw.json','w').write(body)
d=json.loads(body)
print('search code=',d.get('code'),'msg=',d.get('message'))
res=(d.get('data') or {}).get('result') or []
print('results=',len(res))
for r in res[:3]:
    print('  bvid=',r.get('bvid'),'title=',(r.get('title') or '')[:30].replace('<em class="keyword">','').replace('</em>',''),'duration=',r.get('duration'))
if res:
    bvid=res[0]['bvid']
    v=json.loads(curl(f'https://api.bilibili.com/x/web-interface/view?bvid={bvid}'))
    open('view-raw.json','w').write(json.dumps(v,ensure_ascii=False,indent=1))
    cid=v['data']['cid']; print('picked bvid=',bvid,'cid=',cid,'title=',v['data']['title'])
    p=json.loads(curl(f'https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}&fnval=4048&fnver=0&fourk=1'))
    open('playurl-raw.json','w').write(json.dumps(p,ensure_ascii=False,indent=1))
    print('playurl code=',p.get('code'))
    dash=(p.get('data') or {}).get('dash') or {}
    ad=dash.get('audio') or []
    print('dash.audio count=',len(ad))
    for a in ad:
        u=a.get('baseUrl') or (a.get('base_url'))
        print('  id=',a.get('id'),'bw=',a.get('bandwidth'),'codecs=',a.get('codecs'),'host=',urllib.parse.urlparse(u).hostname)
        print('    backup count=',len(a.get('backupUrl') or []))
    if ad:
        open('dash-audio-url.txt','w').write(ad[0]['baseUrl'])
        print('WROTE dash-audio-url.txt host=',urllib.parse.urlparse(ad[0]['baseUrl']).hostname)
