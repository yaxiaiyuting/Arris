// Deterministic visual checkpoints for the imported film and Android adapter.
const { chromium } = require(process.env.ARRIS_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../app/src/main/assets/birthday');
const out = path.resolve(process.argv[2] || '/tmp/arris-motion-qa');
fs.mkdirSync(out, { recursive: true });
(async () => {
  const server = http.createServer((req, res) => {
    const file = path.join(root, new URL(req.url, 'http://localhost').pathname);
    try {
      res.setHeader('Content-Type', ({'.html':'text/html','.css':'text/css','.js':'application/javascript','.png':'image/png'})[path.extname(file)] || 'application/octet-stream');
      res.end(fs.readFileSync(file));
    } catch { res.statusCode = 404; res.end(); }
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const origin = `http://127.0.0.1:${server.address().port}`;
  let browser;
  const results = [];
  try {
    browser = await chromium.launch({headless:true});
    for (const [width, height] of [[390,844],[360,640],[844,390],[1600,900]]) {
      const page = await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
      const errors = [], requests = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
      page.on('request', r => requests.push(r.url()));
      await page.addInitScript(() => { window.events=[]; window.ArrisHost={event:s=>events.push(s)}; });
      await page.goto(`${origin}/index.html?app=1&still=1&t=0&clean=1&motion=1`);
      await page.waitForFunction(() => window.BDAY?.ready && window.ArrisBirthday);
      await page.evaluate(() => {
        ArrisBirthday.configure({title:'生日快乐。',wishes:['愿你耳边，常有喜欢的歌。','愿你心里，常有值得期待的事。','新的一岁，按自己的节奏发光。']});
        BDAY.pause();
      });
      const frames = [];
      let camera;
      for (const time of [9.8,14.85,19.9,20.0,26.2,27.7,31.9,34.9,44.5]) {
        await page.evaluate(t => BDAY.seek(t), time);
        await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
        const state = await page.evaluate(() => ({
          camera:[...document.querySelectorAll('[data-depth]')].map(e=>e.style.transform),
          mid:document.querySelector('#e-mid').getAttribute('transform'),
          filters:[...document.querySelectorAll('#svg-env [filter],#svg-scene [filter]')].map(e=>getComputedStyle(e).filter),
          brightness:[...document.querySelectorAll('#stage [style]')].filter(e=>e.style.filter.includes('brightness')).map(e=>e.id),
          bounds:Object.fromEntries(['tux-head','cake'].map(id=>[id,document.getElementById(id).getBoundingClientRect().toJSON()])),
          errors:BDAY.errors,
          duration:BDAY.master.duration()
        }));
        if (camera) assert.deepEqual(state.camera, camera, 'static scene camera drifted');
        camera = state.camera;
        assert.ok(state.filters.every(f=>f==='none'), 'SVG filter was re-enabled');
        assert.deepEqual(state.brightness, [], 'per-frame brightness filter returned');
        assert.deepEqual(state.errors, []);
        assert.equal(state.duration, 48);
        if (time === 19.9) for (const [id,b] of Object.entries(state.bounds)) {
          assert.ok(b.left >= -3 && b.right <= width+3 && b.top >= 0 && b.bottom <= height, `${id} outside ${width}x${height}`);
        }
        const file = `${width}x${height}-${String(time).replace('.','_')}.png`;
        await page.screenshot({path:path.join(out,file)});
        frames.push({time,file,...state});
      }
      // Check the gate with real time, rather than only jumping its timeline.
      await page.evaluate(() => { ArrisBirthday.replay(); BDAY.seek(26.1); BDAY.play(); });
      await page.waitForFunction(() => ArrisBirthday.state().waiting);
      await page.keyboard.press('Space');
      await page.locator('#stage').click({position:{x:10,y:10}});
      assert.equal(await page.evaluate(() => BDAY.time()), 26.2);
      assert.equal(await page.evaluate(() => ArrisBirthday.blow()), true);
      assert.equal(await page.evaluate(() => ArrisBirthday.blow()), false);
      await page.evaluate(() => { BDAY.master.time(48,false); });
      assert.ok(await page.evaluate(() => events.includes('DONE')));
      await page.evaluate(() => ArrisBirthday.stop());
      assert.equal(await page.evaluate(() => ArrisBirthday.blow()), false);
      assert.deepEqual(errors, []);
      assert.ok(requests.every(url=>url.startsWith(origin+'/') || url.startsWith('data:')));
      results.push({width,height,frames,errors,gate:true,duplicatePuffRejected:true,disposed:true});
      await page.close();
      console.log(`${width}x${height}: 9 checkpoints, static camera/filter/gate checks passed`);
    }
    fs.writeFileSync(path.join(out,'motion-results.json'),JSON.stringify(results,null,2)+'\n');
  } finally { if (browser) await browser.close(); await new Promise(r=>server.close(r)); }
})().catch(e=>{console.error(e);process.exitCode=1;});
