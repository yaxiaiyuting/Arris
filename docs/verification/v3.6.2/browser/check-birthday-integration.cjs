const {chromium}=require('/home/duanjb666/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const http=require('node:http');
const ROOT='/home/duanjb666/deepseek/Arris-review-fixes/app/src/main/assets/birthday';
const server=http.createServer((req,res)=>{const file=path.join(ROOT,new URL(req.url,'http://localhost').pathname);try{const data=fs.readFileSync(file);res.setHeader('Content-Type',({'.html':'text/html','.js':'application/javascript','.css':'text/css','.png':'image/png'})[path.extname(file)]||'text/plain');res.end(data)}catch{res.statusCode=404;res.end()}});
const OUT='/home/duanjb666/deepseek/dist/birthday-integration-qa';
fs.mkdirSync(OUT,{recursive:true});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const origin='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({headless:true,args:['--force-color-profile=srgb']});
 const result=[];
 try {
  for(const [w,h] of [[390,844],[360,640],[430,932],[844,390],[1600,900],[320,568]]){
   const page=await browser.newPage({viewport:{width:w,height:h},deviceScaleFactor:1});
   const errors=[],requests=[];
   page.on('pageerror',e=>errors.push(e.message));
   page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});
   page.on('request',r=>requests.push(r.url()));
   await page.addInitScript(()=>{window.events=[];window.ArrisHost={event:x=>events.push(x)};Object.defineProperty(window,'ArrisBirthday',{configurable:true,set(value){delete window.ArrisBirthday;window.ArrisBirthday=value;value.configure({title:'early call',wishes:[]});window.earlyAttempted=true}})});
   await page.goto(origin+'/index.html?app=1&still=1&t=0&clean=1&motion=1');
   await page.waitForFunction(()=>window.BDAY?.ready && window.ArrisBirthday);
   assert.equal(await page.evaluate(()=>BDAY.master.duration()),48);assert.equal(await page.evaluate(()=>window.earlyAttempted),true);
   await page.evaluate(()=>{ArrisBirthday.configure({title:'生日快乐。',wishes:['愿你耳边，常有喜欢的歌。','愿你心里，常有值得期待的事。','新的一岁，按自己的节奏发光。']});BDAY.pause()});
   const frames=[];
   for(const t of [9.8,14.85,19.9,24.5,27.7,31.9,34.9,44.5]){
    await page.evaluate(t=>BDAY.seek(t),t);
    await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
    const bounds=await page.evaluate(()=>Object.fromEntries(['tux-head','cake','blessing'].map(id=>[id,document.getElementById(id).getBoundingClientRect().toJSON()])));
    if(t===19.9){for(const id of ['tux-head','cake']){assert.ok(bounds[id].x>=-3,`${w} ${id} left`);assert.ok(bounds[id].right<=w+3,`${w} ${id} right`)}}
    if(t===44.5 && h>w){assert.ok(bounds.blessing.bottom<h-110,`${w} blessing collides with native buttons: ${bounds.blessing.bottom}`);assert.ok(bounds.blessing.x>=0 && bounds.blessing.right<=w)}
    const file=`${w}x${h}-${String(t).replace('.','_')}.png`;
    await page.screenshot({path:path.join(OUT,file)});
    frames.push({time:t,file,bounds});
   }
   // Real playback crosses the hold point. It must stay there until a puff command.
   await page.evaluate(()=>{ArrisBirthday.replay();BDAY.seek(26.1);BDAY.play()});
   await page.waitForFunction(()=>ArrisBirthday.state().waiting);
   const hold=await page.evaluate(()=>ArrisBirthday.state());
   await page.keyboard.press('Space');
   await page.waitForTimeout(120);
   assert.equal(await page.evaluate(()=>BDAY.time()),26.2);
   assert.equal(await page.evaluate(()=>ArrisBirthday.blow()),true);
   assert.equal(await page.evaluate(()=>ArrisBirthday.blow()),false);
   await page.evaluate(()=>{BDAY.master.time(48,false)});
   assert.ok(await page.evaluate(()=>events.includes('DONE')));
   assert.equal(await page.evaluate(()=>BDAY.master.paused()),true);
   await page.evaluate(()=>ArrisBirthday.replay());
   assert.equal(await page.evaluate(()=>ArrisBirthday.state().blown),false);
   await page.evaluate(()=>ArrisBirthday.stop());
   assert.equal(await page.evaluate(()=>ArrisBirthday.state().disposed),true);
   assert.equal(await page.evaluate(()=>ArrisBirthday.blow()),false);
   assert.deepEqual(errors,[]);
   assert.ok(requests.every(x=>x.startsWith(origin+'/')||x.startsWith('data:')));
   result.push({width:w,height:h,frames,hold,errors,requests:requests.length,flow:'hold/puff/duplicate-puff/done/replay/dispose passed'});
   await page.close();
   fs.writeFileSync(path.join(OUT,'summary.json'),JSON.stringify(result,null,2));
   console.log(`${w}x${h}: 8 frames, no console errors, puff gate and lifecycle commands passed`);
  }
  const locales=JSON.parse(fs.readFileSync('/home/duanjb666/deepseek/dist/birthday-locales.json'));
  for(const [w,h] of [[360,640],[390,844]]) for(let i=0;i<locales.length;i++){
   const page=await browser.newPage({viewport:{width:w,height:h}});
   await page.goto(origin+'/index.html?app=1&still=1&t=0&clean=1&motion=1');
   await page.waitForFunction(()=>window.BDAY?.ready && window.ArrisBirthday);
   await page.evaluate(config=>{ArrisBirthday.configure(config);BDAY.pause();BDAY.seek(44.5)},locales[i]);
   const geometry=await page.evaluate(()=>({box:document.querySelector('#blessing').getBoundingClientRect().toJSON(),spans:Array.from(document.querySelectorAll('#blessing .line span')).map(x=>x.getBoundingClientRect().toJSON())}));
   assert.ok(geometry.box.bottom<h-110,`locale ${i} ${w} footer ${geometry.box.bottom}`);
   for(const box of geometry.spans) assert.ok(box.x>=-1 && box.right<=w+1,`locale ${i} ${w} line overflows`);
   await page.screenshot({path:path.join(OUT,`${w}x${h}-locale-${i}.png`)});
   await page.close();
  }
  console.log('14 localized portrait layouts passed');
 } finally {await browser.close();server.close()}
})().catch(e=>{console.error(e);process.exit(1)});
