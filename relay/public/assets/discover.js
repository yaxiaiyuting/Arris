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

/* 4.0.0 · 章节入场：只观察 [data-reveal]，一次触发后即解绑；
   尊重 prefers-reduced-motion（此时直接全部可见）。 */
(()=>{
  const targets=[...document.querySelectorAll('[data-reveal]')];
  if(!targets.length)return;
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches||!('IntersectionObserver' in window)){
    targets.forEach(el=>el.classList.add('is-in'));
    return;
  }
  const io=new IntersectionObserver((entries)=>{
    entries.forEach(e=>{
      if(!e.isIntersecting)return;
      e.target.classList.add('is-in');
      io.unobserve(e.target);
    });
  },{rootMargin:'0px 0px -12% 0px',threshold:.12});
  targets.forEach(el=>io.observe(el));
})();

/* 4.0.0 · 更新记录改为运行时渲染。
 *
 * 背景：这套站点的首页 HTML 是嵌在 worker 里的静态快照，而当前部署链路没能把新 HTML
 * 推上去（静态资源与 /api/* 都正常）。于是"改版本卡片"这件事改成**从前端做**：
 * 静态资源能更新，接口也能更新，那就让浏览器自己去取 releases 再渲染。
 * 以后新版本发布只要 GitHub Release 有了，页面自动跟上，不再依赖改 HTML。
 */
(() => {
  const grid = document.querySelector('.release-grid.mainline');
  if (!grid) return;

  const TAGS = { // 中文标签（未知版本回落到通用词）
    '4.0.0': '均衡器 & 工具链',
    '3.8.18': '可诊断', '3.8.17': '可诊断', '3.8.16': '彩蛋 & 网络',
    '3.8.15': '下载', '3.8.14': '分段下载', '3.8.13': '下载',
    '3.8.12': '更新检查', '3.8.11': '信号', '3.8.10': 'B站',
  };

  const esc = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const firstLine = (body) => {
    const lines = String(body || '').split('\n').map((l) => l.trim())
      .filter((l) => l && !l.startsWith('#') && !l.startsWith('>') && !l.startsWith('---'));
    return lines.length ? lines[0].replace(/^[-*]\s*/, '') : '';
  };

  const CARD = (r) => {
    const ver = String(r.tag_name).replace(/^v/, '').replace(/-gpl$/, '');
    const tag = TAGS[ver] || '发布';
    const title = firstLine(r.body).slice(0, 34) || `${ver} 发布`;
    const text = String(r.body || '').split('\n').map((l) => l.replace(/^[-*]\s*/, '').trim())
      .filter((l) => l && !l.startsWith('#') && !l.startsWith('>') && l !== title)[0] || '';
    return `<article><div class="release-version"><span>${esc(ver)}</span><small>${esc(tag)}</small></div>` +
      `<h4>${esc(title)}</h4><p>${esc(text.slice(0, 76))}</p>` +
      `<a href="${esc(r.html_url)}" target="_blank" rel="noopener noreferrer" aria-label="查看 ${esc(ver)} 发布说明">查看说明 ↗</a></article>`;
  };

  fetch('/api/releases?nocache=1', { cache: 'no-store' })
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error('http'))))
    .catch(() => fetch('/api-fallback/releases-array.json', { cache: 'no-store' }).then((r) => r.json()))
    .then((list) => {
      if (!Array.isArray(list) || !list.length) return;
      const mainline = list.filter((r) => /^v\d+\.\d+\.\d+-gpl$/.test(r.tag_name)).slice(0, 5);
      if (mainline.length) grid.innerHTML = mainline.map(CARD).join('');

      const lts = list.find((r) => r.tag_name === 'lts-3.7');
      const ltsRow = document.querySelector('.release-grid.lts-row');
      if (lts && ltsRow) {
        const asset = (lts.assets || [])[0];
        const ver = String(asset && asset.name || lts.tag_name).match(/v?(\d+\.\d+\.\d+)/);
        const v = ver ? ver[1] : '3.7.x';
        ltsRow.innerHTML =
          `<article class="lts"><div class="release-version"><span>${esc(v)}</span><small>LTS</small></div>` +
          `<h4>旧设备，也跟得上。</h4>` +
          `<p>与主线同代：Kotlin 2、media3 1.9、均衡器、间奏呼吸点与竖屏字号修复。</p>` +
          `<a href="${esc(lts.html_url)}" target="_blank" rel="noopener noreferrer" aria-label="查看 ${esc(v)} 发布说明">查看说明 ↗</a></article>`;
      }
    })
    .catch(() => { /* 静默：拿不到就保留服务端渲染的静态卡片 */ });
})();
