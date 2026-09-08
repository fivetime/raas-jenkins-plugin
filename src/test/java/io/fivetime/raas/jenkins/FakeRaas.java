package io.fivetime.raas.jenkins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the RaaS Jenkins API, strict about the same things the real one is strict about:
 * every call needs the token from /v1/jenkins/token, refusals carry a stable code, and the
 * provision body must have all four fields.
 */
final class FakeRaas implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();

    final HttpServer server;
    final String url;
    final AtomicInteger tokenCalls = new AtomicInteger();
    final AtomicInteger unauthorizedOnce = new AtomicInteger();
    final List<JsonNode> provisionBodies = new ArrayList<>();
    final List<String> deletes = new ArrayList<>();
    final List<JsonNode> deleteBodies = new ArrayList<>();
    final List<Long> connected = new ArrayList<>();
    /** When set, POST /agents answers with this status and code instead of creating an agent. */
    volatile int refuseStatus;
    volatile String refuseCode;
    volatile String secret = "s3cret";
    volatile String token = "tok-1";

    FakeRaas() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        byte[] raw = ex.getRequestBody().readAllBytes();
        JsonNode body = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
        try {
            if (method.equals("POST") && path.equals("/v1/jenkins/token")) {
                tokenCalls.incrementAndGet();
                if (!secret.equals(body.path("secret").asText())) {
                    reply(ex, 401, "{\"error\":\"application credential 无效\",\"code\":\"unauthorized\"}");
                    return;
                }
                reply(ex, 200, "{\"token\":\"" + token + "\",\"expires_at\":\"2099-01-01T00:00:00Z\",\"project_id\":\"proj-a\"}");
                return;
            }
            String tok = ex.getRequestHeaders().getFirst("X-Auth-Token");
            if (unauthorizedOnce.get() > 0) {
                unauthorizedOnce.decrementAndGet();
                reply(ex, 401, "{\"error\":{\"code\":401,\"message\":\"invalid or missing token\"}}");
                return;
            }
            if (!token.equals(tok)) {
                reply(ex, 401, "{\"error\":{\"code\":401,\"message\":\"invalid or missing token\"}}");
                return;
            }
            if (method.equals("GET") && path.equals("/v1/jenkins/labels")) {
                reply(ex, 200, "{\"labels\":[{\"label\":\"raas-ubuntu-24.04\",\"flavor_name\":\"ci-build-4\",\"image_name\":\"full\",\"default\":true}]}");
                return;
            }
            if (method.equals("POST") && path.equals("/v1/jenkins/agents")) {
                for (String f : new String[] {"label", "node_name", "jenkins_url", "secret"}) {
                    if (body.path(f).asText("").isEmpty()) {
                        reply(ex, 400, "{\"error\":\"缺 " + f + "\",\"code\":\"bad_request\"}");
                        return;
                    }
                }
                if (refuseStatus != 0) {
                    reply(ex, refuseStatus, "{\"error\":\"refused\",\"code\":\"" + refuseCode + "\"}");
                    return;
                }
                synchronized (provisionBodies) {
                    provisionBodies.add(body);
                }
                reply(ex, 201, "{\"id\":17,\"state\":\"requested\",\"node_name\":\"" + body.path("node_name").asText() + "\"}");
                return;
            }
            if (method.equals("POST") && path.matches("/v1/jenkins/agents/\\d+/connected")) {
                synchronized (connected) {
                    connected.add(Long.parseLong(path.split("/")[4]));
                }
                reply(ex, 204, "");
                return;
            }
            if (method.equals("DELETE") && path.matches("/v1/jenkins/agents/\\d+")) {
                synchronized (deletes) {
                    deletes.add(path.split("/")[4]);
                    deleteBodies.add(body);
                }
                reply(ex, 204, "");
                return;
            }
            reply(ex, 404, "{\"error\":\"no route\",\"code\":\"not_found\"}");
        } finally {
            ex.close();
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (b.length == 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
