package io.fivetime.raas.jenkins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the RaaS Jenkins API ({@code /v1/jenkins/*}).
 *
 * <p>The tenant only configures the RaaS URL and an application credential. The credential is
 * exchanged for a Keystone token at {@code POST /v1/jenkins/token}; the token is cached until five
 * minutes before it expires and refreshed once on a 401. Every refusal comes back as a
 * {@link RaasException} carrying the API's stable {@code code}.
 */
public class RaasClient {
    private static final Logger LOGGER = Logger.getLogger(RaasClient.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REFRESH_LEAD = Duration.ofMinutes(5);

    private final String baseUrl;
    private final String credentialId;
    private final String credentialSecret;
    private final HttpClient http;

    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public RaasClient(String baseUrl, String credentialId, String credentialSecret) {
        this(baseUrl, credentialId, credentialSecret, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    RaasClient(String baseUrl, String credentialId, String credentialSecret, HttpClient http) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.baseUrl = b;
        this.credentialId = credentialId;
        this.credentialSecret = credentialSecret;
        this.http = http;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /** One label RaaS is willing to deliver right now. */
    public static final class Label {
        public final String label;
        public final String flavorName;
        public final String imageName;
        public final boolean isDefault;

        Label(String label, String flavorName, String imageName, boolean isDefault) {
            this.label = label;
            this.flavorName = flavorName;
            this.imageName = imageName;
            this.isDefault = isDefault;
        }

        @Override
        public String toString() {
            return label + " (" + flavorName + ", " + imageName + (isDefault ? ", default" : "") + ")";
        }
    }

    /** The agent RaaS created for us. */
    public static final class Agent {
        public final long id;
        public final String state;

        Agent(long id, String state) {
            this.id = id;
            this.state = state;
        }
    }

    /** {@code GET /v1/jenkins/labels}: what this tenant may ask for. Also the "Test connection" probe. */
    public List<Label> labels() throws IOException, InterruptedException {
        JsonNode body = call("GET", "/v1/jenkins/labels", null, 200);
        List<Label> out = new ArrayList<>();
        for (JsonNode n : body.path("labels")) {
            out.add(new Label(n.path("label").asText(), n.path("flavor_name").asText(), n.path("image_name").asText(),
                    n.path("default").asBoolean(false)));
        }
        return out;
    }

    /**
     * {@code POST /v1/jenkins/agents}: ask for one agent. RaaS takes a machine, attaches the tenant network and
     * pushes {@code secret} to the machine agent over mTLS; the machine then connects outbound to {@code jenkinsUrl}.
     */
    public Agent provision(String label, String nodeName, String jenkinsUrl, String secret)
            throws IOException, InterruptedException {
        ObjectNode req = JSON.createObjectNode();
        req.put("label", label);
        req.put("node_name", nodeName);
        req.put("jenkins_url", jenkinsUrl);
        req.put("secret", secret);
        JsonNode body = call("POST", "/v1/jenkins/agents", req, 201);
        return new Agent(body.path("id").asLong(), body.path("state").asText());
    }

    /** {@code POST /v1/jenkins/agents/{id}/connected}: the node came online; RaaS starts billing here. */
    public void connected(long id) throws IOException, InterruptedException {
        call("POST", "/v1/jenkins/agents/" + id + "/connected", null, 204);
    }

    /** {@code DELETE /v1/jenkins/agents/{id}}: the node is gone; RaaS destroys the machine. Idempotent. */
    public void terminate(long id, String buildRef, String conclusion) throws IOException, InterruptedException {
        ObjectNode req = JSON.createObjectNode();
        if (buildRef != null) {
            req.put("build_ref", buildRef);
        }
        if (conclusion != null) {
            req.put("conclusion", conclusion);
        }
        call("DELETE", "/v1/jenkins/agents/" + id, req, 204);
    }

    // ---- plumbing ----

    private synchronized String token(boolean force) throws IOException, InterruptedException {
        if (!force && token != null && Instant.now().plus(REFRESH_LEAD).isBefore(tokenExpiry)) {
            return token;
        }
        ObjectNode req = JSON.createObjectNode();
        req.put("application_credential_id", credentialId);
        req.put("secret", credentialSecret);
        HttpRequest r = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/jenkins/token")).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(req.toString())).build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw error(resp);
        }
        JsonNode body = JSON.readTree(resp.body());
        token = body.path("token").asText();
        String exp = body.path("expires_at").asText(null);
        tokenExpiry = exp == null || exp.isEmpty() ? Instant.now().plus(Duration.ofMinutes(30))
                : OffsetDateTime.parse(exp).toInstant();
        LOGGER.log(Level.FINE, "RaaS token refreshed, expires {0}", tokenExpiry);
        return token;
    }

    private JsonNode call(String method, String path, JsonNode body, int expected) throws IOException, InterruptedException {
        HttpResponse<String> resp = send(method, path, body, token(false));
        if (resp.statusCode() == 401) {
            // Token expired early or was revoked: refresh once and retry.
            resp = send(method, path, body, token(true));
        }
        if (resp.statusCode() != expected) {
            throw error(resp);
        }
        String text = resp.body();
        if (text == null || text.isBlank()) {
            return JSON.createObjectNode();
        }
        return JSON.readTree(text);
    }

    private HttpResponse<String> send(String method, String path, JsonNode body, String tok)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT)
                .header("X-Auth-Token", tok).header("Accept", "application/json");
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static RaasException error(HttpResponse<String> resp) {
        String code = "error";
        String msg = "HTTP " + resp.statusCode();
        try {
            JsonNode n = JSON.readTree(resp.body());
            if (n.hasNonNull("code")) {
                code = n.get("code").asText();
            }
            if (n.hasNonNull("error")) {
                msg = n.get("error").asText();
            }
        } catch (IOException | RuntimeException ignored) {
            if (resp.body() != null && !resp.body().isBlank()) {
                msg = msg + ": " + resp.body().strip();
            }
        }
        return new RaasException(resp.statusCode(), code, msg);
    }
}
