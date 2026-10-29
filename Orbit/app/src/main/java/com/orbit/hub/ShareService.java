package com.orbit.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shares this phone's screen to the Bomba Live wall.
 *
 * Speaks the PeerJS protocol natively (the same protocol the Bomba Live
 * pages use): registers the peer id bomba-<code>-<device> with the PeerJS
 * cloud, answers media calls with the MediaProjection capture (WebRTC),
 * and answers data connections with remote-control commands, which are
 * handed to OrbitAccessibilityService.
 */
public class ShareService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_NAME = "name";

    /** Latest status JSON, read by the bridge's shareState(). */
    public static volatile String lastStatusJson = "{}";
    /** Set by MainActivity to receive live status pushes. */
    public static volatile StatusSink sink;

    public interface StatusSink {
        void onShareStatus(JSONObject status);
    }

    private HandlerThread thread;
    private Handler io;
    private String code;
    private String name;
    private String myPeerId;

    private EglBase egl;
    private PeerConnectionFactory factory;
    private VideoCapturer capturer;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private MediaProjection mediaProjection;
    private boolean captureRunning;

    private SigSocket socket;
    private final Map<String, Conn> conns = new HashMap<>();
    private final Set<String> liveViewers = new HashSet<>();
    private int offers;
    private int answers;

    private static class Conn {
        PeerConnection pc;
        String remote;
        String type; // "media" or "data"
        DataChannel channel;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("argus-share");
        thread.start();
        io = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNow();
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        code = intent.getStringExtra(EXTRA_CODE);
        name = intent.getStringExtra(EXTRA_NAME);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (code == null || resultData == null) {
            report(errorStatus("Sharing setup was incomplete"));
            stopSelf();
            return START_NOT_STICKY;
        }
        myPeerId = peerIdFor(code, name);
        report(status("starting", null));
        io.post(() -> begin(resultCode, resultData));
        return START_NOT_STICKY;
    }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "argus-share", "Screen sharing", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, "argus-share")
                .setContentTitle("Orbit is sharing your screen")
                .setContentText("Visible to devices on your Bomba Live wall")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, n);
        }
    }

    private void begin(int resultCode, Intent resultData) {
        try {
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    io.post(() -> stopSharing("Screen capture stopped"));
                }
            }, io);

            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(this)
                            .createInitializationOptions());
            egl = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                            egl.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                    .createPeerConnectionFactory();

            ScreenCapturerAndroid cap = new ScreenCapturerAndroid(resultData,
                    new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            io.post(() -> stopSharing("Screen capture stopped"));
                        }
                    });
            capturer = cap;
            videoSource = factory.createVideoSource(true);
            SurfaceTextureHelper helper = SurfaceTextureHelper.create("argus-capture",
                    egl.getEglBaseContext());
            capturer.initialize(helper, this, videoSource.getCapturerObserver());
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels, h = dm.heightPixels;
            int longest = Math.max(w, h);
            if (longest > 1280) {
                float s = 1280f / longest;
                w = Math.round(w * s / 2) * 2;
                h = Math.round(h * s / 2) * 2;
            }
            capturer.startCapture(w, h, 15);
            captureRunning = true;
            videoTrack = factory.createVideoTrack("argus-screen", videoSource);
            videoTrack.setEnabled(true);

            connectSignaling();
        } catch (Exception e) {
            report(errorStatus("Couldn't start sharing: " + e.getMessage()));
            stopSelf();
        }
    }

    // ------------------------------------------------------------ signaling

    private void connectSignaling() {
        try {
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            URI uri = new URI("wss://0.peerjs.com/peerjs?key=peerjs&id=" + myPeerId + "&token=" + token);
            socket = new SigSocket(uri);
            socket.connect();
        } catch (Exception e) {
            report(errorStatus("Couldn't reach the live service"));
        }
    }

    private class SigSocket extends WebSocketClient {
        SigSocket(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            // Wait for the server's OPEN message before reporting live.
        }

        @Override
        public void onMessage(String message) {
            io.post(() -> handleSignal(message));
        }

        @Override
        public void onClose(int c, String reason, boolean remote) {
            io.post(() -> {
                if (captureRunning) report(errorStatus("Live connection closed"));
            });
        }

        @Override
        public void onError(Exception ex) {
            io.post(() -> {
                if (captureRunning) report(errorStatus("Live connection error"));
            });
        }
    }

    private void sendSignal(JSONObject msg) {
        try {
            if (socket != null && socket.isOpen()) socket.send(msg.toString());
        } catch (Exception ignored) {
        }
    }

    private void handleSignal(String raw) {
        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.optString("type");
            String src = msg.optString("src", "");
            JSONObject payload = msg.optJSONObject("payload");
            switch (type) {
                case "OPEN":
                    report(status("live", null));
                    break;
                case "OFFER":
                    if (payload != null) onOffer(src, payload);
                    break;
                case "CANDIDATE":
                    if (payload != null) onCandidate(payload);
                    break;
                case "LEAVE":
                    closeConnsFrom(src);
                    break;
                case "ERROR":
                case "ID-TAKEN": {
                    String m = payload != null ? payload.optString("msg", "") : "";
                    report(errorStatus(m.contains("taken")
                            ? "Another screen is already sharing as " + name + ". Pick a different device name."
                            : ("Live service error: " + m)));
                    break;
                }
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private void onOffer(String src, JSONObject payload) throws Exception {
        String connId = payload.optString("connectionId");
        String kind = payload.optString("type", "media");
        JSONObject sdpObj = payload.optJSONObject("sdp");
        if (connId.isEmpty() || sdpObj == null) return;
        if ("media".equals(kind)) {
            offers++;
            report(status("live", null));
        }
        Conn old = conns.remove(connId);
        if (old != null) disposeConn(old);

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers());
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        Conn conn = new Conn();
        conn.remote = src;
        conn.type = kind;
        PeerConnection pc = factory.createPeerConnection(cfg, new PcObserver(connId));
        if (pc == null) return;
        conn.pc = pc;
        conns.put(connId, conn);
        if ("media".equals(kind) && videoTrack != null) {
            pc.addTrack(videoTrack, Collections.singletonList("argus"));
        }
        SessionDescription offer = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpObj.optString("type", "offer")),
                sdpObj.optString("sdp"));
        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                pc.createAnswer(new SimpleSdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        pc.setLocalDescription(new SimpleSdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                try {
                                    JSONObject sdp = new JSONObject();
                                    sdp.put("type", answer.type.canonicalForm());
                                    sdp.put("sdp", answer.description);
                                    JSONObject p = new JSONObject();
                                    p.put("sdp", sdp);
                                    p.put("type", kind);
                                    p.put("connectionId", connId);
                                    JSONObject m = new JSONObject();
                                    m.put("type", "ANSWER");
                                    m.put("dst", src);
                                    m.put("payload", p);
                                    sendSignal(m);
                                    if ("media".equals(kind)) {
                                        answers++;
                                        report(status("live", null));
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }, answer);
                    }
                }, new MediaConstraints());
            }
        }, offer);
    }

    private void onCandidate(JSONObject payload) {
        String connId = payload.optString("connectionId");
        JSONObject cand = payload.optJSONObject("candidate");
        Conn conn = conns.get(connId);
        if (conn == null || conn.pc == null || cand == null) return;
        conn.pc.addIceCandidate(new IceCandidate(
                cand.optString("sdpMid"), cand.optInt("sdpMLineIndex"),
                cand.optString("candidate")));
    }

    private void closeConnsFrom(String src) {
        for (String id : new ArrayList<>(conns.keySet())) {
            Conn c = conns.get(id);
            if (c != null && src.equals(c.remote)) {
                conns.remove(id);
                disposeConn(c);
            }
        }
    }

    private void disposeConn(Conn c) {
        try {
            if (c.channel != null) c.channel.close();
        } catch (Exception ignored) {
        }
        try {
            if (c.pc != null) c.pc.dispose();
        } catch (Exception ignored) {
        }
    }

    private class PcObserver implements PeerConnection.Observer {
        private final String connId;

        PcObserver(String connId) {
            this.connId = connId;
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            Conn c = conns.get(connId);
            if (c == null) return;
            try {
                JSONObject cand = new JSONObject();
                cand.put("candidate", candidate.sdp);
                cand.put("sdpMid", candidate.sdpMid);
                cand.put("sdpMLineIndex", candidate.sdpMLineIndex);
                JSONObject p = new JSONObject();
                p.put("candidate", cand);
                p.put("type", c.type);
                p.put("connectionId", connId);
                JSONObject m = new JSONObject();
                m.put("type", "CANDIDATE");
                m.put("dst", c.remote);
                m.put("payload", p);
                sendSignal(m);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onDataChannel(DataChannel channel) {
            Conn c = conns.get(connId);
            if (c == null) return;
            c.channel = channel;
            channel.registerObserver(new DataChannel.Observer() {
                @Override
                public void onBufferedAmountChange(long amount) {
                }

                @Override
                public void onStateChange() {
                    if (channel.state() == DataChannel.State.OPEN
                            && OrbitAccessibilityService.instance == null) {
                        sendNote(channel, "Turn on Orbit screen control in Android Settings > Accessibility so this phone can be controlled.");
                    }
                }

                @Override
                public void onMessage(DataChannel.Buffer buffer) {
                    try {
                        ByteBuffer d = buffer.data;
                        byte[] bytes = new byte[d.remaining()];
                        d.get(bytes);
                        JSONObject cmd = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                        OrbitAccessibilityService a = OrbitAccessibilityService.instance;
                        if (a != null) a.handleCommand(cmd);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Conn c = conns.get(connId);
            if (c == null) return;
            if ("media".equals(c.type)) {
                if (state == PeerConnection.IceConnectionState.CONNECTED
                        || state == PeerConnection.IceConnectionState.COMPLETED) {
                    if (liveViewers.add(connId)) report(status("live", null));
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED
                        || state == PeerConnection.IceConnectionState.FAILED
                        || state == PeerConnection.IceConnectionState.CLOSED) {
                    if (liveViewers.remove(connId)) report(status("live", null));
                    conns.remove(connId);
                    disposeConn(c);
                }
            }
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
        @Override public void onIceConnectionReceivingChange(boolean b) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] cs) { }
        @Override public void onAddStream(MediaStream s) { }
        @Override public void onRemoveStream(MediaStream s) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) { }
    }

    private void sendNote(DataChannel channel, String note) {
        try {
            JSONObject o = new JSONObject();
            o.put("note", note);
            byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
            channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
        } catch (Exception ignored) {
        }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }

    // ------------------------------------------------------------ status

    private JSONObject status(String state, String problem) {
        try {
            JSONObject s = new JSONObject();
            s.put("state", state);
            if (name != null) s.put("name", name);
            if (!liveViewers.isEmpty()) s.put("viewers", liveViewers.size());
            else if (offers > 0) {
                s.put("offers", offers);
                s.put("answers", answers);
            }
            if (problem != null) s.put("problem", problem);
            return s;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject errorStatus(String message) {
        try {
            JSONObject s = new JSONObject();
            s.put("state", "error");
            s.put("message", message);
            return s;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void report(JSONObject s) {
        lastStatusJson = s.toString();
        StatusSink sk = sink;
        if (sk != null) sk.onShareStatus(s);
    }

    // ------------------------------------------------------------ teardown

    private void stopSharing(String why) {
        report(status("stopped", null));
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // Run teardown on the io thread, then let the thread finish.
        Handler h = io;
        h.post(() -> {
            captureRunning = false;
            for (Conn c : conns.values()) disposeConn(c);
            conns.clear();
            liveViewers.clear();
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {
            }
            try {
                if (capturer != null) {
                    capturer.stopCapture();
                    capturer.dispose();
                }
            } catch (Exception ignored) {
            }
            try {
                if (videoTrack != null) videoTrack.dispose();
            } catch (Exception ignored) {
            }
            try {
                if (videoSource != null) videoSource.dispose();
            } catch (Exception ignored) {
            }
            try {
                if (factory != null) factory.dispose();
            } catch (Exception ignored) {
            }
            try {
                if (egl != null) egl.release();
            } catch (Exception ignored) {
            }
            try {
                if (mediaProjection != null) mediaProjection.stop();
            } catch (Exception ignored) {
            }
            lastStatusJson = "{}";
            thread.quitSafely();
        });
        super.onDestroy();
    }

    // ------------------------------------------------------------ helpers

    private ArrayList<PeerConnection.IceServer> iceServers() {
        ArrayList<PeerConnection.IceServer> list = new ArrayList<>();
        list.add(PeerConnection.IceServer.builder(
                "stun:stun.l.google.com:19302").createIceServer());
        list.add(PeerConnection.IceServer.builder(
                "stun:stun1.l.google.com:19302").createIceServer());
        return list;
    }

    static String peerIdFor(String code, String deviceName) {
        String c = code.toLowerCase().replaceAll("[^a-z0-9]", "");
        String slug = deviceName.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-+$", "");
        return "bomba-" + c + "-" + slug;
    }
}
