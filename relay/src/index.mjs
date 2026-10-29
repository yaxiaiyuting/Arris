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

const MAX_MEMBERS = 16;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
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
    if (!m) return new Response("not found", { status: 404 });
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

  async webSocketClose(ws, code, reason) {
    const meta = ws.deserializeAttachment() || {};
    const left = JSON.stringify({ t: "presence", role: meta.role, name: meta.name, left: true });
    for (const peer of this.state.getWebSockets()) {
      if (peer === ws) continue;
      try { peer.send(left); } catch (_) {}
    }
    try { ws.close(code, reason); } catch (_) {}
  }

  async webSocketError(ws) {
    try { ws.close(1011, "error"); } catch (_) {}
  }
}
