package com.example.aegisx;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    private static final int BROADCAST_PORT = 50000;
    private static final int MESH_PORT = 50001;
    private static final int VOICE_PORT = 50002;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHUNK_SIZE = 1024;
    private static final int PEER_TIMEOUT_SEC = 30;

    private final String nodeId = "TRIAGIX_" + (System.currentTimeMillis() % 10000);
    private final Map<String, Long> activePeers = new ConcurrentHashMap<>();
    private final Map<String, String> peerNames = new ConcurrentHashMap<>();
    private final Set<String> processedMessages = Collections.synchronizedSet(new HashSet<>());

    private volatile boolean isInCall = false;

    // 2-Way Full Duplex Live Tracking
    private volatile boolean is2WayLocationSharingActive = false;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;
    private boolean hasGpsFix = false;

    // Target Persistence
    private double lastTargetLat = 0.0;
    private double lastTargetLon = 0.0;
    private String lastTargetSender = "";
    private boolean hasTargetActive = false;
    private boolean hasReachedAlertTriggered = false;

    // Healthcare AI State
    private String currentSelectedEmergency = "BURN";
    private String currentTriageTag = "RED / IMMEDIATE";
    private String currentEmergencyDetails = "";

    // In-App Map
    private MapView osmMapView;
    private Marker selfMarker;
    private Marker targetMarker;

    // UI Elements
    private Button tabCalls, tabMsg, tabPeers, tabMap, tabMed;
    private LinearLayout windowCalls, windowMsg, windowPeers, windowMap, windowMed;
    private TextView tvBaseNodeId, tvCallState, tvConsoleLogs, tvPeerSummary, tvPeerListWindow;
    private TextView tvTargetTelemetryTitle, tvTargetDistance, tvEtaFoot, tvEtaBike, tvEtaVehicle;
    private TextView tvMedConditionTitle, tvMedTriageTag, tvMedGuidanceSteps;
    private ScrollView svLogContainer;
    private EditText etTargetIp, etMessage;
    private Button btnCallStart, btnCallCut, btnSosQuick, btnSendMsg, btnToggle2WayLoc;
    private Button btnMedBurn, btnMedBleed, btnMedCpr, btnMedFracture, btnCall108Emergency, btnSendMedicalMesh;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private DatagramSocket voiceRxSocket;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;

    // Public Humanitarian Tiles (Zero API Key, Zero Watermark, Clear Place Names)
    private static final XYTileSource TACTICAL_PUBLIC_TILE_SOURCE = new XYTileSource(
            "OSMHotClean",
            1, 19, 256, ".png",
            new String[]{
                    "https://a.tile.openstreetmap.fr/hot/",
                    "https://b.tile.openstreetmap.fr/hot/"
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue("TRIAGIX-Tactical-RescueClient/6.0 (tactical.hot@triagix.io)");

        setContentView(R.layout.activity_main);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Bind 5 Tabs
        tabCalls = findViewById(R.id.tabCalls);
        tabMsg = findViewById(R.id.tabMsg);
        tabPeers = findViewById(R.id.tabPeers);
        tabMap = findViewById(R.id.tabMap);
        tabMed = findViewById(R.id.tabMed);

        // Bind 5 Windows
        windowCalls = findViewById(R.id.windowCalls);
        windowMsg = findViewById(R.id.windowMsg);
        windowPeers = findViewById(R.id.windowPeers);
        windowMap = findViewById(R.id.windowMap);
        windowMed = findViewById(R.id.windowMed);

        // Bind HUD & Telemetry
        tvBaseNodeId = findViewById(R.id.tvBaseNodeId);
        tvCallState = findViewById(R.id.tvCallState);
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs);
        svLogContainer = findViewById(R.id.svLogContainer);
        tvPeerSummary = findViewById(R.id.tvPeerSummary);
        tvPeerListWindow = findViewById(R.id.tvPeerListWindow);
        tvTargetTelemetryTitle = findViewById(R.id.tvTargetTelemetryTitle);
        tvTargetDistance = findViewById(R.id.tvTargetDistance);
        tvEtaFoot = findViewById(R.id.tvEtaFoot);
        tvEtaBike = findViewById(R.id.tvEtaBike);
        tvEtaVehicle = findViewById(R.id.tvEtaVehicle);
        etTargetIp = findViewById(R.id.etTargetIp);
        etMessage = findViewById(R.id.etMessage);

        btnCallStart = findViewById(R.id.btnCallStart);
        btnCallCut = findViewById(R.id.btnCallCut);
        btnSosQuick = findViewById(R.id.btnSosQuick);
        btnSendMsg = findViewById(R.id.btnSendMsg);
        btnToggle2WayLoc = findViewById(R.id.btnToggle2WayLoc);

        // Bind Healthcare AI Components
        btnMedBurn = findViewById(R.id.btnMedBurn);
        btnMedBleed = findViewById(R.id.btnMedBleed);
        btnMedCpr = findViewById(R.id.btnMedCpr);
        btnMedFracture = findViewById(R.id.btnMedFracture);
        btnCall108Emergency = findViewById(R.id.btnCall108Emergency);
        btnSendMedicalMesh = findViewById(R.id.btnSendMedicalMesh);
        tvMedConditionTitle = findViewById(R.id.tvMedConditionTitle);
        tvMedTriageTag = findViewById(R.id.tvMedTriageTag);
        tvMedGuidanceSteps = findViewById(R.id.tvMedGuidanceSteps);

        tvBaseNodeId.setText("TRIAGIX: [" + nodeId + "]");
        etTargetIp.setText("all");

        // Map Setup
        osmMapView = findViewById(R.id.osmMapView);
        osmMapView.setTileSource(TACTICAL_PUBLIC_TILE_SOURCE);
        osmMapView.setMultiTouchControls(true);
        osmMapView.getController().setZoom(17.5);
        osmMapView.getOverlayManager().getTilesOverlay().setColorFilter(null);

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("TriagixMulticastLock");
            multicastLock.acquire();
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Triagix::WakeLock");
            wakeLock.acquire(10 * 60 * 1000L);
        }

        requestAllPermissions();
        initHighPrecisionLocationTracker();

        // Tabs Routing
        tabCalls.setOnClickListener(v -> switchWindow(0));
        tabMsg.setOnClickListener(v -> switchWindow(1));
        tabPeers.setOnClickListener(v -> switchWindow(2));
        tabMap.setOnClickListener(v -> switchWindow(3));
        tabMed.setOnClickListener(v -> switchWindow(4));

        btnCallStart.setOnClickListener(v -> startCall());
        btnCallCut.setOnClickListener(v -> cutCall());
        btnSosQuick.setOnClickListener(v -> triggerLiveGeoSos());
        btnToggle2WayLoc.setOnClickListener(v -> toggle2WayLocationShare());

        // Healthcare AI Action Hooks
        btnMedBurn.setOnClickListener(v -> executeHealthcareAI("BURN"));
        btnMedBleed.setOnClickListener(v -> executeHealthcareAI("BLEED"));
        btnMedCpr.setOnClickListener(v -> executeHealthcareAI("CPR"));
        btnMedFracture.setOnClickListener(v -> executeHealthcareAI("FRACTURE"));

        btnCall108Emergency.setOnClickListener(v -> triggerAutomated108Call());
        btnSendMedicalMesh.setOnClickListener(v -> dispatchMedicalTriageOverMesh());

        btnSendMsg.setOnClickListener(v -> {
            String msg = etMessage.getText().toString().trim();
            String target = etTargetIp.getText().toString().trim();
            if (!msg.isEmpty()) {
                if (msg.startsWith("/")) {
                    handleCommand(msg);
                } else {
                    sendGroupMessage(msg, target.isEmpty() ? "all" : target);
                }
                etMessage.setText("");
            }
        });

        startVoiceReceiverPlayback();
        startVoiceSenderCapture();
        startMeshPacketListener();
        startPeerDiscoveryListener();
        startDiscoveryBroadcaster();
        startCleanupStalePeers();
        start2WayLocationBroadcastLoop();

        // Default Healthcare Setup
        executeHealthcareAI("BURN");
    }

    private void switchWindow(int windowIndex) {
        Button[] tabs = {tabCalls, tabMsg, tabPeers, tabMap, tabMed};
        LinearLayout[] windows = {windowCalls, windowMsg, windowPeers, windowMap, windowMed};

        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackgroundColor(Color.parseColor("#121C2B"));
            tabs[i].setTextColor(Color.parseColor("#5C708A"));
            windows[i].setVisibility(View.GONE);
        }

        tabs[windowIndex].setBackgroundColor(Color.parseColor("#00F0FF"));
        tabs[windowIndex].setTextColor(Color.parseColor("#050811"));
        windows[windowIndex].setVisibility(View.VISIBLE);

        if (windowIndex == 3) {
            osmMapView.onResume();
        }
    }

    // ================= HEALTHCARE AI ENGINE =================
    private void executeHealthcareAI(String condition) {
        currentSelectedEmergency = condition;
        triggerTacticalAlertHaptic(false);

        if ("BURN".equals(condition)) {
            currentTriageTag = "RED / IMMEDIATE";
            tvMedConditionTitle.setText("CRITICAL THERMAL BURN (DEGREE II/III)");
            tvMedTriageTag.setText("TRIAGE: RED / IMMEDIATE EVACUATION");
            tvMedTriageTag.setTextColor(Color.parseColor("#FF2A2A"));

            currentEmergencyDetails = "Severe Burn Injury. Clean water cooling applied. Immediate 108 Ambulance required.";
            tvMedGuidanceSteps.setText(
                    "1. STOP the burning process immediately.\n" +
                            "2. COOL with clean, running water for 15-20 mins (DO NOT use ice or freezing water!).\n" +
                            "3. REMOVE rings, watches, or tight clothing around the burn before swelling begins.\n" +
                            "4. NEVER pop blisters or apply toothpaste, butter, or oil ointments.\n" +
                            "5. COVER loosely with clean, sterile plastic wrap or sterile non-fluffy cloth.\n" +
                            "6. AUTOMATED 108 CALL READY: Keep patient warm to prevent hypothermia & shock."
            );

        } else if ("BLEED".equals(condition)) {
            currentTriageTag = "RED / ARTERIAL HEMORRHAGE";
            tvMedConditionTitle.setText("MASSIVE HEMORRHAGE / BLEEDING");
            tvMedTriageTag.setText("TRIAGE: RED / ARTERIAL PRESSING");
            tvMedTriageTag.setTextColor(Color.parseColor("#FF2A2A"));

            currentEmergencyDetails = "Massive arterial/venous bleeding. Direct pressure/tourniquet applied.";
            tvMedGuidanceSteps.setText(
                    "1. APPLY firm, direct continuous pressure on the wound using clean gauze or cloth.\n" +
                            "2. If blood soaks through, DO NOT remove the original cloth; layer more on top.\n" +
                            "3. IF ARTERIAL (Spurting bright red blood): Apply a TOURNIQUET 2-3 inches above the wound.\n" +
                            "4. Note exact tourniquet application time on patient's forehead.\n" +
                            "5. Elevate limb above heart level if no fracture is suspected.\n" +
                            "6. DIAL 108 immediately to prepare for surgical intervention."
            );

        } else if ("CPR".equals(condition)) {
            currentTriageTag = "BLACK / RED CARDIAC ARREST";
            tvMedConditionTitle.setText("CARDIAC ARREST / UNRESPONSIVE");
            tvMedTriageTag.setText("TRIAGE: RED / LIFE-SAVING CPR");
            tvMedTriageTag.setTextColor(Color.parseColor("#FF2A2A"));

            currentEmergencyDetails = "Patient unresponsive with no carotid pulse. CPR in progress.";
            tvMedGuidanceSteps.setText(
                    "1. CHECK RESPONSE: Tap shoulders & shout 'Are you okay?'. Check carotid pulse for 10s.\n" +
                            "2. If no pulse or abnormal breathing (agonal gasps): START COMPRESSIONS.\n" +
                            "3. PLACE hands in center of chest (lower half of sternum).\n" +
                            "4. PUSH HARD & FAST: 100-120 beats per minute, 2 inches (5cm) depth.\n" +
                            "5. RATIO: 30 chest compressions followed by 2 rescue breaths (or hands-only CPR).\n" +
                            "6. DO NOT STOP until 108 paramedics arrive or AED is attached."
            );

        } else if ("FRACTURE".equals(condition)) {
            currentTriageTag = "YELLOW / DELAYED";
            tvMedConditionTitle.setText("COMPOUND / CLOSED BONE FRACTURE");
            tvMedTriageTag.setText("TRIAGE: YELLOW / IMMOBILIZE");
            tvMedTriageTag.setTextColor(Color.parseColor("#FFC107"));

            currentEmergencyDetails = "Fracture detected. Anatomical splint applied.";
            tvMedGuidanceSteps.setText(
                    "1. IMMOBILIZE the injured limb above and below the injured joint.\n" +
                            "2. DO NOT try to push protruding bone back into the wound.\n" +
                            "3. Apply a padded splint (rolled magazine, stick, or opposite leg/arm).\n" +
                            "4. Check pulse and capillary refill in fingers/toes distal to fracture.\n" +
                            "5. Apply ice wrapped in towel for 15 mins to reduce pain and swelling."
            );
        }
    }

    // AUTOMATED 108 EMERGENCY CELLULAR CALL ROUTER
    private void triggerAutomated108Call() {
        triggerTacticalAlertHaptic(true);
        Toast.makeText(this, "🚑 CONNECTING TO 108 EMERGENCY SERVICES...", Toast.LENGTH_LONG).show();

        try {
            Intent callIntent = new Intent(Intent.ACTION_DIAL);
            callIntent.setData(Uri.parse("tel:108"));
            callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callIntent);
            printConsole("🚑 [AUTOMATED EMERGENCY] Routed dial request to 108 Emergency Ambulance");
        } catch (Exception e) {
            printConsole("[!] 108 Dialer failed: " + e.getMessage());
        }
    }

    // DISPATCH MEDICAL TRIAGE & FIRST-AID METRICS ACROSS MESH
    private void dispatchMedicalTriageOverMesh() {
        triggerTacticalAlertHaptic(true);
        Toast.makeText(this, "📡 MEDICAL TRIAGE DISPATCHED OVER MESH", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);

                String packetId = "med_" + nodeId + "_" + System.currentTimeMillis();
                processedMessages.add(packetId);

                JSONObject payload = new JSONObject();
                payload.put("condition", currentSelectedEmergency);
                payload.put("triage", currentTriageTag);
                payload.put("details", currentEmergencyDetails);

                JSONObject gps = new JSONObject();
                gps.put("lat", currentLatitude);
                gps.put("lon", currentLongitude);
                payload.put("gps", gps);
                payload.put("battery", getBatteryLevel());

                JSONObject json = new JSONObject();
                json.put("id", packetId);
                json.put("type", "MED_TRIAGE_SOS");
                json.put("sender", nodeId);
                json.put("target", "all");
                json.put("ttl", 3);
                json.put("hops", 0);
                json.put("payload", payload);

                byte[] data = json.toString().getBytes();
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), MESH_PORT));

                printConsole("🚨 [MED MESH BROADCAST SENT]");
                printConsole("   Condition: " + currentSelectedEmergency + " | Priority: " + currentTriageTag);
                printConsole("   GPS: " + currentLatitude + ", " + currentLongitude);
            } catch (Exception e) {
                printConsole("[!] Med dispatch error: " + e.getMessage());
            }
        }).start();
    }

    private void toggle2WayLocationShare() {
        is2WayLocationSharingActive = !is2WayLocationSharingActive;
        if (is2WayLocationSharingActive) {
            btnToggle2WayLoc.setText("🔴 STOP 2-WAY LOC");
            btnToggle2WayLoc.setBackgroundColor(Color.parseColor("#FF2A2A"));
            btnToggle2WayLoc.setTextColor(Color.WHITE);
            Toast.makeText(this, "🟢 2-WAY LIVE LOCATION STREAM ACTIVE", Toast.LENGTH_SHORT).show();
            printConsole("⚡ [2-WAY LOC ENGINE] Live broadcast active (1.5s interval)");
        } else {
            btnToggle2WayLoc.setText("📡 START 2-WAY LOC");
            btnToggle2WayLoc.setBackgroundColor(Color.parseColor("#00F0FF"));
            btnToggle2WayLoc.setTextColor(Color.parseColor("#050811"));
            Toast.makeText(this, "🔴 2-WAY LIVE LOCATION STREAM STOPPED", Toast.LENGTH_SHORT).show();
            printConsole("⚡ [2-WAY LOC ENGINE] Live broadcast paused");
        }
    }

    private void start2WayLocationBroadcastLoop() {
        new Thread(() -> {
            while (!isFinishing()) {
                if (is2WayLocationSharingActive && hasGpsFix) {
                    broadcastLocationPacket("LIVE_STREAM_GEO");
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private void broadcastLocationPacket(String packetType) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);

            String packetId = "loc_" + nodeId + "_" + System.currentTimeMillis();
            processedMessages.add(packetId);

            JSONObject gps = new JSONObject();
            gps.put("lat", currentLatitude);
            gps.put("lon", currentLongitude);
            gps.put("accuracy", "FUSED_HIGH_ACCURACY");

            int battery = getBatteryLevel();

            JSONObject payload = new JSONObject();
            payload.put("gps", gps);
            payload.put("battery", battery);
            payload.put("priority", packetType.equals("SOS_LIVE_GEO") ? "CRITICAL" : "ROUTINE_LIVE");

            JSONObject json = new JSONObject();
            json.put("id", packetId);
            json.put("type", packetType);
            json.put("sender", nodeId);
            json.put("target", "all");
            json.put("ttl", 3);
            json.put("hops", 0);
            json.put("payload", payload);

            byte[] data = json.toString().getBytes();
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), MESH_PORT));
        } catch (Exception ignored) {}
    }

    private void triggerTacticalAlertHaptic(boolean isDoublePulse) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (isDoublePulse) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200, 500}, -1));
                } else {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 150, 250}, -1));
                }
            } else {
                vibrator.vibrate(new long[]{0, 300, 150, 300}, -1);
            }
        }
    }

    private void requestAllPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CALL_PHONE
        };
        boolean needsRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }
        if (needsRequest) {
            ActivityCompat.requestPermissions(this, permissions, 200);
        }
    }

    private void initHighPrecisionLocationTracker() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        updateSelfLocation(location.getLatitude(), location.getLongitude());
                        osmMapView.getController().animateTo(new GeoPoint(location.getLatitude(), location.getLongitude()));
                    }
                });

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500)
                .setMinUpdateIntervalMillis(1000)
                .setMinUpdateDistanceMeters(0.5f)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        updateSelfLocation(location.getLatitude(), location.getLongitude());
                    }
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void updateSelfLocation(double lat, double lon) {
        currentLatitude = lat;
        currentLongitude = lon;
        hasGpsFix = (lat != 0.0 && lon != 0.0);

        runOnUiThread(() -> {
            GeoPoint point = new GeoPoint(lat, lon);
            if (selfMarker == null) {
                selfMarker = new Marker(osmMapView);
                selfMarker.setTitle("YOU: [" + nodeId + "]");
                selfMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                osmMapView.getOverlays().add(selfMarker);
                osmMapView.getController().setCenter(point);
            }
            selfMarker.setPosition(point);
            osmMapView.invalidate();

            if (hasTargetActive && hasGpsFix) {
                recalculateTargetRange(lastTargetSender, lastTargetLat, lastTargetLon);
            }
        });
    }

    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void recalculateTargetRange(String senderNode, double targetLat, double targetLon) {
        if (!hasGpsFix) {
            tvTargetTelemetryTitle.setText("TARGET: " + senderNode + " [COORDINATES READY]");
            tvTargetDistance.setText("Acquiring local GPS hardware fix...");
            return;
        }

        double distanceMeters = calculateHaversineDistance(currentLatitude, currentLongitude, targetLat, targetLon);

        if (distanceMeters <= 10.0) {
            tvTargetTelemetryTitle.setText("🎯 TARGET LOCATION REACHED [ON-SITE]");
            tvTargetTelemetryTitle.setTextColor(Color.parseColor("#39FF14"));
            tvTargetDistance.setText("DISPLACEMENT: 0m (ARRIVED AT SCENE)");
            tvTargetDistance.setTextColor(Color.parseColor("#39FF14"));

            tvEtaFoot.setText("🚶 On Foot\nARRIVED");
            tvEtaBike.setText("🚴 Bicycle\nARRIVED");
            tvEtaVehicle.setText("🚙 Vehicle\nARRIVED");

            if (!hasReachedAlertTriggered) {
                hasReachedAlertTriggered = true;
                triggerTacticalAlertHaptic(true);
                Toast.makeText(this, "🎯 LOCATION REACHED: ARRIVED AT " + senderNode, Toast.LENGTH_LONG).show();
            }
            return;
        }

        hasReachedAlertTriggered = false;
        int etaFootSec = (int) (distanceMeters / 1.39);
        int etaBikeSec = (int) (distanceMeters / 4.17);
        int etaVehicleSec = (int) (distanceMeters / 11.11);

        tvTargetTelemetryTitle.setText("TARGET: " + senderNode + " [LIVE TRACKING]");
        tvTargetTelemetryTitle.setTextColor(Color.parseColor("#00F0FF"));
        tvTargetDistance.setText(String.format(Locale.US, "Range Distance: %.1f m (%.2f km)", distanceMeters, distanceMeters / 1000.0));
        tvTargetDistance.setTextColor(Color.parseColor("#39FF14"));

        tvEtaFoot.setText("🚶 On Foot\n" + (etaFootSec / 60) + "m " + (etaFootSec % 60) + "s");
        tvEtaBike.setText("🚴 Bicycle\n" + (etaBikeSec / 60) + "m " + (etaBikeSec % 60) + "s");
        tvEtaVehicle.setText("🚙 Vehicle\n" + (etaVehicleSec / 60) + "m " + (etaVehicleSec % 60) + "s");
    }

    private void updateTargetTelemetry(String senderNode, double targetLat, double targetLon) {
        lastTargetLat = targetLat;
        lastTargetLon = targetLon;
        lastTargetSender = senderNode;
        hasTargetActive = true;

        runOnUiThread(() -> {
            GeoPoint targetPoint = new GeoPoint(targetLat, targetLon);

            if (targetMarker == null) {
                targetMarker = new Marker(osmMapView);
                targetMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                osmMapView.getOverlays().add(targetMarker);
            }
            targetMarker.setTitle("📍 PEER: " + senderNode);
            targetMarker.setPosition(targetPoint);
            osmMapView.invalidate();

            recalculateTargetRange(senderNode, targetLat, targetLon);
        });
    }

    private int getBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return (int) ((level / (float) scale) * 100);
        }
        return 50;
    }

    private void printConsole(String msg) {
        runOnUiThread(() -> {
            tvConsoleLogs.append(msg + "\n");
            svLogContainer.post(() -> svLogContainer.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void refreshPeerWindow() {
        runOnUiThread(() -> {
            tvPeerSummary.setText("ONLINE: " + activePeers.size());
            if (activePeers.isEmpty()) {
                tvPeerListWindow.setText("Scanning for active node beacons...\nAuto-cleanup threshold: 30s");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> entry : activePeers.entrySet()) {
                String ip = entry.getKey();
                String name = peerNames.getOrDefault(ip, "Unknown Node");
                sb.append("• ").append(name).append(" -> ").append(ip).append("\n");
            }
            tvPeerListWindow.setText(sb.toString().trim());
        });
    }

    private void registerPeer(String ip, String name) {
        long now = System.currentTimeMillis();
        if (!activePeers.containsKey(ip)) {
            printConsole("⚡ [NEW NODE DISCOVERED] " + (name != null ? name : "Node") + " registered at " + ip);
        }
        if (name != null) peerNames.put(ip, name);
        activePeers.put(ip, now);
        refreshPeerWindow();
    }

    private void startCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAllPermissions();
            printConsole("[!] Microphone permission required!");
            return;
        }
        if (!isInCall) {
            isInCall = true;
            runOnUiThread(() -> {
                tvCallState.setText("STATUS: 🟢 ACTIVE (48kHz STREAM LIVE)");
                tvCallState.setTextColor(Color.parseColor("#39FF14"));
            });
            printConsole("[*] Voice Call Connected: ACTIVE (48kHz Studio Quality)");
        }
    }

    private void cutCall() {
        if (isInCall) {
            isInCall = false;
            runOnUiThread(() -> {
                tvCallState.setText("STATUS: 🔴 DISCONNECTED (MUTED)");
                tvCallState.setTextColor(Color.parseColor("#FF2A2A"));
            });
            printConsole("[*] Voice Call CUT / DISCONNECTED");
        }
    }

    private void handleCommand(String input) {
        if (input.equalsIgnoreCase("/call")) {
            startCall();
        } else if (input.equalsIgnoreCase("/cut") || input.equalsIgnoreCase("/end")) {
            cutCall();
        } else if (input.startsWith("/msg")) {
            String[] parts = input.split(" ", 3);
            if (parts.length >= 3) {
                sendGroupMessage(parts[2], parts[1]);
            } else {
                printConsole("Usage: /msg all <message> OR /msg <IP> <message>");
            }
        } else if (input.equalsIgnoreCase("/peers")) {
            printConsole("[*] Discovered Nodes: " + activePeers.size());
            for (String ip : activePeers.keySet()) {
                printConsole("  -> " + peerNames.getOrDefault(ip, "Unknown") + " (" + ip + ")");
            }
        } else if (input.equalsIgnoreCase("/sos")) {
            triggerLiveGeoSos();
        }
    }

    private void sendGroupMessage(String message, String targetIp) {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);

                String packetId = "msg_" + nodeId + "_" + System.currentTimeMillis();
                processedMessages.add(packetId);

                JSONObject pkt = new JSONObject();
                pkt.put("id", packetId);
                pkt.put("type", "TEXT");
                pkt.put("sender", nodeId);
                pkt.put("target", targetIp.isEmpty() ? "all" : targetIp);
                pkt.put("payload", message);
                pkt.put("ttl", 3);
                pkt.put("hops", 0);
                pkt.put("timestamp", System.currentTimeMillis() / 1000.0);

                byte[] encoded = pkt.toString().getBytes();
                socket.send(new DatagramPacket(encoded, encoded.length, InetAddress.getByName("255.255.255.255"), MESH_PORT));
                printConsole("[Mesh Sent (TTL:3)]: " + message);
            } catch (Exception e) {
                printConsole("[!] Send error: " + e.getMessage());
            }
        }).start();
    }

    private void triggerLiveGeoSos() {
        triggerTacticalAlertHaptic(false);
        Toast.makeText(this, "🚨 LIVE GEO SOS TRANSMITTED!", Toast.LENGTH_SHORT).show();

        if (currentLatitude != 0.0 && currentLongitude != 0.0) {
            updateSelfLocation(currentLatitude, currentLongitude);
            runOnUiThread(() -> {
                tvTargetTelemetryTitle.setText("YOU TRIGGERED SOS [LOCAL ACTIVE]");
                tvTargetDistance.setText("Broadcasting Live Coordinates...");
            });
        }

        new Thread(() -> broadcastLocationPacket("SOS_LIVE_GEO")).start();
    }

    private void startDiscoveryBroadcaster() {
        new Thread(() -> {
            try {
                while (!isFinishing()) {
                    try (DatagramSocket socket = new DatagramSocket()) {
                        socket.setBroadcast(true);
                        JSONObject beacon = new JSONObject();
                        beacon.put("node_id", nodeId);
                        beacon.put("type", "BEACON");
                        byte[] data = beacon.toString().getBytes();
                        socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), BROADCAST_PORT));
                    } catch (Exception ignored) {}
                    Thread.sleep(5000);
                }
            } catch (InterruptedException ignored) {}
        }).start();
    }

    private void startPeerDiscoveryListener() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(BROADCAST_PORT)) {
                socket.setReuseAddress(true);
                byte[] buf = new byte[2048];
                while (!isFinishing()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String senderIp = packet.getAddress().getHostAddress();
                    String raw = new String(packet.getData(), 0, packet.getLength());
                    try {
                        JSONObject pkt = new JSONObject(raw);
                        String clientNode = pkt.optString("node_id", senderIp);
                        if (!nodeId.equals(clientNode)) {
                            registerPeer(senderIp, clientNode);
                        }
                    } catch (Exception e) {
                        registerPeer(senderIp, senderIp);
                    }
                }
            } catch (Exception e) {
                printConsole("[!] Discovery listener error: " + e.getMessage());
            }
        }).start();
    }

    private void startMeshPacketListener() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(MESH_PORT)) {
                socket.setReuseAddress(true);
                byte[] buf = new byte[4096];

                while (!isFinishing()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String senderIp = packet.getAddress().getHostAddress();

                    String rawData = new String(packet.getData(), 0, packet.getLength());
                    try {
                        JSONObject pkt = new JSONObject(rawData);
                        String msgId = pkt.optString("id", null);

                        if (msgId != null) {
                            if (processedMessages.contains(msgId)) continue;
                            processedMessages.add(msgId);
                            if (processedMessages.size() > 2000) processedMessages.clear();
                        }

                        String sender = pkt.optString("sender", "Node");
                        String msgType = pkt.optString("type");
                        String target = pkt.optString("target", "all");
                        int ttl = pkt.optInt("ttl", 1);
                        int hops = pkt.optInt("hops", 0);

                        registerPeer(senderIp, sender);

                        if ("all".equalsIgnoreCase(target) || nodeId.equalsIgnoreCase(target)) {
                            if ("TEXT".equals(msgType)) {
                                if (!nodeId.equals(sender)) {
                                    printConsole("💬 [" + sender + " (Hop " + hops + ")]: " + pkt.optString("payload"));
                                }
                            }
                            // GEOSPATIAL & 2-WAY POSITION PACKETS
                            else if ("SOS_LIVE_GEO".equals(msgType) || "LIVE_STREAM_GEO".equals(msgType)) {
                                JSONObject payload = pkt.optJSONObject("payload");
                                JSONObject gps = payload != null ? payload.optJSONObject("gps") : null;
                                double lat = gps != null ? gps.optDouble("lat", 0.0) : 0.0;
                                double lon = gps != null ? gps.optDouble("lon", 0.0) : 0.0;

                                if (lat != 0.0 && lon != 0.0 && !nodeId.equals(sender)) {
                                    if ("SOS_LIVE_GEO".equals(msgType)) {
                                        triggerTacticalAlertHaptic(false);
                                        runOnUiThread(() -> {
                                            switchWindow(3);
                                            Toast.makeText(MainActivity.this, "🚨 LIVE SOS ALERT FROM " + sender, Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                    updateTargetTelemetry(sender, lat, lon);
                                }
                            }
                            // HEALTHCARE TRIAGE EMERGENCY PACKET
                            else if ("MED_TRIAGE_SOS".equals(msgType)) {
                                JSONObject payload = pkt.optJSONObject("payload");
                                String condition = payload != null ? payload.optString("condition", "CASUALTY") : "CASUALTY";
                                String triage = payload != null ? payload.optString("triage", "RED") : "RED";
                                JSONObject gps = payload != null ? payload.optJSONObject("gps") : null;
                                double lat = gps != null ? gps.optDouble("lat", 0.0) : 0.0;
                                double lon = gps != null ? gps.optDouble("lon", 0.0) : 0.0;

                                triggerTacticalAlertHaptic(true);
                                runOnUiThread(() -> {
                                    switchWindow(3); // Switch to Map to show Casualty location
                                    Toast.makeText(MainActivity.this, "🏥 MEDICAL SOS: " + condition + " [" + triage + "] FROM " + sender, Toast.LENGTH_LONG).show();
                                });

                                if (lat != 0.0 && lon != 0.0 && !nodeId.equals(sender)) {
                                    updateTargetTelemetry(sender + " (" + condition + ")", lat, lon);
                                }

                                printConsole("🏥 [MED TRIAGE ALERT] " + sender + " reported " + condition + " (" + triage + ")");
                            }
                        }

                        // RELAY FORWARDING LOGIC
                        if (ttl > 1 && !"SOS_ACK".equals(msgType)) {
                            pkt.put("ttl", ttl - 1);
                            pkt.put("hops", hops + 1);

                            byte[] relayData = pkt.toString().getBytes();
                            new Thread(() -> {
                                try (DatagramSocket relaySocket = new DatagramSocket()) {
                                    relaySocket.setBroadcast(true);
                                    relaySocket.send(new DatagramPacket(
                                            relayData,
                                            relayData.length,
                                            InetAddress.getByName("255.255.255.255"),
                                            MESH_PORT
                                    ));
                                } catch (Exception ignored) {}
                            }).start();
                        }

                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                printConsole("[!] Mesh packet listener error: " + e.getMessage());
            }
        }).start();
    }

    private void startVoiceReceiverPlayback() {
        new Thread(() -> {
            try {
                if (voiceRxSocket == null || voiceRxSocket.isClosed()) {
                    voiceRxSocket = new DatagramSocket(VOICE_PORT);
                    voiceRxSocket.setReuseAddress(true);
                    voiceRxSocket.setReceiveBufferSize(131072);
                }

                int minBuf = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );

                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuf * 4, CHUNK_SIZE * 4),
                        AudioTrack.MODE_STREAM
                );
                audioTrack.play();

                byte[] buffer = new byte[8192];
                while (!isFinishing()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    voiceRxSocket.receive(packet);
                    String senderIp = packet.getAddress().getHostAddress();
                    registerPeer(senderIp, null);

                    if (isInCall && audioTrack != null && packet.getLength() > 0) {
                        audioTrack.write(packet.getData(), packet.getOffset(), packet.getLength());
                    }
                }
            } catch (Exception e) {
                printConsole("[!] Voice Rx error: " + e.getMessage());
            }
        }).start();
    }

    private void startVoiceSenderCapture() {
        new Thread(() -> {
            int sendChunkBytes = CHUNK_SIZE * 2;
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            while (!isFinishing()) {
                if (isInCall) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        isInCall = false;
                        continue;
                    }

                    try {
                        audioRecord = new AudioRecord(
                                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                Math.max(minBuf * 2, sendChunkBytes * 2)
                        );

                        audioRecord.startRecording();
                        byte[] micData = new byte[sendChunkBytes];
                        DatagramSocket txSocket = new DatagramSocket();
                        txSocket.setBroadcast(true);

                        while (isInCall) {
                            int bytesRead = audioRecord.read(micData, 0, micData.length, AudioRecord.READ_BLOCKING);
                            if (bytesRead > 0) {
                                if (!activePeers.isEmpty()) {
                                    for (String peerIp : activePeers.keySet()) {
                                        try {
                                            txSocket.send(new DatagramPacket(micData, bytesRead, InetAddress.getByName(peerIp), VOICE_PORT));
                                        } catch (Exception ignored) {}
                                    }
                                } else {
                                    try {
                                        txSocket.send(new DatagramPacket(micData, bytesRead, InetAddress.getByName("255.255.255.255"), VOICE_PORT));
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                        txSocket.close();
                    } catch (Exception e) {
                        printConsole("[!] Voice Tx error: " + e.getMessage());
                    } finally {
                        if (audioRecord != null) {
                            try {
                                audioRecord.stop();
                                audioRecord.release();
                            } catch (Exception ignored) {}
                            audioRecord = null;
                        }
                    }
                } else {
                    try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private void startCleanupStalePeers() {
        new Thread(() -> {
            try {
                while (!isFinishing()) {
                    long now = System.currentTimeMillis();
                    activePeers.entrySet().removeIf(entry -> {
                        boolean isStale = (now - entry.getValue()) > (PEER_TIMEOUT_SEC * 1000L);
                        if (isStale) peerNames.remove(entry.getKey());
                        return isStale;
                    });
                    refreshPeerWindow();
                    Thread.sleep(10000);
                }
            } catch (InterruptedException ignored) {}
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (osmMapView != null) osmMapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (osmMapView != null) osmMapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isInCall = false;
        is2WayLocationSharingActive = false;
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (osmMapView != null) {
            osmMapView.onDetach();
        }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (voiceRxSocket != null && !voiceRxSocket.isClosed()) voiceRxSocket.close();
        if (audioTrack != null) audioTrack.release();
    }
}