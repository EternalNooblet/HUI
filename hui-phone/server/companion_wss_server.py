#!/usr/bin/env python3
"""
Secure WebSocket Server (WSS) for Companion App — with the voice-in loop.

Voice-in design (hui-phone/voice-in-design.md, 2026-10-31):
  phone tap → stream pcm16 chunks (existing `audio` event + voice_session)
  → server assembles per (device_id, voice_session) keyed by sequence
  → STT in Hermes's committed venv (ONE model, never a second copy)
  → prompt to hermes serve :9119 as a mini-client (verified gateway contract)
  → reply arrives as message.* stream → ONE final `speak` command back.

Wire additions (everything else unchanged):
  phone→server events: voice_start / audio(+voice_session) / voice_send / voice_cancel
  server→phone: voice_state {received|transcribing|failed} + speak{+voice_session}
"""

import asyncio
import base64
import glob
import hashlib
import hmac
import json
import logging
import os
import ssl
import sys
import time
import uuid
import wave
from datetime import datetime
from aiohttp import web, WSMsgType
import websockets  # bridge client to hermes serve (text JSON-RPC frames)
import urllib.parse

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

event_queue = asyncio.Queue()
connected_devices = {}  # device_id -> list of {'ws': ws, 'connected_at': ..., 'auth_token': ...}

# ---------------------------------------------------------------- voice-in ---
# Tuning per the agreed design. No new config knobs — audio_chunk_ms: 100 stays.
IDLE_DROP_S = 30.0          # session with no voice_send and no activity → drop silently
GAP_FAIL_MS = 30_000        # sequence gaps ≥ 30 s of missing coverage → fail
MAX_COVER_MS = 60_000       # hard cap; phone enforces, server truncates → failed
STT_TIMEOUT_S = 90
REPLY_TIMEOUT_S = 180
GATEWAY_WS_URL = 'ws://10.0.0.126:9119/api/ws'  # runtime: os.environ['GATEWAY_WS_URL'] wins
MINT_SCRIPT = os.path.expanduser('~/projects/hui/hui-cli/tools/mint_access_token.py')

# ------------------------------------------------------------------- auth ---
# Phone auth (option C: per-token revocation). The QR token is the same
# signed blob hermes serve verifies for /api/ws: base64(payload || sig),
# sig = HMAC-SHA256(payload, dashboard.basic_auth secret). Payload carries
# a unique jti per minted QR (mint_access_token.py / hermes_auth_qr.py);
# hermes serve ignores extra claims, we enforce them here:
#   valid HMAC + kind=access + exp in future + jti/fingerprint NOT revoked.
# Revoked list: server/revoked_tokens.json, edited by
# server/hermes_revoke_token.py. Legacy pre-jti tokens (no jti claim) are
# accepted if otherwise valid — revoke those by fingerprint instead.
# Set COMPANION_AUTH=off to disable (dev only; default is enforced).
HERMES_CFG = os.path.expanduser('~/.hermes/config.yaml')
REVOKED_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'revoked_tokens.json')
AUTH_REQUIRED = os.environ.get('COMPANION_AUTH', 'on').lower() not in ('off', '0', 'false', 'no')

def _basic_auth_secret():
    """dashboard.basic_auth secret from ~/.hermes/config.yaml, no PyYAML dep."""
    user = raw = None
    in_dashboard = in_basic = False
    try:
        with open(HERMES_CFG) as f:
            for line in f:
                if not line.strip() or line.lstrip().startswith('#'):
                    continue
                indent = len(line) - len(line.lstrip())
                key, _, val = line.strip().partition(':')
                val = val.strip().strip('"').strip("'")
                if indent == 0:
                    in_dashboard = key == 'dashboard'
                    in_basic = False
                elif in_dashboard and indent == 2:
                    in_basic = key == 'basic_auth'
                elif in_dashboard and in_basic and indent >= 4:
                    if key == 'username':
                        user = val
                    elif key == 'secret':
                        raw = val
    except OSError:
        return None
    if not raw:
        return None
    for dec in (base64.b64decode, bytes.fromhex):
        try:
            d = dec(raw)
            if len(d) >= 16:
                return d
        except Exception:
            pass
    return raw.encode('utf-8')

def _load_revoked():
    try:
        with open(REVOKED_FILE) as f:
            data = json.load(f)
        return set(data.get('jti', [])), set(data.get('fingerprints', []))
    except (OSError, ValueError):
        return set(), set()

def token_fingerprint(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()

def verify_phone_token(token: str):
    """(ok, reason, claims). reason is a short string for logs/401 body."""
    if not AUTH_REQUIRED:
        return True, 'auth_disabled', {}
    if not token:
        return False, 'no_token', {}
    secret = _basic_auth_secret()
    if secret is None:
        logger.error('auth: cannot load dashboard.basic_auth secret — rejecting (fail closed)')
        return False, 'server_misconfigured', {}
    try:
        padded = token + '=' * (-len(token) % 4)
        try:
            blob = base64.urlsafe_b64decode(padded.encode())
        except Exception:
            blob = base64.b64decode(padded.encode())
        if len(blob) <= 32:
            return False, 'malformed', {}
        raw, sig = blob[:-32], blob[-32:]
        expected = hmac.new(secret, raw, hashlib.sha256).digest()
        if not hmac.compare_digest(sig, expected):
            return False, 'bad_signature', {}
        claims = json.loads(raw)
    except Exception:
        return False, 'malformed', {}
    if claims.get('kind') != 'access':
        return False, 'wrong_kind', {}
    if int(claims.get('exp', 0)) <= int(time.time()):
        return False, 'expired', {}
    revoked_jti, revoked_fp = _load_revoked()
    if claims.get('jti') and claims['jti'] in revoked_jti:
        return False, 'revoked', {}
    if token_fingerprint(token) in revoked_fp:
        return False, 'revoked', {}
    return True, 'ok', claims

def is_token_revoked(token: str, claims: dict) -> bool:
    revoked_jti, revoked_fp = _load_revoked()
    return bool((claims.get('jti') and claims['jti'] in revoked_jti)
                or token_fingerprint(token) in revoked_fp)
HOTWORDS = ['Hermes', 'HUI', 'phone']  # → welcome config + whisper initial_prompt
TTS_VOICES = [
    'en-US', 'en-GB', 'en-IN',
    'de-DE', 'fr-FR', 'es-ES', 'es-US',
    'it-IT', 'pt-BR', 'ro-RO', 'ru-RU',
    'pl-PL', 'cs-CZ', 'zh-CN'
]  # exposed in welcome config for client voice choice

voice_sessions = {}   # (device_id, voice_session) -> session dict
VOICE_TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.voice_tmp')
UPLOAD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'uploads')
os.makedirs(VOICE_TMP, exist_ok=True)

# Raw-gateway-frame log (the hedge for the ONE shape fact a mock can't prove):
# every frame to/from the bridge is logged; first real use = observation, fix from
# the log. Set env GATEWAY_FRAME_LOG='' to disable, path to relocate.
GATEWAY_FRAME_LOG = os.environ.get('GATEWAY_FRAME_LOG',
                                   os.path.join(VOICE_TMP, 'gateway_frames.jsonl'))
os.path.dirname(GATEWAY_FRAME_LOG or '.') and os.makedirs(
    os.path.dirname(GATEWAY_FRAME_LOG) or '.', exist_ok=True)

def _gwlog(direction, raw):
    if not GATEWAY_FRAME_LOG:
        return
    try:
        with open(GATEWAY_FRAME_LOG, 'a') as fh:
            fh.write(json.dumps({'dir': direction, 'raw': str(raw)[:4000]}) + '\n')
    except OSError:
        pass

def resolve_committed_python():
    """Hermes's committed env per ~/.hermes/installs/<hash>/facts.json → packages.venv."""
    for facts in sorted(glob.glob(os.path.expanduser('~/.hermes/installs/*/facts.json'))):
        try:
            with open(facts) as f:
                env = json.load(f)['packages']['venv']['environment']
            py = os.path.join(env, 'bin', 'python3')
            if os.path.exists(py):
                return py
        except (KeyError, ValueError):
            continue
    return None

class BridgeDropped(Exception):
    """The bridge connection died — caller resumes + retries once."""

class BridgeFailed(Exception):
    """The gateway refused the turn — terminal for this voice session."""

class GatewayBridge:
    """Mini-client of hermes serve following the verified contract:
    auth = signed blob via ?token= (re-minted on every dial); session.create sends
    only {source}; after every ws drop → session.resume with the stored id from
    the create snapshot; prompt.submit with NO submission_id (queued IS allowed)."""
    def __init__(self):
        self.ws = None
        self.client = None
        self.session_id = None       # runtime id — prompt.submit names this
        self.stored_session_id = None  # session.resume names THIS
        self._req = 0

    def _frame(self, method, params):
        self._req += 1
        return json.dumps({'jsonrpc': '2.0', 'id': str(self._req),
                           'method': method, 'params': params})

    async def _send(self, data):
        _gwlog('out', data)
        await self.ws.send(data)

    async def _recv(self, timeout):
        raw = await asyncio.wait_for(self.ws.recv(), timeout)
        _gwlog('in', raw)
        return raw

    def _msg_text(self, params):
        """Permissive across the message.* nesting shapes (the ONE real shape is
        confirmed only by observing the frame log on first use)."""
        payload = params.get('payload') or {}
        for src in (payload, params):
            t = src.get('text')
            if isinstance(t, str) and t:
                return t
            m = src.get('message')
            if isinstance(m, dict) and isinstance(m.get('text'), str) and m['text']:
                return m['text']
        return ''

    def _msg_status(self, params):
        for src in (params.get('payload') or {}, params):
            if src.get('status') is not None:
                return src.get('status')
        return None

    async def dial(self):
        if self.ws is not None:
            return
        proc = await asyncio.create_subprocess_exec(
            sys.executable, MINT_SCRIPT,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        out, _ = await asyncio.wait_for(proc.communicate(), 20)
        if proc.returncode != 0:
            raise BridgeFailed('token mint failed: check dashboard.basic_auth in config.yaml')
        token = out.decode().strip()
        url = (os.environ.get('GATEWAY_WS_URL') or GATEWAY_WS_URL) + '?' + \
            urllib.parse.urlencode({'token': token})
        self.client = websockets.connect(url, max_size=16 << 20)
        self.ws = await self.client.__aenter__()
        if self.session_id is None:
            snap = await self.rpc('session.create', {'source': 'cli'})  # request_id rejected by serve
        else:
            # contract: after EVERY ws drop resume with the stored snapshot id;
            # runtime ids answer 4007 "session not found" — expected, not a bug.
            snap = await self.rpc('session.resume', {'session_id': self.stored_session_id})
        if snap.get('session_id'):
            self.session_id = snap['session_id']
        if snap.get('stored_session_id'):
            self.stored_session_id = snap['stored_session_id']  # create snapshot's stored id

    async def rpc(self, method, params):
        req_id = str(uuid.uuid4())[:8]
        await self._send(json.dumps({'jsonrpc': '2.0', 'id': req_id,
                                     'method': method, 'params': params}))
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                raw = await self._recv(10)
            except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
                raise BridgeDropped() from None
            f = _json_frame(raw)
            if f is None:
                continue
            if f.get('id') == req_id:
                if 'error' in f:
                    raise BridgeFailed(f"{method} refused: {f['error']}")
                return f.get('result', {})
        raise BridgeDropped()

    async def prompt_reply(self, text, timeout_s=REPLY_TIMEOUT_S):
        """Submit a prompt, collect the message.* stream, return the full text.
        NO submission_id (queued IS allowed). ws drops mid-stream → resume and retry once."""
        last_err = None
        for attempt in range(2):
            try:
                await self.dial()
                req_id = str(uuid.uuid4())[:8]
                await self._send(json.dumps({'jsonrpc': '2.0', 'id': req_id,
                    'method': 'prompt.submit',
                    'params': {'session_id': self.session_id, 'text': text, 'queued': True}}))
                streamed = []
                final = None
                started = time.monotonic()
                while time.monotonic() - started < timeout_s:
                    try:
                        raw = await self._recv(15)
                    except asyncio.TimeoutError:
                        # hui's answer to a 15 s silence: gateway.ping; 45 s dead = drop.
                        await self._send(json.dumps({'jsonrpc': '2.0', 'id': -1,
                                                     'method': 'gateway.ping'}))
                        continue
                    except websockets.exceptions.ConnectionClosed:
                        raise BridgeDropped('bridge closed')
                    f = _json_frame(raw)
                    if f is None:
                        continue
                    if f.get('method') == 'event':
                        params = f.get('params', {})
                        ev_type = params.get('type')
                        if ev_type == 'message.delta':
                            t = self._msg_text(params)
                            if t:
                                streamed.append(t)
                        elif ev_type == 'message.complete':
                            final = self._msg_text(params) or final
                            status = self._msg_status(params)
                            if status not in (None, '', 'ok', 'complete', 'completed', 'done'):
                                last_err = f"turn ended: {status}"
                            return final or ''.join(streamed)
                        elif ev_type == 'error':
                            payload = params.get('payload') or {}
                            last_err = (self._msg_text(params)
                                        or payload.get('message') or 'gateway error')
                            raise BridgeFailed(last_err)
                        # message.start/interim, tool.*, session.*: ignored (contract)
                    elif f.get('id') == req_id and 'error' in f:
                        raise BridgeFailed(str(f['error']))
                    # else: the submit receipt — queued is allowed, ignore.
                last_err = 'reply timed out'
                raise BridgeDropped('reply timeout — exercise the resume path once')
            except BridgeDropped:
                await self.close()
                continue
            except BridgeFailed as e:
                raise
        raise BridgeFailed(last_err or 'bridge failed')

    async def close(self):
        try:
            if self.ws is not None:
                await self.ws.close()
        except Exception:
            pass
        try:
            if self.client is not None:
                await self.client.__aexit__(None, None, None)
        except Exception:
            pass
        self.ws = None
        self.client = None

bridge = None  # lazily created — the server works standalone without hermes serve

def _json_frame(raw):
    try:
        f = json.loads(raw)
        return f if isinstance(f, dict) else None
    except (json.JSONDecodeError, TypeError):
        return None

async def send_voice_command(ws, device_id, command, payload):
    """Dispatch to the socket that owns the voice session (falls back to the
    device's last registered ws — that's what makes POST /events work)."""
    if ws is None:
        entries = connected_devices.get(device_id)
        ws = entries[-1]['ws'] if entries else None
    if ws is None:
        logger.warning(f"No socket for voice dispatch to {device_id}: {command}")
        return False
    try:
        await ws.send_json({'type': 'command', 'command': command,
                            'request_id': str(uuid.uuid4())[:8], 'payload': payload})
        return True
    except Exception as e:
        logger.error(f"Voice command failed for {device_id}: {e}")
        return False

stt_helper = None

def _stt_ensure_started():
    py = resolve_committed_python()
    helper = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'companion_stt.py')
    if not py or not os.path.exists(helper):
        return None
    return py, helper

async def stt_transcribe(wav_path, prompt):
    """NDJSON pipe into companion_stt.py running on the COMMITTED venv python.
    One model total: the helper is long-lived (loads base once), never a second
    in-process copy; never pip-installs anything."""
    global stt_helper
    try:
        if stt_helper is None or stt_helper.returncode is not None:
            py_helper = _stt_ensure_started()
            if py_helper is None:
                return None, 'no committed venv python or companion_stt.py'
            stt_helper = await asyncio.create_subprocess_exec(
                py_helper[0], py_helper[1],
                stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                stderr=None)
        stt_helper.stdin.write((json.dumps({'wav': wav_path, 'prompt': prompt}) + '\n').encode())
        await stt_helper.stdin.drain()
        line = await asyncio.wait_for(stt_helper.stdout.readline(), STT_TIMEOUT_S)
    except asyncio.TimeoutError:
        if stt_helper:
            stt_helper.kill()
        return None, 'stt timeout'
    except Exception as e:
        return None, f'stt failed: {e}'
    try:
        res = json.loads(line)
    except (json.JSONDecodeError, TypeError):
        return None, 'stt bad output'
    if not res.get('success'):
        return None, res.get('error', 'stt error')
    return res.get('transcript', ''), None

def _write_wav(name, pcm, rate):
    path = os.path.join(VOICE_TMP, name)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return path

async def chat_handle(device_id, event, ws):
    payload = event.get('payload', {})
    text = payload.get('text', '').strip()
    voice_session = payload.get('voice_session')
    if not text:
        return
    logger.info(f"💬 chat from {device_id}: {text[:80]} (session={voice_session})")
    
    # MOCK_CHAT=1 → canned reply, never touches the real thinking model
    if os.environ.get('MOCK_CHAT') == '1':
        import random
        canned = [
            f"Mock reply: I heard you say '{text}'. (LLM mocked, no real model called)",
            f"Mock: '{text}' — that's a canned response from the test mock LLM.",
            f"Mock reply received: '{text}'. Mock gateway active.",
        ]
        reply = random.choice(canned)
        await send_voice_command(ws, device_id, 'chat_reply', {'text': reply, 'voice_session': voice_session})
        logger.info(f"✅ mock chat done for {voice_session}: {reply[:80]}")
        return

    # Use the same bridge to Hermes gateway
    global bridge
    if bridge is None:
        bridge = GatewayBridge()
    try:
        reply = await bridge.prompt_reply(text)
    except Exception as e:
        logger.error(f"bridge failed for chat: {e}")
        await send_voice_command(ws, device_id, 'chat_reply', {'text': f'Error: {e}', 'voice_session': voice_session})
        return

    reply = ' '.join(reply.split())
    if not reply:
        await send_voice_command(ws, device_id, 'chat_reply', {'text': '...', 'voice_session': voice_session})
        return
    
    await send_voice_command(ws, device_id, 'chat_reply', {'text': reply, 'voice_session': voice_session})
    logger.info(f"✅ chat done for {voice_session}: {reply[:80]}")


async def voice_handle(device_id, event, ws):
    etype = event['type']
    payload = event.get('payload', {})
    session = payload.get('voice_session')
    if not session:
        return
    key = (device_id, session)
    now = time.monotonic()

    if etype == 'voice_start':
        entry = voice_sessions.get(key)
        if entry is None:
            entry = {'chunks': {}, 'ws': ws, 'sample_rate': int(payload.get('sample_rate') or 16000),
                     'channels': int(payload.get('channels') or 1),
                     'fmt': payload.get('format') or 'pcm16', 'last_activity': now}
            voice_sessions[key] = entry
        logger.info(f"🎤 voice_start {session} from {device_id}")
    elif etype == 'audio':
        entry = voice_sessions.get(key)
        if entry is None:  # ws drop between voice_start and chunks: create implicitly
            entry = {'chunks': {}, 'ws': ws, 'sample_rate': 16000, 'channels': 1,
                     'fmt': 'pcm16', 'last_activity': now}
            voice_sessions[key] = entry
        seq = payload.get('sequence')
        raw = base64.b64decode(payload.get('data_b64') or '')
        if seq is not None:
            entry['chunks'][int(seq)] = raw
        entry['last_activity'] = now
        logger.debug(f"audio chunk {seq} ({len(raw)} B) for {session}")
    elif etype == 'voice_cancel':
        voice_sessions.pop(key, None)
        logger.info(f"voice_cancel {session} (auto-silence guard) — nothing sent")
    elif etype == 'voice_send':
        entry = voice_sessions.pop(key, None)
        if entry is None:
            # no session and no activity for 30 s → drop silently (idle timeout).
            logger.info(f"voice_send for unknown/expired session {session} — dropped")
            return
        entry['ws'] = ws or entry['ws']
        asyncio.create_task(_voice_finalize(device_id, entry, payload))

def _voice_sweep():
    now = time.monotonic()
    expired = [k for k, e in voice_sessions.items() if now - e['last_activity'] > IDLE_DROP_S]
    for k in expired:
        voice_sessions.pop(k, None)
        logger.info(f"⏳ voice session {k[1][:24]} idle >{IDLE_DROP_S}s — dropped silently")

async def _voice_finalize(device_id, entry, payload):
    chunks = entry['chunks']
    rate = entry['sample_rate'] or 16000
    chans = entry['channels'] or 1
    last_seq = payload.get('last_sequence')
    session = payload.get('voice_session')
    total_seq = int(last_seq) if last_seq is not None else (max(chunks) if chunks else -1)
    have_ms = int(sum(len(b) for b in chunks.values()) * 1000 / (2 * chans * rate))
    missing = sum(1 for s in range(0, total_seq + 1) if s not in chunks)
    # Gap length estimate: nominal chunk span from the declared duration, else 100 ms
    # (the audio_chunk_ms contract value — no new config knobs).
    nominal_ms = max(1, int((payload.get('duration_ms') or 0) / (total_seq + 1))) if total_seq >= 0 else 100
    missing_ms = missing * nominal_ms
    logger.info(f"🎤 voice_send {session} from {device_id}: {len(chunks)} chunks, "
                f"have={have_ms}ms, gaps={missing} seqs (~{missing_ms}ms)")

    if have_ms == 0:
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return
    if have_ms > MAX_COVER_MS:
        logger.info(f"voice {session} exceeds 60 s — truncated → failed")
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return
    if missing_ms >= GAP_FAIL_MS:
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return

    await send_voice_command(entry['ws'], device_id, 'voice_state',
                             {'state': 'received', 'voice_session': session})

    pcm = b''.join(chunks[s] for s in sorted(chunks))
    try:
        wav_path = _write_wav(f'{session}.wav', pcm, rate)
    except Exception as e:
        logger.error(f"voice wav failed: {e}")
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return

    await send_voice_command(entry['ws'], device_id, 'voice_state',
                             {'state': 'transcribing', 'voice_session': session})
    transcript, err = await stt_transcribe(wav_path, ' '.join(HOTWORDS))
    try:
        os.unlink(wav_path)
    except FileNotFoundError:
        pass

    if not transcript or transcript.strip() == '':
        logger.info(f"voice {session}: no transcript ({'err: ' + str(err) if err else 'silence'})")
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return

    global bridge
    if bridge is None:
        bridge = GatewayBridge()
    try:
        reply = await bridge.prompt_reply(transcript)
    except BridgeFailed as e:
        logger.error(f"bridge failed for {session}: {e}")
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return
    except Exception as e:  # DropError etc. escaping the retry loop
        logger.error(f"bridge drop unrecovered for {session}: {e}")
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return

    reply = ' '.join(reply.split())
    if not reply:
        await send_voice_command(entry['ws'], device_id, 'voice_state',
                                 {'state': 'failed', 'voice_session': session})
        return
    # ONE final speak — no partial-sentence TTS. Existing speak + voice_session pairing.
    await send_voice_command(entry['ws'], device_id, 'speak',
                             {'text': reply, 'voice_session': session})
    logger.info(f"✅ voice loop done for {session}: {reply[:80]}")

async def handle_event(device_id: str, event: dict, ws=None):
    event_type = event.get('type')
    payload = event.get('payload', {})
    if event_type in ('voice_start', 'voice_send', 'voice_cancel'):
        await voice_handle(device_id, event, ws)
        return
    if event_type == 'audio' and payload.get('voice_session'):
        await voice_handle(device_id, event, ws)
        return
    if event_type == 'chat':
        await chat_handle(device_id, event, ws)
        return
    logger.info(f"Event from {device_id}: {event_type}")
    if event_type == 'attention' and payload.get('addressing'):
        logger.info(f"🎯 ATTENTION DETECTED from {device_id}: confidence={payload.get('confidence')}")
        await event_queue.put({'type': 'attention', 'device_id': device_id, 'payload': payload, 'timestamp': event.get('timestamp')})
    elif event_type == 'battery':
        logger.info(f"🔋 Battery from {device_id}: level={payload.get('level')}, charging={payload.get('charging')}")
        await event_queue.put({'type': 'battery', 'device_id': device_id, 'payload': payload, 'timestamp': event.get('timestamp')})
    elif event_type == 'notification':
        logger.info(f"🔔 Notification from {device_id}: {payload.get('package')} - {payload.get('title')}")
        await event_queue.put({'type': 'notification', 'device_id': device_id, 'payload': payload, 'timestamp': event.get('timestamp')})
    elif event_type == 'screen':
        logger.info(f"📱 Screen from {device_id}: state={payload.get('state')}")
        await event_queue.put({'type': 'screen', 'device_id': device_id, 'payload': payload, 'timestamp': event.get('timestamp')})
    elif event_type == 'audio':
        await event_queue.put({'type': 'audio', 'device_id': device_id, 'payload': payload, 'timestamp': event.get('timestamp')})

async def ws_handler(request):
    device_id = request.headers.get('X-Device-ID', 'unknown')
    auth_token = request.headers.get('Authorization', '').replace('Bearer ', '').strip()
    ok, reason, claims = verify_phone_token(auth_token)
    if not ok:
        logger.warning(f"Auth rejected device {device_id}: {reason}")
        return web.Response(status=401, text=json.dumps({'error': 'unauthorized', 'reason': reason}),
                            content_type='application/json')
    ws = web.WebSocketResponse()
    await ws.prepare(request)

    logger.info(f"Device connected: {device_id} (secure, jti={claims.get('jti', 'legacy')[:8] if claims.get('jti') else 'legacy'})")
    entry = {'ws': ws, 'connected_at': datetime.now().isoformat(), 'auth_token': auth_token,
             'auth_claims': claims}
    if device_id not in connected_devices:
        connected_devices[device_id] = []
    connected_devices[device_id].append(entry)

    await ws.send_json({
        'type': 'welcome',
        'device_id': device_id,
        'config': {
            'gaze_check_interval_ms': 2000,
            'audio_chunk_ms': 100,
            'battery_report_interval_s': 60,
            'notification_filter_packages': [
                'com.whatsapp', 'com.google.android.apps.messaging',
                'com.android.mms', 'com.google.android.gm',
                'com.microsoft.office.outlook', 'org.telegram.messenger'
            ],
            'hotwords': HOTWORDS,
            'tts_voices': TTS_VOICES
        }
    })

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    event = json.loads(msg.data)
                    await handle_event(device_id, event, ws)
                except json.JSONDecodeError as e:
                    logger.error(f"Invalid JSON from {device_id}: {e}")
            elif msg.type == WSMsgType.ERROR:
                logger.error(f"WS error for {device_id}: {ws.exception()}")
    finally:
        # Remove this specific connection from the list
        if device_id in connected_devices:
            connected_devices[device_id] = [e for e in connected_devices[device_id] if e['ws'] != ws]
            if not connected_devices[device_id]:
                connected_devices.pop(device_id, None)
        logger.info(f"Device disconnected: {device_id}")

    return ws

async def http_event_handler(request):
    event = await request.json()
    device_id = request.headers.get('X-Device-ID', event.get('device_id', 'unknown'))
    await handle_event(device_id, event)
    return web.json_response({"status": "ok"})

async def http_command_handler(request):
    """HTTP endpoint to send commands to devices"""
    data = await request.json()
    device_id = data.get('device_id')
    command = data.get('command')
    payload = data.get('payload', {})

    if not device_id or not command:
        return web.json_response({"error": "device_id and command required"}, status=400)

    success = await send_command(device_id, command, payload)
    return web.json_response({"status": "ok" if success else "failed", "device_id": device_id})

async def http_upload_handler(request):
    """HTTP endpoint to upload attachment files. Returns {url} for use in chat markdown."""
    reader = await request.multipart()
    field = await reader.next()
    if field is None or field.name != 'file':
        return web.json_response({"error": "file field required"}, status=400)

    filename = field.filename or 'attachment.bin'
    os.makedirs(UPLOAD_DIR, exist_ok=True)
    filepath = os.path.join(UPLOAD_DIR, filename)

    with open(filepath, 'wb') as f:
        while True:
            chunk = await field.read_chunk()
            if not chunk:
                break
            f.write(chunk)

    # Return the URL the client can use to fetch the file
    # Served via static route or direct path; for now return a relative path
    url = f"/uploads/{filename}"
    logger.info(f"📎 Upload saved: {filepath} -> {url}")
    return web.json_response({"status": "ok", "url": url, "filename": filename})

async def send_command(device_id: str, command: str, payload: dict):
    if device_id not in connected_devices or not connected_devices[device_id]:
        logger.warning(f"Device {device_id} not connected")
        return False
    # Legacy behavior: target the last registered connection
    ws = connected_devices[device_id][-1]['ws']
    request_id = str(uuid.uuid4())[:8]
    message = {'type': 'command', 'command': command, 'request_id': request_id, 'payload': payload}
    try:
        await ws.send_json(message)
        logger.info(f"Sent command {command} to {device_id} (req_id={request_id})")
        return True
    except Exception as e:
        logger.error(f"Failed to send command to {device_id}: {e}")
        return False

async def voice_sweeper():
    while True:
        await asyncio.sleep(5)
        _voice_sweep()

async def revoked_watcher():
    """Drop live connections whose token was revoked after connect."""
    while True:
        await asyncio.sleep(5)
        try:
            for device_id, entries in list(connected_devices.items()):
                for entry in list(entries):
                    if is_token_revoked(entry.get('auth_token', ''), entry.get('auth_claims', {})):
                        logger.warning(f"Auth revoked: closing connection for {device_id}")
                        try:
                            await entry['ws'].close(code=1008, message='token revoked')
                        except Exception:
                            pass
        except Exception as e:
            logger.error(f"revoked_watcher: {e}")

async def main():
    app = web.Application()
    app.router.add_get('/ws', ws_handler)
    app.router.add_post('/events/{type}', http_event_handler)
    app.router.add_post('/events', http_event_handler)
    app.router.add_post('/command', http_command_handler)
    app.router.add_post('/upload', http_upload_handler)
    app.router.add_static('/uploads', UPLOAD_DIR)

    # SSL context with self-signed cert
    cert_dir = os.path.dirname(os.path.abspath(__file__))
    ssl_context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    ssl_context.load_cert_chain(
        os.path.join(cert_dir, 'server.crt'),
        os.path.join(cert_dir, 'server.key')
    )

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, '0.0.0.0', 8081, ssl_context=ssl_context)
    await site.start()

    asyncio.create_task(voice_sweeper())
    asyncio.create_task(revoked_watcher())

    logger.info("Companion WSS server running on wss://0.0.0.0:8081/ws")
    logger.info("HTTP events: POST https://0.0.0.0:8081/events")
    logger.info("HTTP upload: POST https://0.0.0.0:8081/uploads")

    try:
        while True:
            await asyncio.sleep(3600)
    except KeyboardInterrupt:
        await runner.cleanup()

if __name__ == '__main__':
    asyncio.run(main())
