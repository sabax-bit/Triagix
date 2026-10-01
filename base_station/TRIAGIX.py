import socket
import threading
from datetime import datetime
from flask import Flask
from flask_socketio import SocketIO, emit

app = Flask(__name__)
app.config['SECRET_KEY'] = 'triagix_tactical_key'
socketio = SocketIO(app, cors_allowed_origins="*")

# State Storage
active_nodes = {}

# ================= 1. UDP FULL DUPLEX AUDIO RELAY (50001 -> 50002) =================
AUDIO_RECEIVE_PORT = 50001
AUDIO_BROADCAST_PORT = 50002

def audio_udp_relay():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", AUDIO_RECEIVE_PORT))
    print(f"[*] [AUDIO RELAY] Listening on UDP Port {AUDIO_RECEIVE_PORT}...")

    while True:
        try:
            data, addr = sock.recvfrom(2048)
            # Send the received audio back to the mobile client on 50002
            sock.sendto(data, (addr[0], AUDIO_BROADCAST_PORT))
        except Exception as e:
            print(f"[!] Audio stream error: {e}")

# Start UDP Audio thread
threading.Thread(target=audio_udp_relay, daemon=True).start()

# ================= 2. SOCKET.IO REAL-TIME OPERATIONS =================
@socketio.on('connect')
def handle_connect():
    print(f"[+] Tactical Unit Connected: Client Session Active")

@socketio.on('disconnect')
def handle_disconnect():
    print(f"[-] Tactical Unit Disconnected")

# Chat Communication
@socketio.on('send_chat')
def handle_chat(data):
    sender_id = data.get('sender_id', 'UNKNOWN')
    message = data.get('message', '')
    timestamp = data.get('timestamp', datetime.now().strftime("%H:%M"))
    print(f"[CHAT] [{timestamp}] {sender_id}: {message}")

    # Broadcast to all connected units
    emit('receive_chat', {
        'sender_id': sender_id,
        'message': message,
        'timestamp': timestamp
    }, broadcast=True)

# 2-Way Location Sync
@socketio.on('update_location')
def handle_location(data):
    node_id = data.get('node_id')
    lat = data.get('lat')
    lon = data.get('lon')
    status = data.get('status', 'ACTIVE')

    active_nodes[node_id] = {'lat': lat, 'lon': lon, 'status': status}
    print(f"[LOC UPDATE] Node: {node_id} | Coordinates: {lat}, {lon}")

    # Broadcast coordinates
    emit('node_moved', {
        'node_id': node_id,
        'lat': lat,
        'lon': lon,
        'status': status
    }, broadcast=True)

# Geo-SOS Distress Call
@socketio.on('trigger_geo_sos')
def handle_sos(data):
    node_id = data.get('node_id')
    lat = data.get('lat')
    lon = data.get('lon')
    msg = data.get('message', 'EMERGENCY DISTRESS TRIGGERED')

    print(f"\n🚨 [CRITICAL ALERT] GEO-SOS TRIGGERED BY: {node_id}")
    print(f"🚨 Location: Lat {lat} | Lon {lon} | Note: {msg}\n")

    # Broadcast immediate emergency telemetry
    emit('geo_sos_broadcast', {
        'node_id': node_id,
        'lat': lat,
        'lon': lon,
        'message': msg
    }, broadcast=True)

if __name__ == '__main__':
    print("==================================================")
    print("  TRIAGIX TACTICAL BASE STATION ONLINE")
    print("  Socket.IO: http://0.0.0.0:5000")
    print("  Voice UDP Engine: Port 50001 (RX) / 50002 (TX)")
    print("==================================================")
    socketio.run(app, host='0.0.0.0', port=5000, allow_unsafe_werkzeug=True)