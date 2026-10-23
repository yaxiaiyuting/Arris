const { chromium } = require(process.env.ARRIS_PLAYWRIGHT_MODULE || 'playwright');
const http=require('http'),fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../app/src/main/assets/birthday');
const out=path.resolve(process.argv[2] || '/tmp/arris-celebration-qa');
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const server=http.createServer((req,res)=>{ const p=path.join(root,new URL(req.url,'http://localhost').pathname); try{res.setHeader('Content-Type',({'.html':'text/html','.css':'text/css','.js':'application/javascript','.png':'image/png'})[path.extname(p)]||'application/octet-stream');res.end(fs.readFileSync(p));}catch(e){res.statusCode=404;res.end();}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const results=[];
 try {
 for(const [width,height] of [[360,800],[412,915],[320,568],[320,480],[800,360],[915,412],[640,320],[1280,720]]){
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{window.events=[];window.ArrisHost={event:s=>window.events.push(s)}});
  await page.goto(`http://127.0.0.1:${server.address().port}/index.html?app=1&still=1&t=0&clean=1&motion=1`);
  await page.waitForFunction(()=>window.BDAY?.ready&&window.ArrisBirthday);
  await page.evaluate(()=>{ArrisBirthday.configure({title:'生日快乐。',wishes:['愿你听见的每一首歌，都恰好回应心情。','愿所有的热爱，都能慢慢长成日常。','新的一岁，继续自在发光。'],continueLabel:'继续听歌',replayLabel:'再看一遍'});BDAY.pause();BDAY.master.time(26.2,false);});
  if(!await page.evaluate(()=>ArrisBirthday.state().waiting))throw Error('waiting gate');
  await page.evaluate(()=>{ArrisBirthday.blow();BDAY.pause();BDAY.seek(47.9);BDAY.master.time(48,false);});
  await page.waitForFunction(()=>document.body.classList.contains('arris-done'));
  const geometry=await page.evaluate(()=>{
   const rect=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,bottom:r.bottom,right:r.right}};
   return {text:rect(document.querySelector('.body.last')),actions:rect(document.querySelector('.arris-actions')),buttons:[...document.querySelectorAll('.arris-actions button')].map(rect),duration:BDAY.master.duration(),errors:BDAY.errors};
  });
  for(const b of geometry.buttons)if(b.x<0||b.right>width+.5||b.y<geometry.text.bottom||b.bottom>height-24||b.height<48)throw Error(JSON.stringify({width,height,geometry}));
  await page.screenshot({path:`${out}/birthday-${width}x${height}.png`});
  await page.locator('.arris-actions button:last-child').click();
  if(await page.evaluate(()=>document.body.classList.contains('arris-done')))throw Error('replay did not hide actions');
  await page.evaluate(()=>{BDAY.pause();BDAY.master.time(26.2,false)});
  if(!await page.evaluate(()=>ArrisBirthday.state().waiting))throw Error('replay skipped puff gate');
  await page.evaluate(()=>{ArrisBirthday.blow();BDAY.pause();BDAY.seek(47.9);BDAY.master.time(48,false)});
  await page.locator('.arris-actions button:first-child').click();
  if(!await page.evaluate(()=>events.includes('CLOSE')))throw Error('close bridge not called');
  if(errors.length||geometry.errors.length)throw Error(JSON.stringify(errors));
  results.push({width,height,...geometry,close:true,replay:true,errors});await page.close();
 }
 fs.writeFileSync(`${out}/layout-results.json`,JSON.stringify(results,null,2));console.log(JSON.stringify(results));
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
