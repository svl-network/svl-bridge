/*
 * Copyright (c) 2026 Sunveil Network. All rights reserved.
 *
 * PROPRIETARY & CONFIDENTIAL
 *
 * This file is part of Sunveil Connect and the Sunveil Bridge.
 * Unauthorized copying of this file, via any medium, is strictly prohibited.
 */

package net.sunveil.bridge.tunnel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TunnelClient implements WebSocket.Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-tunnel");

    private static final byte PKT_OPEN = 0x01;
    private static final byte PKT_DATA = 0x02;
    private static final byte PKT_CLOSE = 0x03;

    private final String masterApiUrl;
    private final String serverKey;
    private final String masterToken;
    private final int localServerPort;

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "svl-tunnel-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService ioThreadPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "svl-tunnel-io");
        t.setDaemon(true);
        return t;
    });

    private final Map<Integer, Socket> localSockets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private WebSocket activeWs;

    public TunnelClient(String masterApiUrl, String serverKey, String masterToken, int localServerPort) {
        this.masterApiUrl = masterApiUrl;
        this.serverKey = serverKey;
        this.masterToken = masterToken;
        this.localServerPort = localServerPort > 0 ? localServerPort : 25565;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public synchronized void start() {
        if (running.getAndSet(true)) {
            return;
        }
        LOGGER.info("[SVL-Tunnel] Initializing Secure Zero-Portforwarding Tunnel client...");
        connect();
    }

    public synchronized void stop() {
        running.set(false);
        if (activeWs != null) {
            try {
                activeWs.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabled").join();
            } catch (Exception ignored) {}
        }
        for (Socket s : localSockets.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        localSockets.clear();
        scheduler.shutdownNow();
        ioThreadPool.shutdownNow();
        LOGGER.info("[SVL-Tunnel] Secure Tunnel stopped.");
    }

    private void connect() {
        if (!running.get()) return;

        try {
            String wsBase = deriveWsUrl(masterApiUrl);
            String fullUrl = String.format("%s/api/v1/tunnel/ws?serverKey=%s&token=%s",
                    wsBase,
                    URLEncoder.encode(serverKey, StandardCharsets.UTF_8),
                    URLEncoder.encode(masterToken, StandardCharsets.UTF_8));

            LOGGER.info("[SVL-Tunnel] Connecting to Master Relay at {}...", wsBase);

            httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + masterToken)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(fullUrl), this)
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            LOGGER.warn("[SVL-Tunnel] Could not connect to Tunnel Relay ({}). Retrying in 10s...", err.getMessage());
                            scheduleReconnect(10);
                        } else {
                            this.activeWs = ws;
                            LOGGER.info("[SVL-Tunnel] Outbound tunnel established successfully!");
                        }
                    });
        } catch (Exception e) {
            LOGGER.error("[SVL-Tunnel] Error building WebSocket tunnel URI: {}", e.getMessage());
            scheduleReconnect(10);
        }
    }

    private void scheduleReconnect(int delaySeconds) {
        if (!running.get()) return;
        scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    private String deriveWsUrl(String httpUrl) {
        if (httpUrl == null || httpUrl.isBlank()) return "ws://localhost:3001";
        try {
            URI uri = URI.create(httpUrl);
            String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
            String host = uri.getHost() != null ? uri.getHost() : "localhost";
            int port = uri.getPort();
            return port != -1 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (Exception e) {
            return "ws://localhost:3001";
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonObject json = JsonParser.parseString(data.toString()).getAsJsonObject();
            if (json.has("type") && "TUNNEL_READY".equals(json.get("type").getAsString())) {
                String host = json.get("publicHost").getAsString();
                int port = json.get("publicPort").getAsInt();
                LOGGER.info("=========================================================");
                LOGGER.info("🛡️ [SVL-Tunnel] ONLINE! No Port-Forwarding required.");
                LOGGER.info("🛡️ Players can join via Sunveil Connect or directly: {}:{}", host, port);
                LOGGER.info("🛡️ Your home IP is fully protected and hidden.");
                LOGGER.info("=========================================================");
            }
        } catch (Exception ignored) {}
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        if (data.remaining() >= 5) {
            byte pktType = data.get();
            int connId = data.getInt();

            if (pktType == PKT_OPEN) {
                handleClientOpen(connId);
            } else if (pktType == PKT_DATA) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                handleClientData(connId, chunk);
            } else if (pktType == PKT_CLOSE) {
                handleClientClose(connId);
            }
        }
        webSocket.request(1);
        return null;
    }

    private void handleClientOpen(int connId) {
        ioThreadPool.submit(() -> {
            try {
                Socket localSocket = new Socket("127.0.0.1", localServerPort);
                localSocket.setTcpNoDelay(true);
                localSockets.put(connId, localSocket);

                InputStream in = localSocket.getInputStream();
                byte[] buffer = new byte[8192];
                int read;

                while (running.get() && (read = in.read(buffer)) != -1) {
                    if (activeWs != null) {
                        ByteBuffer frame = ByteBuffer.allocate(5 + read);
                        frame.put(PKT_DATA);
                        frame.putInt(connId);
                        frame.put(buffer, 0, read);
                        frame.flip();
                        activeWs.sendBinary(frame, true);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                handleClientClose(connId);
            }
        });
    }

    private void handleClientData(int connId, byte[] chunk) {
        Socket localSocket = localSockets.get(connId);
        if (localSocket != null && !localSocket.isClosed()) {
            try {
                OutputStream out = localSocket.getOutputStream();
                out.write(chunk);
                out.flush();
            } catch (Exception e) {
                handleClientClose(connId);
            }
        }
    }

    private void handleClientClose(int connId) {
        Socket socket = localSockets.remove(connId);
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        if (activeWs != null) {
            ByteBuffer closeFrame = ByteBuffer.allocate(5);
            closeFrame.put(PKT_CLOSE);
            closeFrame.putInt(connId);
            closeFrame.flip();
            try {
                activeWs.sendBinary(closeFrame, true);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        LOGGER.warn("[SVL-Tunnel] Tunnel connection closed (Code {}: {}). Reconnecting in 5s...", statusCode, reason);
        scheduleReconnect(5);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOGGER.warn("[SVL-Tunnel] Tunnel error: {}. Reconnecting in 5s...", error.getMessage());
        scheduleReconnect(5);
    }
}
