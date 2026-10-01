# Triagix 🚨

**Tactical Mesh Emergency Communication & Responder Telemetry Network**

Triagix is a hybrid mission-critical emergency response system designed to maintain real-time situational awareness, peer-to-peer communication, and voice telemetry between field responders and emergency command base stations.

---

## 🏗 System Architecture

The project is structured into two main components:

```
Triagix/
├── android/            # Android Mobile Tactical Node Client
└── base_station/       # Central Operations Command (Server & Tactical Desktop GUI)
```

---

## 📱 1. Android Tactical Node (`/android`)

An Android application built for field responders operating in emergency zones.

### Key Capabilities:
- **Real-Time 2-Way Location Telemetry**: Continuous GPS location streaming via Google Play Services FusedLocationProvider and OpenStreetMap (`osmdroid`).
- **Geo-SOS Distress Beacon**: Instant high-priority distress trigger with coordinates broadcast across the responder network.
- **Mesh Text Communication**: Low-latency tactical chat dispatching via Socket.IO.
- **Full-Duplex Voice Relay**: Low-latency UDP voice streaming (audio capture & playback) for peer-to-peer / tactical walkie-talkie communication.
- **Battery & Device Telemetry**: Hardware vitals tracking for situational monitoring.

### Setup & Build:
1. Open the `/android` directory in **Android Studio**.
2. Sync the project with Gradle files (`build.gradle.kts`).
3. Configure the Base Station IP address in `MainActivity.java`.
4. Build and deploy to target Android devices (minimum SDK 24+).

---

## 🖥 2. Tactical Base Station (`/base_station`)

A command center operations suite featuring a Socket.IO / UDP voice relay server and an interactive tactical desktop GUI.

### Components:
- **`TRIAGIX.py`**: The backend communication engine providing:
  - UDP Full-Duplex Audio Relay (Ports `50001` RX / `50002` TX).
  - Socket.IO server for real-time location telemetry, chat, and Geo-SOS alerts.
- **`triagix_base_gui.py`**: A desktop tactical dashboard built with Tkinter, featuring:
  - Live interactive map powered by `tkintermapview` with responder node markers.
  - Active node list with Haversine distance calculations from Base HQ.
  - Real-time tactical chat log and emergency broadcast console.
  - Live Geo-SOS distress monitor.
- **`build_app.py`**: Automated PyInstaller packaging script to produce a standalone Windows `.exe`.

### Installation & Usage:

```bash
cd base_station

# Install dependencies
pip install -r requirements.txt

# Start the tactical server & voice relay
python TRIAGIX.py

# Launch the Tactical Command GUI
python triagix_base_gui.py
```

### Packaging as Standalone Windows App:
```bash
python build_app.py
```
The compiled executable will be generated in `base_station/dist/TRIAGIX_Base_Station.exe`.

---

## 🛰 Network Ports Summary

| Port | Protocol | Purpose |
|------|----------|---------|
| `5000` | HTTP / WebSocket (Socket.IO) | Telemetry, Geo-SOS, and Tactical Chat |
| `50000` | UDP Broadcast | Local peer discovery / beaconing |
| `50001` | UDP | Audio Receive Port (Mobile -> Base) |
| `50002` | UDP | Audio Broadcast / Relay Port (Base -> Responders) |

---

## 📄 License
MIT License.
