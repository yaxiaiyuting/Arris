from pathlib import Path
import subprocess, xml.etree.ElementTree as E, re, time
ADB='/home/duanjb666/Android/sdk/platform-tools/adb'
OUT=Path(__file__).resolve().parent

def adb(*args, binary=False):
 r=subprocess.run([ADB,'-s','emulator-5554',*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=25)
 return r.stdout if binary else r.stdout.decode(errors='replace')

def dump(name='window'):
 adb('shell','uiautomator','dump','/sdcard/arris-native-window.xml')
 data=adb('exec-out','cat','/sdcard/arris-native-window.xml',binary=True)
 (OUT/(name+'.xml')).write_bytes(data)
 return E.fromstring(data)

def nodes():
 return [(n.get('text') or n.get('content-desc'),n.get('bounds')) for n in dump().iter('node') if n.get('text') or n.get('content-desc')]

def click(text, contains=False):
 r=dump()
 for n in r.iter('node'):
  label=n.get('text') or n.get('content-desc') or ''
  if (text in label if contains else text==label):
   v=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
   if len(v)==4 and v[2]>v[0] and v[3]>v[1]:
    adb('shell','input','tap',(v[0]+v[2])//2,(v[1]+v[3])//2)
    return True
 return False

def scroll_to(text):
 for _ in range(14):
  r=dump();bounds=list(map(int,re.findall(r'\d+',r[0].get('bounds'))));w,h=bounds[2:]
  for n in r.iter('node'):
   if (n.get('text') or n.get('content-desc'))==text:
    v=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
    if len(v)==4 and 80<v[1]<v[3]<h*.76:
     adb('shell','input','tap',(v[0]+v[2])//2,(v[1]+v[3])//2)
     return True
  adb('shell','input','swipe',w//2,int(h*.66),w//2,int(h*.32),350)
 return False

def shot(name):
 (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))

def back():adb('shell','input','keyevent',4)

if __name__=='__main__':
 for label,bounds in nodes():print(label[:120],bounds)
