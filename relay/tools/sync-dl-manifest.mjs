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

fs.writeFileSync(
  path.join(root, 'src/dl-manifest.mjs'),
  '// 由 tools/sync-dl-manifest.mjs 生成；改动了 public/dl/*.apk 之后重跑一次。\n' +
    'export const HOSTED_APK = ' + JSON.stringify(files, null, 2) + ';\n',
);
fs.writeFileSync(path.join(dlDir, 'manifest.json'), JSON.stringify({ files }, null, 2) + '\n');
console.log('hosted apk:', files.length, files.join(', '));
