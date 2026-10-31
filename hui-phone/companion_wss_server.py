#!/usr/bin/env python3
"""
Secure WebSocket Server (WSS) for Companion App
Uses TLS with self-signed certificate
"""

import asyncio
import json
import logging
import ssl
from datetime import datetime
from aiohttp import web, WSMsgType
import uuid

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

event_queue = asyncio.Queue()
connected_devices = {}

async def ws_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    
    device_id = request.headers.get('X-Device-ID', 'unknown')
    auth_token = request.headers.get('Authorization', '').replace('Bearer ', '')
    
    logger.info(f"Device connected: {device_id} (secure)")
    connected_devices[device_id] = {
        'ws': ws,
        'connected_at': datetime.now().isoformat(),
        'auth_token': auth_token
    }
    
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
            ]
        }
    })
    
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    event = json.loads(msg.data)
                    await handle_event(device_id, event)
                except json.JSONDecodeError as e:
                    logger.error(f"Invalid JSON from {device_id}: {e}")
            elif msg.type == WSMsgType.ERROR:
                logger.error(f"WS error for {device_id}: {ws.exception()}")
    finally:
        connected_devices.pop(device_id, None)
        logger.info(f"Device disconnected: {device_id}")
    
    return ws

async def handle_event(device_id: str, event: dict):
    event_type = event.get('type')
    payload = event.get('payload', {})
    
    logger.info(f"Event from {device_id}: {event_type}")
    
    if event_type == 'attention' and payload.get('addressing'):
        logger.info(f"🎯 ATTENTION DETECTED from {device_id}: confidence={payload.get('confidence')}")
        await event_queue.put({
            'type': 'attention', 'device_id': device_id, 'payload': payload, 'timestamp': event.get('timestamp')
        })
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

async def http_event_handler(request):
    event = await request.json()
    device_id = request.headers.get('X-Device-ID', 'unknown')
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

async def send_command(device_id: str, command: str, payload: dict):
    if device_id not in connected_devices:
        logger.warning(f"Device {device_id} not connected")
        return False
    ws = connected_devices[device_id]['ws']
    request_id = str(uuid.uuid4())[:8]
    message = {'type': 'command', 'command': command, 'request_id': request_id, 'payload': payload}
    try:
        await ws.send_json(message)
        logger.info(f"Sent command {command} to {device_id} (req_id={request_id})")
        return True
    except Exception as e:
        logger.error(f"Failed to send command to {device_id}: {e}")
        return False

async def main():
    app = web.Application()
    app.router.add_get('/ws', ws_handler)
    app.router.add_post('/events/{type}', http_event_handler)
    app.router.add_post('/events', http_event_handler)
    app.router.add_post('/command', http_command_handler)
    
    # SSL context with self-signed cert
    import os
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
    
    logger.info("Companion WSS server running on wss://0.0.0.0:8081/ws")
    logger.info("HTTP events: POST https://0.0.0.0:8081/events")
    
    try:
        while True:
            await asyncio.sleep(3600)
    except KeyboardInterrupt:
        await runner.cleanup()

if __name__ == '__main__':
    asyncio.run(main())