// 仅离线渲染本目录 SVG，不启动浏览器、不连接业务服务。
const fs = require('fs');
const path = require('path');
const sharp = require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp');
(async () => {
  const pages=[];
  for(let i=1;i<=6;i++) {
    const source=path.join(__dirname,`演示文稿界面-${i}.svg`);
    const target=path.join(__dirname,`演示文稿界面-${i}.png`);
    await sharp(fs.readFileSync(source)).png().toFile(target);
    pages.push(await sharp(target).resize(720,450).png().toBuffer());
  }
  await sharp({create:{width:1440,height:1350,channels:4,background:'#ffffff'}})
    .composite(pages.map((input,i)=>({input,left:(i%2)*720,top:Math.floor(i/2)*450})))
    .png().toFile(path.join(__dirname,'界面图稿总览.png'));
  console.log('已离线渲染 6 幅 PNG 和总览图。');
})().catch(e=>{console.error(e.message);process.exit(1)});
