#!/usr/bin/env python3
"""Voice-in deterministic test matrix — NO hermes model turn, NO agent session.

Gateway leg = companion_mock_gateway.py (verified-contract fixture, scripted replies).
Everything else real: the actual server on :8081 + local whisper STT (allowed — not
the thinking model). Exit 0 iff all claims pass.

Cases:
  M1 cancel → silence        M2 gap ≥30 s → failed      M3 >60 s → failed
  M4 unknown session → silent-drop   M5 no voice_send idle → silent-drop (log-marked)
  B1 reply leg + POST /events fallback + framing asserts
  B2 mid-turn ws drop → session.resume(STORED id) → still replies
  B3flat/B3msg permissive message parsing (params.text / params.message.text)
  B4 token refused (403) → clean voice_state failed, no hang, no prompt
  B5 partial gap <30 s → transcribe what exists → reply
  B6 silence → empty transcript → failed, NO prompt submitted

Run: python3 companion_voice_matrix.py  (system python — websockets/aiohttp present)
"""
import asyncio
import os
import sys
import json
import time
import uuid
import base64
import random
import ssl

import websockets

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from companion_voice_test import pcm_from_wav  # proven fixture resampler

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, 'companion_wss_server.py')
MOCK = os.path.join(HERE, 'companion_mock_gateway.py')
FIXTURE_WAV = os.path.expanduser('~/.hermes/cache/scratch/voicetest.wav')
MOCK_PORT = 9207
SAMPLE_RATE = 16000
TMP = os.path.join(HERE, '.voice_tmp')

results = []


def claim(name, cond, note=''):
    results.append((name, bool(cond), str(note)[:200]))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f' — {note}' if note else ''), flush=True)


def shuffled_order(n, seed=42):
    rng = random.Random(seed)
    order = list(range(n))
    rng.shuffle(order)
    return order


def try_json(raw):
    try:
        f = json.loads(raw)
        return f if isinstance(f, dict) else None
    except (json.JSONDecodeError, TypeError):
        return None


async def spawn(script, extra_env, ready_marker, log_path, timeout=25):
    env = dict(os.environ)
    env.update(extra_env)
    if os.path.exists(log_path):
        os.remove(log_path)
    out = open(log_path, 'ab')
    proc = await asyncio.create_subprocess_exec(sys.executable, script,
                                               stdout=out, stderr=out, env=env)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with open(log_path, 'rb') as f:
                if ready_marker.encode() in f.read():
                    return proc
        except FileNotFoundError:
            pass
        await asyncio.sleep(0.2)
    try:
        proc.kill()
    except ProcessLookupError:
        pass
    raise RuntimeError(f'spawn failed: marker missing in {log_path}')


async def stop(proc):
    try:
        proc.terminate()
        await asyncio.wait_for(proc.wait(), 10)
    except (ProcessLookupError, asyncio.TimeoutError):
        try:
            proc.kill()
        except ProcessLookupError:
            pass


def read_text(path):
    try:
        with open(log := path, 'rb') as f:
            return f.read().decode(errors='replace')
    except OSError:
        return ''


def read_frames(path):
    out = []
    for line in read_text(path).splitlines():
        f = try_json(line)
        if f:
            out.append(f)
    return out


async def client_ws(device_id):
    ctx = ssl.SSLContext()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ws_url = os.environ.get('COMPANION_WS_URL', 'wss://127.0.0.1:8081/ws')
    ws = await websockets.connect(ws_url, ssl=ctx,
                                  additional_headers={'X-Device-ID': device_id}).__aenter__()
    await ws.send(json.dumps({'type': 'hello', 'device_id': device_id,
                              'capabilities': ['audio', 'voice']}))
    await asyncio.wait_for(ws.recv(), 10)  # welcome
    return ws


async def stream_session(ws, device_id, session, chunks, order=None,
                        last_sequence=None, duration_ms=None, cancel=False,
                        send_voice_send=True):
    await ws.send(json.dumps({'type': 'voice_start', 'device_id': device_id,
        'payload': {'voice_session': session, 'format': 'pcm16',
                    'sample_rate': SAMPLE_RATE, 'channels': 1}}))
    for seq in (order if order is not None else range(len(chunks))):
        c = chunks[seq]
        await ws.send(json.dumps({'type': 'audio', 'device_id': device_id,
            'payload': {'voice_session': session, 'format': 'pcm16',
                        'sample_rate': SAMPLE_RATE, 'channels': 1,
                        'duration_ms': 100,
                        'data_b64': base64.b64encode(c).decode(),
                        'sequence': seq}}))
        await asyncio.sleep(0.01)
    if cancel:
        await ws.send(json.dumps({'type': 'voice_cancel', 'device_id': device_id,
                                  'payload': {'voice_session': session}}))
    elif send_voice_send:
        await ws.send(json.dumps({'type': 'voice_send', 'device_id': device_id,
            'payload': {'voice_session': session,
                        'last_sequence': last_sequence,
                        'duration_ms': duration_ms}}))


async def collect(ws, session, timeout, extra_drain=1.5):
    got = []

    async def drain(secs):
        end = asyncio.get_running_loop().time() + secs
        while asyncio.get_running_loop().time() < end:
            try:
                raw = await asyncio.wait_for(ws.recv(), 2)
            except asyncio.TimeoutError:
                continue
            except websockets.exceptions.ConnectionClosed:
                return
            f = try_json(raw)
            if f is None or f.get('type') != 'command':
                continue
            p = f.get('payload') or {}
            if p.get('voice_session') != session:
                continue
            if f.get('command') == 'speak':
                got.append(('speak', p.get('text')))

    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        try:
            raw = await asyncio.wait_for(ws.recv(), 5)
        except asyncio.TimeoutError:
            continue
        except websockets.exceptions.ConnectionClosed:
            break
        f = try_json(raw)
        if f is None or f.get('type') != 'command':
            continue
        p = f.get('payload') or {}
        if p.get('voice_session') != session:
            continue
        if f.get('command') == 'voice_state':
            got.append(('state', p.get('state')))
            if p.get('state') == 'failed':
                await drain(extra_drain)
                return got
        elif f.get('command') == 'speak':
            got.append(('speak', p.get('text')))
            await drain(extra_drain)
            return got
    return got


async def post_session(device_id, session, chunks, last_sequence, duration_ms):
    """POST /events flavour — stdlib urllib, unverified context (self-signed cert)."""
    from urllib.request import Request, urlopen
    import ssl as _ssl

    ctx = _ssl.SSLContext()
    ctx.check_hostname = False
    ctx.verify_mode = _ssl.CERT_NONE

    def post(etype, payload):
        body = {'type': etype, 'device_id': device_id,
                'payload': dict(payload, voice_session=session)}
        req = Request(os.environ.get('COMPANION_HTTP_URL', 'https://127.0.0.1:8081') + '/events',
                      data=json.dumps(body).encode(), method='POST',
                      headers={'Content-Type': 'application/json',
                               'X-Device-ID': device_id})
        with urlopen(req, context=ctx, timeout=20) as r:
            return r.read()

    post('voice_start', {'format': 'pcm16', 'sample_rate': SAMPLE_RATE,
                         'channels': 1})
    for seq, c in enumerate(chunks):
        post('audio', {'format': 'pcm16', 'sample_rate': SAMPLE_RATE,
                       'channels': 1, 'duration_ms': 100,
                       'data_b64': base64.b64encode(c).decode(),
                       'sequence': seq})
        await asyncio.sleep(0.01)
    post('voice_send', {'last_sequence': last_sequence,
                        'duration_ms': duration_ms})


def mock_framing_check(mocklog_path):
    """Oracle over frames the bridge SENT to the gateway (mock logged them all)."""
    frames = read_frames(mocklog_path)
    creates = [f for f in frames if f.get('method') == 'session.create']
    submits = [f for f in frames if f.get('method') == 'prompt.submit']
    resumes = [f for f in frames if f.get('method') == 'session.resume']
    return {
        'creates': len(creates), 'submits': len(submits),
        'create_ok': bool(creates) and all(
            set((f.get('params') or {}).keys()) == {'source'} for f in creates),
        'submit_ok': bool(submits) and all(
            set((f.get('params') or {}).keys()) <= {'session_id', 'text', 'queued'}
            and (f.get('params') or {}).get('queued') is True
            and (f.get('params') or {}).get('text')
            for f in submits),
        'resume_ids': [r['params'].get('session_id') for r in resumes],
    }


# ───────────────────────────── M group: no bridge at all ─────────────────────────

async def m_group(fixture_chunks, silence_chunk):
    log = os.path.join(TMP, 'm_srv.log')
    srv = await spawn(SERVER, {'GATEWAY_FRAME_LOG': ''},
                      'Companion WSS server running', log)
    try:
        dev = 'voice-matrix-m'
        ws = await client_ws(dev)
        try:
            # M1 voice_cancel → total silence
            s = 'vs-m1-' + uuid.uuid4().hex[:4]
            await stream_session(ws, dev, s, fixture_chunks[:5], cancel=True)
            got = await collect(ws, s, timeout=3, extra_drain=0)
            claim('M1 cancel → silence', not got, f'frames={got}')
            claim('M1 log: guard line', 'voice_cancel' in read_text(log))

            # M2 declared 35 s coverage, only 5 s present → gap ≥30 s → failed
            s = 'vs-m2-' + uuid.uuid4().hex[:4]
            await stream_session(ws, dev, s, [silence_chunk] * 50,
                                 last_sequence=349, duration_ms=35000)
            got = await collect(ws, s, timeout=10)
            claim('M2 gap≥30s → failed only', got == [('state', 'failed')],
                  f'got={got}')

            # M3 declared 60.1 s coverage → cap → failed
            s = 'vs-m3-' + uuid.uuid4().hex[:4]
            await stream_session(ws, dev, s, [silence_chunk] * 601,
                                 last_sequence=600, duration_ms=60100)
            got = await collect(ws, s, timeout=15)
            claim('M3 >60s cap → failed only', got == [('state', 'failed')],
                  f'got={got}')

            # M4 voice_send for a session that never existed → silent drop
            s = 'vs-m4-' + uuid.uuid4().hex[:4]
            await ws.send(json.dumps({'type': 'voice_send', 'device_id': dev,
                                      'payload': {'voice_session': s,
                                                  'last_sequence': 3, 'duration_ms': 400}}))
            got = await collect(ws, s, timeout=3, extra_drain=0)
            claim('M4 unknown session → silence', not got, f'frames={got}')
            claim('M4 log: unknown-dropped line', 'unknown/expired session' in read_text(log))

            # M5 no voice_send, idle 30 s → silent drop (35 s wait, log-marked)
            s = 'vs-m5-' + uuid.uuid4().hex[:4]
            await stream_session(ws, dev, s, [silence_chunk] * 5,
                                 send_voice_send=False)
            idle_deadline = time.monotonic() + 35
            idle_frames = 0
            while time.monotonic() < idle_deadline:
                try:
                    raw = await asyncio.wait_for(ws.recv(), 5)
                except asyncio.TimeoutError:
                    continue
                except websockets.exceptions.ConnectionClosed:
                    break
                f = try_json(raw)
                if f and f.get('type') == 'command' and \
                        (f.get('payload') or {}).get('voice_session') == s:
                    idle_frames += 1
            claim('M5 idle 30 s → silence to client', idle_frames == 0)
            claim('M5 log: idle-dropped line', 'dropped silently' in read_text(log))
        finally:
            try:
                await ws.close()
            except Exception:
                pass
    finally:
        await stop(srv)


# ─────────────────────── B group: bridge vs mock oracle ─────────────────────────

async def b_case(name, mode, shape, chunks, seqs, last_sequence, duration_ms,
                 device_tag, reply_expected=False, post_variant=False,
                 failed_at_bridge=False, failed_after_stt=False,
                 resume_expected=0, collect_timeout=170):
    mocklog = os.path.join(TMP, f'mock_{name}.jsonl')
    gwlog = os.path.join(TMP, f'gw_{name}.jsonl')
    slog = os.path.join(TMP, f'srv_{name}.log')
    mstdout = os.path.join(TMP, f'mock_{name}.out')
    for p in (mocklog, gwlog, slog, mstdout):
        if os.path.exists(p):
            os.remove(p)
    mock = srv = ws = None
    try:
        mock = await spawn(MOCK, {'MOCK_FRAME_LOG': mocklog},
                           'mock gateway ready', mstdout, timeout=15)
        srv = await spawn(SERVER, {
            'GATEWAY_WS_URL': f'ws://127.0.0.1:{MOCK_PORT}/ws/{mode}/{shape}',
            'GATEWAY_FRAME_LOG': gwlog,
        }, 'Companion WSS server running', slog)
        dev = f'voice-matrix-{device_tag}'
        ws = await client_ws(dev)
        sess = 'vs-' + name + '-' + uuid.uuid4().hex[:4]
        await stream_session(ws, dev, sess, chunks, order=seqs,
                             last_sequence=last_sequence, duration_ms=duration_ms)
        got = await collect(ws, sess, timeout=collect_timeout)
        states = [v for k, v in got if k == 'state']
        speaks = [t for k, t in got if k == 'speak']

        if reply_expected:
            ok = (states[:2] == ['received', 'transcribing']
                  and len(speaks) == 1
                  and 'Mock reply OK' in speaks[0]
                  and f'(shape={shape})' in speaks[0])
            claim(name + ' reply leg', ok,
                  f'states={states} speaks={len(speaks)} txt={speaks[:1]}')
        if failed_at_bridge:
            ok = (states[:2] == ['received', 'transcribing']
                  and states[-1:] == ['failed'] and not speaks)
            claim(name + ' bridge refused → failed, no hang', ok, f'states={states}')
        if failed_after_stt:
            ok = (states == ['received', 'transcribing', 'failed'] and not speaks)
            claim(name + ' empty transcript → failed, no speak', ok,
                  f'states={states}')
            fm = mock_framing_check(mocklog)
            claim(name + ' NO prompt submitted', fm['submits'] == 0,
                  f'submits={fm["submits"]}')

        if post_variant:
            sess2 = 'vs-' + name + '-post-' + uuid.uuid4().hex[:4]
            await post_session(dev, sess2, chunks, last_sequence, duration_ms)
            got2 = await collect(ws, sess2, timeout=collect_timeout)
            st2 = [v for k, v in got2 if k == 'state']
            sp2 = [t for k, t in got2 if k == 'speak']
            claim(name + '-POST fallback loop', st2[:2] == ['received', 'transcribing']
                  and len(sp2) == 1 and 'Mock reply OK' in (sp2[0] if sp2 else ''),
                  f'states={st2} speaks={len(sp2)}')

        if reply_expected or post_variant or resume_expected:
            fm = mock_framing_check(mocklog)
            claim(name + ' session.create framing ({source} only)', fm['create_ok'],
                  f"creates={fm['creates']}")
            want = (2 if post_variant else 1) + (1 if resume_expected else 0)
            claim(name + f' prompt.submit framing (no submission_id, queued, text)',
                  fm['submit_ok'], f"submits={fm['submits']} want={want}")
            claim(name + f' submit count == {want}', fm['submits'] == want,
                  f'submits={fm["submits"]}')
            if resume_expected:
                ids = fm['resume_ids']
                claim(name + f' resume ×{resume_expected} with STORED ids',
                      len(ids) == resume_expected
                      and all(isinstance(i, str) and i.startswith('st-') for i in ids),
                      f'resume_ids={ids}')
        if reply_expected:  # live-path hedge: raw frames observable for first real use
            gw_text = read_text(gwlog)
            claim(name + ' bridge raw-frame log captured reply shape',
                  'message.complete' in gw_text and '"dir": "in"' in gw_text)
    finally:
        if ws:
            try:
                await ws.close()
            except Exception:
                pass
        for p in (srv, mock):
            if p:
                await stop(p)


async def main():
    os.makedirs(TMP, exist_ok=True)
    if not os.path.exists(FIXTURE_WAV):
        print('missing fixture', FIXTURE_WAV)
        return 1
    pcm = pcm_from_wav(FIXTURE_WAV)
    chunk_bytes = SAMPLE_RATE * 2 // 10
    fixture_chunks = [pcm[i:i + chunk_bytes] for i in range(0, len(pcm), chunk_bytes)]
    silence_chunk = bytes(chunk_bytes)
    n_full = len(fixture_chunks)
    print(f'fixture: {n_full} chunks @100ms', flush=True)

    await m_group(fixture_chunks, silence_chunk)

    order = shuffled_order(n_full, seed=42)
    await b_case('B1', 'ok', 'a', fixture_chunks, order, n_full - 1, n_full * 100,
                 'b1', reply_expected=True, post_variant=True)
    await b_case('B2', 'drop_after_submit', 'a', fixture_chunks, order, n_full - 1,
                 n_full * 100, 'b2', reply_expected=True, resume_expected=1)
    await b_case('B3flat', 'ok', 'flat', fixture_chunks, order, n_full - 1,
                 n_full * 100, 'b3', reply_expected=True)
    await b_case('B3msg', 'ok', 'msg', fixture_chunks, order, n_full - 1,
                 n_full * 100, 'b3b', reply_expected=True)
    await b_case('B4', 'reject', 'a', fixture_chunks, order, n_full - 1,
                 n_full * 100, 'b4', failed_at_bridge=True)
    all70 = [fixture_chunks[i % n_full] for i in range(70)]
    keep = [s for s in range(70) if not (55 <= s <= 64)]
    random.Random(7).shuffle(keep)
    await b_case('B5', 'ok', 'a', all70, keep, 69, 7000, 'b5', reply_expected=True)
    await b_case('B6', 'ok', 'a', [silence_chunk] * 20, None, 19, 2000, 'b6',
                 failed_after_stt=True)

    print('\n==== matrix results ====')
    failed = [r for r in results if not r[1]]
    for name, ok, note in results:
        print(f'{name}: {"PASS" if ok else "FAIL"}' + (f' | {note}' if note else ''))
    print(f'{len(results) - len(failed)}/{len(results)} pass')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(asyncio.run(main()) or 0)
