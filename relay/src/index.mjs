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

export class Room {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
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
    return new Response(null, { status: 101, webSocket: client });
  }

  /** 收到一条消息：原样转给同房间的其他人（含房主→成员、成员→房主）。 */
  async webSocketMessage(ws, message) {
    const meta = ws.deserializeAttachment() || {};
    for (const peer of this.state.getWebSockets()) {
      if (peer === ws) continue;
      try { peer.send(message); } catch (_) {}
    }
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
