/**
 * Arris 一起听 · 中继服务（Cloudflare Worker + Durable Object）
 *
 * 职责只有一件：**把一行的消息转给同房间的其他人**。
 * 不存账号、不碰音频、不做播放裁决 —— 房主才是权威（客户端 RoomEngine 负责）。
 *
 * 为什么用 Durable Object 而不是普通 Worker：
 * 普通 Worker 实例是无状态、会被随时回收的，WebSocket 连接必须挂在一个
 * **唯一且长期存在的对象**上；一个房间 = 一个 DO 实例，天然满足"同房间同一处连接集合"。
 *
 * 计费要点（新手最容易踩）：
 * - 建立一个 WebSocket 连接 = 1 次请求；
 * - **发出去的消息不计费**，只有**进来的**消息计费，且按 **20:1** 折算（100 条 = 5 次请求）；
 * - 用 Hibernation API（`state.acceptWebSocket`）后，房间没人说话时**不产生时长费用**。
 */

import { HOSTED_APK, DL_SIZE } from "./dl-manifest.mjs";
import { ALIAS_TAGS } from "./alias-tags.mjs";
import { serveRangedAsset } from "./asset-range.mjs";
import { serveStudioAudio } from "./studio-audio.mjs";

const MAX_MEMBERS = 16;

/** 本站托管的 APK 名单与尺寸（部署时由 tools/sync-dl-manifest.mjs 生成）。 */
const HOSTED = new Set(HOSTED_APK);

const BUILD_TAG = "4.0.4";


// 反馈节流：每个出口 IP 每小时 5 条，够用且挡住脚本刷。
// 用 isolate 内存表即可 —— 反馈是低频人工动作，丢一次限流不算故障。
const feedbackHits = new Map();

/** 小工具：统一 JSON 响应（feedback 路由用；其它路由沿用既有写法）。 */
function jsonResponse(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function allowFeedback(ip) {
  const now = Date.now();
  const win = 60 * 60 * 1000;
  const list = (feedbackHits.get(ip) || []).filter((t) => now - t < win);
  if (list.length >= 5) {
    feedbackHits.set(ip, list);
    return false;
  }
  list.push(now);
  feedbackHits.set(ip, list);
  if (feedbackHits.size > 5000) feedbackHits.clear();
  return true;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/assets/after-the-rain.mp3" &&
        (request.method === "GET" || request.method === "HEAD")) {
      return serveStudioAudio(request, env.ASSETS);
    }
    // 首页：项目介绍（静态 HTML 打包在 Worker 里，域名一绑即可访问）
    if (url.pathname === "/" || url.pathname === "/index.html") {
      return new Response(INDEX_HTML, {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-cache, must-revalidate" },
      });
    }
    if (url.pathname.startsWith("/get/") || url.pathname.startsWith("/dl/")) {
      const name = url.pathname.slice(url.pathname.indexOf("/", 1) + 1);

      // 国内直连 302 到 Gitee（4.0.15）。
      //
      // 为什么：探针实测中国移动家宽到本站被 CF 免费版路由到 **SEA（西雅图）**，APK 只有
      // 40~50 KB/s；Gitee 是国内 CDN，通常 1~5 MB/s。把大陆用户直接甩给 Gitee 是最省的优化
      // ——不花钱、不改客户端、立刻生效。
      //
      // 几个刻意的取舍：
      //   · 只对 `get/` 生效，`dl/` 保留静态直出（方便排查、也给脚本用）
      //   · 只对能推出 tag 的 APK 生效：`Arris-vX.Y.Z-gpl.apk` / `Arris-vX.Y.Z-lts-gpl.apk`
      //   · 别名（latest.apk / lts-latest.apk）不跳转 —— 它们指向"当前版本"，跳过去容易拿到旧包
      //   · `?geo=CN` 用于本地验证（CF 的 cf-ipcountry 外部无法伪造）
      if (url.pathname.startsWith("/get/") && env.GITEE_REDIRECT !== "0") {
        const country = (url.searchParams.get("geo") || request.headers.get("cf-ipcountry") || "").toUpperCase();
        if (country === "CN") {
          // ① 版本化文件名：直接拼 Gitee 地址
          const m = /^Arris-(v\d+\.\d+\.\d+)-(lts-)?gpl\.apk$/.exec(name);
          let target = m
            ? `https://gitee.com/yaxiaiyuting/arris/releases/download/${m[1]}-gpl/${name}`
            : null;
          // ② 别名（latest.apk / lts-latest.apk）：用部署时生成的 `alias-tags.mjs`，
          //    运行时零 subrequest —— 之前现场查 GitHub/自调 /api/releases，撞限流就静默不跳转。
          if (!target) {
            const a = ALIAS_TAGS[name];
            if (a && a.tag && a.f) {
              target = `https://gitee.com/yaxiaiyuting/arris/releases/download/${a.tag}/${a.f}`;
            }
          }
          if (target) {
            return new Response(null, {
              status: 302,
              headers: {
                location: target,
                // 短缓存：万一 Gitee 上没有这个资产，别让客户端记住一次坏跳转
                "cache-control": "public, max-age=60",
                "x-arris-mirror": "gitee",
              },
            });
          }
        }
      }

      const assetUrl = new URL("/dl/" + name, url.origin);
      const size = HOSTED.has(name) ? DL_SIZE[name] : undefined;
      const rolling = name === "latest.apk" || name === "lts-latest.apk";
      return serveRangedAsset(request, env.ASSETS, assetUrl, {
        size,
        // 别名（latest.apk / lts-latest.apk）原来写 no-store —— 每次下载都要重新回源，
        // 国内线路上这一跳就是"忽快忽慢"的主要来源。改成 5 分钟可缓存：
        // 版本更新最多晚 5 分钟生效，但边缘能命中、浏览器也能复用。
        cacheControl: rolling ? "public, max-age=300" : size ? "public, max-age=31536000, immutable" : undefined,
      });
    }


    // ---------------------------------------------------------------- 反馈（4.0.11）
    //
    // 客户端不发令牌，也不直连 Gitee —— 令牌在服务端（wrangler secret GITEE_FEEDBACK_TOKEN），
    // 这里只做"校验 + 节流 + 建 issue"。选 Gitee 是因为国内可达，反馈不该依赖能不能连 GitHub。
    if (url.pathname === "/api/feedback") {
      if (request.method !== "POST") return new Response("method not allowed", { status: 405 });
      const ip = request.headers.get("cf-connecting-ip") || "unknown";
      if (!allowFeedback(ip)) {
        return jsonResponse({ error: "too many requests" }, 429);
      }
      let payload;
      try {
        payload = await request.json();
      } catch {
        return jsonResponse({ error: "invalid json" }, 400);
      }
      const message = String(payload?.message ?? "").trim();
      if (message.length < 5) return jsonResponse({ error: "message too short" }, 400);
      const token = env.GITEE_FEEDBACK_TOKEN;
      if (!token) return jsonResponse({ error: "feedback not configured" }, 503);

      const title = String(payload?.title ?? "").trim() || "用户反馈";
      const contact = String(payload?.contact ?? "").trim();
      const body = [
        "### " + title,
        "",
        message,
        "",
        contact ? "**联系方式**：" + contact : "",
        "",
        "| | |",
        "|---|---|",
        "| 版本 | " + String(payload?.app ?? "") + " (" + String(payload?.code ?? "") + ") |",
        "| 系统 | " + String(payload?.android ?? "") + " |",
        "| 设备 | " + String(payload?.device ?? "") + " |",
        "| 语言 / 主题 | " + String(payload?.language ?? "") + " / " + String(payload?.theme ?? "") + " |",
        "| 音质档 | " + String(payload?.quality ?? "") + " |",
        "| 登录 | ncm=" + String(payload?.ncm ?? "") + " qm=" + String(payload?.qq ?? "") + " |",
        payload?.extra ? "\n补充：" + String(payload.extra) : "",
        "",
        "_由客户端「发送反馈」自动创建_",
      ].filter(Boolean).join("\n");

      // 落到 Gitee 仓库的 feedback/ 目录（一条一个 markdown 文件）。
      //
      // 为什么不是 issue：Gitee 的建 issue 接口要 `issues` 授权，我们的服务端令牌只有 `projects`；
      // 用「写文件」这条路径零额外授权就能通，而且一条反馈一个文件，翻起来比 issue 列表更清楚。
      // 想升级成 issue：给令牌补 `issues` 权限后把这里换成 /issues 端点即可。
      const now = new Date();
      const stamp = now.toISOString().replace(/[-:T]/g, "").slice(0, 14);
      const rand = Math.random().toString(36).slice(2, 8);
      const path = `feedback/${stamp}-${rand}.md`;
      const content = btoa(unescape(encodeURIComponent(body)));
      const res = await fetch(
        `https://gitee.com/api/v5/repos/yaxiaiyuting/arris-feedback/contents/${path}`,
        {
          method: "POST",
          headers: { "content-type": "application/json;charset=UTF-8" },
          body: JSON.stringify({
            access_token: token,
            content,
            message: `feedback: ${title.slice(0, 50)}`,
          }),
        },
      );
      if (!res.ok) {
        return jsonResponse({ error: "upstream " + res.status }, 502);
      }
      const saved = await res.json();
      return jsonResponse({ ok: true, issue: saved?.content?.html_url || "" }, 200);
    }

    // ---------------------------------------------------------------- 更新检查（不经 GitHub）
    if (url.pathname === "/api/releases" || url.pathname === "/api/latest") {
      // 三级取数，任何一级成功都能出结果：
      //   1. 边缘缓存（caches.default，10 分钟）—— 绝大多数请求走这里，不打 GitHub；
      //   2. 现取 GitHub（服务端没有墙，但**匿名限额 60 次/小时/出口 IP**，共享 IP 常被限流）；
      //   3. 部署时生成的静态快照 `public/api-fallback/releases.json` —— GitHub 挂了也能检查更新。
      const cacheKey = new URL("/api/releases?feed=4.0.4", url.origin).toString();
      const cache = caches.default;
      const bypass = url.searchParams.get("nocache") === "1";
      let list = null;
      let source = "cache";

      if (!bypass) {
        const cached = await cache.match(cacheKey);
        if (cached) {
          list = (await cached.json()).releases ?? null;
        }
      }

      if (!Array.isArray(list)) {
        source = "github";
        try {
          const gh = await fetch(
            "https://api.github.com/repos/yaxiaiyuting/Arris/releases?per_page=100",
            {
              headers: { "user-agent": "Arris-Relay/1.0", accept: "application/vnd.github+json" },
              // 4.0.11：这里原本又叠了一层 5 分钟的 CF 缓存，导致「刚发布的新版在 /api/latest
              // 里看不到、要再等 5 分钟」。发布是低频动作、GitHub 那边本来就有自己的 CDN，
              // 去掉这层；worker 自己的 caches.default 保留（可用 ?nocache=1 绕过）。
              cf: { cacheTtl: 0 },
            },
          );
          if (gh.ok) {
            const raw = await gh.json();
            if (Array.isArray(raw)) list = raw.filter((r) => r && !r.draft);
          }
        } catch (_) {}
      }

      if (!Array.isArray(list)) {
        source = "snapshot";
        try {
          const snap = await env.ASSETS.fetch(new URL("/api-fallback/releases.json", url.origin).toString());
          if (snap.ok) {
            const body = await snap.json();
            if (Array.isArray(body?.releases)) list = body.releases;
          }
        } catch (_) {}
      }

      if (!Array.isArray(list)) {
        return Response.json({ error: "no_source" }, { status: 503 });
      }

      // v3.9.0：把响应压小。手机裸连到 CF 的 RTT 实测 560ms 且丢包，
      // 原来 500KB 的 JSON（90 个 release 各带最多 4KB 更新说明）在 12s 预算里经常拿不完
      // —— 表现就是「裸连检查更新失败」。更新检查真正只需要 tag / 时间 / APK 资产；
      // 更新说明只保留最近 5 条（够展示「发现新版本」时的那一份）。
      const BODY_KEEP = 5;
      const releases = list.map((r, index) => ({
        tag_name: r.tag_name,
        name: r.name,
        draft: !!r.draft,
        prerelease: !!r.prerelease,
        published_at: r.published_at,
        html_url: r.html_url,
        // 国内裸连打不开 GitHub：浏览器入口默认指向官网，GitHub 只作数据来源与兜底。
        site_url: `${url.origin}/#download`,
        body: (index < BODY_KEEP || r.tag_name === "lts-3.7") && typeof r.body === "string" ? r.body.slice(0, 4000) : "",
        assets: (r.assets || [])
          .filter((a) => a && /\.apk$/i.test(a.name || ""))
          .map((a) => ({
            id: a.id,
            name: a.name,
            size: a.size,
            // 指向 /get/（Range 可用），不是 /dl/：静态直出不支持 206，多线程与续传都会退化。
            browser_download_url: HOSTED.has(a.name)
              ? `${url.origin}/get/${encodeURIComponent(a.name)}`
              : a.browser_download_url || `${url.origin}/get/${encodeURIComponent(a.name)}`,
          })),
      }));

      const headers = {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "public, max-age=120",
        "access-control-allow-origin": "*",
        "x-arris-source": source,
      };

      if (url.pathname === "/api/latest") {
        const latest = releases.find((r) => !r.prerelease && /^v\d+\.\d+/i.test(r.tag_name) && !/^v3\.7\./i.test(r.tag_name) && r.assets.length) || null;
        const apk = latest?.assets?.find((a) => /\.apk$/i.test(a.name || "")) || null;
        return new Response(JSON.stringify({
          version: latest?.tag_name || null,
          name: latest?.name || null,
          publishedAt: latest?.published_at || null,
          pageUrl: latest?.site_url || `${url.origin}/#download`,
          body: latest?.body || null,
          apk: apk ? { id: apk.id, name: apk.name, size: apk.size, url: apk.browser_download_url } : null,
        }), { headers });
      }

      const body = JSON.stringify(releases);
      if (source !== "cache") {
        // 回填边缘缓存（不阻塞响应）
        try {
          await cache.put(
            cacheKey,
            new Response(JSON.stringify({ releases }), {
              headers: { "content-type": "application/json", "cache-control": "public, max-age=120" },
            }),
          );
        } catch (_) {}
      }
      return new Response(body, { headers });
    }

    if (url.pathname === "/health" && url.searchParams.get("h") === "1") {
      return Response.json({ method: request.method, headers: Object.fromEntries(request.headers) });
    }
    if (url.pathname === "/health") {
      return new Response("ok", { headers: { "content-type": "text/plain" } });
    }
    // GET /rooms —— 扫描当前活跃房间（App 里的"自动扫描"就靠它）
    if (url.pathname === "/rooms" && request.method === "GET") {
      const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("global"));
      return dir.fetch("https://directory/list");
    }
    // /room/<roomId>?role=host|member&name=昵称
    const m = url.pathname.match(/^\/room\/([a-f0-9]{24})$/);
    // 其余路径交给静态资源（manifest.json 等）；不存在时由资产层自然 404。
    if (!m) return env.ASSETS.fetch(request);
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const roomId = m[1];
    const stub = env.ROOM.get(env.ROOM.idFromName(roomId));
    return stub.fetch(request);
  },
};

/**
 * 房间目录：记录「哪些房间现在有人」，给 App 的"扫描房间"用。
 *
 * 为什么要单独一个 DO：房间本身是一个个独立 DO，跨房间的枚举必须有一个单点。
 * 用 alarm 定期清理过期条目（超过 90 秒没有心跳的房间视为已关闭）。
 */
export class Directory {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/list") {
      const rooms = (await this.state.storage.get("rooms")) || {};
      const now = Date.now();
      const alive = Object.entries(rooms)
        .filter(([, r]) => now - r.seenAt < 90_000)
        .map(([id, r]) => ({ roomId: id, host: r.host, members: r.members, title: r.title, seenAt: r.seenAt }));
      return Response.json({ rooms: alive });
    }
    if (url.pathname === "/beat") {
      const body = await request.json();
      const rooms = (await this.state.storage.get("rooms")) || {};
      rooms[body.roomId] = {
        session: body.session,
        host: body.host || "",
        members: body.members || 0,
        title: (body.title || "").slice(0, 40),
        seenAt: Date.now(),
      };
      await this.state.storage.put("rooms", rooms);
      // 保证有清理定时器
      if (!(await this.state.storage.getAlarm())) {
        await this.state.storage.setAlarm(Date.now() + 60_000);
      }
      return Response.json({ ok: true });
    }
    if (url.pathname === "/drop") {
      const id = url.searchParams.get("roomId");
      const rooms = (await this.state.storage.get("rooms")) || {};
      if (id && rooms[id]?.session === url.searchParams.get("session")) {
        delete rooms[id];
        await this.state.storage.put("rooms", rooms);
      }
      return Response.json({ ok: true });
    }
    return new Response("not found", { status: 404 });
  }

  async alarm() {
    const rooms = (await this.state.storage.get("rooms")) || {};
    const now = Date.now();
    let changed = false;
    for (const [id, r] of Object.entries(rooms)) {
      if (now - r.seenAt > 90_000) { delete rooms[id]; changed = true; }
    }
    if (changed) await this.state.storage.put("rooms", rooms);
    if (Object.keys(rooms).length > 0) await this.state.storage.setAlarm(now + 60_000);
  }
}

export class Room {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.directoryTail = Promise.resolve();
  }

  sockets(session) {
    return this.state.getWebSockets().filter(ws => {
      const meta = ws.deserializeAttachment();
      return meta?.session && !meta.closed && (!session || meta.session === session);
    });
  }

  host(sockets) {
    return sockets.find(ws => ws.deserializeAttachment().role === "host");
  }

  async fetch(request) {
    const url = new URL(request.url);
    const roomId = url.pathname.split("/").pop();
    const wantsHost = url.searchParams.get("role") === "host";
    if (wantsHost) {
      const secret = request.headers.get("Authorization")?.match(/^Bearer ([0-9a-f]{32})$/)?.[1];
      if (!secret || !/^[a-f0-9]{32}$/.test(secret)) return new Response("host key required", { status: 403 });
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
      const code = [...new Uint8Array(digest).slice(0, 12)].map(b => b.toString(16).padStart(2, "0")).join("");
      if (code !== roomId) return new Response("wrong host key", { status: 403 });
    }
    const host = this.host(this.sockets());
    if (wantsHost && host) return new Response("room already hosted", { status: 409 });
    if (!wantsHost && !host) return new Response("no host", { status: 404 });
    const session = host?.deserializeAttachment().session || crypto.randomUUID();
    const members = this.sockets(session);
    if (members.length >= MAX_MEMBERS) return new Response("room full", { status: 429 });

    const [client, server] = Object.values(new WebSocketPair());
    const role = wantsHost ? "host" : "member";
    const name = (url.searchParams.get("name") || "").slice(0, 24);
    this.state.acceptWebSocket(server, [role]);
    server.serializeAttachment({ role, name, roomId, session });
    const joined = JSON.stringify({ t: "presence", role, name, members: members.length + 1 });
    for (const ws of [...members, server]) this.send(ws, joined);
    if (host) this.send(server, JSON.stringify({
      t: "presence", role: "host", name: host.deserializeAttachment().name, members: members.length + 1,
    }));
    await this.beat(session);
    return new Response(null, { status: 101, webSocket: client });
  }

  send(socket, message) {
    try { socket.send(message); } catch (_) {}
  }

  directory(url, options) {
    const send = () => {
      const dir = this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("global"));
      return dir.fetch(url, options);
    };
    this.directoryTail = this.directoryTail.then(send).catch(() => {});
    return this.directoryTail;
  }

  async beat(session, title = "") {
    const sockets = this.sockets(session);
    const host = this.host(sockets)?.deserializeAttachment();
    if (!host) return;
    await this.directory("https://directory/beat", {
      method: "POST",
      body: JSON.stringify({ roomId: host.roomId, session, host: host.name, members: sockets.length, title }),
    });
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > 65_536) return;
    const meta = ws.deserializeAttachment();
    if (!meta?.session || meta.closed) return;
    const sockets = this.sockets(meta.session);
    const host = this.host(sockets);
    if (!host || !sockets.includes(ws)) return;
    let payload;
    try { payload = JSON.parse(message); } catch (_) { return; }
    if (ws !== host) {
      if (payload?.t === "ping" && Number.isSafeInteger(payload.t0)) this.send(host, message);
      return;
    }
    if (payload?.t !== "snapshot" && payload?.t !== "pong") return;
    for (const peer of sockets) if (peer !== ws) this.send(peer, message);
    if (payload.t === "snapshot") {
      const tracks = payload.state?.queue;
      const current = Array.isArray(tracks) && tracks.find(track => track?.roomTrackId === payload.state.currentRoomTrackId);
      await this.beat(meta.session, typeof current?.title === "string" ? current.title : "");
    }
  }

  async webSocketClose(ws, code, reason) {
    await this.leave(ws, code, reason);
  }

  async webSocketError(ws) {
    await this.leave(ws, 1011, "error");
  }

  async leave(ws, code, reason) {
    const meta = ws.deserializeAttachment();
    if (!meta?.session || meta.closed) return;
    const peers = this.sockets(meta.session).filter(p => p !== ws);
    ws.serializeAttachment({ ...meta, closed: true });
    try { ws.close(code, reason); } catch (_) {}
    if (meta.role === "host") {
      for (const peer of peers) {
        peer.serializeAttachment({ ...peer.deserializeAttachment(), closed: true });
        this.send(peer, JSON.stringify({ t: "closed", reason: "host-left" }));
        try { peer.close(4001, "host-left"); } catch (_) {}
      }
      const params = new URLSearchParams({ roomId: meta.roomId, session: meta.session });
      await this.directory("https://directory/drop?" + params);
    } else {
      const presence = JSON.stringify({ t: "presence", role: meta.role, name: meta.name, members: peers.length, left: true });
      for (const peer of peers) this.send(peer, presence);
      await this.beat(meta.session);
    }
  }
}

// 首页内容由 tools/sync-homepage.mjs 从 public/index.html 生成。
const INDEX_HTML = "<!doctype html>\n<!-- build-marker 20261104-studio-motion -->\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n<meta name=\"theme-color\" content=\"#f6f7f2\">\n<script>\n/* Set the effective palette before the first paint, including when storage is blocked. */\n(() => {\n  const key = 'arris.site.appearance';\n  const media = matchMedia('(prefers-color-scheme: dark)');\n  const root = document.documentElement;\n  const tint = document.querySelector('meta[name=\"theme-color\"]');\n  let choice = 'system';\n  try {\n    const saved = localStorage.getItem(key);\n    if (saved === 'light' || saved === 'dark') choice = saved;\n  } catch {}\n  function apply() {\n    const dark = choice === 'dark' || (choice === 'system' && media.matches);\n    root.dataset.theme = dark ? 'dark' : 'light';\n    tint.content = dark ? '#101a16' : '#f6f7f2';\n  }\n  apply();\n  if (media.addEventListener) media.addEventListener('change', apply);\n  else media.addListener(apply);\n  document.addEventListener('DOMContentLoaded', () => {\n    const select = document.getElementById('appearance-mode');\n    select.value = choice;\n    select.disabled = false;\n    select.addEventListener('change', () => {\n      choice = select.value;\n      try {\n        if (choice === 'system') localStorage.removeItem(key);\n        else localStorage.setItem(key, choice);\n      } catch {}\n      apply();\n    });\n  });\n})();\n</script>\n<title>Arris — 喜欢的音乐，都在这里。</title>\n<meta name=\"description\" content=\"Arris，开源 Android 音乐播放器。网易云与 QQ 音乐双源、双语逐字歌词、听歌识曲，与朋友一起听。找到你的下一首喜欢。\">\n<meta property=\"og:title\" content=\"Arris — 喜欢的音乐，都在这里。\"><meta property=\"og:description\" content=\"双音源、逐字歌词、听歌识曲、一起听。一个好好听歌的开源 Android 客户端。\"><meta property=\"og:type\" content=\"website\"><meta property=\"og:url\" content=\"https://arris.de5.net/\">\n<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 40'%3E%3Crect width='40' height='40' rx='12' fill='%23184032'/%3E%3Cpath d='M12 27V14l16-3v13M12 23c-8-1-8 8-1 7M28 20c-8-1-8 8-1 7' stroke='%23d7edaa' stroke-width='3' fill='none'/%3E%3C/svg%3E\">\n<style>\n:root{--paper:#f6f7f2;--white:#fff;--ink:#173c2e;--muted:#66736a;--green:#286345;--lime:#d7eaa5;--border:#dfe4da;--serif:Georgia,'Noto Serif CJK SC',serif;--sans:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI','Noto Sans CJK SC','PingFang SC','Microsoft YaHei',sans-serif}\n*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:100px}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.7 var(--sans);-webkit-font-smoothing:antialiased}a{color:inherit;text-decoration:none}button{font:inherit;color:inherit;cursor:pointer}button,a{-webkit-tap-highlight-color:transparent}button:disabled{cursor:wait;opacity:.65}button:focus-visible,a:focus-visible,summary:focus-visible{outline:3px solid #5c9658;outline-offset:5px}button{border:0}img,svg{display:block}::selection{background:#d7eaa5;color:#173c2e}h1,h2,h3,p{margin:0}h1,h2,h3{text-wrap:balance}p{text-wrap:pretty}.icon{width:21px;height:21px;flex:none}.svg-defs{position:absolute;width:0;height:0;overflow:hidden}.shell{width:min(1180px,calc(100% - 96px));margin-inline:auto}.skip-link{position:absolute;top:-60px;left:20px;z-index:99;padding:10px 20px;background:var(--ink);color:#fff}.skip-link:focus{top:10px}.site-header{position:sticky;top:0;z-index:20;background:rgba(246,247,242,.93);backdrop-filter:blur(18px);border-bottom:1px solid rgba(23,60,46,.07)}.nav{height:85px;display:flex;align-items:center;justify-content:space-between;gap:24px}.brand{display:flex;align-items:center;gap:10px;font-size:27px;font-weight:750;letter-spacing:-1px}.brand-mark{width:39px;height:39px;border-radius:12px;background:var(--ink);overflow:hidden;padding:3px}.brand-mark svg{width:100%;height:100%}.nav-links{display:flex;align-items:center;gap:32px;font-size:13px;font-weight:550}.nav-links>a:not(.nav-download){color:#65736a}.nav-links>a:hover{color:var(--green)}.nav-download{background:var(--ink);color:#fff;padding:10px 19px;border-radius:8px;display:flex;align-items:center;gap:10px}.nav-download .icon{width:15px;height:15px}.menu-toggle{display:none;background:transparent;padding:8px}.hero{display:grid;grid-template-columns:1fr 1fr;align-items:center;gap:38px;padding-block:70px 45px;min-height:700px}.eyebrow{font-size:10px;font-weight:650;letter-spacing:2.2px;display:flex;align-items:center;gap:10px;line-height:1.5}.eyebrow:before{content:'';width:23px;height:1px;background:currentColor}.hero-label{display:inline-flex;align-items:center;gap:8px;font-size:11px;border:1px solid #d5dfd0;border-radius:30px;padding:6px 12px;margin-bottom:26px;color:#54715a;background:#eef2e7}.small-dot{width:6px;height:6px;border-radius:50%;background:#6b984e;flex:none}.hero h1{font-size:clamp(48px,5.25vw,70px);line-height:1.2;font-weight:760;letter-spacing:-3px;margin-bottom:24px}.hero h1 span{color:#60854b}.hero-description{color:var(--muted);font-size:15px;line-height:1.95;max-width:405px}.hero-actions{display:flex;gap:12px;margin-top:32px;flex-wrap:wrap}.button{display:inline-flex;align-items:center;justify-content:center;gap:12px;min-height:50px;padding:12px 23px;font-size:13px;font-weight:650;border-radius:9px;transition:background .2s,transform .2s,border-color .2s}.button:hover{transform:translateY(-2px)}.button-primary{background:var(--ink);color:#fff;box-shadow:0 7px 18px #173c2e12}.button-primary:hover{background:#2c6045}.button-outline{background:transparent;border:1px solid #ccd5c7}.button-outline:hover{border-color:#6d8b62;background:#eef1e8}.button .icon{width:17px;height:17px}.hero-meta{font-size:10px;color:#626f5c;display:flex;gap:14px;margin-top:19px;flex-wrap:wrap}.hero-meta span+span:before{content:'·';margin-right:14px}.hero-visual{height:575px;position:relative;isolation:isolate}.hero-visual:before{content:'';position:absolute;width:420px;height:420px;border-radius:50%;background:#e6ebd9;top:70px;left:calc(50% - 210px);z-index:-2}.orbit{position:absolute;border:1px solid #dce3d1;width:500px;height:500px;border-radius:50%;top:28px;left:calc(50% - 250px);z-index:-3}.orbit:before,.orbit:after{content:'';width:8px;height:8px;border:5px solid var(--paper);box-sizing:content-box;border-radius:50%;background:#91a975;position:absolute;top:45px;left:77px}.orbit:after{width:5px;height:5px;top:auto;left:auto;bottom:59px;right:58px;background:#b7c28f}.phone{width:264px;position:absolute;left:calc(50% - 132px);top:15px;border:6px solid #254332;border-radius:34px;background:#eaf0e2;padding:14px 16px 17px;box-shadow:13px 26px 45px #21442c21,inset 0 0 0 1px #c6d0bc;transform:rotate(-6deg);color:#234734}.phone-top{display:flex;justify-content:space-between;font-size:8px;font-weight:700;padding:1px 2px 17px}.phone-signals{letter-spacing:2px}.phone-heading{display:flex;align-items:center;justify-content:space-between;font-size:9px;margin-bottom:17px}.phone-heading>span{font-size:8px;letter-spacing:2px}.album{height:208px;background:#284e39;position:relative;border-radius:12px;overflow:hidden;display:flex;justify-content:center;align-items:center}.album:before{content:'';width:155px;height:155px;position:absolute;border:1px solid #c2d7a325;border-radius:50%;box-shadow:0 0 0 24px #c2d7a30b,0 0 0 48px #c2d7a307}.album-tux{height:165px;width:155px;position:relative;margin-top:24px}.album-caption{position:absolute;top:12px;left:0;right:0;text-align:center;letter-spacing:3px;font-size:5px;color:#b8c7a3}.track-row{display:flex;align-items:center;justify-content:space-between;margin-top:18px}.track-title{font-size:17px;letter-spacing:-.5px;font-weight:700}.track-artist{font-size:8px;color:#708369;margin-top:2px}.track-row .icon{width:17px;height:17px;color:#60854b}.demo-lyrics{font-size:9px;color:#70856a;margin-top:13px;line-height:1.9}.demo-lyrics strong{color:#41623c;font-weight:650}.waveform{height:29px;width:100%;margin:9px 0 3px;opacity:.8}.demo-progress{height:2px;background:#cdd8c1;border-radius:3px}.demo-progress:before{content:'';display:block;width:37%;height:100%;background:#5c8150}.demo-times{display:flex;justify-content:space-between;margin-top:3px;font-size:6px;color:#738368}.demo-controls{display:flex;align-items:center;justify-content:space-between;margin:13px 9px 0;font-size:17px}.demo-controls>svg{width:15px;height:15px}.demo-play{border-radius:50%;width:43px;height:43px;display:grid;place-items:center;background:#284e39;color:#e8efdb}.demo-play svg{width:15px;height:15px;fill:currentColor}.pause-symbol{display:none}.demo-play[aria-pressed=\"true\"] .play-symbol{display:none}.demo-play[aria-pressed=\"true\"] .pause-symbol{display:block}.phone-bottom{width:62px;height:3px;background:#38574244;border-radius:5px;margin:18px auto 0}.float-chip{position:absolute;z-index:2;display:flex;align-items:center;gap:12px;background:#fffdf7;border:1px solid #f1f0e4;border-radius:12px;padding:14px 17px;box-shadow:0 10px 35px #2a402312;font-size:11px}.float-chip .chip-icon{width:35px;height:35px;display:grid;place-items:center;background:#eff3e6;border-radius:10px;color:#668752}.float-chip strong{display:block;font-size:11px;font-weight:650}.float-chip small{display:block;font-size:8px;color:#809073;margin-top:2px}.chip-lyrics{top:88px;left:-12px;transform:rotate(-3deg)}.chip-together{right:-8px;bottom:88px;transform:rotate(5deg);background:#dcebb9;border-color:#d1e1aa}.avatars{display:flex;padding-left:6px}.avatars span{width:28px;height:28px;border-radius:50%;border:2px solid #dcebb9;display:grid;place-items:center;margin-left:-7px;font-size:9px;font-weight:700;color:#426144;background:#f6e9bb}.avatars span:nth-child(2){background:#a8c39e}.avatars span:nth-child(3){background:#e4ba9e}.visual-caption{position:absolute;bottom:0;left:0;right:0;text-align:center;font-size:8px;color:#67765b;letter-spacing:.7px}.hero-spark{position:absolute;right:54px;top:26px;color:#8ca463;width:29px;height:29px}.source-strip{display:flex;justify-content:space-between;gap:24px;align-items:center;border-block:1px solid var(--border);padding:23px 3px;color:#697666;font-size:11px}.source-label{font-size:9px;letter-spacing:1.8px}.source-name{display:flex;align-items:center;gap:10px;font-weight:600;font-size:14px}.source-symbol{display:grid;place-items:center;border-radius:50%;width:27px;height:27px;font-size:13px;background:#f1e1da;color:#b36f5a;font-weight:650}.source-symbol.qq{background:#e8e8cd;color:#7d844e}.strip-detail{font-size:11px;color:#5e6d56}.section{padding-block:95px}.section-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:30px;margin:22px 0 34px}.section-heading h2,.download-heading h2{font-size:38px;line-height:1.35;letter-spacing:-1.7px;font-weight:700}.section-heading>p{font-size:13px;color:var(--muted);max-width:295px;line-height:1.9}.feature-grid{display:grid;grid-template-columns:1.35fr 1fr;gap:22px}.feature-card{border-radius:20px;padding:29px 31px;overflow:hidden;min-height:345px;position:relative}.lyrics-card{background:#e7eddd}.recognize-card{background:#eee9dc}.feature-label{font-size:9px;letter-spacing:1.2px;color:#526646;display:flex;align-items:center;gap:7px}.feature-label .icon{width:14px;height:14px}.feature-card h3{font-size:22px;letter-spacing:-.7px;margin-top:9px;font-weight:650}.feature-card p{font-size:12px;color:#586750;margin-top:6px}.lyric-example{margin:23px 0 16px;position:relative;padding-left:17px;border-left:2px solid #a8bf8c}.lyric-example>small{font-size:10px;color:#67785b}.lyric-pair{display:flex;flex-direction:column;min-height:72px;justify-content:center;margin-top:4px;gap:3px}.lyric-pair .original{font-family:var(--serif);font-size:25px;line-height:1.25;font-style:italic;color:#416839;order:1}.lyric-pair .translation{font-size:12px;line-height:1.6;color:#7c8e6d;order:2}.lyric-pair[data-primary=\"translation\"] .translation{font-family:var(--sans);font-size:21px;color:#416839;font-weight:600;order:1}.lyric-pair[data-primary=\"translation\"] .original{font-size:14px;color:#7c8e6d;order:2}.segmented{display:inline-flex;gap:3px;background:#dce5cf;padding:3px;border-radius:7px;font-size:9px}.segmented button{padding:5px 12px;background:transparent;border-radius:5px;color:#526448;min-height:30px}.segmented button[aria-pressed=\"true\"]{background:#fbfcf5;color:#3b6035;box-shadow:0 1px 3px #273d2110}.example-note{display:block;font-size:8px;color:#7c8c6e;margin-top:8px}.recognize-graphic{height:172px;position:relative;display:grid;place-items:center}.mic-orbit{border:1px solid #d6cfba;border-radius:50%;width:125px;height:125px;display:grid;place-items:center;position:relative}.mic-orbit:before{content:'';position:absolute;inset:13px;border:1px solid #dcd5c3;border-radius:50%}.mic-disc{width:68px;height:68px;border-radius:50%;background:#d9ca9f;display:grid;place-items:center;color:#766846;z-index:1}.mic-disc .icon{width:27px;height:27px}.sound-bar{position:absolute;display:flex;align-items:center;gap:4px;top:72px}.sound-bar.left{left:9px}.sound-bar.right{right:9px}.sound-bar i{display:block;width:3px;height:13px;background:#bdaf85;border-radius:2px}.sound-bar i:nth-child(2n){height:25px}.sound-bar i:nth-child(3n){height:37px}.recognize-caption{font-size:9px;color:#73664a;text-align:center}.mini-features{display:grid;grid-template-columns:repeat(3,1fr);gap:34px;margin-top:40px}.mini-feature{border-top:1px solid #d8dfcf;padding-top:21px}.mini-feature>.icon{width:22px;height:22px;color:#718c5c;margin-bottom:13px}.mini-feature h3{font-size:14px;font-weight:650}.mini-feature p{font-size:11px;color:#5e6d56;line-height:1.9;margin-top:7px;max-width:295px}.together-section{background:#183f30;color:#f4f4e5;padding-block:75px}.together-grid{display:grid;grid-template-columns:1fr 1fr;gap:85px;align-items:center}.together-copy .eyebrow{color:#a5b68d}.together-copy h2{font-size:42px;font-weight:650;letter-spacing:-1.8px;line-height:1.35;margin:20px 0}.together-copy>p{font-size:12px;line-height:1.95;color:#adbfaa;max-width:345px}.together-art{height:170px;position:relative;width:350px;max-width:100%;margin-top:25px}.disc{position:absolute;width:125px;height:125px;border-radius:50%;background:repeating-radial-gradient(circle at center,#102e23 0px,#102e23 6px,#2d5140 7px,#102e23 8px);border:1px solid #4a6850;top:12px;left:20px;display:grid;place-items:center;box-shadow:0 8px 25px #09251a33}.disc-center{width:44px;height:44px;border-radius:50%;background:#cecc99;display:grid;place-items:center;color:#304d32;font-size:9px;letter-spacing:1px}.disc-center:after{content:'';width:5px;height:5px;border-radius:50%;background:#25402d}.disc.second{left:190px;top:35px;transform:rotate(18deg);width:105px;height:105px}.disc.second .disc-center{background:#a7bc8c;width:37px;height:37px}.disc-link{position:absolute;left:142px;top:62px;width:56px;border-top:1px dashed #8ca17b}.disc-link:before,.disc-link:after{content:'';position:absolute;top:-3px;width:5px;height:5px;background:#aac48e;border-radius:50%}.disc-link:before{left:0}.disc-link:after{right:0}.together-art small{position:absolute;bottom:0;left:20px;font-size:8px;letter-spacing:1px;color:#a1b48d}.connection-panel{background:#224a38;border:1px solid #44634a;border-radius:16px;padding:26px}.connection-tabs{display:flex;border-bottom:1px solid #45634a;gap:20px;margin-bottom:22px}.connection-tabs button{font-size:11px;padding:0 0 12px;color:#a7bba0;background:transparent;position:relative;min-height:36px;white-space:nowrap}.connection-tabs button[aria-selected=\"true\"]{color:#e0edbf}.connection-tabs button[aria-selected=\"true\"]:after{content:'';position:absolute;bottom:-1px;left:0;right:0;height:2px;background:#cfdfaa}.connection-pane[hidden]{display:none}.connection-pane{min-height:166px}.step{display:grid;grid-template-columns:25px 1fr;gap:9px;align-items:start;margin-bottom:17px}.step>span{font-size:9px;color:#b7cb93;letter-spacing:1px;padding-top:3px}.step h3{font-size:12px;font-weight:550}.step p{font-size:10px;color:#a7bba0;margin-top:4px;line-height:1.9}.connection-note{font-size:9px;color:#9db692;border-top:1px solid #45634a;padding-top:16px;margin-top:5px;line-height:1.9}.relay-actions{display:flex;gap:9px;flex-wrap:wrap;margin-top:15px}.relay-button{background:#315a41;border:1px solid #5e7953;color:#dce9c2;border-radius:6px;padding:8px 10px;min-height:36px;font-size:10px;display:flex;align-items:center;gap:7px}.relay-button .icon{width:13px;height:13px}.relay-status{font-size:10px;color:#e0edbf;margin-top:11px;min-height:20px}.relay-address{font-family:ui-monospace,monospace;background:#193b2b;padding:9px 10px;font-size:11px;overflow-wrap:anywhere;border-radius:5px;margin-top:14px;color:#cfdfad}.download-section{padding-block:80px 75px}.download-heading{text-align:center}.download-heading .eyebrow{justify-content:center}.download-heading h2{margin-top:18px}.download-heading>p{color:var(--muted);font-size:12px;margin-top:13px}.versions{display:grid;grid-template-columns:1fr 1fr;max-width:860px;gap:22px;margin:33px auto 0}.version{padding:25px 28px;background:#fffdf7;border:1px solid var(--border);border-radius:15px;display:flex;flex-direction:column}.version-top{display:flex;justify-content:space-between;align-items:center;font-size:9px;color:#5f7054;letter-spacing:1px}.version-badge{font-size:8px;padding:3px 8px;border-radius:12px;background:#edf2e1;color:#65834b;letter-spacing:0}.version h3{font-size:24px;font-weight:650;margin-top:16px;letter-spacing:-.8px}.version p{font-size:11px;color:#5c6c52;margin:8px 0 23px;flex:1}.version .button{font-size:11px;min-height:43px;padding:9px 15px;justify-content:space-between}.version .requirements{font-size:10px;color:#6b7b5d;margin-top:6px}.version.featured{border-color:#a4b88d;background:#edf1e4}.download-note{font-size:9px;line-height:1.9;color:#87917c;text-align:center;margin:19px auto 0;max-width:620px}.faq{max-width:860px;margin:55px auto 0}.faq-heading{font-size:16px;margin-bottom:14px;letter-spacing:-.4px}.faq details{border-bottom:1px solid #dce2d3;padding:16px 0}.faq summary{font-size:12px;cursor:pointer;list-style:none;display:flex;justify-content:space-between;gap:15px;align-items:center}.faq summary::-webkit-details-marker{display:none}.faq summary:after{content:'+';font-weight:300;font-size:20px;color:#849473;line-height:1}.faq details[open] summary:after{content:'−'}.faq details>p,.faq details>div{font-size:11px;color:#53634b;line-height:1.95;padding:11px 30px 0 0}.faq details a{text-decoration:underline;text-underline-offset:3px}.api-list{display:grid;gap:7px;margin:9px 0}.api-list code{font:10px/1.8 ui-monospace,monospace;color:#48613c;overflow-wrap:anywhere}.site-footer{border-top:1px solid var(--border);padding:31px 0 23px}.footer-main{display:flex;align-items:center;justify-content:space-between;gap:25px}.footer-brand .brand{font-size:22px}.footer-brand p{font-size:10px;color:#65735c;margin-top:5px}.footer-links{display:flex;gap:24px;font-size:10px;color:#64735b}.footer-links a:hover{text-decoration:underline;text-underline-offset:4px}.footer-legal{display:flex;justify-content:space-between;gap:25px;border-top:1px solid #e3e7db;margin-top:26px;padding-top:17px;font-size:8px;color:#64725b;line-height:1.9}.footer-legal>p{max-width:650px}.entrance{animation:enter .65s ease-out both}@keyframes enter{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:translateY(0)}}\n@media(min-width:1450px){.hero{min-height:730px;padding-block:80px 60px}.hero h1{font-size:76px}.hero-visual{transform:scale(1.04)}}\n@media(max-width:1100px){.shell{width:calc(100% - 64px)}.hero{gap:10px;min-height:640px;padding-block:50px 35px}.hero h1{font-size:54px;letter-spacing:-2.5px}.hero-visual{transform:scale(.9);transform-origin:center center;margin-inline:-22px}.chip-lyrics{left:-10px}.chip-together{right:-5px}.together-grid{gap:45px}.section{padding-block:75px}.phone{top:12px}.source-label{display:none}.feature-card{padding:25px}.lyric-pair .original{font-size:22px}}\n@media(max-width:760px){.shell{width:calc(100% - 40px)}.nav{height:70px}.brand{font-size:24px}.brand-mark{width:35px;height:35px}.menu-toggle{display:block}.nav-links{display:none;position:absolute;top:69px;left:0;right:0;background:var(--paper);border-bottom:1px solid var(--border);padding:17px 22px;gap:14px;flex-direction:column;align-items:stretch;font-size:14px}.nav-links.is-open{display:flex}.nav-links>a{padding:7px 4px}.nav-links>a.nav-download{justify-content:center;padding:11px}.hero{grid-template-columns:1fr;gap:10px;padding-top:42px;padding-bottom:30px;min-height:0}.hero-copy{text-align:center}.hero-label{margin-bottom:22px;font-size:10px}.hero h1{font-size:clamp(40px,9.5vw,62px);letter-spacing:-1.8px;line-height:1.25;margin-bottom:20px}.hero-description{font-size:13px;margin-inline:auto;max-width:340px}.hero-actions{justify-content:center;margin-top:25px;gap:10px}.button{font-size:12px;min-height:47px;padding:10px 19px}.hero-meta{justify-content:center;font-size:9px;gap:10px}.hero-meta span+span:before{margin-right:10px}.hero-visual{height:570px;transform:scale(.9);transform-origin:center top;margin:24px -8px -37px}.orbit{width:440px;height:440px;left:calc(50% - 220px);top:40px}.hero-visual:before{width:370px;height:370px;left:calc(50% - 185px);top:78px}.chip-lyrics{left:0;top:80px;padding:12px;gap:9px}.chip-together{right:-5px;bottom:90px;padding:12px}.hero-spark{right:20px}.source-strip{flex-wrap:wrap;justify-content:center;gap:17px 30px;padding-block:20px}.source-name{font-size:12px}.source-symbol{width:23px;height:23px;font-size:10px}.strip-detail{font-size:9px}.section{padding-block:54px}.eyebrow{font-size:8px;letter-spacing:1.5px}.section-heading{display:block;margin-top:16px}.section-heading h2,.download-heading h2{font-size:29px;letter-spacing:-1px}.section-heading>p{margin-top:16px;font-size:12px;max-width:340px}.feature-grid{grid-template-columns:1fr;gap:15px}.feature-card{border-radius:15px;padding:26px;min-height:330px}.feature-card h3{font-size:22px}.lyric-pair .original{font-size:clamp(21px,5vw,27px)}.recognize-card{min-height:310px}.recognize-graphic{height:155px}.sound-bar.left{left:12%}.sound-bar.right{right:12%}.mini-features{gap:22px;grid-template-columns:1fr;margin-top:30px}.mini-feature{display:grid;grid-template-columns:24px 1fr;gap:0 14px;padding-top:18px}.mini-feature>.icon{grid-row:span 2;margin-top:2px}.mini-feature h3{font-size:14px}.mini-feature p{max-width:none;font-size:11px;margin-top:4px}.together-section{padding-block:48px}.together-grid{grid-template-columns:1fr;gap:23px}.together-copy h2{font-size:33px;letter-spacing:-1px;margin-top:18px}.together-copy>p{font-size:12px;max-width:none}.together-art{margin:20px auto 0;height:163px}.connection-panel{padding:22px;border-radius:13px}.connection-tabs{justify-content:space-between;gap:12px}.connection-tabs button{font-size:11px}.connection-pane{min-height:170px}.step h3{font-size:12px}.step p{font-size:11px}.connection-note{font-size:10px}.download-section{padding-block:50px}.download-heading>p{font-size:11px}.versions{grid-template-columns:1fr;gap:15px;margin-top:26px}.version{padding:25px}.version h3{font-size:25px}.version p{font-size:12px}.version .button{font-size:12px;min-height:45px}.download-note{font-size:10px;max-width:340px}.faq{margin-top:36px}.faq summary{font-size:12px;line-height:1.8}.faq details>p,.faq details>div{font-size:11px;padding-right:6px}.footer-main{align-items:flex-start;gap:24px;flex-direction:column}.footer-links{font-size:11px;gap:24px;flex-wrap:wrap}.footer-legal{display:block;font-size:9px}.footer-legal>p+p{margin-top:12px}}\n@media(max-width:390px){.shell{width:calc(100% - 32px)}.hero-visual{transform:scale(.79);margin-left:-16px;margin-right:-16px;margin-bottom:-90px}.float-chip{font-size:10px}.chip-lyrics{left:-4px}.chip-together{right:-4px}.source-strip{gap:14px 23px}.strip-detail{font-size:8px}.feature-card{padding:23px}.lyric-pair .original{font-size:20px}.connection-panel{padding:18px}.hero h1{font-size:39px}.footer-links{gap:20px}}\n@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}*,*:before,*:after{animation:none!important;transition:none!important}}\n/* Reading sizes stay comfortable on compact screens as well as desktop. */\n.hero-description{font-size:16px}.feature-card p{font-size:13px}.mini-feature p{font-size:13px}.step h3{font-size:14px}.step p{font-size:12px}.connection-tabs button{font-size:12px}.connection-note{font-size:11px}.download-heading>p{font-size:14px}.version p{font-size:13px}.version .requirements{font-size:12px}.version .button{font-size:12px}.faq summary{font-size:14px}.faq details>p,.faq details>div{font-size:13px}.footer-legal{font-size:10px}.visual-caption{font-size:9px}\n@media(max-width:760px){.hero-description{font-size:14px}.feature-card p,.mini-feature p,.step p,.version p{font-size:13px}.connection-tabs button{font-size:12px}.connection-note{font-size:11px}.faq summary{font-size:14px}.faq details>p,.faq details>div{font-size:13px}.footer-legal{font-size:10px}.visual-caption{font-size:9px}}\n.orbit{width:min(500px,calc(100% + 30px));height:auto;aspect-ratio:1;left:50%;transform:translateX(-50%)}\n</style>\n<link rel=\"stylesheet\" href=\"/assets/discover.css?v=20261102b\">\n<link rel=\"stylesheet\" href=\"/assets/appearance.css?v=20261104-studio\">\n<link rel=\"stylesheet\" href=\"/assets/studio-player-motion.css\">\n</head>\n<body>\n<a class=\"skip-link\" href=\"#main\">跳到主要内容</a>\n<svg class=\"svg-defs\" aria-hidden=\"true\" xmlns=\"http://www.w3.org/2000/svg\"><defs>\n <linearGradient id=\"penguin\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop stop-color=\"#34433C\"/><stop offset=\".55\" stop-color=\"#1B2521\"/><stop offset=\"1\" stop-color=\"#0E1713\"/></linearGradient>\n <linearGradient id=\"belly\" x1=\"0\" y1=\"0\" x2=\".2\" y2=\"1\"><stop stop-color=\"#FFFFF6\"/><stop offset=\"1\" stop-color=\"#EAEEDC\"/></linearGradient>\n <radialGradient id=\"halo\"><stop stop-color=\"#DAEBD2\"/><stop offset=\"1\" stop-color=\"#DAEBD2\" stop-opacity=\"0\"/></radialGradient>\n <g id=\"spark\"><path d=\"M0 -11Q1 -1 11 0Q1 1 0 11Q-1 1 -11 0Q-1 -1 0 -11Z\" fill=\"currentColor\"/></g>\n <g id=\"note\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" stroke-linecap=\"round\"><path d=\"M8 16V-7L23 -11V12\"/><ellipse cx=\"2\" cy=\"17\" rx=\"7\" ry=\"5\" fill=\"currentColor\" stroke=\"none\"/><ellipse cx=\"17\" cy=\"13\" rx=\"7\" ry=\"5\" fill=\"currentColor\" stroke=\"none\"/></g>\n <g id=\"tux-base\">\n  <ellipse cx=\"140\" cy=\"293\" rx=\"106\" ry=\"12\" fill=\"#10271A\" opacity=\".10\"/>\n  <path d=\"M75 139C43 145 21 191 26 229Q32 246 54 233L89 192Z\" fill=\"#1C2922\"/>\n  <path d=\"M207 138C237 146 259 194 252 229Q247 244 229 229L194 189Z\" fill=\"#17241D\"/>\n  <path d=\"M67 173C63 132 75 102 93 88Q107 73 140 74Q177 75 193 93C213 114 222 147 216 185L227 238Q223 282 141 286Q58 280 56 238Z\" fill=\"url(#penguin)\"/>\n  <ellipse cx=\"140\" cy=\"191\" rx=\"67\" ry=\"86\" fill=\"url(#belly)\"/>\n  <path d=\"M72 103Q63 59 87 28Q106 5 139 7Q178 6 198 32Q219 59 207 111Q198 147 140 149Q80 145 72 103Z\" fill=\"url(#penguin)\"/>\n  <path d=\"M97 100Q84 67 101 43Q116 24 133 57Q144 35 164 39Q194 49 187 100Q165 131 140 134Q114 130 97 100Z\" fill=\"#FFFFF5\"/>\n  <g class=\"tux-eyes\"><ellipse cx=\"119\" cy=\"75\" rx=\"10\" ry=\"15\" fill=\"#13241B\"/><ellipse cx=\"165\" cy=\"75\" rx=\"10\" ry=\"15\" fill=\"#13241B\"/><circle cx=\"122\" cy=\"71\" r=\"3\" fill=\"white\"/><circle cx=\"168\" cy=\"71\" r=\"3\" fill=\"white\"/></g>\n  <path d=\"M109 103Q140 85 174 103Q166 125 142 126Q121 125 109 103Z\" fill=\"#E9B64D\"/>\n  <path d=\"M117 108Q141 117 166 107\" fill=\"none\" stroke=\"#B77F2C\" stroke-width=\"2\" stroke-linecap=\"round\"/>\n  <path d=\"M80 252C65 252 34 265 27 282Q25 293 47 296Q76 302 111 289Q117 277 107 265Z\" fill=\"#E9B64D\"/>\n  <path d=\"M200 252C215 252 246 265 253 282Q255 293 233 296Q204 302 169 289Q163 277 173 265Z\" fill=\"#E9B64D\"/>\n  <path d=\"M61 81Q60 1 140 0Q219 1 218 81\" fill=\"none\" stroke=\"#76AF7E\" stroke-width=\"13\" stroke-linecap=\"round\"/>\n  <path d=\"M70 70Q69 10 140 9Q210 10 209 70\" fill=\"none\" stroke=\"#C6DEC0\" stroke-width=\"3\"/>\n  <rect x=\"53\" y=\"68\" width=\"23\" height=\"48\" rx=\"10\" fill=\"#176C46\"/><rect x=\"205\" y=\"68\" width=\"23\" height=\"48\" rx=\"10\" fill=\"#176C46\"/>\n  <path d=\"M81 143Q140 163 198 142L200 157Q140 181 79 157Z\" fill=\"#176C46\"/>\n  <path d=\"M169 158L166 197L187 193L189 154Z\" fill=\"#176C46\"/>\n  <path d=\"M168 182L185 178\" fill=\"none\" stroke=\"#9CC99F\" stroke-width=\"3\"/>\n </g>\n <g id=\"tux-party\">\n  <use href=\"#tux-base\"/>\n  <path d=\"M99 22L137 -53L174 23Z\" fill=\"#EBA38F\" transform=\"rotate(-9 138 18)\"/>\n  <path d=\"M113 -5L160 -10M122 -26L149 -29\" stroke=\"#F9E2AB\" stroke-width=\"9\"/>\n  <path d=\"M97 24Q136 14 176 25\" fill=\"none\" stroke=\"#FFF4D5\" stroke-width=\"10\" stroke-linecap=\"round\"/>\n  <circle cx=\"125\" cy=\"-53\" r=\"10\" fill=\"#F1CB70\"/>\n  <rect x=\"105\" y=\"216\" width=\"71\" height=\"43\" rx=\"5\" fill=\"#EBA38F\"/>\n  <path d=\"M105 221Q113 231 121 223Q130 237 139 223Q148 236 158 223Q168 233 176 221V215H105Z\" fill=\"#FFF8DC\"/>\n  <rect x=\"120\" y=\"196\" width=\"5\" height=\"21\" fill=\"#176C46\"/><rect x=\"139\" y=\"194\" width=\"5\" height=\"23\" fill=\"#176C46\"/><rect x=\"157\" y=\"198\" width=\"5\" height=\"19\" fill=\"#176C46\"/>\n  <path d=\"M122 195Q113 187 123 180Q131 188 122 195M141 192Q132 184 141 177Q150 185 141 192M159 195Q152 188 160 182Q168 189 159 195Z\" fill=\"#E9B64D\"/>\n </g>\n <g id=\"tux-listener\">\n  <use href=\"#tux-base\"/>\n  <g transform=\"translate(198 226)\"><circle r=\"46\" fill=\"#0D1812\" stroke=\"#435C4D\" stroke-width=\"2\"/><circle r=\"37\" fill=\"none\" stroke=\"#405346\"/><circle r=\"28\" fill=\"none\" stroke=\"#405346\"/><circle r=\"19\" fill=\"#A3C995\"/><circle r=\"4\" fill=\"#14211D\"/><path d=\"M-30 -14A34 34 0 0 1 -6 -33\" fill=\"none\" stroke=\"#7A8D7D\" stroke-width=\"3\"/></g>\n  <path d=\"M206 185Q230 187 229 206Q218 231 188 232L174 218Q193 209 206 185Z\" fill=\"#263A2C\"/>\n </g>\n</defs></svg>\n<header class=\"site-header\"><nav class=\"nav shell\" aria-label=\"主导航\">\n<a class=\"brand\" href=\"#\" aria-label=\"Arris 首页\"><span class=\"brand-mark\"><svg viewBox=\"55 -6 175 165\" aria-hidden=\"true\"><use href=\"#tux-base\"/></svg></span>Arris<span class=\"brand-tagline\">好好听歌。</span></a>\n<div class=\"nav-tools\"><label class=\"appearance-control\"><span class=\"sr-only\">网站外观</span><select id=\"appearance-mode\" disabled><option value=\"system\">跟随系统</option><option value=\"light\">浅色</option><option value=\"dark\">深色</option></select></label>\n<button class=\"menu-toggle\" aria-label=\"展开导航\" aria-expanded=\"false\" aria-controls=\"nav-links\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M4 7h16M4 12h16M4 17h16\"/></svg></button></div>\n<div class=\"nav-links\" id=\"nav-links\"><a href=\"#features\">体验亮点</a><a href=\"#studio\">在线试听</a><a href=\"#together\">一起听</a><a href=\"#evolution\">更新与演进</a><a href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub ↗</a><a class=\"nav-download\" href=\"#download\">获取 Arris <svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4\"/></svg></a></div>\n</nav></header>\n<main id=\"main\">\n<section class=\"hero shell\" aria-labelledby=\"hero-title\">\n<div class=\"hero-copy entrance\"><div class=\"hero-label\"><span class=\"small-dot\"></span>为喜欢的音乐，留一个自由的位置</div>\n<h1 id=\"hero-title\">喜欢的音乐，<br><span>都在这里。</span></h1>\n<p class=\"hero-description\">网易云与 QQ 音乐，一处聆听。<br>把逐字歌词、听歌识曲和一起听，<br>装进你的口袋。</p>\n<div class=\"hero-actions\"><a class=\"button button-primary\" href=\"/get/latest.apk\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4\"/></svg> 下载 Arris</a><a class=\"button button-outline\" href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M9 19c-4 1-4-2-6-2m12 5v-4a3.5 3.5 0 0 0-1-2.7c3.3-.4 6.7-1.6 6.7-7.3a5.7 5.7 0 0 0-1.5-4 5.3 5.3 0 0 0-.1-4s-1.2-.4-4.1 1.5a14.1 14.1 0 0 0-7.4 0C4.7-.4 3.5 0 3.5 0a5.3 5.3 0 0 0-.1 4A5.7 5.7 0 0 0 2 8c0 5.7 3.4 6.9 6.7 7.3A3.5 3.5 0 0 0 8 18v4\" transform=\"translate(1 1) scale(.9)\"/></svg> 看看源码</a></div>\n<div class=\"hero-meta\"><span>为 Android 而生</span><span>开源 GPLv3</span><span>自由聆听</span></div></div>\n<div class=\"hero-visual\" aria-label=\"Arris 播放器与 Tux 视觉示意\">\n<div class=\"studio-print\" aria-hidden=\"true\"><span>ARRIS / LISTENING STUDIO</span><span>01 — 03</span></div>\n<svg class=\"studio-trace\" viewBox=\"0 0 560 180\" preserveAspectRatio=\"none\" aria-hidden=\"true\"><defs><linearGradient id=\"studio-signal\"><stop stop-color=\"currentColor\" stop-opacity=\"0\"/><stop offset=\".23\" stop-color=\"currentColor\"/><stop offset=\".77\" stop-color=\"currentColor\"/><stop offset=\"1\" stop-color=\"currentColor\" stop-opacity=\"0\"/></linearGradient></defs><path d=\"M0 90H560\" stroke=\"url(#studio-signal)\" stroke-opacity=\".36\"/><path d=\"M0 90C34 90 38 80 51 81S73 114 89 112 113 59 130 58 145 127 164 130 180 37 199 37 221 145 237 143 259 25 280 25 300 148 319 149 341 52 356 50 379 130 398 130 418 68 432 70 456 110 469 109 493 82 510 83 541 91 560 90\" fill=\"none\" stroke=\"url(#studio-signal)\" stroke-width=\"1.5\"/></svg>\n<div class=\"orbit\" aria-hidden=\"true\"></div><svg class=\"icon hero-spark\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"m12 2 2.6 7.4L22 12l-7.4 2.6L12 22l-2.6-7.4L2 12l7.4-2.6Z\"/></svg>\n<div class=\"phone\"><div class=\"phone-top\"><span>9:41</span><span class=\"phone-signals\">▮▮▮ ▰</span></div><div class=\"phone-heading\"><span>‹</span><span>正在播放</span><span>···</span></div>\n<div class=\"album\"><span class=\"album-caption\">THE QUIET LISTENER</span><svg class=\"album-tux\" viewBox=\"0 -8 280 320\" role=\"img\" aria-label=\"戴着绿色耳机、抱着唱片的 Tux\"><use href=\"#tux-listener\"/></svg></div>\n<div class=\"track-row\"><div><div class=\"track-title\">自由听众席</div><p class=\"track-artist\">Tux · Arris 视觉示意</p></div><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M20.8 4.6a5.4 5.4 0 0 0-7.6 0L12 5.8l-1.2-1.2a5.4 5.4 0 0 0-7.6 7.6L12 21l8.8-8.8a5.4 5.4 0 0 0 0-7.6Z\"/></svg></div>\n<div class=\"demo-lyrics\">让喜欢的声音，<br><strong>陪你再久一点。</strong></div>\n<svg class=\"waveform\" viewBox=\"0 0 220 30\" aria-hidden=\"true\"><path d=\"M0 15Q5 14 9 16T18 14T27 18T36 11T45 17T54 8T63 20T72 6T81 21T90 4T99 23T108 8T117 21T126 5T135 24T144 9T153 19T162 11T171 18T180 12T189 16T198 14T210 16T220 15\" fill=\"none\" stroke=\"#739663\" stroke-width=\"2\"/><path d=\"M0 15Q9 12 18 15T36 14T54 18T72 12T90 18T108 10T126 21T144 11T162 19T180 12T198 17T220 15\" fill=\"none\" stroke=\"#afbf87\" stroke-width=\"1\" opacity=\".6\"/></svg>\n<div class=\"demo-progress\"></div><div class=\"demo-times\"><span>01:16</span><span>03:28</span></div>\n<div class=\"demo-controls\"><svg class=\"icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" aria-hidden=\"true\"><path d=\"m3 6 4 0 10 12h4M17 4l4 2-4 2M3 18h4l4-5m4-3 2-4h4\"/></svg><span aria-hidden=\"true\">⏮</span><a class=\"demo-play\" href=\"#studio\" aria-label=\"前往在线试听\"><svg viewBox=\"0 0 20 20\" aria-hidden=\"true\"><path d=\"m6 3 11 7-11 7Z\"/></svg></a><span aria-hidden=\"true\">⏭</span><svg class=\"icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" aria-hidden=\"true\"><path d=\"M3 6h18M3 12h18M3 18h18\"/></svg></div><div class=\"phone-bottom\"></div></div>\n<div class=\"float-chip chip-lyrics\"><span class=\"chip-icon\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M4 15v-3a8 8 0 0 1 16 0v3M4 13H2v7h5v-7zm16 0h2v7h-5v-7z\"/></svg></span><div><strong>每一句，都听得懂。</strong><small>逐字高亮 · 双语对照</small></div></div>\n<div class=\"float-chip chip-together\"><div class=\"avatars\" aria-hidden=\"true\"><span>T</span><span>A</span><span>♫</span></div><div><strong>好音乐，一起听。</strong><small>把此刻分享给朋友</small></div></div>\n<p class=\"visual-caption\" id=\"demo-caption\">播放器插画 · <a href=\"#studio\">听一听真实波形 ↘</a></p>\n</div></section>\n<div class=\"source-strip shell\" aria-label=\"音源与格式支持\"><span class=\"source-label\">MORE MUSIC. ONE PLACE.</span><span class=\"source-name\"><span class=\"source-symbol\">云</span>网易云音乐</span><span class=\"source-name\"><span class=\"source-symbol qq\">Q</span>QQ 音乐</span><span class=\"strip-detail\">LRC / TTML / YRC / QRC</span><span class=\"strip-detail\">8 种界面语言</span></div>\n<nav class=\"chapter-nav shell\" aria-label=\"探索 Arris\">\n<a href=\"#studio\"><span class=\"chapter-no\">01</span><span><strong>试听播放器</strong><small>原创器乐与实时波形</small></span><span class=\"chapter-arrow\" aria-hidden=\"true\">↘</span></a>\n<a href=\"#together\"><span class=\"chapter-no\">02</span><span><strong>邀请朋友</strong><small>同一首歌，同一个时刻</small></span><span class=\"chapter-arrow\" aria-hidden=\"true\">↘</span></a>\n<a href=\"#download\"><span class=\"chapter-no\">03</span><span><strong>选下载版本</strong><small>主线与旧设备 LTS</small></span><span class=\"chapter-arrow\" aria-hidden=\"true\">↘</span></a>\n</nav>\n<section class=\"section shell\" id=\"features\" data-acc=\"pine\" data-reveal aria-labelledby=\"features-title\"><p class=\"eyebrow\"><span class=\"eyebrow__no\">01</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">MADE FOR LISTENING</span></p><div class=\"section-heading\"><h2 id=\"features-title\">听歌这件小事，<br>值得多一点用心。</h2><p>让旋律有回应，让歌词有温度。<br>从找到一首歌，到把它留在身边。</p></div>\n<div class=\"feature-grid\"><article class=\"feature-card lyrics-card\"><div class=\"feature-label\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M4 15v-3a8 8 0 0 1 16 0v3M4 13H2v7h5v-7zm16 0h2v7h-5v-7z\"/></svg> 双语 · 逐字歌词</div><h3>听见旋律，也读懂每一句。</h3><p>原文和译文同步显示，你来决定哪一行更重要。</p><div class=\"lyric-example\"><small>♪ 每一种心情，都有自己的声音。</small><div class=\"lyric-pair\" data-primary=\"original\"><p class=\"original\" lang=\"en\">Keep your favorite sound close.</p><p class=\"translation\">把喜欢的声音，留在身边。</p></div></div><div class=\"segmented\" role=\"group\" aria-label=\"双语歌词主行示意\"><button type=\"button\" data-lyric=\"original\" aria-pressed=\"true\">原文优先</button><button type=\"button\" data-lyric=\"translation\" aria-pressed=\"false\">译文优先</button></div><small class=\"example-note\">排版示意 · 无翻译时自动回到单行</small></article>\n<article class=\"feature-card recognize-card\"><div class=\"feature-label\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"9\" y=\"2\" width=\"6\" height=\"13\" rx=\"3\"/><path d=\"M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8\"/></svg> 听歌识曲</div><h3>留住偶然听见的喜欢。</h3><p>通过你的网易云账号识别身边的旋律。</p><div class=\"recognize-graphic\" aria-hidden=\"true\"><div class=\"sound-bar left\"><i></i><i></i><i></i><i></i><i></i></div><div class=\"mic-orbit\"><div class=\"mic-disc\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"9\" y=\"2\" width=\"6\" height=\"13\" rx=\"3\"/><path d=\"M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8\"/></svg></div></div><div class=\"sound-bar right\"><i></i><i></i><i></i><i></i><i></i></div></div><div class=\"recognize-caption\">听到 → 识别 → 找到下一首喜欢</div></article></div>\n<div class=\"mini-features\"><article class=\"mini-feature\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M7 18H5a4 4 0 0 1 0-8 7 7 0 0 1 13-2 5 5 0 0 1 1 10h-2M12 12v10m-4-4 4 4 4-4\"/></svg><h3>离线，也有好声音。</h3><p>完整缓存的歌曲，断网也能继续听。容量按你的习惯调整。</p></article><article class=\"mini-feature\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M20.8 4.6a5.4 5.4 0 0 0-7.6 0L12 5.8l-1.2-1.2a5.4 5.4 0 0 0-7.6 7.6L12 21l8.8-8.8a5.4 5.4 0 0 0 0-7.6Z\"/></svg><h3>账号独立，喜欢相通。</h3><p>网易云与 QQ 音乐分别登录，在一处搜索、播放和整理音乐。</p></article><article class=\"mini-feature\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M3 12h18M12 3c5 5 5 13 0 18-5-5-5-13 0-18Z\"/></svg><h3>用熟悉的语言听歌。</h3><p>支持 8 种界面语言，随时切换。还有 Tux 陪你发现小小的彩蛋。</p></article></div></section>\n<section class=\"studio-section\" id=\"studio\" aria-labelledby=\"studio-title\">\n  <div class=\"shell studio-layout\">\n    <div class=\"studio-copy\">\n      <p class=\"eyebrow\"><span class=\"eyebrow__no\">试听室</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">LISTENING SESSION</span></p>\n      <h2 id=\"studio-title\">按下播放，<br>看见声音。</h2>\n      <p>试听一段原创器乐。看低、中、高三段波形各自流动，也可以切到歌词，让双语文案随音乐逐字点亮。</p>\n      <p class=\"studio-credit\">《雨后》 · 约 37 秒 · 为 Arris 合成的原创试听曲<br>曲目无人声，双语文案用于演示 · <a href=\"https://creativecommons.org/publicdomain/zero/1.0/\" target=\"_blank\" rel=\"noopener noreferrer\">CC0 1.0 ↗</a> · <a href=\"/assets/after-the-rain.mp3\" download>下载试听曲</a></p>\n    </div>\n    <div class=\"studio-player\" data-studio data-view=\"cover\">\n      <div class=\"session-head\">\n        <span class=\"session-now\"><i aria-hidden=\"true\"></i>正在播放</span>\n        <div class=\"session-tabs\" role=\"group\" aria-label=\"播放器视图\">\n          <a id=\"studio-tab-cover\" href=\"#studio\" aria-controls=\"studio-pane-cover\" data-studio-view=\"cover\">封面</a>\n          <a id=\"studio-tab-lyrics\" href=\"#studio-pane-lyrics\" aria-controls=\"studio-pane-lyrics\" data-studio-view=\"lyrics\">歌词</a>\n        </div>\n        <span class=\"session-web\">网页演示</span>\n      </div>\n      <div class=\"session-stage\">\n        <div class=\"session-cover\" aria-hidden=\"true\">\n          <span class=\"session-cover-edition\">ARRIS STUDIO<span>01</span></span>\n          <svg viewBox=\"0 0 280 315\"><use href=\"#tux-listener\"/></svg>\n          <span class=\"session-cover-name\">AFTER THE RAIN</span>\n        </div>\n        <div class=\"session-song\"><h3>雨后</h3><p>Arris Studio · 原创器乐</p></div>\n        <div class=\"session-song-mini\" aria-hidden=\"true\"><strong>雨后</strong><p>Arris Studio · 原创器乐</p></div>\n        <div id=\"studio-pane-cover\" class=\"session-pane-cover\" role=\"region\" aria-labelledby=\"studio-tab-cover\">\n          <span class=\"sr-only\">《雨后》的试听封面</span>\n          <button type=\"button\" class=\"session-knock\" aria-label=\"轻敲 Tux 唱片封面\" title=\"试试轻敲三下\" hidden></button>\n          <span class=\"session-cover-reply\" role=\"status\"></span>\n        </div>\n        <div id=\"studio-pane-lyrics\" class=\"session-lyrics\" role=\"region\" aria-labelledby=\"studio-tab-lyrics\">\n          <p class=\"session-neighbor session-previous\"></p>\n          <div class=\"session-active\">\n            <p class=\"session-primary\" lang=\"en\">The rain has let the evening in.</p>\n            <p class=\"session-secondary\" lang=\"zh-CN\">雨停了，傍晚悄悄进来。</p>\n          </div>\n          <p class=\"session-neighbor session-next\"></p>\n          <div class=\"session-line-options\" role=\"group\" aria-label=\"双语主行\" hidden><button type=\"button\" data-studio-line=\"original\" aria-pressed=\"true\">原文优先</button><button type=\"button\" data-studio-line=\"translation\" aria-pressed=\"false\">译文优先</button></div>\n        </div>\n      </div>\n      <div class=\"session-wavebox\" hidden>\n        <div class=\"session-wave-labels\">\n          <span class=\"studio-band-low\"><i aria-hidden=\"true\"></i>低频</span>\n          <span class=\"studio-band-mid\"><i aria-hidden=\"true\"></i>中频</span>\n          <span class=\"studio-band-high\"><i aria-hidden=\"true\"></i>高频</span>\n        </div>\n        <canvas class=\"session-wave\" aria-hidden=\"true\"></canvas>\n        <span class=\"session-wave-note\" role=\"status\"></span>\n      </div>\n      <audio id=\"studio-audio\" controls preload=\"none\" src=\"/assets/after-the-rain.mp3\" aria-label=\"播放原创试听曲《雨后》\"></audio>\n      <div class=\"session-controls\" hidden>\n        <label class=\"sr-only\" for=\"studio-seek\">试听播放进度</label>\n        <input id=\"studio-seek\" type=\"range\" min=\"0\" max=\"37.48\" step=\".05\" value=\"0\" aria-valuetext=\"0 分 0 秒\">\n        <div class=\"session-times\"><span id=\"studio-time\">0:00</span><span class=\"session-format\">原创试听</span><button type=\"button\" class=\"session-format session-liner-button\" aria-haspopup=\"dialog\" hidden>原创试听 <span aria-hidden=\"true\">✦</span></button><span id=\"studio-duration\">0:37</span></div>\n        <div class=\"session-actions\">\n          <button id=\"studio-mute\" type=\"button\" aria-label=\"静音\" aria-pressed=\"false\"><svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M4 9v6h4l5 4V5L8 9H4Z\"/><path class=\"studio-sound-on\" d=\"M16 9a4 4 0 0 1 0 6m2-8a7 7 0 0 1 0 10\"/><path class=\"studio-sound-off\" d=\"m16 10 5 5m0-5-5 5\"/></svg></button>\n          <button type=\"button\" data-studio-skip=\"-5\" aria-label=\"后退 5 秒\"><svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M18 6 8 12l10 6zM5 6v12\"/></svg><small>5</small></button>\n          <button id=\"studio-play\" type=\"button\"><svg viewBox=\"0 0 20 20\" aria-hidden=\"true\"><path class=\"studio-icon-play\" d=\"m6 3 11 7-11 7Z\"/><path class=\"studio-icon-pause\" d=\"M4 3h4v14H4zm8 0h4v14h-4z\"/></svg><span class=\"sr-only\">播放</span></button>\n          <button type=\"button\" data-studio-skip=\"5\" aria-label=\"快进 5 秒\"><svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"m6 6 10 6-10 6zm13 0v12\"/></svg><small>5</small></button>\n          <button id=\"studio-open-lyrics\" type=\"button\" aria-label=\"打开歌词\" aria-pressed=\"false\"><svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M4 6h16M4 11h16M4 16h10\"/></svg><span>歌词</span></button>\n        </div>\n        <p id=\"studio-status\" class=\"session-status\" role=\"status\"></p>\n        <dialog class=\"session-liner\" aria-labelledby=\"studio-liner-title\">\n          <p class=\"session-liner-kicker\">ARRIS STUDIO · 01</p>\n          <h3 id=\"studio-liner-title\">唱片内页</h3>\n          <p>《雨后》的底鼓、低音和镲片都是合成的。仔细看，底鼓会敲亮左边的暖色波形，镲片则在右边留下细小的波峰。</p>\n          <p class=\"session-liner-signature\">Tux 留字 · 雨后适合慢慢听。</p>\n          <form method=\"dialog\"><button type=\"submit\" autofocus>继续听</button></form>\n        </dialog>\n      </div>\n    </div>\n  </div>\n</section>\n<section class=\"showcase-section\" data-acc=\"amber\" data-reveal id=\"showcase\" aria-labelledby=\"showcase-title\">\n<div class=\"shell\"><div class=\"showcase-heading\"><div><p class=\"eyebrow\"><span class=\"eyebrow__no\">02</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">A CLOSER LOOK</span></p><h2 id=\"showcase-title\">让界面退后，<br>让音乐靠近。</h2></div><p>松绿与米白，安静也清晰。<br>从独自聆听，到把喜欢分享给别人。</p></div>\n<div class=\"showcase-tabs\" role=\"tablist\" aria-label=\"应用界面展示\">\n<button type=\"button\" role=\"tab\" id=\"view-player\" aria-controls=\"show-player\" aria-selected=\"true\" data-showcase=\"player\">01 播放器</button>\n<button type=\"button\" role=\"tab\" id=\"view-recognition\" aria-controls=\"show-recognition\" aria-selected=\"false\" tabindex=\"-1\" data-showcase=\"recognition\">02 听歌识曲</button>\n<button type=\"button\" role=\"tab\" id=\"view-together\" aria-controls=\"show-together\" aria-selected=\"false\" tabindex=\"-1\" data-showcase=\"together\">03 一起听</button></div>\n<div class=\"showcase-panel\" role=\"tabpanel\" id=\"show-player\" aria-labelledby=\"view-player\"><div class=\"showcase-copy\"><span class=\"detail-kicker\">YOUR MUSIC, YOUR SPACE.</span><h3>歌词在中间，<br>其余都让开。</h3><p>封面、歌名与工具条收在上方，歌词按当前句定位；进度、音质与传输键压在最下面一行。深浅两套都是界面本身，没有系统栏。</p><ul class=\"detail-list\"><li>当前句左侧竖条定位，译文紧随其下</li><li>双语主行可切，无翻译回退单行</li><li>音质标签与进度同处一行，不抢歌词</li></ul><a class=\"text-link light-link\" href=\"#download\">找到适合你的版本 <span aria-hidden=\"true\">↗</span></a></div><div class=\"screen-pair\"><figure class=\"screen-figure player-figure pm-light\">\n<div class=\"player-mock\" role=\"img\" aria-label=\"Arris 浅色主题播放页版式示意：封面头部、歌词区、进度与传输键\">\n  <div class=\"pm-head\">\n    <span class=\"pm-cover\"></span>\n    <span class=\"pm-meta\"><b>夜行列车</b><small>本地样例 · ncm</small></span>\n    <span class=\"pm-collapse\">⌄</span>\n  </div>\n  <div class=\"pm-tools\"><span>↗</span><span>A−</span><span>A+</span><span class=\"pm-on\">原文</span></div>\n  <div class=\"pm-lyrics\">\n    <p class=\"pm-line pm-past\">站台的风 把日历翻旧</p>\n    <p class=\"pm-line pm-cur\">窗外的灯 一盏一盏退后</p>\n    <p class=\"pm-line pm-sub\">窗外的灯，一盏一盏退后</p>\n    <p class=\"pm-line pm-future\">我把今天 折好放进口袋</p>\n    <p class=\"pm-line pm-future dim\">等下一次 再拆开来看</p>\n  </div>\n  <div class=\"pm-progress\"><span>0:19</span><span class=\"pm-bar\"><i></i></span><span>4:59</span></div>\n  <div class=\"pm-quality\">无损</div>\n  <div class=\"pm-transport\">\n    <span class=\"pm-skip\">⏮</span><span class=\"pm-play\">▶</span><span class=\"pm-skip\">⏭</span>\n  </div>\n  <div class=\"pm-toolbar\"><span>💬</span><span>≡</span><span>✓</span><span>⛶</span><span>⧉</span></div>\n</div><figcaption>LIGHT / 米白 · 播放页</figcaption></figure><figure class=\"screen-figure player-figure pm-dark\">\n<div class=\"player-mock\" role=\"img\" aria-label=\"Arris 深色主题播放页版式示意：封面头部、歌词区、进度与传输键\">\n  <div class=\"pm-head\">\n    <span class=\"pm-cover\"></span>\n    <span class=\"pm-meta\"><b>夜行列车</b><small>本地样例 · ncm</small></span>\n    <span class=\"pm-collapse\">⌄</span>\n  </div>\n  <div class=\"pm-tools\"><span>↗</span><span>A−</span><span>A+</span><span class=\"pm-on\">原文</span></div>\n  <div class=\"pm-lyrics\">\n    <p class=\"pm-line pm-past\">站台的风 把日历翻旧</p>\n    <p class=\"pm-line pm-cur\">窗外的灯 一盏一盏退后</p>\n    <p class=\"pm-line pm-sub\">窗外的灯，一盏一盏退后</p>\n    <p class=\"pm-line pm-future\">我把今天 折好放进口袋</p>\n    <p class=\"pm-line pm-future dim\">等下一次 再拆开来看</p>\n  </div>\n  <div class=\"pm-progress\"><span>0:19</span><span class=\"pm-bar\"><i></i></span><span>4:59</span></div>\n  <div class=\"pm-quality\">无损</div>\n  <div class=\"pm-transport\">\n    <span class=\"pm-skip\">⏮</span><span class=\"pm-play\">▶</span><span class=\"pm-skip\">⏭</span>\n  </div>\n  <div class=\"pm-toolbar\"><span>💬</span><span>≡</span><span>✓</span><span>⛶</span><span>⧉</span></div>\n</div><figcaption>DARK / 松绿 · 播放页</figcaption></figure></div></div>\n<div class=\"showcase-panel\" role=\"tabpanel\" id=\"show-recognition\" aria-labelledby=\"view-recognition\" hidden><div class=\"showcase-copy\"><span class=\"detail-kicker\">CATCH THAT LITTLE MOMENT.</span><h3>路过的旋律，<br>不必再错过。</h3><p>打开听歌识曲，主动开始一次聆听。用网易云账号识别身边的音乐，把偶然遇见的一首歌接回播放器。</p><ul class=\"detail-list\"><li>你来决定什么时候开始和停止</li><li>识别结果回到同一个音乐入口</li><li>需要麦克风权限、联网与网易云登录</li></ul><a class=\"text-link light-link\" href=\"#getting-started\">第一次使用？从这里开始 <span aria-hidden=\"true\">↗</span></a></div><figure class=\"single-screen screen-figure\"><button type=\"button\" class=\"screen-zoom\" data-screen=\"/assets/recognition.webp\" aria-label=\"放大听歌识曲截图\"><img src=\"/assets/recognition.webp\" alt=\"Arris 听歌识曲页面，显示聆听入口、录音说明和开始识别按钮\" width=\"630\" height=\"1365\" loading=\"lazy\" decoding=\"async\"></button><figcaption>LISTEN / 给偶然的喜欢一个名字</figcaption></figure></div>\n<div class=\"showcase-panel\" role=\"tabpanel\" id=\"show-together\" aria-labelledby=\"view-together\" hidden><div class=\"showcase-copy\"><span class=\"detail-kicker\">SAME SONG. SAME MOMENT.</span><h3>把下一首，<br>留给一起听的人。</h3><p>局域网、P2P 或中继，选择适合你们的连接方式。分享房间，同步进度，一起安排接下来想听的歌。</p><ul class=\"detail-list\"><li>同一 Wi-Fi 或热点下直接加入</li><li>远程可尝试 P2P 或配置中继</li><li>各自播放音源，账号留在各自设备</li></ul><a class=\"text-link light-link\" href=\"#together\">看看怎么连接 <span aria-hidden=\"true\">↗</span></a></div><figure class=\"single-screen screen-figure\"><button type=\"button\" class=\"screen-zoom\" data-screen=\"/assets/together.webp\" aria-label=\"放大一起听截图\"><img src=\"/assets/together.webp\" alt=\"Arris 一起听页面，包含连接方式、创建房间和加入房间入口\" width=\"630\" height=\"1365\" loading=\"lazy\" decoding=\"async\"></button><figcaption>TOGETHER / 相隔很远，也在同一拍</figcaption></figure></div>\n<p class=\"screenshot-source\">CSS 版式示意：头部、歌词区、进度与传输键按 4.0.x 播放页的层级复刻；文案为自写样例（不含第三方歌词）。</p></div></section>\n\n<section class=\"together-section\" data-acc=\"indigo\" data-reveal id=\"together\" aria-labelledby=\"together-title\"><div class=\"shell together-grid\"><div class=\"together-copy\"><p class=\"eyebrow\"><span class=\"eyebrow__no\">03</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">BETTER TOGETHER</span></p><h2 id=\"together-title\">相隔很远，<br>也能听同一首。</h2><p>建一个房间，把此刻分享给朋友。<br>同步的是播放状态，音频由各自的音源播放，<br>你的账号留在自己的设备。</p><div class=\"together-art\" aria-hidden=\"true\"><div class=\"disc\"><div class=\"disc-center\"></div></div><div class=\"disc-link\"></div><div class=\"disc second\"><div class=\"disc-center\"></div></div><small>一首歌，两边的同一个时刻。 / 连接示意</small></div></div>\n<div class=\"connection-panel\"><div class=\"connection-tabs\" role=\"tablist\" aria-label=\"一起听连接方式\"><button role=\"tab\" id=\"tab-lan\" data-tab=\"lan\" aria-selected=\"true\" aria-controls=\"panel-lan\" tabindex=\"0\">同一网络</button><button role=\"tab\" id=\"tab-remote\" data-tab=\"remote\" aria-selected=\"false\" aria-controls=\"panel-remote\" tabindex=\"-1\">跨网络</button><button role=\"tab\" id=\"tab-relay\" data-tab=\"relay\" aria-selected=\"false\" aria-controls=\"panel-relay\" tabindex=\"-1\">自建中继</button></div>\n<div class=\"connection-pane\" id=\"panel-lan\" role=\"tabpanel\" aria-labelledby=\"tab-lan\"><div class=\"step\"><span>01</span><div><h3>连上同一个 Wi-Fi 或热点</h3><p>在「一起听」中选择局域网建房。</p></div></div><div class=\"step\"><span>02</span><div><h3>把房间地址发给朋友</h3><p>朋友通过扫描房间，或填写 IP:端口加入。</p></div></div><div class=\"step\"><span>03</span><div><h3>选一首你们都喜欢的歌</h3><p>由房主控制播放，成员跟随同一个进度。</p></div></div></div>\n<div class=\"connection-pane\" id=\"panel-remote\" role=\"tabpanel\" aria-labelledby=\"tab-remote\" hidden><div class=\"step\"><span>01</span><div><h3>尝试 P2P 建房与加入</h3><p>分享应用生成的 IP:端口#令牌，连接成功率取决于双方网络与 NAT。</p></div></div><div class=\"step\"><span>02</span><div><h3>也可以使用虚拟局域网</h3><p>双方已配置 Tailscale 等虚拟局域网时，用对应地址通过局域网入口加入。</p></div></div><div class=\"step\"><span>03</span><div><h3>需要时改用中继</h3><p>移动网络或严格 NAT 环境直连困难时，可选择可用的中继。</p></div></div></div>\n<div class=\"connection-pane\" id=\"panel-relay\" role=\"tabpanel\" aria-labelledby=\"tab-relay\" hidden><div class=\"step\"><span>01</span><div><h3>使用同一个中继地址</h3><p>在客户端「一起听」中填写本站地址，然后创建或加入房间。</p></div></div><div class=\"relay-address\">https://arris.de5.net</div><div class=\"relay-actions\"><button class=\"relay-button\" id=\"copy-relay\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"8\" y=\"8\" width=\"13\" height=\"13\" rx=\"2\"/><path d=\"M16 8V3H3v13h5\"/></svg> 复制地址</button><button class=\"relay-button\" id=\"check-health\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"m5 12 4 4L19 6\"/></svg> 检查中继</button></div><p class=\"relay-status\" role=\"status\" aria-live=\"polite\" id=\"relay-status\"></p></div>\n<p class=\"connection-note\">双方需能访问所选歌曲，账号权益与地区限制由音源决定。直连和中继可用性以实际网络为准。</p></div></div></section>\n<section class=\"section shell everyday-section\" data-acc=\"ochre\" data-reveal id=\"everyday\" aria-labelledby=\"everyday-title\"><p class=\"eyebrow\"><span class=\"eyebrow__no\">04</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">LITTLE THINGS, EVERY DAY</span></p><div class=\"section-heading\"><h2 id=\"everyday-title\">从第一首喜欢，<br>到你自己的音乐日常。</h2><p>一些小工具，一点点积累。<br>让好音乐有地方留下，也有机会被分享。</p></div>\n<div class=\"everyday-grid\"><article class=\"everyday-card cache-card\"><div class=\"tool-top\"><span class=\"tool-number\">01 / OFFLINE</span><span class=\"tool-tag\">完整缓存回放</span></div><h3>把好声音，留给没网的时候。</h3><p>已完整缓存的歌曲可以直接点播。缓存默认 1 GiB，最高可调至 16 GiB，在独立离线列表中查看和管理。</p><div class=\"storage-diagram\" aria-hidden=\"true\"><div class=\"storage-head\"><span>给音乐多一点空间</span><strong>1 → 16 <small>GiB</small></strong></div><div class=\"storage-track\"><i></i></div><div class=\"storage-labels\"><span>日常轻装</span><span>更多离线时刻</span></div></div></article>\n<article class=\"everyday-card library-card\"><div class=\"tool-top\"><span class=\"tool-number\">02 / COLLECTION</span><span class=\"tool-tag\">本地歌单与队列</span></div><h3>喜欢的歌，有自己的位置。</h3><p>创建本地歌单，管理收藏。临时想到一首歌，可以插播到下一首，也能追加到队尾。</p><div class=\"queue-diagram\" aria-label=\"队列操作概念图\"><div><span class=\"album-tile tile-a\">♫</span><span>此刻正在听<small>正在播放</small></span><span class=\"queue-eq\" aria-hidden=\"true\">▂▅▃</span></div><div><span class=\"album-tile tile-b\">↳</span><span>刚刚想到的那首<small>添加到下一首播放</small></span><span>02</span></div><div><span class=\"album-tile tile-c\">＋</span><span>留到后面慢慢听<small>追加到队列</small></span><span>03</span></div></div><small class=\"diagram-note\">队列操作示意</small></article>\n<article class=\"everyday-card stats-card\"><div class=\"tool-top\"><span class=\"tool-number\">03 / YOUR RHYTHM</span><span class=\"tool-tag\">桌面 · 统计 · 分享</span></div><h3>日常的陪伴，也值得记住。</h3><p>桌面组件随手播放，用收听统计回望音乐足迹；把喜欢的歌词选成片段，复制或生成分享图。</p><div class=\"rhythm-diagram\" aria-hidden=\"true\"><div class=\"rhythm-bars\"><i style=\"--bar:34%\"></i><i style=\"--bar:58%\"></i><i style=\"--bar:43%\"></i><i style=\"--bar:77%\"></i><i style=\"--bar:56%\"></i><i style=\"--bar:92%\"></i><i style=\"--bar:68%\"></i></div><span>每一段旋律，都是日常的一小部分。</span></div><small class=\"diagram-note\">收听足迹概念图，非真实用户数据</small></article>\n<article class=\"everyday-card tux-card\"><div class=\"tool-top\"><span class=\"tool-number\">04 / A LITTLE SURPRISE</span><span class=\"tool-tag\">Tux 陪伴</span></div><div class=\"tux-story\"><div><h3>不只在耳边，<br>也在特别的那一天。</h3><p>设置生日，让 Tux 带来蛋糕与祝福。错过当天，一周内首次打开也能收到迟来的心意。</p><span class=\"secret-hint\">还有一个小秘密：试试连点版本号 7 次。</span></div><svg viewBox=\"0 0 280 315\" class=\"story-tux\" aria-label=\"戴绿色耳机的 Tux 吉祥物\" role=\"img\"><use href=\"#tux-base\"/></svg></div></article></div></section>\n<section class=\"film-section shell\" aria-labelledby=\"film-title\"><div class=\"film-heading\"><p class=\"eyebrow\"><span class=\"eyebrow__no\">THE ARRIS STORY</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">03:39</span></p><h2 id=\"film-title\">花一首歌的时间，<br>认识 Arris。</h2><p>从 Ncrust 出发，看看这个 fork 的功能、修复与体验演进。</p><span>中文配音 · 中文字幕 · 点击播放</span></div><figure class=\"film-player\"><video controls playsinline preload=\"none\" poster=\"/assets/film-poster.webp\" aria-label=\"Arris 项目介绍影片，3 分 39 秒，包含中文配音与字幕\"><source src=\"/assets/arris-introduction.mp4\" type=\"video/mp4\"><track kind=\"captions\" srclang=\"zh-CN\" label=\"中文旁白\" src=\"/assets/introduction.zh-CN.vtt\"><p>当前浏览器无法播放此视频，可<a href=\"/assets/arris-introduction.mp4\">下载介绍影片</a>。</p></video><figcaption>影片内容截至 3.8.9；含标明版本的历史实录。最新修复请查看下方更新说明。</figcaption></figure></section>\n<section class=\"evolution-section\" id=\"evolution\" aria-labelledby=\"evolution-title\"><div class=\"shell\"><p class=\"eyebrow\"><span class=\"eyebrow__no\">05</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">ROOTS & GROWTH</span></p><div class=\"section-heading\"><h2 id=\"evolution-title\">继承好的基础，<br>继续长出新的可能。</h2><p>原作 Ncrust 提供了出发点。<br>Arris 围绕多音源、歌词、离线与日常体验继续扩展。</p></div>\n<div class=\"comparison-wrap\"><table class=\"comparison\"><caption>以 fork 起点 Ncrust v1.3.1 为对照，不代表当前上游版本的状态。</caption><thead><tr><th scope=\"col\">听歌的不同侧面</th><th scope=\"col\">原版基础 <small>NCRUST v1.3.1</small></th><th scope=\"col\">Arris 的延伸 <small>BUILT ON TOP</small></th></tr></thead><tbody><tr><th scope=\"row\">音乐来源</th><td>网易云曲库与基础播放</td><td>网易云 + QQ；可选 B站音源、聚合搜索</td></tr><tr><th scope=\"row\">歌词阅读</th><td>逐行歌词与翻译</td><td>多源逐字、音译与双语主行选择</td></tr><tr><th scope=\"row\">音乐日常</th><td>队列、播放模式与状态恢复</td><td>完整离线缓存、本地歌单、桌面组件与统计</td></tr><tr><th scope=\"row\">新的相遇</th><td>发现、推荐与曲库浏览</td><td>听歌识曲、一起听房间与共同待播</td></tr><tr><th scope=\"row\">你的界面</th><td>主题、多语言与大屏基础</td><td>松绿纸感、音频可视化、可调动效与 Tux</td></tr></tbody></table></div>\n<div class=\"release-heading\"><h3>最近，继续把细节做好。</h3><a class=\"text-link\" href=\"https://github.com/yaxiaiyuting/Arris/releases\" target=\"_blank\" rel=\"noopener noreferrer\">完整发布记录 ↗</a></div><div class=\"release-grid mainline\"><article><div class=\"release-version\"><span>4.0.14</span><small>歌词同步</small></div><h4>开唱时，歌词就跟上。</h4><p>有些歌曲的逐字时间早于整行标记。新版按首字时间切句，拖动进度后也会及时对齐歌词。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.14-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.14 发布说明\">查看说明 ↗</a></article><article><div class=\"release-version\"><span>4.0.11</span><small>歌词</small></div><h4>换行也有交接。</h4><p>当前句切换时模糊→清晰、偏下→就位；横屏补上当前句竖条。收藏改为底表判定，点了不会再被反手取消。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.11-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.11 发布说明\">查看说明 ↗</a></article><article><div class=\"release-version\"><span>4.0.10</span><small>下载</small></div><h4>多条线路一起下。</h4><p>段轮转分配（本站 / Gitee / ghproxy），Gitee 单流回退；某个镜像挂了不影响下载。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.10-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.10 发布说明\">查看说明 ↗</a></article><article><div class=\"release-version\"><span>4.0.6</span><small>歌词</small></div><h4>比例回到该在的位置。</h4><p>控制栏没收起时歌词被挤在上半屏——去掉面板底部留白，锚点回到视口 36%。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.6-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.6 发布说明\">查看说明 ↗</a></article><article><div class=\"release-version\"><span>4.0.4</span><small>稳定性</small></div><h4>续播、更新与一起听。</h4><p>保护续播进度，修复更新下载和识曲取消。一起听升级房主与 P2P 认证，参与者需一同更新并重新建房。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.4-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.4 发布说明\">查看说明 ↗</a></article><article><div class=\"release-version\"><span>4.0.3</span><small>审查</small></div><h4>把确认过的，逐个关掉。</h4><p>FM 意图、分段下载取消与校验、房主唯一、P2P 握手门禁、识曲可取消。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.3-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.3 发布说明\">查看说明 ↗</a></article><article><div class=\"release-version\"><span>4.0.2</span><small>队列</small></div><h4>该退出的，别赖着。</h4><p>视觉收尾：播放页光效、波形、生日动画横竖屏；队列三处修复同版本包含。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.2-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.2 发布说明\">查看说明 ↗</a></article><article><div class=\"release-version\"><span>4.0.0</span><small>均衡器 &amp; 工具链</small></div><h4>听感与歌词，一起跨代。</h4><p>10 段均衡器进设置页；间奏呼吸点；Kotlin 2 + media3 1.9。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.0-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 4.0.0 发布说明\">查看说明 ↗</a></article></div><div class=\"release-grid lts-row\"><article class=\"lts\"><div class=\"release-version\"><span>3.7.33</span><small>LTS</small></div><h4>旧设备也能跟上歌词。</h4><p>回移歌词慢半拍修复。普通歌词和跟唱对齐首字时间，拖动进度后及时切句。</p><a href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v3.7.33-gpl\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"查看 3.7.33 LTS 发布说明\">查看说明 ↗</a></article></div><p class=\"evolution-note\">修复记录包含整个 fork 迭代中发现的问题。感谢原作、上游项目和每一次认真反馈。</p></div></section>\n<section class=\"section shell getting-started\" id=\"getting-started\" aria-labelledby=\"start-title\"><p class=\"eyebrow\"><span class=\"eyebrow__no\">06</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">PRESS PLAY</span></p><div class=\"section-heading\"><h2 id=\"start-title\">第一次见面，<br>从这三步开始。</h2><p>选版本、连接曲库，然后播放。<br>更多设置，可以跟着自己的习惯慢慢调整。</p></div><ol class=\"onboard-steps\"><li><span>01</span><h3>选对设备版本</h3><p>Android 10 及以上选择主线；Android 7–9 可使用 3.7.x LTS。已有安装优先覆盖更新。</p><a class=\"text-link\" href=\"#download\">帮我选版本 ↘</a></li><li><span>02</span><h3>连接你的音乐账号</h3><p>网易云与 QQ 分别登录。各平台的歌单、收藏和会员权益仍属于对应账号。</p><a class=\"text-link\" href=\"#features\">了解音乐与歌词 ↗</a></li><li><span>03</span><h3>找到下一首喜欢</h3><p>搜索歌曲、打开歌单，或试试听歌识曲。想留给离线时刻，可以在完整缓存后从离线列表播放。</p><a class=\"text-link\" href=\"#together\">邀请朋友一起听 ↗</a></li></ol></section>\n\n<section class=\"download-section shell\" data-acc=\"pine\" data-reveal id=\"download\" aria-labelledby=\"download-title\"><div class=\"download-heading\"><p class=\"eyebrow\"><span class=\"eyebrow__no\">07</span><i class=\"eyebrow__rule\" aria-hidden=\"true\"></i><span class=\"eyebrow__label\">YOUR NEXT FAVORITE</span></p><h2 id=\"download-title\">把喜欢，带在身边。</h2><p>选择适合你设备的版本。下一首喜欢，从这里开始。</p></div>\n<div class=\"version-helper\"><div><label for=\"android-version\">不确定选哪个？</label><p>按手机的 Android 版本选择。</p></div><select id=\"android-version\" aria-describedby=\"version-advice\"><option value=\"modern\">Android 10 或更高</option><option value=\"legacy\">Android 7 / 8 / 9</option><option value=\"older\">低于 Android 7</option></select><p id=\"version-advice\" role=\"status\" aria-live=\"polite\">推荐主线，获得新功能与持续修复。</p><a class=\"text-link\" id=\"recommended-download\" href=\"/get/latest.apk\">下载主线 APK ↗</a></div>\n<div class=\"versions\"><article class=\"version featured\"><div class=\"version-top\"><span>ARRIS / 4.0.14</span><span class=\"version-badge\">主线 · Android 10+</span></div><h3>一起发现新声音。</h3><div class=\"requirements\">Android 10 及以上</div><p>v4.0.14-gpl · 修复部分歌曲歌词慢半拍。新句在首字开始时进入高亮，播放位置更新后及时切句。</p><a class=\"button button-primary\" href=\"/get/Arris-v4.0.14-gpl.apk\">直接下载 APK <svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M14 3h7v7m0-7L10 14M10 3H3v18h18v-7\"/></svg></a><div class=\"mirror-row\"><span class=\"mirror-label\">备用下载</span><a class=\"mirror-link\" href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v4.0.14-gpl\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub Release</a></div></article><article class=\"version\"><div class=\"version-top\"><span>ARRIS / 3.7.33 LTS</span><span class=\"version-badge\">LTS · Android 7+</span></div><h3>让熟悉的，一直好用。</h3><div class=\"requirements\">Android 7.0 及以上</div><p>v3.7.33-gpl · 修复部分歌曲歌词慢半拍。普通歌词和跟唱都按首字切句，Android 7.0 起可安装。</p><a class=\"button button-outline\" href=\"/get/Arris-v3.7.33-lts-gpl.apk\">下载 LTS 版 <svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M14 3h7v7m0-7L10 14M10 3H3v18h18v-7\"/></svg></a><div class=\"mirror-row\"><span class=\"mirror-label\">备用下载</span><a class=\"mirror-link\" href=\"https://github.com/yaxiaiyuting/Arris/releases/tag/v3.7.33-gpl\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub Release</a></div></article></div>\n\n<p class=\"download-note\">国内建议用 Gitee 或 gh-proxy 镜像，官网直链走 Cloudflare、速度取决于线路；三条镜像的包与本机 SHA-256 一致。<br>请在 GitHub Release 页面核对版本、最低系统要求和安装包说明。<br>音源账号、会员权益和曲目版权由平台决定，Arris 不提供或分发音乐内容。</p>\n<div class=\"faq\"><h3 class=\"faq-heading\">开始之前，你可能想知道。</h3>\n<details><summary>需要分别登录网易云和 QQ 音乐吗？</summary><p>是的，两个音源使用各自的账号和登录态。会员专享、高音质、付费曲目等权限仍由对应平台判断。</p></details>\n<details><summary>识曲和一起听在所有版本都能用吗？</summary><p>这些功能在 3.8.x 功能线中持续开发，旧版本不一定提供。请查看对应 Release 的更新说明；识曲需要麦克风权限及网易云登录态，一起听需要双方可用的连接方式和曲目权限。</p></details>\n<details><summary>下载很慢，或者老是断？</summary><div>本站直链走 Cloudflare，国内速度取决于线路。可以先试 <a href=\"/get/latest.apk\">主站直链</a>（支持断点续传）；仍然慢的话用 <a href=\"https://github.com/yaxiaiyuting/Arris/releases\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub Releases</a>。安装前可按发布页的 SHA-256 校验安装包。</div></details>\n<details><summary>主线和 LTS 有什么区别？</summary><p>主线面向 Android 10 及以上，承载新功能与体验更新。3.7.x LTS 兼容 Android 7 起，主要维护错误修复与稳定性。不同系统上的可见功能和系统媒体面板样式可能不同，请以所装版本的发布说明为准。</p></details>\n<details><summary>离线缓存等于下载了完整歌曲吗？</summary><p>播放过一部分，不代表整首已缓存。只有完整缓存的音频才能在离线列表直接回放；缓存会受容量上限和清理操作影响。音乐权限仍由对应音源决定。</p></details>\n<details><summary>怎样开启双语歌词或发现 Tux 彩蛋？</summary><p>在歌词设置中调整翻译、音译和主行偏好；没有翻译时会回退单行。系统通知和实况窗的最终可见行数取决于系统模板。生日可以在应用中设置，关于页连续点击版本号 7 次还有一个小彩蛋。</p></details>\n<details><summary>想反馈问题，或者参与开发？</summary><p>欢迎到 <a href=\"https://github.com/yaxiaiyuting/Arris/issues\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub Issues</a> 描述复现步骤、应用版本和设备信息。源码、构建配置与许可证都保留在仓库中。</p></details>\n<details><summary>中继接口与自行构建说明</summary><div>本站同时提供一起听中继，仅转发房间状态，不转发音频。接口如下：<div class=\"api-list\"><code>GET /health — 健康检查</code><code>GET /rooms — 活跃房间目录</code><code>WS /room/&lt;房间码&gt;?role=host|member&amp;name=昵称</code></div>客户端与中继构建方法见 <a href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\">项目源码及 README</a>。签名 Release 需要配置 keystore.properties。</div></details>\n</div></section>\n</main>\n<dialog class=\"screenshot-dialog\" aria-labelledby=\"screenshot-dialog-title\"><div class=\"dialog-top\"><p id=\"screenshot-dialog-title\">Arris 界面预览</p><button type=\"button\" id=\"close-screenshot\" aria-label=\"关闭截图预览\">关闭 ×</button></div><img alt=\"\" id=\"large-screenshot\"><p class=\"dialog-source\">CSS 版式示意 · 结构与配色对齐 4.0.x</p></dialog>\n<footer class=\"site-footer\"><div class=\"shell\"><div class=\"footer-main\"><div class=\"footer-brand\"><a class=\"brand\" href=\"#\">Arris<span style=\"color:#7e9570;font-weight:350\">.</span></a><p>为自由，也为每一首好音乐。</p></div><div class=\"footer-links\"><a href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub ↗</a><a href=\"https://github.com/yaxiaiyuting/Arris/releases/latest\" target=\"_blank\" rel=\"noopener noreferrer\">最新正式发布 ↗</a><a href=\"https://github.com/yaxiaiyuting/Arris/issues\" target=\"_blank\" rel=\"noopener noreferrer\">反馈问题 ↗</a><a href=\"https://github.com/yaxiaiyuting/Arris#license\" target=\"_blank\" rel=\"noopener noreferrer\">开源许可 ↗</a></div></div><div class=\"footer-legal\"><p>开源 Android 音乐客户端 · GPLv3。与网易云音乐、QQ 音乐官方无关联。<br>基于 Ncrust，使用 Kanesumi 设计语言。Tux 原始角色：Larry Ewing / The GIMP；此处沿用 Arris 项目矢量演绎。</p><p>© 2026 Arris contributors<br>Made for the love of music.</p></div></div></footer>\n<script>\n(()=>{'use strict';\nconst toggle=document.querySelector('.menu-toggle'),nav=document.querySelector('#nav-links');\ntoggle.addEventListener('click',()=>{const open=toggle.getAttribute('aria-expanded')!=='true';toggle.setAttribute('aria-expanded',String(open));toggle.setAttribute('aria-label',open?'收起导航':'展开导航');nav.classList.toggle('is-open',open)});\nnav.querySelectorAll('a').forEach(a=>a.addEventListener('click',()=>{toggle.setAttribute('aria-expanded','false');toggle.setAttribute('aria-label','展开导航');nav.classList.remove('is-open')}));\ndocument.addEventListener('keydown',e=>{if(e.key==='Escape'&&toggle.getAttribute('aria-expanded')==='true'){toggle.click();toggle.focus()}});\ndocument.querySelectorAll('[data-lyric]').forEach(b=>b.addEventListener('click',()=>{document.querySelector('.lyric-pair').dataset.primary=b.dataset.lyric;document.querySelectorAll('[data-lyric]').forEach(x=>x.setAttribute('aria-pressed',String(x===b)))}));\nconst tabs=[...document.querySelectorAll('[data-tab]')];\nfunction selectTab(tab,focus){tabs.forEach(b=>{const active=b===tab;b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;document.getElementById(b.getAttribute('aria-controls')).hidden=!active});if(focus)tab.focus()}\ntabs.forEach((b,i)=>{b.addEventListener('click',()=>selectTab(b,false));b.addEventListener('keydown',e=>{let n;if(e.key==='ArrowRight')n=(i+1)%tabs.length;if(e.key==='ArrowLeft')n=(i+tabs.length-1)%tabs.length;if(e.key==='Home')n=0;if(e.key==='End')n=tabs.length-1;if(n!==undefined){e.preventDefault();selectTab(tabs[n],true)}})});\nconst status=document.querySelector('#relay-status');\ndocument.querySelector('#copy-relay').addEventListener('click',async()=>{try{await navigator.clipboard.writeText('https://arris.de5.net');status.textContent='已复制中继地址。'}catch{status.textContent='请手动复制上方地址：https://arris.de5.net'}});\ndocument.querySelector('#check-health').addEventListener('click',async e=>{const b=e.currentTarget;b.disabled=true;status.textContent='正在检查中继…';const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),6000);try{const r=await fetch('/health',{signal:controller.signal,cache:'no-store'});if(!r.ok||(await r.text()).trim()!=='ok')throw new Error('unavailable');status.textContent='中继响应正常，可以在客户端尝试连接。'}catch{status.textContent='暂时无法确认中继状态，请稍后重试。'}finally{clearTimeout(timeout);b.disabled=false}});\n})();\n</script>\n<script src=\"/assets/discover.js?v=20261102b\" defer></script>\n<script src=\"/assets/studio-player-motion.js\" defer></script>\n</body></html>";
