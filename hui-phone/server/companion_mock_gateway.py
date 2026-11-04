#!/usr/bin/env python3
"""Mock hermes-serve gateway — the voice loop's test oracle. NO model, NO agent turn.

Speaks ONLY the verified gateway contract (skills: hui-gateway-remote-test):
  - auth: signed-blob token via ?token=; a bad one gets a plain HTTP 403, the
    upgrade never happens (serve-like refusal). Check here is STRUCTURAL ONLY
    (length >=60): HMAC verification is serve's / the mint script's job.
  - session.create sends ONLY {source}; request_id in params → refused.
    Returns {session_id, stored_session_id} — deliberately DIFFERENT ids, like serve.
  - after every ws drop → session.resume with the STORED id; a runtime id answers
    4007 "session not found" — verified contract behaviour, not a bug.
  - prompt.submit: NO submission_id (queued IS allowed); receipt {queued:true}.
  - replies: scripted message.delta ×2 + message.complete frames; ?shape= switches
    the nesting so the bridge's permissive parsing is testable.
  - ws drops happen even with healthy pings → mode=drop_after_submit closes the
    connection right after the prompt.submit receipt.

Route (mode/shape in the path so the bridge's "?token=" append stays clean):
  ws://127.0.0.1:<port>/ws/ok/a                       payload.text shape
  ws://127.0.0.1:<port>/ws/ok/flat                   text directly under params
  ws://127.0.0.1:<port>/ws/ok/msg                    text under params.message
  ws://127.0.0.1:<port>/ws/drop_after_submit/<shape> drop right after receipt
  ws://127.0.0.1:<port>/ws/reject/<shape>            every token → HTTP 403
  ws://127.0.0.1:<port>/api/ws?mode=&shape=          the direct phone's URL
    (sends the `gateway.ready` event first, like hermes serve, so the
    phone's ready->bootstrap path is testable; the bridge ignores it)

Usage: companion_mock_gateway.py <port>   (env MOCK_FRAME_LOG = jsonl path)
Every frame the bridge sends is logged there — the test's oracle for framing."""
import asyncio
import json
import os
import secrets
import sys

from aiohttp import web

MOCK_FRAME_LOG = os.environ.get('MOCK_FRAME_LOG', '')  # '' → logging off

SESSIONS = {}  # stored_session_id -> {session_id, stored_session_id}; sessions
# live across connections — a dropped ws must be resumable on a fresh dial.
_DROP_LEFT = [1]  # drop_after_submit mode drops only the FIRST turn (bridge retries once)


def token_ok(token):
    return bool(token) and len(token) >= 60  # raw literals are short; blobs aren't


def event_frames(shape, full_text):
    """Scripted message.delta ×2 + message.complete; shape switches nesting."""
    half = len(full_text) // 2

    def ev(t, kind):
        if shape == 'flat':
            return {'method': 'event',
                    'params': {'type': 'message.' + kind, 'text': t}}
        if shape == 'msg':
            return {'method': 'event',
                    'params': {'type': 'message.' + kind,
                               'message': {'text': t}}}
        return {'method': 'event',
                'params': {'type': 'message.' + kind,
                           'payload': {'text': t}}}

    frames = [ev(full_text[:half], 'delta'),
              ev(full_text[half:], 'delta'),
              ev(full_text, 'complete')]
    if shape == 'a':
        frames[2]['params']['payload']['status'] = 'complete'
    else:
        frames[2]['params']['status'] = 'complete'
    return frames


async def gw_handler(request):
    parts = request.path.split('/')  # /ws/<mode>/<shape> or /api/ws?mode=&shape=
    direct = request.path == '/api/ws'
    if direct:
        mode = request.query.get('mode', 'ok')
        shape = request.query.get('shape', 'a')
    else:
        mode = parts[2] if len(parts) > 2 else 'ok'
        shape = parts[3] if len(parts) > 3 else 'a'

    token = request.query.get('token')
    if mode == 'reject' or not token_ok(token):
        # Plain 403 response — ws.prepare() never called → no upgrade (serve-like).
        return web.Response(status=403, text='auth refused (signed blob required)')

    ws = web.WebSocketResponse()
    await ws.prepare(request)

    if direct:
        # hermes serve's first frame: the gateway.ready event (skin payload
        # is irrelevant to the phone — it only gates bootstrap on it).
        await ws.send_str(json.dumps({'jsonrpc': '2.0', 'method': 'event',
                                      'params': {'type': 'gateway.ready',
                                                 'payload': {'skin': {'name': 'mock'},
                                                             'change_events': False,
                                                             'replay_epoch': 'mock'}}}))

    async for msg in ws:
        if msg.type.name != 'TEXT':
            continue
        try:
            f = json.loads(msg.data)
        except json.JSONDecodeError:
            continue
        if not isinstance(f, dict):
            continue
        if MOCK_FRAME_LOG:
            try:
                with open(MOCK_FRAME_LOG, 'a') as fh:
                    fh.write(msg.data + '\n')
            except OSError:
                pass

        method = f.get('method')
        params = f.get('params') or {}
        rid = f.get('id')

        async def reply(obj):
            await ws.send_str(json.dumps({'jsonrpc': '2.0', 'id': rid, **obj}))

        if method == 'session.create':
            if 'request_id' in params:
                await reply({'error': {'code': -4011, 'message': 'request_id rejected'}})
                continue
            sid = {'session_id': 'rt-' + secrets.token_hex(6),
                   'stored_session_id': 'st-' + secrets.token_hex(6)}
            SESSIONS[sid['stored_session_id']] = sid
            await reply({'result': sid})
        elif method == 'session.resume':
            sid = SESSIONS.get(params.get('session_id'))
            if sid is None:  # runtime id → 4007, per the verified contract
                await reply({'error': {'code': 4007, 'message': 'session not found'}})
            else:
                await reply({'result': dict(sid)})
        elif method == 'prompt.submit':
            await reply({'result': {'queued': True}})
            if mode == 'drop_after_submit' and _DROP_LEFT[0] > 0:
                _DROP_LEFT[0] -= 1
                await ws.close()
                return
            echo = ' '.join((params.get('text') or '').split())[:60]
            full = f"Mock reply OK for transcript [{echo}] (shape={shape})"
            for frame in event_frames(shape, full):
                await ws.send_str(json.dumps(frame))
        elif method == 'gateway.ping':
            await reply({'result': {'ok': True}})
        elif method == 'runtime.describe':  # -32601 is expected, not a bug
            await reply({'error': {'code': -32601, 'message': 'method not found'}})
        else:
            await reply({'result': {}})
    return ws


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9207
    app = web.Application()
    app.router.add_get('/ws/{mode}/{shape}', gw_handler)
    app.router.add_get('/ws/{mode}', gw_handler)
    app.router.add_get('/api/ws', gw_handler)
    runner = web.AppRunner(app)
    await runner.setup()
    # 0.0.0.0 so the LAN phone can reach the fixture at <lan-ip>:<port>
    # (the bridge/matrix tests dial 127.0.0.1, which 0.0.0.0 still serves).
    site = web.TCPSite(runner, '0.0.0.0', port)
    await site.start()
    print('mock gateway ready port=' + str(port), flush=True)
    try:
        while True:
            await asyncio.sleep(3600)
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        await runner.cleanup()


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
