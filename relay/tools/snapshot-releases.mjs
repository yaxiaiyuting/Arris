// 抓一份 GitHub Releases 快照，随静态资源一起部署。
//
// 为什么需要：`/api/releases` 是 Worker 现取 GitHub。GitHub 匿名 API 的限额是
// **60 次/小时/出口 IP**，而 Cloudflare 的边缘出口 IP 是共享的 —— 一旦被限流，
// 我们的接口就 502，客户端再回落到直连 api.github.com（裸连必然失败），
// 结果就是「检查更新失败」。
//
// 有了快照，Worker 取不到上游时可以直接把这份静态文件吐出去：
// 数据可能晚几小时，但「能检查到更新」这件事不再依赖 GitHub 的可用性。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outDir = path.join(root, 'public/api-fallback');
const outFile = path.join(outDir, 'releases.json');

// 用 `gh api`（已认证，5000 次/小时）而不是匿名 fetch —— 匿名限额 60 次/小时/出口 IP，
// 共享 IP 上几乎拿不到。
import { execFileSync } from 'node:child_process';

let raw;
try {
  const out = execFileSync('gh', ['api', '--paginate', 'repos/yaxiaiyuting/Arris/releases?per_page=100'], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    env: { ...process.env, http_proxy: process.env.http_proxy || 'http://127.0.0.1:7890', https_proxy: process.env.https_proxy || 'http://127.0.0.1:7890' },
  });
  raw = JSON.parse(out);
} catch (e) {
  console.error('snapshot failed:', String(e.message || e).split('\n')[0]);
  process.exit(1);
}
const list = raw.filter((r) => r && !r.draft).map((r) => ({
  tag_name: r.tag_name,
  name: r.name,
  draft: false,
  prerelease: !!r.prerelease,
  published_at: r.published_at,
  html_url: r.html_url,
  body: typeof r.body === 'string' ? r.body.slice(0, 4000) : '',
  assets: (r.assets || [])
    .filter((a) => a && /\.apk$/i.test(a.name || ''))
    .map((a) => ({ id: a.id, name: a.name, size: a.size, browser_download_url: '' })),
}));
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(outFile, JSON.stringify({ generatedAt: new Date().toISOString(), releases: list }, null, 1) + '\n');
// 客户端兜底用的纯数组（应用只会解析数组，见 ReleaseFeed.evaluateReleases）
fs.writeFileSync(
  path.join(outDir, 'releases-array.json'),
  JSON.stringify(list) + '\n',
);
console.log('snapshot:', list.length, 'releases →', path.relative(root, outFile));
