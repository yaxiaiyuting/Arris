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

const MAX_MEMBERS = 16;

/** 本站托管的 APK 名单与尺寸（部署时由 tools/sync-dl-manifest.mjs 生成）。 */
const HOSTED = new Set(HOSTED_APK);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // 首页：项目介绍（静态 HTML 打包在 Worker 里，域名一绑即可访问）
    if (url.pathname === "/" || url.pathname === "/index.html") {
      return new Response(INDEX_HTML, {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-cache, must-revalidate" },
      });
    }
    // ---------------------------------------------------------------- APK 直链（支持 Range）
    //
    // Cloudflare 的静态资源服务**不认 Range**：`curl -r 0-1023` 会拿到整个 16MB。
    // 国内网络抖动多，下载器（IDM / aria2 / 浏览器续传）没有 206 就只能从 0 重来，
    // 体感就是「下载老是失败」。这里自己实现 206：边缘返回 200 时按 header 切片。
    // `/get/*`：支持 Range 的下载入口。`/dl/*` 是静态资源直出（快，但 Cloudflare
    // 的资产层不认 Range），所以续传/多线程走这里：路径本身没有同名静态文件，
    // 保证请求一定进 Worker。
    if (url.pathname.startsWith("/get/") || url.pathname.startsWith("/dl/")) {
      const assetPath = url.pathname.startsWith("/get/")
        ? "/dl/" + url.pathname.slice("/get/".length)
        : url.pathname;
      const assetUrl = new URL(assetPath, url.origin).toString();
      const range = request.headers.get("range");
      const mark = { "x-arris-route": "dl", "x-arris-range": String(range) };
      if (request.method !== "GET") {
        const head = await env.ASSETS.fetch(new Request(assetUrl, { method: "HEAD" }));
        const h = new Headers(head.headers);
        h.set("accept-ranges", "bytes");
        for (const [k, v] of Object.entries(mark)) h.set(k, v);
        return new Response(null, { status: head.status, headers: h });
      }

      // 1) 有 Range：先问 ASSETS 要这一段（转发 Range 头）。它若真给了 206 就直接用。
      if (range) {
        const part = await env.ASSETS.fetch(new Request(assetUrl, { headers: { range } }));
        if (part.status === 206) {
          const h = new Headers(part.headers);
          for (const [k, v] of Object.entries(mark)) h.set(k, v);
          h.set("accept-ranges", "bytes");
          return new Response(part.body, { status: 206, headers: h });
        }
        // 2) 拿不到 206（资产层不认 Range）→ 自己按总量流式切片。
        // ASSETS 的响应既没有 content-length、也不认 Range（实测：三种请求全 200 且 len=null），
        // 总长只能来自部署时生成的清单。
        const total = Number(DL_SIZE[decodeURIComponent(assetPath.slice("/dl/".length))] || 0);
        const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
        if (total > 0 && m) {
          const start = m[1] === "" ? 0 : parseInt(m[1], 10);
          const rawEnd = m[2] === "" ? total - 1 : parseInt(m[2], 10);
          const end = Math.min(Number.isFinite(rawEnd) ? rawEnd : total - 1, total - 1);
          if (!Number.isFinite(start) || start >= total || start > end) {
            return new Response(null, { status: 416, headers: { "content-range": `bytes */${total}`, "accept-ranges": "bytes", ...mark } });
          }
          let cursor = 0;
          const slicer = new TransformStream({
            transform(chunk, controller) {
              const cs = cursor;
              const ce = cursor + chunk.byteLength - 1;
              cursor += chunk.byteLength;
              if (ce < start || cs > end) return;
              const from = Math.max(start - cs, 0);
              const to = Math.min(end - cs, chunk.byteLength - 1);
              controller.enqueue(chunk.subarray(from, to + 1));
            },
          });
          const full = await env.ASSETS.fetch(new Request(assetUrl));
          return new Response(full.body.pipeThrough(slicer), {
            status: 206,
            headers: {
              ...mark,
              "content-type": full.headers.get("content-type") || "application/vnd.android.package-archive",
              "content-range": `bytes ${start}-${end}/${total}`,
              "content-length": String(end - start + 1),
              "accept-ranges": "bytes",
              "cache-control": "public, max-age=31536000, immutable",
              "etag": full.headers.get("etag") || "",
            },
          });
        }
        return part;
      }

      // 3) 无 Range：整包直出，但补上 Accept-Ranges 说明支持续传。
      const full = await env.ASSETS.fetch(new Request(assetUrl));
      const h = new Headers(full.headers);
      h.set("accept-ranges", "bytes");
      for (const [k, v] of Object.entries(mark)) h.set(k, v);
      return new Response(full.body, { status: full.status, headers: h });
    }

    // ---------------------------------------------------------------- 更新检查（不经 GitHub）
    if (url.pathname === "/api/releases" || url.pathname === "/api/latest") {
      const gh = await fetch(
        "https://api.github.com/repos/yaxiaiyuting/Arris/releases?per_page=100",
        {
          headers: { "user-agent": "Arris-Relay/1.0", accept: "application/vnd.github+json" },
          cf: { cacheEverything: true, cacheTtl: 300 },
        },
      );
      if (!gh.ok) return Response.json({ error: "upstream_unavailable" }, { status: 502 });
      let list = await gh.json();
      if (!Array.isArray(list)) list = [];
      list = list.filter((r) => r && !r.draft).map((r) => ({
        tag_name: r.tag_name,
        name: r.name,
        draft: !!r.draft,
        prerelease: !!r.prerelease,
        published_at: r.published_at,
        html_url: r.html_url,
        body: typeof r.body === "string" ? r.body.slice(0, 4000) : "",
        assets: (r.assets || [])
          .filter((a) => a && /\.apk$/i.test(a.name || ""))
          .map((a) => ({
            id: a.id,
            name: a.name,
            size: a.size,
            browser_download_url: HOSTED.has(a.name)
              ? `${url.origin}/dl/${encodeURIComponent(a.name)}`
              : a.browser_download_url,
          })),
      }));
      const headers = {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "public, max-age=300",
        "access-control-allow-origin": "*",
      };
      if (url.pathname === "/api/latest") {
        const latest = list.find((r) => !r.prerelease) || list[0] || null;
        const apk = latest?.assets?.find((a) => /\.apk$/i.test(a.name || "")) || null;
        return new Response(JSON.stringify({
          version: latest?.tag_name || null,
          name: latest?.name || null,
          publishedAt: latest?.published_at || null,
          pageUrl: latest?.html_url || null,
          body: latest?.body || null,
          apk: apk ? { name: apk.name, size: apk.size, url: apk.browser_download_url } : null,
        }), { headers });
      }
      return new Response(JSON.stringify(list), { headers });
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
    const m = url.pathname.match(/^\/room\/([A-Za-z0-9_-]{4,32})$/);
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
    const rooms = (await this.state.storage.get("rooms")) || {};

    if (url.pathname === "/list") {
      const now = Date.now();
      const alive = Object.entries(rooms)
        .filter(([, r]) => now - r.seenAt < 90_000)
        .map(([id, r]) => ({ roomId: id, host: r.host, members: r.members, title: r.title, seenAt: r.seenAt }));
      return Response.json({ rooms: alive });
    }
    if (url.pathname === "/beat") {
      const body = await request.json();
      rooms[body.roomId] = {
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
      if (id && rooms[id]) { delete rooms[id]; await this.state.storage.put("rooms", rooms); }
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
    this.roomId = "";
  }

  async fetch(request) {
    const url = new URL(request.url);
    this.roomId = url.pathname.split("/").pop() || "";
    // 关键：Hibernation API 会在空闲后重建 DO 实例，内存字段会丢。
    // roomId 必须落盘，否则 beat/drop 会写到空房间名上 ——
    // 现象就是「房间永远不销毁」和「退出再进连不上」。
    if (this.roomId) await this.state.storage.put("roomId", this.roomId);
    else this.roomId = (await this.state.storage.get("roomId")) || "";
    const role = url.searchParams.get("role") === "host" ? "host" : "member";
    const name = (url.searchParams.get("name") || "").slice(0, 24);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    // Hibernation API：连接交给运行时托管，房间静止时不烧时长
    this.state.acceptWebSocket(server, [role]);

    const members = this.state.getWebSockets();
    if (members.length > MAX_MEMBERS) {
      server.close(4001, "room full");
      return new Response(null, { status: 101, webSocket: client });
    }

    // 成员进来时如果房里已经没有房主，直接告诉他房间结束了 ——
    // 否则他会连上一个「有连接但永远没有快照」的死房间（就是用户说的
    //「退出去再连回来没法同步、报错」）。
    if (role === "member") {
      const hasHost = this.state.getWebSockets().some((ws) => {
        try { return (ws.deserializeAttachment() || {}).role === "host"; } catch (_) { return false; }
      });
      if (!hasHost) {
        try { server.send(JSON.stringify({ t: "closed", reason: "no-host" })); } catch (_) {}
      }
    }

    // 给新成员一个稳定的 id，并广播「谁进来了」
    server.serializeAttachment({ role, name, joinedAt: Date.now() });
    const hello = JSON.stringify({ t: "presence", role, name, members: members.length });
    for (const ws of members) {
      try { ws.send(hello); } catch (_) {}
    }
    await this.beat();
    return new Response(null, { status: 101, webSocket: client });
  }

  /** 收到一条消息：原样转给同房间的其他人（含房主→成员、成员→房主）。 */
  /** 房间状态变化时顺手更新目录，供 /rooms 扫描。 */
  async beat(title = "") {
    try {
      const dir = this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("global"));
      const sockets = this.state.getWebSockets();
      const host = sockets.map((s) => s.deserializeAttachment() || {}).find((a) => a.role === "host");
      await dir.fetch("https://directory/beat", {
        method: "POST",
        body: JSON.stringify({
          roomId: this.roomId || "",
          host: host?.name || "",
          members: sockets.length,
          title,
        }),
      });
    } catch (_) {}
  }

  async webSocketMessage(ws, message) {
    const meta = ws.deserializeAttachment() || {};
    for (const peer of this.state.getWebSockets()) {
      if (peer === ws) continue;
      try { peer.send(message); } catch (_) {}
    }
    await this.beat();
  }

  /** 把房间从目录里摘掉（房间空了或房主离开时）。 */
  async drop(title = "") {
    try {
      const dir = this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("global"));
      await dir.fetch(`https://directory/drop?roomId=${encodeURIComponent(this.roomId || "")}`);
    } catch (_) {}
  }

  async webSocketClose(ws, code, reason) {
    await this.leave(ws, code, reason);
  }

  async webSocketError(ws) {
    await this.leave(ws, 1011, "error");
  }

  /**
   * 有人离开（v3.8.1）。
   *
   * 旧实现只广播 presence，**从不清理目录、也不处理「房主走了」**：
   * 房主退出后房间仍然挂在 /rooms 里，成员连着一个没有房主的房间等快照。
   * 现在的规则：
   * - 房主离开 ⇒ 房间作废，通知所有人后立刻从目录删除；
   * - 普通成员离开且房间已空 ⇒ 删目录；
   * - 还有人 ⇒ 刷新一次目录（成员数变了）。
   */
  async leave(ws, code, reason) {
    const meta = ws.deserializeAttachment() || {};
    const peers = this.state.getWebSockets().filter((p) => p !== ws);
    const presence = JSON.stringify({
      t: "presence", role: meta.role, name: meta.name, members: peers.length, left: true,
    });
    for (const peer of peers) { try { peer.send(presence); } catch (_) {} }
    try { ws.close(code, reason); } catch (_) {}

    if (meta.role === "host") {
      const closed = JSON.stringify({ t: "closed", reason: "host-left" });
      for (const peer of peers) { try { peer.send(closed); } catch (_) {} }
      await this.drop();
    } else if (peers.length === 0) {
      await this.drop();
    } else {
      await this.beat();
    }
  }
}

// 首页内容由 tools/sync-homepage.mjs 从 public/index.html 生成。
const INDEX_HTML = "<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n<meta name=\"theme-color\" content=\"#f6f7f2\">\n<title>Arris — 喜欢的音乐，都在这里。</title>\n<meta name=\"description\" content=\"Arris，开源 Android 音乐播放器。网易云与 QQ 音乐双源、双语逐字歌词、听歌识曲，与朋友一起听。找到你的下一首喜欢。\">\n<meta property=\"og:title\" content=\"Arris — 喜欢的音乐，都在这里。\"><meta property=\"og:description\" content=\"双音源、逐字歌词、听歌识曲、一起听。一个好好听歌的开源 Android 客户端。\"><meta property=\"og:type\" content=\"website\"><meta property=\"og:url\" content=\"https://arris.de5.net/\">\n<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 40'%3E%3Crect width='40' height='40' rx='12' fill='%23184032'/%3E%3Cpath d='M12 27V14l16-3v13M12 23c-8-1-8 8-1 7M28 20c-8-1-8 8-1 7' stroke='%23d7edaa' stroke-width='3' fill='none'/%3E%3C/svg%3E\">\n<style>\n:root{--paper:#f6f7f2;--white:#fff;--ink:#173c2e;--muted:#66736a;--green:#286345;--lime:#d7eaa5;--border:#dfe4da;--serif:Georgia,'Noto Serif CJK SC',serif;--sans:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI','Noto Sans CJK SC','PingFang SC','Microsoft YaHei',sans-serif}\n*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:100px}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.7 var(--sans);-webkit-font-smoothing:antialiased}a{color:inherit;text-decoration:none}button{font:inherit;color:inherit;cursor:pointer}button,a{-webkit-tap-highlight-color:transparent}button:disabled{cursor:wait;opacity:.65}button:focus-visible,a:focus-visible,summary:focus-visible{outline:3px solid #5c9658;outline-offset:5px}button{border:0}img,svg{display:block}::selection{background:#d7eaa5;color:#173c2e}h1,h2,h3,p{margin:0}h1,h2,h3{text-wrap:balance}p{text-wrap:pretty}.icon{width:21px;height:21px;flex:none}.svg-defs{position:absolute;width:0;height:0;overflow:hidden}.shell{width:min(1180px,calc(100% - 96px));margin-inline:auto}.skip-link{position:absolute;top:-60px;left:20px;z-index:99;padding:10px 20px;background:var(--ink);color:#fff}.skip-link:focus{top:10px}.site-header{position:sticky;top:0;z-index:20;background:rgba(246,247,242,.93);backdrop-filter:blur(18px);border-bottom:1px solid rgba(23,60,46,.07)}.nav{height:85px;display:flex;align-items:center;justify-content:space-between;gap:24px}.brand{display:flex;align-items:center;gap:10px;font-size:27px;font-weight:750;letter-spacing:-1px}.brand-mark{width:39px;height:39px;border-radius:12px;background:var(--ink);overflow:hidden;padding:3px}.brand-mark svg{width:100%;height:100%}.nav-links{display:flex;align-items:center;gap:32px;font-size:13px;font-weight:550}.nav-links>a:not(.nav-download){color:#65736a}.nav-links>a:hover{color:var(--green)}.nav-download{background:var(--ink);color:#fff;padding:10px 19px;border-radius:8px;display:flex;align-items:center;gap:10px}.nav-download .icon{width:15px;height:15px}.menu-toggle{display:none;background:transparent;padding:8px}.hero{display:grid;grid-template-columns:1fr 1fr;align-items:center;gap:38px;padding-block:70px 45px;min-height:700px}.eyebrow{font-size:10px;font-weight:650;letter-spacing:2.2px;display:flex;align-items:center;gap:10px;line-height:1.5}.eyebrow:before{content:'';width:23px;height:1px;background:currentColor}.hero-label{display:inline-flex;align-items:center;gap:8px;font-size:11px;border:1px solid #d5dfd0;border-radius:30px;padding:6px 12px;margin-bottom:26px;color:#54715a;background:#eef2e7}.small-dot{width:6px;height:6px;border-radius:50%;background:#6b984e;flex:none}.hero h1{font-size:clamp(48px,5.25vw,70px);line-height:1.2;font-weight:760;letter-spacing:-3px;margin-bottom:24px}.hero h1 span{color:#60854b}.hero-description{color:var(--muted);font-size:15px;line-height:1.95;max-width:405px}.hero-actions{display:flex;gap:12px;margin-top:32px;flex-wrap:wrap}.button{display:inline-flex;align-items:center;justify-content:center;gap:12px;min-height:50px;padding:12px 23px;font-size:13px;font-weight:650;border-radius:9px;transition:background .2s,transform .2s,border-color .2s}.button:hover{transform:translateY(-2px)}.button-primary{background:var(--ink);color:#fff;box-shadow:0 7px 18px #173c2e12}.button-primary:hover{background:#2c6045}.button-outline{background:transparent;border:1px solid #ccd5c7}.button-outline:hover{border-color:#6d8b62;background:#eef1e8}.button .icon{width:17px;height:17px}.hero-meta{font-size:10px;color:#626f5c;display:flex;gap:14px;margin-top:19px;flex-wrap:wrap}.hero-meta span+span:before{content:'·';margin-right:14px}.hero-visual{height:575px;position:relative;isolation:isolate}.hero-visual:before{content:'';position:absolute;width:420px;height:420px;border-radius:50%;background:#e6ebd9;top:70px;left:calc(50% - 210px);z-index:-2}.orbit{position:absolute;border:1px solid #dce3d1;width:500px;height:500px;border-radius:50%;top:28px;left:calc(50% - 250px);z-index:-3}.orbit:before,.orbit:after{content:'';width:8px;height:8px;border:5px solid var(--paper);box-sizing:content-box;border-radius:50%;background:#91a975;position:absolute;top:45px;left:77px}.orbit:after{width:5px;height:5px;top:auto;left:auto;bottom:59px;right:58px;background:#b7c28f}.phone{width:264px;position:absolute;left:calc(50% - 132px);top:15px;border:6px solid #254332;border-radius:34px;background:#eaf0e2;padding:14px 16px 17px;box-shadow:13px 26px 45px #21442c21,inset 0 0 0 1px #c6d0bc;transform:rotate(-6deg);color:#234734}.phone-top{display:flex;justify-content:space-between;font-size:8px;font-weight:700;padding:1px 2px 17px}.phone-signals{letter-spacing:2px}.phone-heading{display:flex;align-items:center;justify-content:space-between;font-size:9px;margin-bottom:17px}.phone-heading>span{font-size:8px;letter-spacing:2px}.album{height:208px;background:#284e39;position:relative;border-radius:12px;overflow:hidden;display:flex;justify-content:center;align-items:center}.album:before{content:'';width:155px;height:155px;position:absolute;border:1px solid #c2d7a325;border-radius:50%;box-shadow:0 0 0 24px #c2d7a30b,0 0 0 48px #c2d7a307}.album-tux{height:165px;width:155px;position:relative;margin-top:24px}.album-caption{position:absolute;top:12px;left:0;right:0;text-align:center;letter-spacing:3px;font-size:5px;color:#b8c7a3}.track-row{display:flex;align-items:center;justify-content:space-between;margin-top:18px}.track-title{font-size:17px;letter-spacing:-.5px;font-weight:700}.track-artist{font-size:8px;color:#708369;margin-top:2px}.track-row .icon{width:17px;height:17px;color:#60854b}.demo-lyrics{font-size:9px;color:#70856a;margin-top:13px;line-height:1.9}.demo-lyrics strong{color:#41623c;font-weight:650}.waveform{height:29px;width:100%;margin:9px 0 3px;opacity:.8}.demo-progress{height:2px;background:#cdd8c1;border-radius:3px}.demo-progress:before{content:'';display:block;width:37%;height:100%;background:#5c8150}.demo-times{display:flex;justify-content:space-between;margin-top:3px;font-size:6px;color:#738368}.demo-controls{display:flex;align-items:center;justify-content:space-between;margin:13px 9px 0;font-size:17px}.demo-controls>svg{width:15px;height:15px}.demo-play{border-radius:50%;width:43px;height:43px;display:grid;place-items:center;background:#284e39;color:#e8efdb}.demo-play svg{width:15px;height:15px;fill:currentColor}.pause-symbol{display:none}.demo-play[aria-pressed=\"true\"] .play-symbol{display:none}.demo-play[aria-pressed=\"true\"] .pause-symbol{display:block}.phone-bottom{width:62px;height:3px;background:#38574244;border-radius:5px;margin:18px auto 0}.float-chip{position:absolute;z-index:2;display:flex;align-items:center;gap:12px;background:#fffdf7;border:1px solid #f1f0e4;border-radius:12px;padding:14px 17px;box-shadow:0 10px 35px #2a402312;font-size:11px}.float-chip .chip-icon{width:35px;height:35px;display:grid;place-items:center;background:#eff3e6;border-radius:10px;color:#668752}.float-chip strong{display:block;font-size:11px;font-weight:650}.float-chip small{display:block;font-size:8px;color:#809073;margin-top:2px}.chip-lyrics{top:88px;left:-12px;transform:rotate(-3deg)}.chip-together{right:-8px;bottom:88px;transform:rotate(5deg);background:#dcebb9;border-color:#d1e1aa}.avatars{display:flex;padding-left:6px}.avatars span{width:28px;height:28px;border-radius:50%;border:2px solid #dcebb9;display:grid;place-items:center;margin-left:-7px;font-size:9px;font-weight:700;color:#426144;background:#f6e9bb}.avatars span:nth-child(2){background:#a8c39e}.avatars span:nth-child(3){background:#e4ba9e}.visual-caption{position:absolute;bottom:0;left:0;right:0;text-align:center;font-size:8px;color:#67765b;letter-spacing:.7px}.hero-spark{position:absolute;right:54px;top:26px;color:#8ca463;width:29px;height:29px}.source-strip{display:flex;justify-content:space-between;gap:24px;align-items:center;border-block:1px solid var(--border);padding:23px 3px;color:#697666;font-size:11px}.source-label{font-size:9px;letter-spacing:1.8px}.source-name{display:flex;align-items:center;gap:10px;font-weight:600;font-size:14px}.source-symbol{display:grid;place-items:center;border-radius:50%;width:27px;height:27px;font-size:13px;background:#f1e1da;color:#b36f5a;font-weight:650}.source-symbol.qq{background:#e8e8cd;color:#7d844e}.strip-detail{font-size:11px;color:#5e6d56}.section{padding-block:95px}.section-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:30px;margin:22px 0 34px}.section-heading h2,.download-heading h2{font-size:38px;line-height:1.35;letter-spacing:-1.7px;font-weight:700}.section-heading>p{font-size:13px;color:var(--muted);max-width:295px;line-height:1.9}.feature-grid{display:grid;grid-template-columns:1.35fr 1fr;gap:22px}.feature-card{border-radius:20px;padding:29px 31px;overflow:hidden;min-height:345px;position:relative}.lyrics-card{background:#e7eddd}.recognize-card{background:#eee9dc}.feature-label{font-size:9px;letter-spacing:1.2px;color:#526646;display:flex;align-items:center;gap:7px}.feature-label .icon{width:14px;height:14px}.feature-card h3{font-size:22px;letter-spacing:-.7px;margin-top:9px;font-weight:650}.feature-card p{font-size:12px;color:#586750;margin-top:6px}.lyric-example{margin:23px 0 16px;position:relative;padding-left:17px;border-left:2px solid #a8bf8c}.lyric-example>small{font-size:10px;color:#67785b}.lyric-pair{display:flex;flex-direction:column;min-height:72px;justify-content:center;margin-top:4px;gap:3px}.lyric-pair .original{font-family:var(--serif);font-size:25px;line-height:1.25;font-style:italic;color:#416839;order:1}.lyric-pair .translation{font-size:12px;line-height:1.6;color:#7c8e6d;order:2}.lyric-pair[data-primary=\"translation\"] .translation{font-family:var(--sans);font-size:21px;color:#416839;font-weight:600;order:1}.lyric-pair[data-primary=\"translation\"] .original{font-size:14px;color:#7c8e6d;order:2}.segmented{display:inline-flex;gap:3px;background:#dce5cf;padding:3px;border-radius:7px;font-size:9px}.segmented button{padding:5px 12px;background:transparent;border-radius:5px;color:#526448;min-height:30px}.segmented button[aria-pressed=\"true\"]{background:#fbfcf5;color:#3b6035;box-shadow:0 1px 3px #273d2110}.example-note{display:block;font-size:8px;color:#7c8c6e;margin-top:8px}.recognize-graphic{height:172px;position:relative;display:grid;place-items:center}.mic-orbit{border:1px solid #d6cfba;border-radius:50%;width:125px;height:125px;display:grid;place-items:center;position:relative}.mic-orbit:before{content:'';position:absolute;inset:13px;border:1px solid #dcd5c3;border-radius:50%}.mic-disc{width:68px;height:68px;border-radius:50%;background:#d9ca9f;display:grid;place-items:center;color:#766846;z-index:1}.mic-disc .icon{width:27px;height:27px}.sound-bar{position:absolute;display:flex;align-items:center;gap:4px;top:72px}.sound-bar.left{left:9px}.sound-bar.right{right:9px}.sound-bar i{display:block;width:3px;height:13px;background:#bdaf85;border-radius:2px}.sound-bar i:nth-child(2n){height:25px}.sound-bar i:nth-child(3n){height:37px}.recognize-caption{font-size:9px;color:#73664a;text-align:center}.mini-features{display:grid;grid-template-columns:repeat(3,1fr);gap:34px;margin-top:40px}.mini-feature{border-top:1px solid #d8dfcf;padding-top:21px}.mini-feature>.icon{width:22px;height:22px;color:#718c5c;margin-bottom:13px}.mini-feature h3{font-size:14px;font-weight:650}.mini-feature p{font-size:11px;color:#5e6d56;line-height:1.9;margin-top:7px;max-width:295px}.together-section{background:#183f30;color:#f4f4e5;padding-block:75px}.together-grid{display:grid;grid-template-columns:1fr 1fr;gap:85px;align-items:center}.together-copy .eyebrow{color:#a5b68d}.together-copy h2{font-size:42px;font-weight:650;letter-spacing:-1.8px;line-height:1.35;margin:20px 0}.together-copy>p{font-size:12px;line-height:1.95;color:#adbfaa;max-width:345px}.together-art{height:170px;position:relative;width:350px;max-width:100%;margin-top:25px}.disc{position:absolute;width:125px;height:125px;border-radius:50%;background:repeating-radial-gradient(circle at center,#102e23 0px,#102e23 6px,#2d5140 7px,#102e23 8px);border:1px solid #4a6850;top:12px;left:20px;display:grid;place-items:center;box-shadow:0 8px 25px #09251a33}.disc-center{width:44px;height:44px;border-radius:50%;background:#cecc99;display:grid;place-items:center;color:#304d32;font-size:9px;letter-spacing:1px}.disc-center:after{content:'';width:5px;height:5px;border-radius:50%;background:#25402d}.disc.second{left:190px;top:35px;transform:rotate(18deg);width:105px;height:105px}.disc.second .disc-center{background:#a7bc8c;width:37px;height:37px}.disc-link{position:absolute;left:142px;top:62px;width:56px;border-top:1px dashed #8ca17b}.disc-link:before,.disc-link:after{content:'';position:absolute;top:-3px;width:5px;height:5px;background:#aac48e;border-radius:50%}.disc-link:before{left:0}.disc-link:after{right:0}.together-art small{position:absolute;bottom:0;left:20px;font-size:8px;letter-spacing:1px;color:#a1b48d}.connection-panel{background:#224a38;border:1px solid #44634a;border-radius:16px;padding:26px}.connection-tabs{display:flex;border-bottom:1px solid #45634a;gap:20px;margin-bottom:22px}.connection-tabs button{font-size:11px;padding:0 0 12px;color:#a7bba0;background:transparent;position:relative;min-height:36px;white-space:nowrap}.connection-tabs button[aria-selected=\"true\"]{color:#e0edbf}.connection-tabs button[aria-selected=\"true\"]:after{content:'';position:absolute;bottom:-1px;left:0;right:0;height:2px;background:#cfdfaa}.connection-pane[hidden]{display:none}.connection-pane{min-height:166px}.step{display:grid;grid-template-columns:25px 1fr;gap:9px;align-items:start;margin-bottom:17px}.step>span{font-size:9px;color:#b7cb93;letter-spacing:1px;padding-top:3px}.step h3{font-size:12px;font-weight:550}.step p{font-size:10px;color:#a7bba0;margin-top:4px;line-height:1.9}.connection-note{font-size:9px;color:#9db692;border-top:1px solid #45634a;padding-top:16px;margin-top:5px;line-height:1.9}.relay-actions{display:flex;gap:9px;flex-wrap:wrap;margin-top:15px}.relay-button{background:#315a41;border:1px solid #5e7953;color:#dce9c2;border-radius:6px;padding:8px 10px;min-height:36px;font-size:10px;display:flex;align-items:center;gap:7px}.relay-button .icon{width:13px;height:13px}.relay-status{font-size:10px;color:#e0edbf;margin-top:11px;min-height:20px}.relay-address{font-family:ui-monospace,monospace;background:#193b2b;padding:9px 10px;font-size:11px;overflow-wrap:anywhere;border-radius:5px;margin-top:14px;color:#cfdfad}.download-section{padding-block:80px 75px}.download-heading{text-align:center}.download-heading .eyebrow{justify-content:center}.download-heading h2{margin-top:18px}.download-heading>p{color:var(--muted);font-size:12px;margin-top:13px}.versions{display:grid;grid-template-columns:1fr 1fr;max-width:860px;gap:22px;margin:33px auto 0}.version{padding:25px 28px;background:#fffdf7;border:1px solid var(--border);border-radius:15px;display:flex;flex-direction:column}.version-top{display:flex;justify-content:space-between;align-items:center;font-size:9px;color:#5f7054;letter-spacing:1px}.version-badge{font-size:8px;padding:3px 8px;border-radius:12px;background:#edf2e1;color:#65834b;letter-spacing:0}.version h3{font-size:24px;font-weight:650;margin-top:16px;letter-spacing:-.8px}.version p{font-size:11px;color:#5c6c52;margin:8px 0 23px;flex:1}.version .button{font-size:11px;min-height:43px;padding:9px 15px;justify-content:space-between}.version .requirements{font-size:10px;color:#6b7b5d;margin-top:6px}.version.featured{border-color:#a4b88d;background:#edf1e4}.download-note{font-size:9px;line-height:1.9;color:#87917c;text-align:center;margin:19px auto 0;max-width:620px}.faq{max-width:860px;margin:55px auto 0}.faq-heading{font-size:16px;margin-bottom:14px;letter-spacing:-.4px}.faq details{border-bottom:1px solid #dce2d3;padding:16px 0}.faq summary{font-size:12px;cursor:pointer;list-style:none;display:flex;justify-content:space-between;gap:15px;align-items:center}.faq summary::-webkit-details-marker{display:none}.faq summary:after{content:'+';font-weight:300;font-size:20px;color:#849473;line-height:1}.faq details[open] summary:after{content:'−'}.faq details>p,.faq details>div{font-size:11px;color:#53634b;line-height:1.95;padding:11px 30px 0 0}.faq details a{text-decoration:underline;text-underline-offset:3px}.api-list{display:grid;gap:7px;margin:9px 0}.api-list code{font:10px/1.8 ui-monospace,monospace;color:#48613c;overflow-wrap:anywhere}.site-footer{border-top:1px solid var(--border);padding:31px 0 23px}.footer-main{display:flex;align-items:center;justify-content:space-between;gap:25px}.footer-brand .brand{font-size:22px}.footer-brand p{font-size:10px;color:#65735c;margin-top:5px}.footer-links{display:flex;gap:24px;font-size:10px;color:#64735b}.footer-links a:hover{text-decoration:underline;text-underline-offset:4px}.footer-legal{display:flex;justify-content:space-between;gap:25px;border-top:1px solid #e3e7db;margin-top:26px;padding-top:17px;font-size:8px;color:#64725b;line-height:1.9}.footer-legal>p{max-width:650px}.entrance{animation:enter .65s ease-out both}@keyframes enter{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:translateY(0)}}\n@media(min-width:1450px){.hero{min-height:730px;padding-block:80px 60px}.hero h1{font-size:76px}.hero-visual{transform:scale(1.04)}}\n@media(max-width:1100px){.shell{width:calc(100% - 64px)}.hero{gap:10px;min-height:640px;padding-block:50px 35px}.hero h1{font-size:54px;letter-spacing:-2.5px}.hero-visual{transform:scale(.9);transform-origin:center center;margin-inline:-22px}.chip-lyrics{left:-10px}.chip-together{right:-5px}.together-grid{gap:45px}.section{padding-block:75px}.phone{top:12px}.source-label{display:none}.feature-card{padding:25px}.lyric-pair .original{font-size:22px}}\n@media(max-width:760px){.shell{width:calc(100% - 40px)}.nav{height:70px}.brand{font-size:24px}.brand-mark{width:35px;height:35px}.menu-toggle{display:block}.nav-links{display:none;position:absolute;top:69px;left:0;right:0;background:var(--paper);border-bottom:1px solid var(--border);padding:17px 22px;gap:14px;flex-direction:column;align-items:stretch;font-size:14px}.nav-links.is-open{display:flex}.nav-links>a{padding:7px 4px}.nav-links>a.nav-download{justify-content:center;padding:11px}.hero{grid-template-columns:1fr;gap:10px;padding-top:42px;padding-bottom:30px;min-height:0}.hero-copy{text-align:center}.hero-label{margin-bottom:22px;font-size:10px}.hero h1{font-size:clamp(40px,9.5vw,62px);letter-spacing:-1.8px;line-height:1.25;margin-bottom:20px}.hero-description{font-size:13px;margin-inline:auto;max-width:340px}.hero-actions{justify-content:center;margin-top:25px;gap:10px}.button{font-size:12px;min-height:47px;padding:10px 19px}.hero-meta{justify-content:center;font-size:9px;gap:10px}.hero-meta span+span:before{margin-right:10px}.hero-visual{height:570px;transform:scale(.9);transform-origin:center top;margin:24px -8px -37px}.orbit{width:440px;height:440px;left:calc(50% - 220px);top:40px}.hero-visual:before{width:370px;height:370px;left:calc(50% - 185px);top:78px}.chip-lyrics{left:0;top:80px;padding:12px;gap:9px}.chip-together{right:-5px;bottom:90px;padding:12px}.hero-spark{right:20px}.source-strip{flex-wrap:wrap;justify-content:center;gap:17px 30px;padding-block:20px}.source-name{font-size:12px}.source-symbol{width:23px;height:23px;font-size:10px}.strip-detail{font-size:9px}.section{padding-block:54px}.eyebrow{font-size:8px;letter-spacing:1.5px}.section-heading{display:block;margin-top:16px}.section-heading h2,.download-heading h2{font-size:29px;letter-spacing:-1px}.section-heading>p{margin-top:16px;font-size:12px;max-width:340px}.feature-grid{grid-template-columns:1fr;gap:15px}.feature-card{border-radius:15px;padding:26px;min-height:330px}.feature-card h3{font-size:22px}.lyric-pair .original{font-size:clamp(21px,5vw,27px)}.recognize-card{min-height:310px}.recognize-graphic{height:155px}.sound-bar.left{left:12%}.sound-bar.right{right:12%}.mini-features{gap:22px;grid-template-columns:1fr;margin-top:30px}.mini-feature{display:grid;grid-template-columns:24px 1fr;gap:0 14px;padding-top:18px}.mini-feature>.icon{grid-row:span 2;margin-top:2px}.mini-feature h3{font-size:14px}.mini-feature p{max-width:none;font-size:11px;margin-top:4px}.together-section{padding-block:48px}.together-grid{grid-template-columns:1fr;gap:23px}.together-copy h2{font-size:33px;letter-spacing:-1px;margin-top:18px}.together-copy>p{font-size:12px;max-width:none}.together-art{margin:20px auto 0;height:163px}.connection-panel{padding:22px;border-radius:13px}.connection-tabs{justify-content:space-between;gap:12px}.connection-tabs button{font-size:11px}.connection-pane{min-height:170px}.step h3{font-size:12px}.step p{font-size:11px}.connection-note{font-size:10px}.download-section{padding-block:50px}.download-heading>p{font-size:11px}.versions{grid-template-columns:1fr;gap:15px;margin-top:26px}.version{padding:25px}.version h3{font-size:25px}.version p{font-size:12px}.version .button{font-size:12px;min-height:45px}.download-note{font-size:10px;max-width:340px}.faq{margin-top:36px}.faq summary{font-size:12px;line-height:1.8}.faq details>p,.faq details>div{font-size:11px;padding-right:6px}.footer-main{align-items:flex-start;gap:24px;flex-direction:column}.footer-links{font-size:11px;gap:24px;flex-wrap:wrap}.footer-legal{display:block;font-size:9px}.footer-legal>p+p{margin-top:12px}}\n@media(max-width:390px){.shell{width:calc(100% - 32px)}.hero-visual{transform:scale(.79);margin-left:-16px;margin-right:-16px;margin-bottom:-90px}.float-chip{font-size:10px}.chip-lyrics{left:-4px}.chip-together{right:-4px}.source-strip{gap:14px 23px}.strip-detail{font-size:8px}.feature-card{padding:23px}.lyric-pair .original{font-size:20px}.connection-panel{padding:18px}.hero h1{font-size:39px}.footer-links{gap:20px}}\n@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}*,*:before,*:after{animation:none!important;transition:none!important}}\n/* Reading sizes stay comfortable on compact screens as well as desktop. */\n.hero-description{font-size:16px}.feature-card p{font-size:13px}.mini-feature p{font-size:13px}.step h3{font-size:14px}.step p{font-size:12px}.connection-tabs button{font-size:12px}.connection-note{font-size:11px}.download-heading>p{font-size:14px}.version p{font-size:13px}.version .requirements{font-size:12px}.version .button{font-size:12px}.faq summary{font-size:14px}.faq details>p,.faq details>div{font-size:13px}.footer-legal{font-size:10px}.visual-caption{font-size:9px}\n@media(max-width:760px){.hero-description{font-size:14px}.feature-card p,.mini-feature p,.step p,.version p{font-size:13px}.connection-tabs button{font-size:12px}.connection-note{font-size:11px}.faq summary{font-size:14px}.faq details>p,.faq details>div{font-size:13px}.footer-legal{font-size:10px}.visual-caption{font-size:9px}}\n.orbit{width:min(500px,calc(100% + 30px));height:auto;aspect-ratio:1;left:50%;transform:translateX(-50%)}\n</style>\n</head>\n<body>\n<a class=\"skip-link\" href=\"#main\">跳到主要内容</a>\n<svg class=\"svg-defs\" aria-hidden=\"true\" xmlns=\"http://www.w3.org/2000/svg\"><defs>\n <linearGradient id=\"penguin\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop stop-color=\"#34433C\"/><stop offset=\".55\" stop-color=\"#1B2521\"/><stop offset=\"1\" stop-color=\"#0E1713\"/></linearGradient>\n <linearGradient id=\"belly\" x1=\"0\" y1=\"0\" x2=\".2\" y2=\"1\"><stop stop-color=\"#FFFFF6\"/><stop offset=\"1\" stop-color=\"#EAEEDC\"/></linearGradient>\n <radialGradient id=\"halo\"><stop stop-color=\"#DAEBD2\"/><stop offset=\"1\" stop-color=\"#DAEBD2\" stop-opacity=\"0\"/></radialGradient>\n <g id=\"spark\"><path d=\"M0 -11Q1 -1 11 0Q1 1 0 11Q-1 1 -11 0Q-1 -1 0 -11Z\" fill=\"currentColor\"/></g>\n <g id=\"note\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" stroke-linecap=\"round\"><path d=\"M8 16V-7L23 -11V12\"/><ellipse cx=\"2\" cy=\"17\" rx=\"7\" ry=\"5\" fill=\"currentColor\" stroke=\"none\"/><ellipse cx=\"17\" cy=\"13\" rx=\"7\" ry=\"5\" fill=\"currentColor\" stroke=\"none\"/></g>\n <g id=\"tux-base\">\n  <ellipse cx=\"140\" cy=\"293\" rx=\"106\" ry=\"12\" fill=\"#10271A\" opacity=\".10\"/>\n  <path d=\"M75 139C43 145 21 191 26 229Q32 246 54 233L89 192Z\" fill=\"#1C2922\"/>\n  <path d=\"M207 138C237 146 259 194 252 229Q247 244 229 229L194 189Z\" fill=\"#17241D\"/>\n  <path d=\"M67 173C63 132 75 102 93 88Q107 73 140 74Q177 75 193 93C213 114 222 147 216 185L227 238Q223 282 141 286Q58 280 56 238Z\" fill=\"url(#penguin)\"/>\n  <ellipse cx=\"140\" cy=\"191\" rx=\"67\" ry=\"86\" fill=\"url(#belly)\"/>\n  <path d=\"M72 103Q63 59 87 28Q106 5 139 7Q178 6 198 32Q219 59 207 111Q198 147 140 149Q80 145 72 103Z\" fill=\"url(#penguin)\"/>\n  <path d=\"M97 100Q84 67 101 43Q116 24 133 57Q144 35 164 39Q194 49 187 100Q165 131 140 134Q114 130 97 100Z\" fill=\"#FFFFF5\"/>\n  <g class=\"tux-eyes\"><ellipse cx=\"119\" cy=\"75\" rx=\"10\" ry=\"15\" fill=\"#13241B\"/><ellipse cx=\"165\" cy=\"75\" rx=\"10\" ry=\"15\" fill=\"#13241B\"/><circle cx=\"122\" cy=\"71\" r=\"3\" fill=\"white\"/><circle cx=\"168\" cy=\"71\" r=\"3\" fill=\"white\"/></g>\n  <path d=\"M109 103Q140 85 174 103Q166 125 142 126Q121 125 109 103Z\" fill=\"#E9B64D\"/>\n  <path d=\"M117 108Q141 117 166 107\" fill=\"none\" stroke=\"#B77F2C\" stroke-width=\"2\" stroke-linecap=\"round\"/>\n  <path d=\"M80 252C65 252 34 265 27 282Q25 293 47 296Q76 302 111 289Q117 277 107 265Z\" fill=\"#E9B64D\"/>\n  <path d=\"M200 252C215 252 246 265 253 282Q255 293 233 296Q204 302 169 289Q163 277 173 265Z\" fill=\"#E9B64D\"/>\n  <path d=\"M61 81Q60 1 140 0Q219 1 218 81\" fill=\"none\" stroke=\"#76AF7E\" stroke-width=\"13\" stroke-linecap=\"round\"/>\n  <path d=\"M70 70Q69 10 140 9Q210 10 209 70\" fill=\"none\" stroke=\"#C6DEC0\" stroke-width=\"3\"/>\n  <rect x=\"53\" y=\"68\" width=\"23\" height=\"48\" rx=\"10\" fill=\"#176C46\"/><rect x=\"205\" y=\"68\" width=\"23\" height=\"48\" rx=\"10\" fill=\"#176C46\"/>\n  <path d=\"M81 143Q140 163 198 142L200 157Q140 181 79 157Z\" fill=\"#176C46\"/>\n  <path d=\"M169 158L166 197L187 193L189 154Z\" fill=\"#176C46\"/>\n  <path d=\"M168 182L185 178\" fill=\"none\" stroke=\"#9CC99F\" stroke-width=\"3\"/>\n </g>\n <g id=\"tux-party\">\n  <use href=\"#tux-base\"/>\n  <path d=\"M99 22L137 -53L174 23Z\" fill=\"#EBA38F\" transform=\"rotate(-9 138 18)\"/>\n  <path d=\"M113 -5L160 -10M122 -26L149 -29\" stroke=\"#F9E2AB\" stroke-width=\"9\"/>\n  <path d=\"M97 24Q136 14 176 25\" fill=\"none\" stroke=\"#FFF4D5\" stroke-width=\"10\" stroke-linecap=\"round\"/>\n  <circle cx=\"125\" cy=\"-53\" r=\"10\" fill=\"#F1CB70\"/>\n  <rect x=\"105\" y=\"216\" width=\"71\" height=\"43\" rx=\"5\" fill=\"#EBA38F\"/>\n  <path d=\"M105 221Q113 231 121 223Q130 237 139 223Q148 236 158 223Q168 233 176 221V215H105Z\" fill=\"#FFF8DC\"/>\n  <rect x=\"120\" y=\"196\" width=\"5\" height=\"21\" fill=\"#176C46\"/><rect x=\"139\" y=\"194\" width=\"5\" height=\"23\" fill=\"#176C46\"/><rect x=\"157\" y=\"198\" width=\"5\" height=\"19\" fill=\"#176C46\"/>\n  <path d=\"M122 195Q113 187 123 180Q131 188 122 195M141 192Q132 184 141 177Q150 185 141 192M159 195Q152 188 160 182Q168 189 159 195Z\" fill=\"#E9B64D\"/>\n </g>\n <g id=\"tux-listener\">\n  <use href=\"#tux-base\"/>\n  <g transform=\"translate(198 226)\"><circle r=\"46\" fill=\"#0D1812\" stroke=\"#435C4D\" stroke-width=\"2\"/><circle r=\"37\" fill=\"none\" stroke=\"#405346\"/><circle r=\"28\" fill=\"none\" stroke=\"#405346\"/><circle r=\"19\" fill=\"#A3C995\"/><circle r=\"4\" fill=\"#14211D\"/><path d=\"M-30 -14A34 34 0 0 1 -6 -33\" fill=\"none\" stroke=\"#7A8D7D\" stroke-width=\"3\"/></g>\n  <path d=\"M206 185Q230 187 229 206Q218 231 188 232L174 218Q193 209 206 185Z\" fill=\"#263A2C\"/>\n </g>\n</defs></svg>\n<header class=\"site-header\"><nav class=\"nav shell\" aria-label=\"主导航\">\n<a class=\"brand\" href=\"#\" aria-label=\"Arris 首页\"><span class=\"brand-mark\"><svg viewBox=\"55 -6 175 165\" aria-hidden=\"true\"><use href=\"#tux-base\"/></svg></span>Arris<span style=\"font-size:10px;letter-spacing:0;color:#8b967c;font-weight:450;margin-left:3px\">好好听歌。</span></a>\n<button class=\"menu-toggle\" aria-label=\"展开导航\" aria-expanded=\"false\" aria-controls=\"nav-links\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M4 7h16M4 12h16M4 17h16\"/></svg></button>\n<div class=\"nav-links\" id=\"nav-links\"><a href=\"#features\">体验亮点</a><a href=\"#together\">一起听</a><a href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub ↗</a><a class=\"nav-download\" href=\"/get/latest.apk\">获取 Arris <svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4\"/></svg></a></div>\n</nav></header>\n<main id=\"main\">\n<section class=\"hero shell\" aria-labelledby=\"hero-title\">\n<div class=\"hero-copy entrance\"><div class=\"hero-label\"><span class=\"small-dot\"></span>为喜欢的音乐，留一个自由的位置</div>\n<h1 id=\"hero-title\">喜欢的音乐，<br><span>都在这里。</span></h1>\n<p class=\"hero-description\">网易云与 QQ 音乐，一处聆听。<br>把逐字歌词、听歌识曲和一起听，<br>装进你的口袋。</p>\n<div class=\"hero-actions\"><a class=\"button button-primary\" href=\"/get/latest.apk\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4\"/></svg> 下载 Arris</a><a class=\"button button-outline\" href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M9 19c-4 1-4-2-6-2m12 5v-4a3.5 3.5 0 0 0-1-2.7c3.3-.4 6.7-1.6 6.7-7.3a5.7 5.7 0 0 0-1.5-4 5.3 5.3 0 0 0-.1-4s-1.2-.4-4.1 1.5a14.1 14.1 0 0 0-7.4 0C4.7-.4 3.5 0 3.5 0a5.3 5.3 0 0 0-.1 4A5.7 5.7 0 0 0 2 8c0 5.7 3.4 6.9 6.7 7.3A3.5 3.5 0 0 0 8 18v4\" transform=\"translate(1 1) scale(.9)\"/></svg> 看看源码</a></div>\n<div class=\"hero-meta\"><span>为 Android 而生</span><span>开源 GPLv3</span><span>自由聆听</span></div></div>\n<div class=\"hero-visual\" aria-label=\"Arris 播放器与 Tux 视觉示意\">\n<div class=\"orbit\" aria-hidden=\"true\"></div><svg class=\"icon hero-spark\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"m12 2 2.6 7.4L22 12l-7.4 2.6L12 22l-2.6-7.4L2 12l7.4-2.6Z\"/></svg>\n<div class=\"phone\"><div class=\"phone-top\"><span>9:41</span><span class=\"phone-signals\">▮▮▮ ▰</span></div><div class=\"phone-heading\"><span>‹</span><span>正在播放</span><span>···</span></div>\n<div class=\"album\"><span class=\"album-caption\">THE QUIET LISTENER</span><svg class=\"album-tux\" viewBox=\"0 -8 280 320\" role=\"img\" aria-label=\"戴着绿色耳机、抱着唱片的 Tux\"><use href=\"#tux-listener\"/></svg></div>\n<div class=\"track-row\"><div><div class=\"track-title\">自由听众席</div><p class=\"track-artist\">Tux · Arris 视觉示意</p></div><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M20.8 4.6a5.4 5.4 0 0 0-7.6 0L12 5.8l-1.2-1.2a5.4 5.4 0 0 0-7.6 7.6L12 21l8.8-8.8a5.4 5.4 0 0 0 0-7.6Z\"/></svg></div>\n<div class=\"demo-lyrics\">让喜欢的声音，<br><strong>陪你再久一点。</strong></div>\n<svg class=\"waveform\" viewBox=\"0 0 220 30\" aria-hidden=\"true\"><path d=\"M0 15Q5 14 9 16T18 14T27 18T36 11T45 17T54 8T63 20T72 6T81 21T90 4T99 23T108 8T117 21T126 5T135 24T144 9T153 19T162 11T171 18T180 12T189 16T198 14T210 16T220 15\" fill=\"none\" stroke=\"#739663\" stroke-width=\"2\"/><path d=\"M0 15Q9 12 18 15T36 14T54 18T72 12T90 18T108 10T126 21T144 11T162 19T180 12T198 17T220 15\" fill=\"none\" stroke=\"#afbf87\" stroke-width=\"1\" opacity=\".6\"/></svg>\n<div class=\"demo-progress\"></div><div class=\"demo-times\"><span>01:16</span><span>03:28</span></div>\n<div class=\"demo-controls\"><svg class=\"icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" aria-hidden=\"true\"><path d=\"m3 6 4 0 10 12h4M17 4l4 2-4 2M3 18h4l4-5m4-3 2-4h4\"/></svg><span aria-hidden=\"true\">⏮</span><button class=\"demo-play\" aria-label=\"切换播放器视觉示意，不播放音频\" aria-pressed=\"false\"><svg viewBox=\"0 0 20 20\" aria-hidden=\"true\"><path class=\"play-symbol\" d=\"m6 3 11 7-11 7Z\"/><path class=\"pause-symbol\" d=\"M4 3h4v14H4zm8 0h4v14h-4z\"/></svg></button><span aria-hidden=\"true\">⏭</span><svg class=\"icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" aria-hidden=\"true\"><path d=\"M3 6h18M3 12h18M3 18h18\"/></svg></div><div class=\"phone-bottom\"></div></div>\n<div class=\"float-chip chip-lyrics\"><span class=\"chip-icon\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M4 15v-3a8 8 0 0 1 16 0v3M4 13H2v7h5v-7zm16 0h2v7h-5v-7z\"/></svg></span><div><strong>每一句，都听得懂。</strong><small>逐字高亮 · 双语对照</small></div></div>\n<div class=\"float-chip chip-together\"><div class=\"avatars\" aria-hidden=\"true\"><span>T</span><span>A</span><span>♫</span></div><div><strong>好音乐，一起听。</strong><small>把此刻分享给朋友</small></div></div>\n<p class=\"visual-caption\" id=\"demo-caption\">原创播放器视觉示意 · 不播放音频</p>\n</div></section>\n<div class=\"source-strip shell\" aria-label=\"音源与格式支持\"><span class=\"source-label\">MORE MUSIC. ONE PLACE.</span><span class=\"source-name\"><span class=\"source-symbol\">云</span>网易云音乐</span><span class=\"source-name\"><span class=\"source-symbol qq\">Q</span>QQ 音乐</span><span class=\"strip-detail\">LRC / TTML / YRC</span><span class=\"strip-detail\">8 种界面语言</span></div>\n<section class=\"section shell\" id=\"features\" aria-labelledby=\"features-title\"><p class=\"eyebrow\">01 / MADE FOR LISTENING</p><div class=\"section-heading\"><h2 id=\"features-title\">听歌这件小事，<br>值得多一点用心。</h2><p>让旋律有回应，让歌词有温度。<br>从找到一首歌，到把它留在身边。</p></div>\n<div class=\"feature-grid\"><article class=\"feature-card lyrics-card\"><div class=\"feature-label\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M4 15v-3a8 8 0 0 1 16 0v3M4 13H2v7h5v-7zm16 0h2v7h-5v-7z\"/></svg> 双语 · 逐字歌词</div><h3>听见旋律，也读懂每一句。</h3><p>原文和译文同步显示，你来决定哪一行更重要。</p><div class=\"lyric-example\"><small>♪ 每一种心情，都有自己的声音。</small><div class=\"lyric-pair\" data-primary=\"original\"><p class=\"original\" lang=\"en\">Keep your favorite sound close.</p><p class=\"translation\">把喜欢的声音，留在身边。</p></div></div><div class=\"segmented\" role=\"group\" aria-label=\"双语歌词主行示意\"><button type=\"button\" data-lyric=\"original\" aria-pressed=\"true\">原文优先</button><button type=\"button\" data-lyric=\"translation\" aria-pressed=\"false\">译文优先</button></div><small class=\"example-note\">排版示意 · 无翻译时自动回到单行</small></article>\n<article class=\"feature-card recognize-card\"><div class=\"feature-label\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"9\" y=\"2\" width=\"6\" height=\"13\" rx=\"3\"/><path d=\"M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8\"/></svg> 听歌识曲</div><h3>留住偶然听见的喜欢。</h3><p>通过你的网易云账号识别身边的旋律。</p><div class=\"recognize-graphic\" aria-hidden=\"true\"><div class=\"sound-bar left\"><i></i><i></i><i></i><i></i><i></i></div><div class=\"mic-orbit\"><div class=\"mic-disc\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"9\" y=\"2\" width=\"6\" height=\"13\" rx=\"3\"/><path d=\"M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8\"/></svg></div></div><div class=\"sound-bar right\"><i></i><i></i><i></i><i></i><i></i></div></div><div class=\"recognize-caption\">听到 → 识别 → 找到下一首喜欢</div></article></div>\n<div class=\"mini-features\"><article class=\"mini-feature\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M7 18H5a4 4 0 0 1 0-8 7 7 0 0 1 13-2 5 5 0 0 1 1 10h-2M12 12v10m-4-4 4 4 4-4\"/></svg><h3>离线，也有好声音。</h3><p>完整缓存的歌曲，断网也能继续听。容量按你的习惯调整。</p></article><article class=\"mini-feature\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M20.8 4.6a5.4 5.4 0 0 0-7.6 0L12 5.8l-1.2-1.2a5.4 5.4 0 0 0-7.6 7.6L12 21l8.8-8.8a5.4 5.4 0 0 0 0-7.6Z\"/></svg><h3>账号独立，喜欢相通。</h3><p>网易云与 QQ 音乐分别登录，在一处搜索、播放和整理音乐。</p></article><article class=\"mini-feature\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M3 12h18M12 3c5 5 5 13 0 18-5-5-5-13 0-18Z\"/></svg><h3>用熟悉的语言听歌。</h3><p>支持 8 种界面语言，随时切换。还有 Tux 陪你发现小小的彩蛋。</p></article></div></section>\n<section class=\"together-section\" id=\"together\" aria-labelledby=\"together-title\"><div class=\"shell together-grid\"><div class=\"together-copy\"><p class=\"eyebrow\">02 / BETTER TOGETHER</p><h2 id=\"together-title\">相隔很远，<br>也能听同一首。</h2><p>建一个房间，把此刻分享给朋友。<br>同步的是播放状态，音频由各自的音源播放，<br>你的账号留在自己的设备。</p><div class=\"together-art\" aria-hidden=\"true\"><div class=\"disc\"><div class=\"disc-center\"></div></div><div class=\"disc-link\"></div><div class=\"disc second\"><div class=\"disc-center\"></div></div><small>一首歌，两边的同一个时刻。 / 连接示意</small></div></div>\n<div class=\"connection-panel\"><div class=\"connection-tabs\" role=\"tablist\" aria-label=\"一起听连接方式\"><button role=\"tab\" id=\"tab-lan\" data-tab=\"lan\" aria-selected=\"true\" aria-controls=\"panel-lan\" tabindex=\"0\">同一网络</button><button role=\"tab\" id=\"tab-remote\" data-tab=\"remote\" aria-selected=\"false\" aria-controls=\"panel-remote\" tabindex=\"-1\">跨网络</button><button role=\"tab\" id=\"tab-relay\" data-tab=\"relay\" aria-selected=\"false\" aria-controls=\"panel-relay\" tabindex=\"-1\">自建中继</button></div>\n<div class=\"connection-pane\" id=\"panel-lan\" role=\"tabpanel\" aria-labelledby=\"tab-lan\"><div class=\"step\"><span>01</span><div><h3>连上同一个 Wi-Fi 或热点</h3><p>在「一起听」中选择局域网建房。</p></div></div><div class=\"step\"><span>02</span><div><h3>把房间地址发给朋友</h3><p>朋友通过扫描房间，或填写 IP:端口加入。</p></div></div><div class=\"step\"><span>03</span><div><h3>选一首你们都喜欢的歌</h3><p>由房主控制播放，成员跟随同一个进度。</p></div></div></div>\n<div class=\"connection-pane\" id=\"panel-remote\" role=\"tabpanel\" aria-labelledby=\"tab-remote\" hidden><div class=\"step\"><span>01</span><div><h3>尝试 P2P 建房与加入</h3><p>分享应用生成的 IP:端口#令牌，连接成功率取决于双方网络与 NAT。</p></div></div><div class=\"step\"><span>02</span><div><h3>也可以使用虚拟局域网</h3><p>双方已配置 Tailscale 等虚拟局域网时，用对应地址通过局域网入口加入。</p></div></div><div class=\"step\"><span>03</span><div><h3>需要时改用中继</h3><p>移动网络或严格 NAT 环境直连困难时，可选择可用的中继。</p></div></div></div>\n<div class=\"connection-pane\" id=\"panel-relay\" role=\"tabpanel\" aria-labelledby=\"tab-relay\" hidden><div class=\"step\"><span>01</span><div><h3>使用同一个中继地址</h3><p>在客户端「一起听」中填写本站地址，然后创建或加入房间。</p></div></div><div class=\"relay-address\">https://arris.de5.net</div><div class=\"relay-actions\"><button class=\"relay-button\" id=\"copy-relay\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"8\" y=\"8\" width=\"13\" height=\"13\" rx=\"2\"/><path d=\"M16 8V3H3v13h5\"/></svg> 复制地址</button><button class=\"relay-button\" id=\"check-health\"><svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"m5 12 4 4L19 6\"/></svg> 检查中继</button></div><p class=\"relay-status\" role=\"status\" aria-live=\"polite\" id=\"relay-status\"></p></div>\n<p class=\"connection-note\">双方需能访问所选歌曲，账号权益与地区限制由音源决定。直连和中继可用性以实际网络为准。</p></div></div></section>\n<section class=\"download-section shell\" id=\"download\" aria-labelledby=\"download-title\"><div class=\"download-heading\"><p class=\"eyebrow\">03 / YOUR NEXT FAVORITE</p><h2 id=\"download-title\">把喜欢，带在身边。</h2><p>选择适合你设备的版本。下一首喜欢，从这里开始。</p></div>\n<div class=\"versions\"><article class=\"version featured\"><div class=\"version-top\"><span>ARRIS / 3.8.4</span><span class=\"version-badge\">主线 · Android 10+</span></div><h3>一起发现新声音。</h3><div class=\"requirements\">Android 10 及以上</div><p>v3.8.4-gpl · 修开关代理后未缓存的歌放不出来（DNS 缓存）；检查更新改走自有域名，不再依赖 GitHub；直链支持断点续传。Android 10 起可安装。</p><a class=\"button button-primary\" href=\"/get/latest.apk\">直接下载 APK <svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M14 3h7v7m0-7L10 14M10 3H3v18h18v-7\"/></svg></a></article><article class=\"version\"><div class=\"version-top\"><span>ARRIS / 3.7.x LTS</span><span class=\"version-badge\">LTS · Android 7+</span></div><h3>让熟悉的，一直好用。</h3><div class=\"requirements\">Android 7.0 及以上</div><p>v3.7.15-gpl · 兼容 Android 7.0 起；已回移竖屏封面遮挡、地址族回退、DNS 缓存（代理开关）、主线程 socket 与官网同款视觉。功能仍以稳定性为主。</p><a class=\"button button-outline\" href=\"/get/lts-latest.apk\">下载 LTS 版 <svg class=\"icon \" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M14 3h7v7m0-7L10 14M10 3H3v18h18v-7\"/></svg></a></article></div>\n<p class=\"download-note\">请在 GitHub Release 页面核对版本、最低系统要求和安装包说明。<br>音源账号、会员权益和曲目版权由平台决定，Arris 不提供或分发音乐内容。</p>\n<div class=\"faq\"><h3 class=\"faq-heading\">开始之前，你可能想知道。</h3>\n<details><summary>需要分别登录网易云和 QQ 音乐吗？</summary><p>是的，两个音源使用各自的账号和登录态。会员专享、高音质、付费曲目等权限仍由对应平台判断。</p></details>\n<details><summary>识曲和一起听在所有版本都能用吗？</summary><p>这些功能在 3.8.x 功能线中持续开发，旧版本不一定提供。请查看对应 Release 的更新说明；识曲需要麦克风权限及网易云登录态，一起听需要双方可用的连接方式和曲目权限。</p></details>\n<details><summary>下载很慢，或者老是断？</summary><div>本站直链走 Cloudflare，国内速度取决于线路。可以先试 <a href=\"/get/latest.apk\">主站直链</a>（支持 `Range`，可用 IDM / aria2 多线程与断点续传）；仍然慢的话用 <a href=\"https://github.com/yaxiaiyuting/Arris/releases\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub Releases</a>，或把 release 资产地址套到任意 GitHub 加速镜像前缀后面。校验以 SHA-256 为准，两处是同一个包。</div></details>\n<details><summary>想反馈问题，或者参与开发？</summary><p>欢迎到 <a href=\"https://github.com/yaxiaiyuting/Arris/issues\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub Issues</a> 描述复现步骤、应用版本和设备信息。源码、构建配置与许可证都保留在仓库中。</p></details>\n<details><summary>中继接口与自行构建说明</summary><div>本站同时提供一起听中继，仅转发房间状态，不转发音频。接口如下：<div class=\"api-list\"><code>GET /health — 健康检查</code><code>GET /rooms — 活跃房间目录</code><code>WS /room/&lt;房间码&gt;?role=host|member&amp;name=昵称</code></div>客户端与中继构建方法见 <a href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\">项目源码及 README</a>。签名 Release 需要配置 keystore.properties。</div></details>\n</div></section>\n</main>\n<footer class=\"site-footer\"><div class=\"shell\"><div class=\"footer-main\"><div class=\"footer-brand\"><a class=\"brand\" href=\"#\">Arris<span style=\"color:#7e9570;font-weight:350\">.</span></a><p>为自由，也为每一首好音乐。</p></div><div class=\"footer-links\"><a href=\"https://github.com/yaxiaiyuting/Arris\" target=\"_blank\" rel=\"noopener noreferrer\">GitHub ↗</a><a href=\"https://github.com/yaxiaiyuting/Arris/releases/latest\" target=\"_blank\" rel=\"noopener noreferrer\">最新正式发布 ↗</a><a href=\"https://github.com/yaxiaiyuting/Arris/issues\" target=\"_blank\" rel=\"noopener noreferrer\">反馈问题 ↗</a><a href=\"https://github.com/yaxiaiyuting/Arris#license\" target=\"_blank\" rel=\"noopener noreferrer\">开源许可 ↗</a></div></div><div class=\"footer-legal\"><p>开源 Android 音乐客户端 · GPLv3。与网易云音乐、QQ 音乐官方无关联。<br>基于 Ncrust，使用 Kanesumi 设计语言。Tux 原始角色：Larry Ewing / The GIMP；此处沿用 Arris 项目矢量演绎。</p><p>© 2026 Arris contributors<br>Made for the love of music.</p></div></div></footer>\n<script>\n(()=>{'use strict';\nconst toggle=document.querySelector('.menu-toggle'),nav=document.querySelector('#nav-links');\ntoggle.addEventListener('click',()=>{const open=toggle.getAttribute('aria-expanded')!=='true';toggle.setAttribute('aria-expanded',String(open));toggle.setAttribute('aria-label',open?'收起导航':'展开导航');nav.classList.toggle('is-open',open)});\nnav.querySelectorAll('a').forEach(a=>a.addEventListener('click',()=>{toggle.setAttribute('aria-expanded','false');toggle.setAttribute('aria-label','展开导航');nav.classList.remove('is-open')}));\ndocument.addEventListener('keydown',e=>{if(e.key==='Escape'&&toggle.getAttribute('aria-expanded')==='true'){toggle.click();toggle.focus()}});\nconst play=document.querySelector('.demo-play');play.addEventListener('click',()=>{const active=play.getAttribute('aria-pressed')!=='true';play.setAttribute('aria-pressed',String(active));document.querySelector('#demo-caption').textContent=active?'播放器视觉示意已切换 · 无音频播放':'原创播放器视觉示意 · 不播放音频'});\ndocument.querySelectorAll('[data-lyric]').forEach(b=>b.addEventListener('click',()=>{document.querySelector('.lyric-pair').dataset.primary=b.dataset.lyric;document.querySelectorAll('[data-lyric]').forEach(x=>x.setAttribute('aria-pressed',String(x===b)))}));\nconst tabs=[...document.querySelectorAll('[data-tab]')];\nfunction selectTab(tab,focus){tabs.forEach(b=>{const active=b===tab;b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;document.getElementById(b.getAttribute('aria-controls')).hidden=!active});if(focus)tab.focus()}\ntabs.forEach((b,i)=>{b.addEventListener('click',()=>selectTab(b,false));b.addEventListener('keydown',e=>{let n;if(e.key==='ArrowRight')n=(i+1)%tabs.length;if(e.key==='ArrowLeft')n=(i+tabs.length-1)%tabs.length;if(e.key==='Home')n=0;if(e.key==='End')n=tabs.length-1;if(n!==undefined){e.preventDefault();selectTab(tabs[n],true)}})});\nconst status=document.querySelector('#relay-status');\ndocument.querySelector('#copy-relay').addEventListener('click',async()=>{try{await navigator.clipboard.writeText('https://arris.de5.net');status.textContent='已复制中继地址。'}catch{status.textContent='请手动复制上方地址：https://arris.de5.net'}});\ndocument.querySelector('#check-health').addEventListener('click',async e=>{const b=e.currentTarget;b.disabled=true;status.textContent='正在检查中继…';const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),6000);try{const r=await fetch('/health',{signal:controller.signal,cache:'no-store'});if(!r.ok||(await r.text()).trim()!=='ok')throw new Error('unavailable');status.textContent='中继响应正常，可以在客户端尝试连接。'}catch{status.textContent='暂时无法确认中继状态，请稍后重试。'}finally{clearTimeout(timeout);b.disabled=false}});\n})();\n</script>\n</body></html>";
