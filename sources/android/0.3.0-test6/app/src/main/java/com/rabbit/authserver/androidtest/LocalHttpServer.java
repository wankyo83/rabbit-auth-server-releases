package com.rabbit.authserver.androidtest;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LocalHttpServer implements AutoCloseable {
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private final int port;
    private final String serverKey;
    private final JobManager jobs;
    private final ExecutorService clients = Executors.newFixedThreadPool(4);
    private volatile boolean running;
    private ServerSocket socket;
    private Thread acceptThread;

    LocalHttpServer(int port, String serverKey, JobManager jobs) {
        this.port = port;
        this.serverKey = serverKey;
        this.jobs = jobs;
    }

    void start() throws IOException {
        socket = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "rabbit-http-accept");
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(10_000);
                clients.execute(() -> handle(client));
            } catch (IOException error) {
                if (running) error.printStackTrace();
            }
        }
    }

    private void handle(Socket client) {
        try (client;
             BufferedInputStream input = new BufferedInputStream(client.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            Request request = readRequest(input);
            Response response = route(request);
            writeResponse(output, response);
        } catch (Exception error) {
            // Never log request headers or bodies because they contain the access key.
            Log.e("RabbitHttp", "Local request failed: " + error.getClass().getSimpleName(), error);
        }
    }

    private Response route(Request request) {
        if (!isTrustedHost(request.headers.get("host")) || !isTrustedOrigin(request.headers.get("origin"))) {
            return error(403, "request_origin_not_allowed", "로컬 앱 요청만 허용됩니다.");
        }
        if (!("Bearer " + serverKey).equals(request.headers.get("authorization"))) {
            return error(401, "unauthorized", "접속 키가 올바르지 않습니다.");
        }

        try {
            if (request.method.equals("GET") && request.path.equals("/health")) {
                return json(200, new JSONObject()
                    .put("ready", true)
                    .put("service", "rabbit-auth-server")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("protocol", 1)
                    .put("concurrency", AppConfig.CONCURRENCY));
            }

            if (request.method.equals("POST") && request.path.equals("/v1/jobs")) {
                Response invalidPost = validateJsonPost(request);
                if (invalidPost != null) return invalidPost;
                JSONObject body = new JSONObject(request.body);
                return json(202, jobs.create(body.optString("url"), body.optString("requestId"), body.optString("kind")));
            }

            String[] parts = request.path.split("/");
            if (parts.length >= 4 && parts[1].equals("v1") && parts[2].equals("jobs")) {
                String id = parts[3];
                if (request.method.equals("GET") && parts.length == 4) {
                    return json(200, jobs.status(id));
                }
                if (request.method.equals("POST") && parts.length == 5) {
                    Response invalidPost = validateJsonPost(request);
                    if (invalidPost != null) return invalidPost;
                    if (parts[4].equals("manifest")) return json(200, jobs.manifest(id));
                    if (parts[4].equals("close")) return json(200, jobs.closeJob(id));
                }
            }
            return error(404, "not_found", "지원하지 않는 경로입니다.");
        } catch (JobManager.JobException error) {
            return error(error.httpStatus, error.code, error.getMessage());
        } catch (Exception error) {
            return error(400, "bad_request", "요청 형식이 올바르지 않습니다.");
        }
    }

    private Response validateJsonPost(Request request) {
        String contentType = request.headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("application/json")) {
            return error(415, "json_required", "JSON 요청만 허용됩니다.");
        }
        if (!"1".equals(request.headers.get("x-lab-request"))) {
            return error(403, "request_header_required", "필수 요청 헤더가 없습니다.");
        }
        return null;
    }

    private static boolean isTrustedHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1:9898") || value.equals("localhost:9898") || value.equals("127.0.0.1") || value.equals("localhost");
    }

    private static boolean isTrustedOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.equals("null")) return true;
        String value = origin.toLowerCase(Locale.ROOT);
        return value.equals("http://127.0.0.1:9898") || value.equals("http://localhost:9898");
    }

    private static Request readRequest(BufferedInputStream input) throws IOException {
        int consumed = 0;
        String requestLine = readLine(input, MAX_HEADER_BYTES);
        consumed += requestLine.length() + 2;
        String[] first = requestLine.split(" ");
        if (first.length != 3) throw new IOException("bad request line");
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(input, MAX_HEADER_BYTES - consumed);
            consumed += line.length() + 2;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("bad header");
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        int length = 0;
        if (headers.containsKey("content-length")) length = Integer.parseInt(headers.get("content-length"));
        if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("body too large");
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) throw new EOFException("short body");
            offset += read;
        }
        String path = first[1].split("\\?", 2)[0];
        return new Request(first[0].toUpperCase(Locale.ROOT), path, headers, new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(BufferedInputStream input, int max) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() <= max) {
            int current = input.read();
            if (current < 0) throw new EOFException();
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            line.write(current);
            previous = current;
        }
        throw new IOException("headers too large");
    }

    private static void writeResponse(BufferedOutputStream output, Response response) throws IOException {
        byte[] body = response.body;
        String head = "HTTP/1.1 " + response.status + " " + reason(response.status) + "\r\n" +
            "Content-Type: " + response.contentType + "\r\n" +
            "Content-Length: " + body.length + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static Response json(int status, JSONObject body) {
        return new Response(status, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Response error(int status, String code, String message) {
        JSONObject root = new JSONObject();
        try {
            root.put("error", code);
        } catch (org.json.JSONException ignored) {
            // String values cannot normally fail in Android's JSONObject implementation.
        }
        return json(status, root);
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 415 -> "Unsupported Media Type";
            case 502 -> "Bad Gateway";
            default -> "Error";
        };
    }

    @Override public void close() {
        running = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        clients.shutdownNow();
    }

    private record Request(String method, String path, Map<String, String> headers, String body) {}
    private record Response(int status, String contentType, byte[] body) {}
}
