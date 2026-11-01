#!/usr/bin/env python3
"""Voice-in end-to-end test for companion_wss_server.py.

1) Full loop over WSS as an app emulator: hello → voice_start → 100 ms pcm16
   chunks (voice_session + sequence) → voice_send. Asserts the server answers
   voice_state(received|transcribing) and finally a speak command with
   voice_session + non-empty text. Also sends a throwaway session that it
   voice_cancel-s (expects: server stays silent).
2) The POST /events canned-base64 path (design's "testable end-to-end by POSTing
   canned base64 audio") against the phone's live device_id, so the phone's own
   service socket receives voice_state + speak.

Usage: companion_voice_test.py [device_id]
Run with system python3 (has websockets)."""
import asyncio
import base64
import json
import ssl
import struct
import sys
import uuid
import wave

import websockets

WS_URL = 'wss://YOUR_LAN_HOST:8081/ws'
HTTP_URL = 'https://YOUR_LAN_HOST:8081'
SAMPLE_RATE = 16000

def pcm_from_wav(path):
    with wave.open(path) as w:
        n = w.getnframes()
        rate = w.getframerate()
        raw = bytearray(w.readframes(n))
    src = memoryview(raw).cast('h')
    out_len = int(len(src) * SAMPLE_RATE / rate)
    # nearest-neighbour resample to 16 kHz, mono pcm16 (source is mono already)
    out = [0] * out_len
    for i in range(out_len):
        out[i] = src[int(i * rate / SAMPLE_RATE)]
    return struct.pack(f'<{out_len}h', *out)

async def main(device_id=None):
    import os
    wav = os.path.expanduser('~/.hermes/cache/scratch/voicetest.wav')
    if not os.path.exists(wav):
        print('missing fixture', wav); return 1
    pcm = pcm_from_wav(wav)
    chunk_bytes = SAMPLE_RATE * 2 // 10  # 100 ms
    chunks = [pcm[i:i + chunk_bytes] for i in range(0, len(pcm), chunk_bytes)]
    print(f'fixture: {len(pcm)*1000/(SAMPLE_RATE*2):.0f} ms, {len(chunks)} chunks @100ms')

    ctx = ssl.SSLContext()  # self-signed cert: no verification client-side
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    # ── 1) WSS full loop ──
    test_id = 'voice-test-client'
    async with websockets.connect(WS_URL, ssl=ctx,
                                  additional_headers={'X-Device-ID': test_id}) as ws:
        await ws.send(json.dumps({'type': 'hello', 'device_id': test_id,
                                  'capabilities': ['audio', 'voice']}))
        session = 'vs-' + uuid.uuid4().hex[:6]
        await ws.send(json.dumps({'type': 'voice_start', 'device_id': test_id,
            'payload': {'voice_session': session, 'format': 'pcm16',
                        'sample_rate': SAMPLE_RATE, 'channels': 1}}))
        for seq, c in enumerate(chunks):
            await ws.send(json.dumps({'type': 'audio', 'device_id': test_id,
                'payload': {'voice_session': session, 'format': 'pcm16',
                            'sample_rate': SAMPLE_RATE, 'channels': 1,
                            'duration_ms': 100, 'data_b64': base64.b64encode(c).decode(),
                            'sequence': seq}}))
            await asyncio.sleep(0.02)
        await ws.send(json.dumps({'type': 'voice_send', 'device_id': test_id,
            'payload': {'voice_session': session, 'last_sequence': len(chunks) - 1,
                        'duration_ms': len(chunks) * 100}}))

        # cancel-only session: server must stay silent about it
        dead = 'vs-' + uuid.uuid4().hex[:6]
        await ws.send(json.dumps({'type': 'voice_cancel', 'device_id': test_id,
                                  'payload': {'voice_session': dead}}))

        states, speak_text, fail_reason = [], None, None
        deadline = asyncio.get_event_cache if False else asyncio.get_running_loop().time() + 180
        while asyncio.get_running_loop().time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), 30)
            except asyncio.TimeoutError:
                break
            f = json.loads(raw)
            if f.get('type') == 'command' and f.get('command') in ('voice_state', 'speak'):
                print('server →', f)
                if f['command'] == 'voice_state':
                    st = f['payload'].get('state')
                    states.append(st)
                    if st == 'failed':
                        fail_reason = 'failed'
                elif f['command'] == 'speak':
                    if f['payload'].get('voice_session') == session:
                        speak_text = f['payload'].get('text')
                        break
        print(f'states seen: {states}')
        ok_wss = speak_text and 'transcribing' in states and 'received' in states
        print('WSS loop:', 'PASS —', repr(speak_text)[:200] if ok_wss else 'FAIL')

    # ── 2) POST /events path → phone ──
    if device_id:
        import aiohttp
        async with aiohttp.ClientSession(ssl=ssl.SSLContext()) as http:
            for etype, payload in [
                ('voice_start', {'voice_session': (s2 := 'vs-' + uuid.uuid4().hex[:6]),
                                 'format': 'pcm16', 'sample_rate': SAMPLE_RATE, 'channels': 1}),
            ]:
                pass
            async def post(etype, payload):
                async with http.post(HTTP_URL + '/events',
                                     headers={'X-Device-ID': device_id},
                                     json={'type': etype, 'device_id': device_id,
                                           'payload': dict(payload, voice_session=s2)}) as r:
                    return await r.json()
            assert (await post('voice_start', {'format': 'pcm16', 'sample_rate': SAMPLE_RATE,
                                               'channels': 1}))['status'] == 'ok'
            for seq, c in enumerate(chunks):
                await post('audio', {'format': 'pcm16', 'sample_rate': SAMPLE_RATE,
                                     'channels': 1, 'duration_ms': 100,
                                     'data_b64': base64.b64encode(c).decode(),
                                     'sequence': seq})
            await post('voice_send', {'last_sequence': len(chunks) - 1,
                                      'duration_ms': len(chunks) * 100})
            print(f'HTTP voice session {s2}: queued; phone replies arrive on ITS ws — '
                  'verify via logcat (voice_state + speak).')
        return 0 if ok_wss else 2
    return 0 if ok_wss else 2

if __name__ == '__main__':
    sys.exit(asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else None)))
