const {chromium}=require(process.argv[2]);
const fs=require('fs');
(async()=>{const b=await chromium.launch();const p=await b.newPage();
for (const [svg,w,h,out] of JSON.parse(process.argv[3])){
  await p.setViewportSize({width:w,height:h});
  await p.setContent(`<html><body style="margin:0;background:transparent">${fs.readFileSync(svg,'utf8').replace(/width="\d+" height="\d+"/,`width="${w}" height="${h}"`)}</body></html>`);
  await p.screenshot({path:out,omitBackground:true,clip:{x:0,y:0,width:w,height:h}});
}
await b.close();})();
