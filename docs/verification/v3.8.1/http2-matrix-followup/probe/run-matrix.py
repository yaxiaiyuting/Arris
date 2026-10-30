from pathlib import Path
import subprocess,os,json,xml.etree.ElementTree as E,hashlib,tempfile,shutil
from datetime import datetime
from zoneinfo import ZoneInfo
root=Path('/home/duanjb666/deepseek/Arris-playback-fixes');out=root/'docs/verification/v3.8.1/http2-matrix-followup';work=Path(tempfile.mkdtemp(prefix='arris-h2-v3-'));work.chmod(0o700)
adb=['/home/duanjb666/Android/sdk/platform-tools/adb','-s','3B15CD00GB700000']
def shell(cmd):return subprocess.check_output(adb+['shell',cmd],text=True).strip()
uidline=next(x for x in shell('pm list packages -U com.takahashirinta.ncrust').splitlines() if x.startswith('package:com.takahashirinta.ncrust uid:'))
uid=uidline.rsplit(':',1)[1]
# Parse credentials in memory only; never print prefs or save them in the repository.
prefs=E.fromstring(subprocess.check_output(adb+['shell','cat','/data/user/0/com.takahashirinta.ncrust/shared_prefs/ncrust_prefs.xml']))
cookie=next(x.text for x in prefs if x.get('name')=='user_cookie')
assert cookie and 'MUSIC_U=' in cookie
prefsdev=E.fromstring(subprocess.check_output(adb+['shell','cat','/data/user/0/com.takahashirinta.ncrust/shared_prefs/ncrust_device.xml']))
dev=next((x.text for x in prefsdev if x.get('name')=='client_device_id'),None)
assert dev
for name,data in [('session.txt',cookie),('device.txt',dev)]:
    f=work/name;f.write_text(data);f.chmod(0o600)
s=(root/'docs/verification/v3.8.0/http2-matrix-2026-10-30/probe/v2/TransportMatrix.java').read_text()
a=s.index('    // Keep ordinary certificate verification;');b=s.index('    LinkedHashMap<String,String> ck=',a)
s=s[:a]+s[b:]
s=s.replace('String device=ck.getOrDefault("deviceId","arris-protocol-matrix");','String device=Files.readString(Paths.get(args[1])).trim();')
s=s.replace('boolean ipv4Only=args.length>2&&args[2].equals("ipv4_only");','String dnsMode=args.length>2?args[2]:"system"; boolean ipv4Only=dnsMode.equals("ipv4_only");')
s=s.replace('if(!ipv4Only)return found;', 'if(dnsMode.equals("reverse_dns")){List<InetAddress> reversed=new ArrayList<>(found); Collections.reverse(reversed); return reversed;} if(!ipv4Only)return found;')
s=s.replace('.sslSocketFactory(ssl.getSocketFactory(),tm)','')
s=s.replace('.connectTimeout(30,TimeUnit.SECONDS)', '.connectTimeout(args.length>3?Integer.parseInt(args[3]):30,TimeUnit.SECONDS)')
s=s.replace('new String[]{"direct","capture_proxy"}','new String[]{"direct"}')
s=s.replace('put("deadline_seconds",8)', 'put("deadline_seconds",8).put("dns_mode",args.length>2?args[2]:"system").put("connect_timeout_seconds",args.length>3?Integer.parseInt(args[3]):30)')
source=out/'probe/TransportMatrix.java';source.write_text(s)
cache=Path('/home/duanjb666/.gradle/caches/modules-2/files-2.1');jars=[next(cache.glob(p)) for p in ['com.squareup.okhttp3/okhttp/4.12.0/*/*.jar','com.squareup.okio/okio-jvm/3.9.1/*/*.jar','org.jetbrains.kotlin/kotlin-stdlib/1.9.24/*/*.jar','org.jetbrains/annotations/23.0.0/*/*.jar']]
android=Path('/home/duanjb666/Android/sdk/platforms/android-36/android.jar');classes=work/'classes';classes.mkdir()
r=subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac','--release','11','-cp',os.pathsep.join(map(str,[android,*jars])),'-d',str(classes),str(source)],capture_output=True,text=True)
print(r.stderr[:1200],flush=True);r.check_returncode()
subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar','cf',str(work/'probe.jar'),'-C',str(classes),'.'],check=True)
r=subprocess.run(['/home/duanjb666/Android/sdk/build-tools/36.0.0/d8','--min-api','29','--lib',str(android),'--output',str(work/'probe.zip'),str(work/'probe.jar'),*map(str,jars)],capture_output=True,text=True);r.check_returncode()
remote='/data/local/tmp/arris-h2-followup'
subprocess.run(adb+['shell','mkdir','-p',remote],check=True)
for name in ['probe.zip','session.txt','device.txt']:
    subprocess.run(adb+['push',str(work/name),remote+'/'+name],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
shell(f'chown -R {uid}:{uid} {remote} && chmod 700 {remote} && chmod 400 {remote}/*')
env={'host_start_jst':datetime.now(ZoneInfo('Asia/Tokyo')).isoformat(),'device_wall':shell('date'),'app_uid':int(uid),'global_proxy_before':shell('settings get global http_proxy'),'source_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'dex_sha256':hashlib.sha256((work/'probe.zip').read_bytes()).hexdigest(),'credentials':'one live prefs snapshot, same device identity; not logged','tls':'system default; verification unchanged','apk_install':False,'global_network_changed':False}
(out/(os.environ.get('ARRIS_DNS_MODE','system')+'-connect'+os.environ.get('ARRIS_CONNECT_TIMEOUT','30')+'-environment.json')).write_text(json.dumps(env,ensure_ascii=False,indent=2)+'\n')
try:
    mode=os.environ.get('ARRIS_DNS_MODE','system');connect=os.environ.get('ARRIS_CONNECT_TIMEOUT','30')
    name=f'{mode}-connect{connect}'
    print('START',name,'UID',uid,flush=True)
    with (out/(name+'.jsonl')).open('w') as stdout,(out/(name+'-stderr.txt')).open('w') as stderr:
        r=subprocess.run(adb+['shell','su',uid,'-c',f'CLASSPATH={remote}/probe.zip app_process /system/bin arris.probe.TransportMatrix {remote}/session.txt {remote}/device.txt {mode} {connect}'],stdout=stdout,stderr=stderr,timeout=300)
    print('DONE',name,r.returncode,flush=True)
    env['global_proxy_after']=shell('settings get global http_proxy');env['host_end_jst']=datetime.now(ZoneInfo('Asia/Tokyo')).isoformat();(out/(os.environ.get('ARRIS_DNS_MODE','system')+'-connect'+os.environ.get('ARRIS_CONNECT_TIMEOUT','30')+'-environment.json')).write_text(json.dumps(env,ensure_ascii=False,indent=2)+'\n')
finally:
    subprocess.run(adb+['shell','rm','-rf',remote],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    shutil.rmtree(work)
