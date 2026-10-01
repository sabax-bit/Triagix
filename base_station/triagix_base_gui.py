import socket
import threading
import json
import time
import math
import queue
import tkinter as tk
from tkinter import ttk, messagebox
import tkintermapview
import numpy as np
import sounddevice as sd

# ================= NETWORK PORTS & AUDIO SETUP =================
BROADCAST_PORT = 50000
MESH_PORT      = 50001
VOICE_PORT     = 50002

SAMPLE_RATE    = 48000
CHUNK_SIZE     = 1024
CHANNELS       = 1

NODE_ID        = "TRIAGIX_BASE_HQ"
PEER_TIMEOUT   = 30

BASE_LAT       = 11.0168
BASE_LON       = 76.9558

# Global Tables
active_peers = {}
peer_names = {}
processed_messages = set()
map_markers = {}

is_in_call = False
is_2way_loc_active = False
audio_playback_queue = queue.Queue(maxsize=200)


# ================= HAVERSINE DISTANCE COMPUTATION =================
def haversine_distance(lat1, lon1, lat2, lon2):
    R = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2.0)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0)**2
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c


# ================= BASE STATION GUI APPLICATION =================
class TriagixBaseStationApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"TRIAGIX Tactical Command Base [{NODE_ID}]")
        self.geometry("1200x750")
        self.configure(bg="#050811")

        self.setup_dark_theme()
        self.create_widgets()

        # Start Background Network Listeners
        threading.Thread(target=self.discovery_broadcaster, daemon=True).start()
        threading.Thread(target=self.peer_discovery_listener, daemon=True).start()
        threading.Thread(target=self.mesh_packet_listener, daemon=True).start()
        threading.Thread(target=self.voice_receiver_playback, daemon=True).start()
        threading.Thread(target=self.cleanup_stale_peers, daemon=True).start()
        threading.Thread(target=self.location_stream_broadcaster, daemon=True).start()

    def setup_dark_theme(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TNotebook", background="#050811", borderwidth=0)
        style.configure("TNotebook.Tab", background="#0B1220", foreground="#5C708A", font=("Helvetica", 10, "bold"), padding=[15, 8])
        style.map("TNotebook.Tab", background=[("selected", "#00F0FF")], foreground=[("selected", "#050811")])

    def create_widgets(self):
        # 1. Top HUD Banner
        hud_frame = tk.Frame(self, bg="#0B1220", highlightbackground="#00F0FF", highlightthickness=1)
        hud_frame.pack(fill="x", padx=10, pady=8)

        self.lbl_base_info = tk.Label(
            hud_frame, text=f"TRIAGIX COMMAND HQ: [{NODE_ID}] | BASE: {BASE_LAT}, {BASE_LON}",
            font=("Monospace", 11, "bold"), fg="#00F0FF", bg="#0B1220"
        )
        self.lbl_base_info.pack(side="left", padx=12, pady=8)

        self.lbl_network_status = tk.Label(
            hud_frame, text="PORTS: 50000 (RADAR) | 50001 (MESH) | 50002 (48kHz VOICE)",
            font=("Monospace", 9, "bold"), fg="#5C708A", bg="#0B1220"
        )
        self.lbl_network_status.pack(side="right", padx=12, pady=8)

        # 2. Main Tabbed Notebook Container
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=10, pady=5)

        # TAB 1: 🗺️ TACTICAL MAP MODULE
        self.tab_map = tk.Frame(self.notebook, bg="#050811")
        self.notebook.add(self.tab_map, text=" 🗺️ TACTICAL MAP ")
        self.build_map_tab()

        # TAB 2: 🏥 HEALTHCARE AI & TRIAGE MODULE
        self.tab_med = tk.Frame(self.notebook, bg="#050811")
        self.notebook.add(self.tab_med, text=" 🏥 HEALTHCARE AI ")
        self.build_med_tab()

        # TAB 3: 📞 CALLS & VOICE MODULE
        self.tab_voice = tk.Frame(self.notebook, bg="#050811")
        self.notebook.add(self.tab_voice, text=" 📞 VOICE CALL ")
        self.build_voice_tab()

        # TAB 4: 💬 MESH CONSOLE & RADAR
        self.tab_mesh = tk.Frame(self.notebook, bg="#050811")
        self.notebook.add(self.tab_mesh, text=" 💬 MESH CONSOLE ")
        self.build_mesh_tab()

    # ================= 1. MAP MODULE BUILDER =================
    def build_map_tab(self):
        # Map Controls & Telemetry HUD Bar
        ctrl_frame = tk.Frame(self.tab_map, bg="#0B1220", highlightbackground="#1F3354", highlightthickness=1)
        ctrl_frame.pack(fill="x", padx=4, pady=4)

        self.btn_toggle_loc = tk.Button(
            ctrl_frame, text="📡 START 2-WAY LOC STREAM", font=("Helvetica", 9, "bold"),
            bg="#00F0FF", fg="#050811", activebackground="#39FF14", command=self.toggle_location_stream
        )
        self.btn_toggle_loc.pack(side="left", padx=8, pady=6)

        self.btn_base_sos = tk.Button(
            ctrl_frame, text="🚨 DISPATCH BASE SOS", font=("Helvetica", 9, "bold"),
            bg="#D50000", fg="#FFFFFF", activebackground="#FF5252", command=self.trigger_base_sos
        )
        self.btn_base_sos.pack(side="left", padx=8, pady=6)

        self.lbl_map_telemetry = tk.Label(
            ctrl_frame, text="TARGET: NO ACTIVE PEER | DISPLACEMENT: --- m | ETA: ---",
            font=("Monospace", 10, "bold"), fg="#39FF14", bg="#0B1220"
        )
        self.lbl_map_telemetry.pack(side="right", padx=12, pady=6)

        # Embedded Tactical Map View (Using Clean Humanitarian / Standard OSM Tiles)
        self.map_widget = tkintermapview.TkinterMapView(self.tab_map, corner_radius=0)
        self.map_widget.pack(fill="both", expand=True)

        # Set Humanitarian OpenStreetMap Tiles (No watermark, clear places/roads)
        self.map_widget.set_tile_server(
            "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png", max_zoom=19
        )
        self.map_widget.set_position(BASE_LAT, BASE_LON)
        self.map_widget.set_zoom(16)

        # Base Station Fixed Pin
        self.map_widget.set_marker(BASE_LAT, BASE_LON, text=f"HQ: {NODE_ID}", marker_color_circle="#00F0FF", marker_color_outside="#0B1220")

    # ================= 2. HEALTHCARE AI MODULE BUILDER =================
    def build_med_tab(self):
        container = tk.Frame(self.tab_med, bg="#050811")
        container.pack(fill="both", expand=True, padx=10, pady=10)

        # Left Column: Casualty Triage Dispatch Form
        form_frame = tk.Frame(container, bg="#0B1220", highlightbackground="#FF2A2A", highlightthickness=1)
        form_frame.pack(side="left", fill="both", expand=True, padx=5, pady=5)

        tk.Label(form_frame, text="CLINICAL TRIAGE EMERGENCY DISPATCH", font=("Helvetica", 11, "bold"), fg="#FF2A2A", bg="#0B1220").pack(anchor="w", padx=10, pady=8)

        # Scenario Buttons
        btn_box = tk.Frame(form_frame, bg="#0B1220")
        btn_box.pack(fill="x", padx=10, pady=4)

        scenarios = [
            ("🔥 THERMAL BURN", "BURN", "#D84315"),
            ("🩸 HEMORRHAGE", "BLEED", "#C2185B"),
            ("💓 CARDIAC CPR", "CPR", "#7B1FA2"),
            ("🦴 FRACTURE", "FRACTURE", "#00838F")
        ]
        for label, cond, color in scenarios:
            b = tk.Button(btn_box, text=label, font=("Helvetica", 9, "bold"), bg=color, fg="#FFFFFF",
                          command=lambda c=cond: self.evaluate_medical_guidance(c))
            b.pack(side="left", expand=True, fill="x", padx=2)

        self.lbl_med_eval_title = tk.Label(form_frame, text="SELECT EMERGENCY CONDITION ABOVE", font=("Helvetica", 11, "bold"), fg="#00F0FF", bg="#0B1220")
        self.lbl_med_eval_title.pack(anchor="w", padx=10, pady=8)

        self.lbl_med_eval_tag = tk.Label(form_frame, text="TRIAGE LEVEL: READY FOR ASSESSMENT", font=("Monospace", 10, "bold"), fg="#39FF14", bg="#0B1220")
        self.lbl_med_eval_tag.pack(anchor="w", padx=10, pady=2)

        self.txt_first_aid_steps = tk.Text(form_frame, bg="#070C16", fg="#E0F7FA", font=("Helvetica", 10), height=14, wrap="word", relief="flat")
        self.txt_first_aid_steps.pack(fill="both", expand=True, padx=10, pady=8)

        btn_action_box = tk.Frame(form_frame, bg="#0B1220")
        btn_action_box.pack(fill="x", padx=10, pady=8)

        tk.Button(
            btn_action_box, text="🚑 ROUTE TO 108 AMBULANCE PROTOCOL", font=("Helvetica", 9, "bold"),
            bg="#D50000", fg="#FFFFFF", command=lambda: messagebox.showinfo("108 ROUTING", "Initiating priority connection to 108 Emergency Medical Response.")
        ).pack(side="left", fill="x", expand=True, padx=4)

        tk.Button(
            btn_action_box, text="📡 BROADCAST TRIAGE OVER MESH", font=("Helvetica", 9, "bold"),
            bg="#00F0FF", fg="#050811", command=self.dispatch_current_triage_mesh
        ).pack(side="left", fill="x", expand=True, padx=4)

        # Right Column: Incoming Field Casualty Telemetry Feed
        feed_frame = tk.Frame(container, bg="#0B1220", highlightbackground="#1F3354", highlightthickness=1)
        feed_frame.pack(side="right", fill="both", expand=True, padx=5, pady=5)

        tk.Label(feed_frame, text="LIVE FIELD CASUALTY INTAKE STREAM", font=("Helvetica", 11, "bold"), fg="#00F0FF", bg="#0B1220").pack(anchor="w", padx=10, pady=8)

        self.txt_med_intake = tk.Text(feed_frame, bg="#070C16", fg="#39FF14", font=("Monospace", 9), wrap="word", relief="flat")
        self.txt_med_intake.pack(fill="both", expand=True, padx=10, pady=8)
        self.txt_med_intake.insert("end", "[*] Listening for Incoming Field Medic Triage Alerts on Mesh Port 50001...\n")

        self.evaluate_medical_guidance("BURN")

    def evaluate_medical_guidance(self, condition):
        self.current_med_condition = condition
        if condition == "BURN":
            self.current_triage = "RED / IMMEDIATE"
            self.lbl_med_eval_title.config(text="CRITICAL THERMAL BURN (DEGREE II/III)")
            self.lbl_med_eval_tag.config(text="TRIAGE: RED / IMMEDIATE EVACUATION", fg="#FF2A2A")
            guidance = (
                "1. STOP the burning source immediately.\n"
                "2. COOL with clean, running water for 15-20 mins (DO NOT use ice or freezing water!).\n"
                "3. REMOVE rings, watches, or tight items around burn before swelling begins.\n"
                "4. NEVER pop blisters or apply toothpaste, oil, or ointments.\n"
                "5. COVER loosely with sterile, clean non-fluffy wrap.\n"
                "6. DISPATCH 108 AMBULANCE: Keep patient warm to prevent hypothermia & shock."
            )
        elif condition == "BLEED":
            self.current_triage = "RED / ARTERIAL HEMORRHAGE"
            self.lbl_med_eval_title.config(text="MASSIVE ARTERIAL HEMORRHAGE")
            self.lbl_med_eval_tag.config(text="TRIAGE: RED / TOURNIQUET PRIORITY", fg="#FF2A2A")
            guidance = (
                "1. APPLY firm, direct continuous pressure on wound with clean gauze/cloth.\n"
                "2. If blood soaks through, DO NOT remove original cloth; add layers on top.\n"
                "3. IF ARTERIAL (Spurting bright blood): Apply TOURNIQUET 2-3 inches above wound.\n"
                "4. Write exact time of tourniquet application on patient's forehead (e.g. T 14:30).\n"
                "5. Elevate injured extremity above heart level if no fracture is suspected."
            )
        elif condition == "CPR":
            self.current_triage = "BLACK / RED CARDIAC ARREST"
            self.lbl_med_eval_title.config(text="CARDIAC ARREST / UNRESPONSIVE")
            self.lbl_med_eval_tag.config(text="TRIAGE: RED / COMPRESSION CADENCE", fg="#FF2A2A")
            guidance = (
                "1. CHECK RESPONSE: Tap shoulders & shout. Verify carotid pulse for 10 seconds.\n"
                "2. If no pulse or abnormal agonal gasps: START CHEST COMPRESSIONS.\n"
                "3. Interlock hands on center of chest (lower half of sternum).\n"
                "4. CADENCE: 100-120 beats per minute, 2 inches (5cm) compression depth.\n"
                "5. RATIO: 30 compressions followed by 2 breaths (or hands-only CPR).\n"
                "6. Continue until AED arrives or 108 paramedics take over."
            )
        elif condition == "FRACTURE":
            self.current_triage = "YELLOW / DELAYED"
            self.lbl_med_eval_title.config(text="COMPOUND / CLOSED BONE FRACTURE")
            self.lbl_med_eval_tag.config(text="TRIAGE: YELLOW / IMMOBILIZE", fg="#FFC107")
            guidance = (
                "1. IMMOBILIZE the injured limb above and below the injured joint.\n"
                "2. DO NOT attempt to push protruding bone back inside.\n"
                "3. Secure a rigid anatomical splint (rolled splint, branch, or adjacent leg).\n"
                "4. Check pulse and capillary refill in extremities to ensure circulation.\n"
                "5. Apply cold pack wrapped in towel for 15 mins to alleviate pain."
            )
        self.txt_first_aid_steps.delete("1.0", "end")
        self.txt_first_aid_steps.insert("end", guidance)

    def dispatch_current_triage_mesh(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

        pkt_id = f"med_{NODE_ID}_{int(time.time() * 1000)}"
        processed_messages.add(pkt_id)

        pkt = {
            "id": pkt_id,
            "type": "MED_TRIAGE_SOS",
            "sender": NODE_ID,
            "target": "all",
            "ttl": 3,
            "hops": 0,
            "payload": {
                "condition": self.current_med_condition,
                "triage": self.current_triage,
                "details": f"Base Command Issued Triage Protocol: {self.current_med_condition}",
                "gps": {"lat": BASE_LAT, "lon": BASE_LON},
                "battery": 100
            }
        }
        sock.sendto(json.dumps(pkt).encode('utf-8'), ('255.255.255.255', MESH_PORT))
        sock.close()
        self.log_console(f"🏥 [MED TRIAGE BROADCAST TRANSMITTED] Condition: {self.current_med_condition}")

    # ================= 3. VOICE MODULE BUILDER =================
    def build_voice_tab(self):
        vframe = tk.Frame(self.tab_voice, bg="#0B1220", highlightbackground="#1F3354", highlightthickness=1)
        vframe.pack(fill="x", padx=10, pady=10)

        tk.Label(vframe, text="TRIAGIX 48kHz HIGH-FIDELITY VOICE ENGINE [PORT 50002]", font=("Helvetica", 11, "bold"), fg="#00F0FF", bg="#0B1220").pack(anchor="w", padx=12, pady=8)

        self.lbl_voice_status = tk.Label(vframe, text="STATUS: 🔴 MUTED / DISCONNECTED", font=("Monospace", 12, "bold"), fg="#FF2A2A", bg="#0B1220")
        self.lbl_voice_status.pack(anchor="w", padx=12, pady=4)

        btn_box = tk.Frame(vframe, bg="#0B1220")
        btn_box.pack(fill="x", padx=12, pady=12)

        self.btn_call_start = tk.Button(btn_box, text="🟢 START 48kHz VOICE CALL", font=("Helvetica", 10, "bold"), bg="#00F0FF", fg="#050811", command=self.start_call)
        self.btn_call_start.pack(side="left", padx=4, pady=4)

        self.btn_call_cut = tk.Button(btn_box, text="🔴 CUT VOICE CALL", font=("Helvetica", 10, "bold"), bg="#331018", fg="#FFFFFF", command=self.cut_call)
        self.btn_call_cut.pack(side="left", padx=4, pady=4)

    # ================= 4. MESH & RADAR MODULE BUILDER =================
    def build_mesh_tab(self):
        container = tk.Frame(self.tab_mesh, bg="#050811")
        container.pack(fill="both", expand=True, padx=8, pady=8)

        # Left Radar Peer List
        left_box = tk.Frame(container, bg="#0B1220", width=300, highlightbackground="#1F3354", highlightthickness=1)
        left_box.pack(side="left", fill="y", padx=4, pady=4)

        self.lbl_radar_summary = tk.Label(left_box, text="RADAR LOCKS: 0", font=("Helvetica", 10, "bold"), fg="#39FF14", bg="#0B1220")
        self.lbl_radar_summary.pack(anchor="w", padx=8, pady=6)

        self.txt_peers = tk.Text(left_box, bg="#070C16", fg="#5C708A", font=("Monospace", 9), width=32, wrap="none")
        self.txt_peers.pack(fill="both", expand=True, padx=8, pady=6)

        # Right Mesh Console
        right_box = tk.Frame(container, bg="#0B1220", highlightbackground="#1F3354", highlightthickness=1)
        right_box.pack(side="right", fill="both", expand=True, padx=4, pady=4)

        self.txt_console = tk.Text(right_box, bg="#070C16", fg="#E0F7FA", font=("Monospace", 9), wrap="word")
        self.txt_console.pack(fill="both", expand=True, padx=8, pady=6)

        send_bar = tk.Frame(right_box, bg="#0B1220")
        send_bar.pack(fill="x", padx=8, pady=6)

        self.ent_target = tk.Entry(send_bar, bg="#121C2B", fg="#00F0FF", font=("Monospace", 10), width=10)
        self.ent_target.insert(0, "all")
        self.ent_target.pack(side="left", padx=4)

        self.ent_msg = tk.Entry(send_bar, bg="#121C2B", fg="#FFFFFF", font=("Helvetica", 10))
        self.ent_msg.pack(side="left", fill="x", expand=True, padx=4)
        self.ent_msg.bind("<Return>", lambda e: self.send_mesh_message())

        tk.Button(send_bar, text="SEND MESH", font=("Helvetica", 9, "bold"), bg="#00F0FF", fg="#050811", command=self.send_mesh_message).pack(side="right", padx=4)

    # ================= LOGIC & TELEMETRY HANDLERS =================
    def log_console(self, text):
        def _write():
            self.txt_console.insert("end", text + "\n")
            self.txt_console.see("end")
        self.after(0, _write)

    def toggle_location_stream(self):
        global is_2way_loc_active
        is_2way_loc_active = not is_2way_loc_active
        if is_2way_loc_active:
            self.btn_toggle_loc.config(text="🔴 STOP 2-WAY LOC STREAM", bg="#FF2A2A", fg="#FFFFFF")
            self.log_console("📡 [2-WAY LOC] Base Station coordinates broadcasting every 1.5s")
        else:
            self.btn_toggle_loc.config(text="📡 START 2-WAY LOC STREAM", bg="#00F0FF", fg="#050811")
            self.log_console("📡 [2-WAY LOC] Broadcast paused")

    def trigger_base_sos(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

        pkt_id = f"sos_{NODE_ID}_{int(time.time() * 1000)}"
        processed_messages.add(pkt_id)

        pkt = {
            "id": pkt_id,
            "type": "SOS_LIVE_GEO",
            "sender": NODE_ID,
            "target": "all",
            "ttl": 3,
            "hops": 0,
            "payload": {
                "gps": {"lat": BASE_LAT, "lon": BASE_LON, "accuracy": "BASE_STATIC_FIX"},
                "battery": 100,
                "priority": "CRITICAL / RED"
            }
        }
        sock.sendto(json.dumps(pkt).encode('utf-8'), ('255.255.255.255', MESH_PORT))
        sock.close()
        self.log_console("🚨 [BASE EMERGENCY SOS DISPATCHED]")

    def send_mesh_message(self):
        msg = self.ent_msg.get().strip()
        target = self.ent_target.get().strip()
        if not msg:
            return

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

        pkt_id = f"msg_{NODE_ID}_{int(time.time() * 1000)}"
        processed_messages.add(pkt_id)

        pkt = {
            "id": pkt_id,
            "type": "TEXT",
            "sender": NODE_ID,
            "target": target if target else "all",
            "payload": msg,
            "ttl": 3,
            "hops": 0,
            "timestamp": time.time()
        }
        sock.sendto(json.dumps(pkt).encode('utf-8'), ('255.255.255.255', MESH_PORT))
        sock.close()
        self.log_console(f"💬 [Sent to {target}]: {msg}")
        self.ent_msg.delete(0, "end")

    def start_call(self):
        global is_in_call
        is_in_call = True
        self.lbl_voice_status.config(text="STATUS: 🟢 ACTIVE (48kHz STREAM LIVE)", fg="#39FF14")
        self.log_console("🟢 [VOICE CALL ACTIVE] 48kHz Mic & Speaker Streams On")

    def cut_call(self):
        global is_in_call
        is_in_call = False
        with audio_playback_queue.mutex:
            audio_playback_queue.queue.clear()
        self.lbl_voice_status.config(text="STATUS: 🔴 MUTED / DISCONNECTED", fg="#FF2A2A")
        self.log_console("🔴 [VOICE CALL CUT] Muted.")

    # ================= MAP & TELEMETRY RENDERER =================
    def update_map_target(self, sender, lat, lon, is_sos=False):
        def _update():
            # Update Map Marker
            if sender in map_markers:
                map_markers[sender].set_position(lat, lon)
            else:
                color = "#FF2A2A" if is_sos else "#39FF14"
                map_markers[sender] = self.map_widget.set_marker(
                    lat, lon, text=f"{sender}", marker_color_circle=color, marker_color_outside="#0B1220"
                )

            # Compute Haversine Distance & Multi-Mode ETA
            dist_m = haversine_distance(BASE_LAT, BASE_LON, lat, lon)
            eta_foot = int(dist_m / 1.39 / 60)
            eta_bike = int(dist_m / 4.17 / 60)
            eta_veh  = int(dist_m / 11.11 / 60)

            if dist_m <= 10.0:
                self.lbl_map_telemetry.config(
                    text=f"🎯 PROXIMITY ALERT: {sender} REACHED BASE [ON-SITE]!", fg="#39FF14"
                )
            else:
                self.lbl_map_telemetry.config(
                    text=f"TARGET: {sender} | RANGE: {dist_m:.1f} m ({dist_m/1000:.2f} km) | 🚶 {eta_foot}m | 🚴 {eta_bike}m | 🚙 {eta_veh}m",
                    fg="#FF2A2A" if is_sos else "#00F0FF"
                )

        self.after(0, _update)

    # ================= BACKGROUND NETWORK THREADS =================
    def discovery_broadcaster(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        while True:
            try:
                beacon = json.dumps({"node_id": NODE_ID, "type": "BEACON"}).encode('utf-8')
                sock.sendto(beacon, ('255.255.255.255', BROADCAST_PORT))
            except Exception:
                pass
            time.sleep(5)

    def peer_discovery_listener(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('0.0.0.0', BROADCAST_PORT))

        while True:
            try:
                data, addr = sock.recvfrom(2048)
                sender_ip = addr[0]
                parsed = json.loads(data.decode('utf-8'))
                client_id = parsed.get("node_id", sender_ip)
                if client_id != NODE_ID:
                    now = time.time()
                    if sender_ip not in active_peers:
                        self.log_console(f"⚡ [RADAR LOCK] New Unit {client_id} linked at {sender_ip}")
                    peer_names[sender_ip] = client_id
                    active_peers[sender_ip] = now
                    self.refresh_peers_ui()
            except Exception:
                pass

    def refresh_peers_ui(self):
        def _refresh():
            self.lbl_radar_summary.config(text=f"RADAR LOCKS: {len(active_peers)}")
            self.txt_peers.delete("1.0", "end")
            for ip, name in peer_names.items():
                self.txt_peers.insert("end", f"• {name}\n  -> {ip}\n")
        self.after(0, _refresh)

    def cleanup_stale_peers(self):
        while True:
            now = time.time()
            stale = [ip for ip, last_seen in list(active_peers.items()) if now - last_seen > PEER_TIMEOUT]
            for ip in stale:
                del active_peers[ip]
                peer_names.pop(ip, None)
            self.refresh_peers_ui()
            time.sleep(10)

    def location_stream_broadcaster(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

        while True:
            if is_2way_loc_active:
                try:
                    pkt_id = f"loc_{NODE_ID}_{int(time.time() * 1000)}"
                    processed_messages.add(pkt_id)

                    pkt = {
                        "id": pkt_id,
                        "type": "LIVE_STREAM_GEO",
                        "sender": NODE_ID,
                        "target": "all",
                        "ttl": 3,
                        "hops": 0,
                        "payload": {
                            "gps": {"lat": BASE_LAT, "lon": BASE_LON, "accuracy": "BASE_STATIC_FIX"},
                            "battery": 100,
                            "priority": "ROUTINE_LIVE"
                        }
                    }
                    sock.sendto(json.dumps(pkt).encode('utf-8'), ('255.255.255.255', MESH_PORT))
                except Exception:
                    pass
            time.sleep(1.5)

    def mesh_packet_listener(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('0.0.0.0', MESH_PORT))

        while True:
            try:
                data, addr = sock.recvfrom(4096)
                sender_ip = addr[0]
                pkt = json.loads(data.decode('utf-8'))

                msg_id = pkt.get("id")
                if msg_id:
                    if msg_id in processed_messages:
                        continue
                    processed_messages.add(msg_id)
                    if len(processed_messages) > 3000:
                        processed_messages.clear()

                sender   = pkt.get("sender", "Unknown")
                msg_type = pkt.get("type")
                target   = pkt.get("target", "all")
                ttl      = pkt.get("ttl", 1)
                hops     = pkt.get("hops", 0)

                if sender == NODE_ID:
                    continue

                if target in ["all", NODE_ID]:
                    if msg_type == "TEXT":
                        self.log_console(f"💬 [{sender} @ Hop {hops}]: {pkt.get('payload', '')}")

                    elif msg_type in ["SOS_LIVE_GEO", "LIVE_STREAM_GEO"]:
                        payload = pkt.get("payload", {})
                        gps = payload.get("gps", {})
                        lat = gps.get("lat", 0.0)
                        lon = gps.get("lon", 0.0)

                        if lat != 0.0 and lon != 0.0:
                            self.update_map_target(sender, lat, lon, is_sos=(msg_type == "SOS_LIVE_GEO"))

                        if msg_type == "SOS_LIVE_GEO":
                            self.log_console(f"🚨 [CRITICAL LIVE SOS] from {sender} at {lat:.6f}, {lon:.6f}")
                            # Auto-ACK
                            ack = {
                                "id": f"ack_{int(time.time() * 1000)}",
                                "type": "SOS_ACK",
                                "sender": NODE_ID,
                                "target": sender,
                                "payload": {"status": "DISPATCHED", "assigned_sector": "Base Command"}
                            }
                            sock.sendto(json.dumps(ack).encode('utf-8'), (sender_ip, MESH_PORT))

                    elif msg_type == "MED_TRIAGE_SOS":
                        payload   = pkt.get("payload", {})
                        condition = payload.get("condition", "CASUALTY")
                        triage    = payload.get("triage", "RED")
                        gps       = payload.get("gps", {})
                        lat       = gps.get("lat", 0.0)
                        lon       = gps.get("lon", 0.0)

                        intake_str = f"[{time.strftime('%H:%M:%S')}] 🚨 INCOMING CASUALTY from {sender}\n" \
                                     f"   Condition : {condition}\n" \
                                     f"   Triage Tag: {triage}\n" \
                                     f"   Location  : {lat:.6f}, {lon:.6f}\n\n"

                        def _log_med():
                            self.txt_med_intake.insert("1.0", intake_str)
                            self.notebook.select(self.tab_med)  # Auto-focus on Med tab
                        self.after(0, _log_med)

                        if lat != 0.0 and lon != 0.0:
                            self.update_map_target(f"{sender} ({condition})", lat, lon, is_sos=True)

                        # Auto-ACK for Paramedic
                        ack = {
                            "id": f"ack_{int(time.time() * 1000)}",
                            "type": "SOS_ACK",
                            "sender": NODE_ID,
                            "target": sender,
                            "payload": {"status": "PARAMEDIC_EN_ROUTE", "assigned_sector": "Base HQ Triage"}
                        }
                        sock.sendto(json.dumps(ack).encode('utf-8'), (sender_ip, MESH_PORT))

                # Multi-Hop Relay
                if ttl > 1 and msg_type != "SOS_ACK":
                    pkt["ttl"] = ttl - 1
                    pkt["hops"] = hops + 1
                    relay_bytes = json.dumps(pkt).encode('utf-8')
                    relay_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    relay_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    relay_sock.sendto(relay_bytes, ('255.255.255.255', MESH_PORT))
                    relay_sock.close()

            except Exception:
                pass

    # ================= 48kHz AUDIO ENGINE =================
    def voice_receiver_playback(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 131072)
        sock.bind(('0.0.0.0', VOICE_PORT))

        while True:
            try:
                data, addr = sock.recvfrom(8192)
                if is_in_call and len(data) > 0:
                    audio_array = np.frombuffer(data, dtype=np.int16)
                    if not audio_playback_queue.full():
                        audio_playback_queue.put_nowait(audio_array)
            except Exception:
                pass


# Audio Callbacks for SoundDevice
def sounddevice_out_callback(outdata, frames, time_info, status):
    if is_in_call:
        try:
            data = audio_playback_queue.get_nowait()
            if len(data) < frames:
                outdata[:len(data), 0] = data
                outdata[len(data):, 0] = 0
            else:
                outdata[:, 0] = data[:frames]
        except queue.Empty:
            outdata.fill(0)
    else:
        outdata.fill(0)

def sounddevice_in_callback(indata, frames, time_info, status):
    if is_in_call:
        try:
            raw_bytes = indata.tobytes()
            tx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            tx_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

            if active_peers:
                for peer_ip in list(active_peers.keys()):
                    tx_sock.sendto(raw_bytes, (peer_ip, VOICE_PORT))
            else:
                tx_sock.sendto(raw_bytes, ('255.255.255.255', VOICE_PORT))
            tx_sock.close()
        except Exception:
            pass


# ================= RUN SERVER APP =================
if __name__ == "__main__":
    with sd.OutputStream(samplerate=SAMPLE_RATE, channels=CHANNELS, dtype='int16',
                         blocksize=CHUNK_SIZE, callback=sounddevice_out_callback), \
         sd.InputStream(samplerate=SAMPLE_RATE, channels=CHANNELS, dtype='int16',
                        blocksize=CHUNK_SIZE, callback=sounddevice_in_callback):

        app = TriagixBaseStationApp()
        app.mainloop()