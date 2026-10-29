#!/usr/bin/env python3
"""Minimal fake gateway: verifies hui's interactive prompt flow.

Serves one WebSocket connection on 127.0.0.1:<port>, runs a scripted
JSON-RPC scenario (gateway.ready → session.create → approval / clarify /
sudo server→client requests) and prints what the client answered.
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def send_frame(conn, payload: bytes, opcode=0x1):
    header = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header += bytes([n])
    elif n <= 0xFFFF:
        header += bytes([126]) + struct.pack(">H", n)
    else:
        header += bytes([127]) + struct.pack(">Q", n)
    conn.sendall(header + payload)


def read_frame(conn):
    head = recv_exact(conn, 2)
    length = head[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", recv_exact(conn, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", recv_exact(conn, 8))[0]
    if head[1] & 0x80:
        mask = recv_exact(conn, 4)
        data = bytearray(recv_exact(conn, length))
        for i in range(length):
            data[i] ^= mask[i % 4]
        return head[0] & 0x0F, bytes(data)
    return head[0] & 0x0F, recv_exact(conn, length)


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("client closed")
        buf += chunk
    return buf


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    print("PORT", srv.getsockname()[1], flush=True)
    conn, _ = srv.accept()
    req = b""
    while b"\r\n\r\n" not in req:
        req += conn.recv(4096)
    key = ""
    for line in req.split(b"\r\n"):
        if line.lower().startswith(b"sec-websocket-key:"):
            key = line.split(b":", 1)[1].strip().decode()
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    conn.sendall((
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())

    def rpc(method, params):
        send_frame(conn, json.dumps({"jsonrpc": "2.0", "method": "event",
                     "params": {"type": method, "payload": params}}).encode())

    rpc("gateway.ready", {"heartbeat": True, "session_authority": True})

    sid = None
    answers = []
    while True:
        op, data = read_frame(conn)
        if op == 0x8:
            break
        if op != 0x1:
            continue
        for line in data.split(b"\n"):
            if not line.strip():
                continue
            f = json.loads(line)
            if "method" not in f:
                answers.append(f)
                rid = f.get("id")
                if rid == "srq-approval1":
                    send_frame(conn, json.dumps({"jsonrpc": "2.0", "id": "srq-clarify1",
                        "method": "clarify", "params": {"session_id": sid,
                        "questions": [{"qid": "q1", "question": "Which color?",
                                       "choices": ["red", "blue"]}]}}).encode())
                elif rid == "srq-clarify1":
                    send_frame(conn, json.dumps({"jsonrpc": "2.0", "id": "srq-sudo1",
                        "method": "sudo", "params": {"session_id": sid,
                        "command": "apt install thing"}}).encode())
                continue
            if f["method"] == "gateway.ping":
                send_frame(conn, json.dumps(
                    {"jsonrpc": "2.0", "id": f["id"], "result": {"ok": True}}).encode())
            elif f["method"] == "session.create":
                sid = "local-fake0000000000000000000000000000000000000000000000000000000000"
                send_frame(conn, json.dumps({"jsonrpc": "2.0", "id": f["id"], "result": {
                    "session_id": sid, "stored_session_id": sid, "messages": [],
                    "message_count": 0, "running": False, "authority_epoch": 1,
                    "replay_epoch": "e", "last_sequence": 0, "subscription_id": "sub-1",
                    "revision": 1, "execution_generation": 1, "pending": [],
                    "prompts": [], "info": {}}}).encode())
                # 1. approval (server→client request)
                send_frame(conn, json.dumps({"jsonrpc": "2.0", "id": "srq-approval1",
                    "method": "approval", "params": {"session_id": sid,
                    "request_id": "req-1", "command": "rm -rf /tmp/x",
                    "description": "delete a directory",
                    "choices": ["once", "session", "always", "deny"]}}).encode())
            elif f["method"] == "session.detach":
                answers.append({"detached": f["params"].get("end_reason")})
                send_frame(conn, json.dumps({"jsonrpc": "2.0", "id": f["id"],
                    "result": {"ok": True}}).encode())

    print("ANSWERS", json.dumps(answers), flush=True)


if __name__ == "__main__":
    main()
