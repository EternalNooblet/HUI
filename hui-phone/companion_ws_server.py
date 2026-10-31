#!/usr/bin/env python3
"""
WebSocket Server for Companion App Integration
Add this to phone_daemon.py or run as a separate service
"""

import asyncio
import json
import logging
from datetime import datetime
from aiohttp import web, WSMsgType
import uuid

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Event queue for daemon integration
event_queue = asyncio.Queue()

# Connected devices
connected_devices = {}

async def ws_handler(request):
    """WebSocket handler for companion app connections"""
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    
    device_id = request.headers.get('X-Device-ID', 'unknown')
    auth_token = request.headers.get('Authorization', '').replace('Bearer ', '')
    
    logger.info(f"Device connected: {device_id}")
    connected_devices[device_id] = {
        'ws': ws,
        'connected_at': datetime.now().isoformat(),
        'auth_token': auth_token
    }
    
    # Send welcome/config
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
    """Process incoming events from companion app"""
    event_type = event.get('type')
    payload = event.get('payload', {})
    
    logger.info(f"Event from {device_id}: {event_type}")
    
    if event_type == 'attention':
        if payload.get('addressing'):
            logger.info(f"🎯 ATTENTION DETECTED from {device_id}: confidence={payload.get('confidence')}")
            # Put in queue for daemon
            await event_queue.put({
                'type': 'attention',
                'device_id': device_id,
                'payload': payload,
                'timestamp': event.get('timestamp')
            })
    
    elif event_type == 'battery':
        logger.info(f"🔋 Battery from {device_id}: level={payload.get('level')}, charging={payload.get('charging')}")
        await event_queue.put({
            'type': 'battery',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })
    
    elif event_type == 'notification':
        logger.info(f"🔔 Notification from {device_id}: {payload.get('package')} - {payload.get('title')}")
        await event_queue.put({
            'type': 'notification',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })
    
    elif event_type == 'screen':
        logger.info(f"📱 Screen from {device_id}: state={payload.get('state')}")
        await event_queue.put({
            'type': 'screen',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })
    
    elif event_type == 'audio':
        # Audio chunks for STT - queue for processing
        await event_queue.put({
            'type': 'audio',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })

async def http_event_handler(request):
    """HTTP fallback for events"""
    event = await request.json()
    device_id = request.headers.get('X-Device-ID', 'unknown')
    await handle_event(device_id, event)
    return web.json_response({"status": "ok"})

async def send_command(device_id: str, command: str, payload: dict):
    """Send command to device"""
    if device_id not in connected_devices:
        logger.warning(f"Device {device_id} not connected")
        return False
    
    ws = connected_devices[device_id]['ws']
    request_id = str(uuid.uuid4())[:8]
    
    message = {
        'type': 'command',
        'command': command,
        'request_id': request_id,
        'payload': payload
    }
    
    try:
        await ws.send_json(message)
        logger.info(f"Sent command {command} to {device_id} (req_id={request_id})")
        return True
    except Exception as e:
        logger.error(f"Failed to send command to {device_id}: {e}")
        return False

# Example usage functions
async def speak(device_id: str, text: str):
    return await send_command(device_id, 'speak', {'text': text})

async def wake_screen(device_id: str):
    return await send_command(device_id, 'screen_action', {'action': 'wake'})

async def launch_app(device_id: str, package: str):
    return await send_command(device_id, 'launch_app', {'package': package})

async def main():
    app = web.Application()
    app.router.add_get('/ws', ws_handler)
    app.router.add_post('/events/{type}', http_event_handler)
    app.router.add_post('/events', http_event_handler)
    
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, '0.0.0.0', 8081)
    await site.start()
    
    logger.info("Companion WebSocket server running on ws://0.0.0.0:8081/ws")
    logger.info("HTTP events: POST http://0.0.0.0:8081/events")
    
    # Keep running
    try:
        while True:
            await asyncio.sleep(3600)
    except KeyboardInterrupt:
        await runner.cleanup()

if __name__ == '__main__':
    asyncio.run(main())