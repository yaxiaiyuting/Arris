import assert from 'node:assert/strict';
import { Room } from '../../../../relay/src/index.mjs';

const fakeSocket = (role) => ({
  received: [], closed: false,
  deserializeAttachment() { return { role, name: role }; },
  send(message) { this.received.push(JSON.parse(message)); },
  close() { this.closed = true; },
});
const host = fakeSocket('host');
const sender = fakeSocket('member');
const victim = fakeSocket('member');
const state = { getWebSockets: () => [host, sender, victim].filter(ws => !ws.closed) };
const directoryCalls = [];
const env = { DIRECTORY: { idFromName: (id) => id, get: () => ({
  async fetch(url) { directoryCalls.push(url); return new Response(null, { status: 204 }); },
}) } };
const room = new Room(state, env);
room.roomId = '654321';
const snapshot = {
  t: 'snapshot',
  state: {
    roomId: '654321', seq: 19, hostUserId: 'host',
    currentRoomTrackId: 'test',
    queue: [{ roomTrackId: 'test', title: 'Synthetic track', publicIds: { songId: '42', source: 'ncm' } }],
    transport: { playing: true, positionMs: 20_000, rate: 1 },
    stampedAtMs: 1,
  },
};
await room.webSocketMessage(sender, JSON.stringify(snapshot));
assert.deepEqual(victim.received, []);
assert.deepEqual(host.received, []);
assert.deepEqual(sender.received, [{ t: 'error', reason: 'not-host' }]);
await room.webSocketMessage(sender, JSON.stringify({ t: 'closed', reason: 'host-left' }));
assert.deepEqual(victim.received, [{ t: 'closed', reason: 'host-left' }]);
await room.webSocketClose(host, 1000, 'test');
assert.equal(victim.closed, true);
assert.equal(directoryCalls.at(-1), 'https://directory/drop?roomId=654321');
await room.webSocketClose(sender, 4001, 'host-left');
assert.equal(directoryCalls.at(-1), 'https://directory/drop?roomId=654321');
console.log('Member snapshots blocked, but member-forged Closed forwarded (I11).');
console.log('Host close closes members and normal close callback drops directory (I12).');
