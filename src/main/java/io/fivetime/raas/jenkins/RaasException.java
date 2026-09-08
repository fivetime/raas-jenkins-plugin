package io.fivetime.raas.jenkins;

import java.io.IOException;

/**
 * A refusal or failure reported by RaaS. {@link #getCode()} is the stable machine-readable
 * code from the API ({@code no_network}, {@code quota}, {@code pool_empty}, {@code unknown_label},
 * {@code unreachable_jenkins}, ...) so the reason lands verbatim in the Jenkins log instead
 * of a timeout.
 */
public class RaasException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public RaasException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** RaaS said "not now" (pool being refilled): Jenkins' provisioner will simply ask again later. */
    public boolean isRetryLater() {
        return status == 503 || "pool_empty".equals(code);
    }

    @Override
    public String toString() {
        return "RaaS " + status + " " + code + ": " + getMessage();
    }
}
