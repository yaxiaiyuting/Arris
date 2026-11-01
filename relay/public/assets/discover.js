/* Local-only interactions: no telemetry, account access or automatic media playback. */
(()=>{'use strict';
const tabs=[...document.querySelectorAll('[data-showcase]')];
function choose(tab,focus=false){tabs.forEach(b=>{const active=b===tab;b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;document.getElementById(b.getAttribute('aria-controls')).hidden=!active});if(focus)tab.focus()}
tabs.forEach((b,i)=>{b.addEventListener('click',()=>choose(b));b.addEventListener('keydown',e=>{const keys={ArrowRight:(i+1)%tabs.length,ArrowLeft:(i+tabs.length-1)%tabs.length,Home:0,End:tabs.length-1};if(Object.hasOwn(keys,e.key)){e.preventDefault();choose(tabs[keys[e.key]],true)}})});
const dialog=document.querySelector('.screenshot-dialog'),large=document.getElementById('large-screenshot');
let trigger=null;
document.querySelectorAll('[data-screen]').forEach(b=>b.addEventListener('click',()=>{large.src=b.dataset.screen;large.alt=b.querySelector('img').alt;trigger=b;if(typeof dialog.showModal==='function')dialog.showModal();else window.open(b.dataset.screen,'_blank','noopener,noreferrer')}));
const close=()=>dialog.close();document.getElementById('close-screenshot').addEventListener('click',close);
dialog.addEventListener('click',e=>{if(e.target===dialog){const b=dialog.getBoundingClientRect();if(e.clientX<b.left||e.clientX>b.right||e.clientY<b.top||e.clientY>b.bottom)close()}});
dialog.addEventListener('close',()=>{if(trigger)trigger.focus()});
const select=document.getElementById('android-version'),advice=document.getElementById('version-advice'),link=document.getElementById('recommended-download');
select.addEventListener('change',()=>{const v=select.value;if(v==='older'){advice.textContent='当前版本最低支持 Android 7，暂不提供适配此系统的安装包。';link.hidden=true;return}link.hidden=false;const legacy=v==='legacy';advice.textContent=legacy?'推荐 3.7.x LTS，保留旧设备兼容并持续修复问题。':'推荐主线，获得新功能与持续修复。';link.textContent=legacy?'下载 LTS APK ↗':'下载主线 APK ↗';link.href=legacy?'/get/lts-latest.apk':'/get/latest.apk'});
})();
