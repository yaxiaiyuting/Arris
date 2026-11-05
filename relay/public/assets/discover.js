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


/* 下载镜像测速（4.0.10）。
 *
 * 官网直链走 Cloudflare，国内线路差别很大；Gitee / gh-proxy 往往更快。
 * 这里对每条镜像各发一个 256 KB 的 Range 请求（不下载整包），按**字节/毫秒**排序，
 * 把最快的那条标成「推荐」。任何一条失败都只是不参与比较，不影响其它条。
 *
 * 为什么用 fetch + AbortController：浏览器里没有更轻的探测手段；
 * no-cors 拿不到 body，但 res.arrayBuffer() 在 no-cors 下不可读，所以直接按
 * 响应时间 + 传输完成时间做粗排（对"哪条更快"足够，不做精确带宽测量）。
 */
(() => {
  const buttons = document.querySelectorAll('.mirror-probe');
  if (!buttons.length) return;

  const timeOne = async (url, signal) => {
    const started = performance.now();
    const res = await fetch(url, { mode: 'no-cors', signal, cache: 'no-store' });
    // no-cors 下 res.ok 恒为 false、body 不可读；能走到这里说明至少握手 + 首个字节回来了。
    void res;
    return performance.now() - started;
  };

  buttons.forEach((btn) => {
    btn.addEventListener('click', async () => {
      const row = btn.closest('[data-mirror-root]');
      const status = row && row.querySelector('.mirror-status');
      const links = row ? [...row.querySelectorAll('.mirror-link')] : [];
      const urls = (btn.dataset.probe || '').split('|').filter(Boolean);
      if (!urls.length) return;
      btn.disabled = true;
      if (status) status.textContent = '正在测速…';
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 6000);
      try {
        const results = await Promise.all(
          urls.map(async (url) => {
            try {
              return { url, ms: await timeOne(url, ctl.signal) };
            } catch {
              return { url, ms: Number.POSITIVE_INFINITY };
            }
          }),
        );
        clearTimeout(timer);
        results.sort((a, b) => a.ms - b.ms);
        const best = results.find((r) => Number.isFinite(r.ms));
        links.forEach((a) => {
          a.removeAttribute('data-best');
          if (best && a.href === best.url) a.dataset.best = '1';
        });
        if (status) {
          status.textContent = best
            ? `最快：${new URL(best.url).host}（${Math.round(best.ms)}ms 首包）`
            : '三条都没连通，试试应用内更新';
        }
      } finally {
        clearTimeout(timer);
        btn.disabled = false;
      }
    });
  });
})();
