// 生成「本站托管了哪些 APK」的清单。
//
// 为什么需要它：`/api/releases` 原本对每个 GitHub 资产发一次 HEAD 去确认文件是否存在，
// 90 个 release × 平均 1.5 个 APK ≈ 135 次 subrequest —— 又慢又容易撞到 Workers
// 的 subrequest 上限（网络一抖动就变成「拉取失败」）。改成部署时生成清单，
// 运行时零 subrequest。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dlDir = path.join(root, 'public/dl');
const files = fs.existsSync(dlDir)
  ? fs.readdirSync(dlDir).filter((f) => f.toLowerCase().endsWith('.apk')).sort()
  : [];

const sizes = {};
for (const f of files) sizes[f] = fs.statSync(path.join(dlDir, f)).size;
fs.writeFileSync(
  path.join(root, 'src/dl-manifest.mjs'),
  '// 由 tools/sync-dl-manifest.mjs 生成；改动了 public/dl/*.apk 之后重跑一次。\n' +
    'export const HOSTED_APK = ' + JSON.stringify(files, null, 2) + ';\n' +
    '/** 文件名 → 字节数。Worker 里实现 Range 时需要总长，而 ASSETS 的响应既没有\n' +
    ' * content-length 也不认 Range，只能靠部署时算好。 */\n' +
    'export const DL_SIZE = ' + JSON.stringify(sizes, null, 2) + ';\n',
);
fs.writeFileSync(path.join(dlDir, 'manifest.json'), JSON.stringify({ files }, null, 2) + '\n');

// 别名 → (tag, 资产名)：给 Worker 的「国内 302 到 Gitee」用。
// 之前那版在请求里现场打 GitHub/i自调 /api/releases，撞限流就**静默不跳转**（表现为"别名还是慢"）。
// 部署时算好、运行时零 subrequest，行为才是确定的。
const aliases = {};
const pick = (file) => files.find((f) => f === file);
const versioned = files.filter((f) => /^Arris-v\d/.test(f));
const parse = (f) => {
  const m = /^Arris-(v\d+\.\d+\.\d+)-(lts-)?gpl\.apk$/.exec(f);
  return m ? { tag: `${m[1]}-gpl`, lts: Boolean(m[2]) } : null;
};
const mainline = versioned.map((f) => ({ f, ...parse(f) })).filter((x) => x.tag && !x.lts)
  .sort((a, b) => a.tag.localeCompare(b.tag, undefined, { numeric: true }));
const lts = versioned.map((f) => ({ f, ...parse(f) })).filter((x) => x.tag && x.lts)
  .sort((a, b) => a.tag.localeCompare(b.tag, undefined, { numeric: true }));
if (mainline.length && pick('latest.apk')) aliases['latest.apk'] = mainline[mainline.length - 1];
if (lts.length && pick('lts-latest.apk')) aliases['lts-latest.apk'] = lts[lts.length - 1];
fs.writeFileSync(
  path.join(root, 'src/alias-tags.mjs'),
  '// 由 tools/sync-dl-manifest.mjs 生成：别名 → (tag, 资产名)，供 Worker 做国内 302。\n' +
    'export const ALIAS_TAGS = ' + JSON.stringify(aliases, null, 2) + ';\n',
);
console.log('alias-tags:', Object.entries(aliases).map(([k, v]) => `${k}→${v.tag}/${v.f}`).join(', ') || '(none)');
console.log('hosted apk:', files.length, files.join(', '));
