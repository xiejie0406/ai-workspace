import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = process.argv[2] && resolve(process.argv[2]);
const origin = process.argv[3];
if (!output || !origin)
    throw new Error('用法：node scripts/prepare-test.mjs <独立输出目录> <http://127.0.0.1:端口>');
const url = new URL(origin);
if (url.hostname !== '127.0.0.1' || url.protocol !== 'http:' || url.origin !== origin)
    throw new Error('只接受明确的 loopback 测试 origin');
if (output === root || root.startsWith(output + '/'))
    throw new Error('输出必须为独立目录');
await mkdir(output, { recursive: true });
for (const file of ['manifest.json', 'config.js', 'background.js', 'parser.js', 'resources.js', 'spool.js', 'picker.js', 'sections.js', 'page-watch.js', 'panel.html', 'panel.js', 'panel.css'])
    await cp(resolve(root, file), resolve(output, file));
const manifest = JSON.parse(await readFile(resolve(output, 'manifest.json'), 'utf8'));
manifest.name = 'Aden 商品采集合成测试';
manifest.host_permissions = [`${origin}/*`];
await writeFile(resolve(output, 'manifest.json'), JSON.stringify(manifest, null, 2));
const config = await readFile(resolve(output, 'config.js'), 'utf8');
await writeFile(resolve(output, 'config.js'), config.replace('fixtureOrigin: null', `fixtureOrigin: ${JSON.stringify(origin)}`));
console.log(output);
