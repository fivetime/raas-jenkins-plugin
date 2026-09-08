package io.fivetime.raas.jenkins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class RaasClientTest {

    /** One token for many calls; a 401 triggers exactly one refresh and a retry. */
    @Test
    public void tokenIsCachedAndRefreshedOnceOn401() throws Exception {
        try (FakeRaas raas = new FakeRaas()) {
            RaasClient c = new RaasClient(raas.url + "/", "ac-1", "s3cret");
            List<RaasClient.Label> labels = c.labels();
            assertEquals(1, labels.size());
            assertEquals("raas-ubuntu-24.04", labels.get(0).label);
            assertTrue(labels.get(0).isDefault);
            c.labels();
            c.labels();
            assertEquals("three calls, one token exchange", 1, raas.tokenCalls.get());

            raas.unauthorizedOnce.set(1);
            RaasClient.Agent a = c.provision("raas-ubuntu-24.04", "raas-x-1", "https://ci.example.com/", "mac");
            assertEquals(17, a.id);
            assertEquals("a 401 costs exactly one refresh", 2, raas.tokenCalls.get());
            assertEquals("https://ci.example.com/", raas.provisionBodies.get(0).path("jenkins_url").asText());
            assertEquals("mac", raas.provisionBodies.get(0).path("secret").asText());
        }
    }

    /** RaaS's refusal code survives verbatim: that is what the tenant reads in the Jenkins log. */
    @Test
    public void refusalCodesSurvive() throws Exception {
        try (FakeRaas raas = new FakeRaas()) {
            RaasClient c = new RaasClient(raas.url, "ac-1", "s3cret");
            raas.refuseStatus = 409;
            raas.refuseCode = "no_network";
            RaasException e = assertThrows(RaasException.class,
                    () -> c.provision("raas-ubuntu-24.04", "n", "https://ci/", "s"));
            assertEquals(409, e.getStatus());
            assertEquals("no_network", e.getCode());
            assertFalse(e.isRetryLater());

            raas.refuseStatus = 503;
            raas.refuseCode = "pool_empty";
            RaasException later = assertThrows(RaasException.class,
                    () -> c.provision("raas-ubuntu-24.04", "n", "https://ci/", "s"));
            assertTrue("503/pool_empty means ask again later, not failure", later.isRetryLater());
        }
    }

    /** A wrong application credential is a clean 401/unauthorized, not a stack trace. */
    @Test
    public void badCredentialIsUnauthorized() throws Exception {
        try (FakeRaas raas = new FakeRaas()) {
            RaasClient c = new RaasClient(raas.url, "ac-1", "wrong");
            RaasException e = assertThrows(RaasException.class, c::labels);
            assertEquals(401, e.getStatus());
            assertEquals("unauthorized", e.getCode());
        }
    }

    /** terminate carries what ran, and is happy with an empty answer. */
    @Test
    public void terminateReportsTheBuild() throws Exception {
        try (FakeRaas raas = new FakeRaas()) {
            RaasClient c = new RaasClient(raas.url, "ac-1", "s3cret");
            c.connected(17);
            c.terminate(17, "job/hello/42", "SUCCESS");
            assertEquals(List.of(17L), raas.connected);
            assertEquals(List.of("17"), raas.deletes);
            assertEquals("job/hello/42", raas.deleteBodies.get(0).path("build_ref").asText());
            assertEquals("SUCCESS", raas.deleteBodies.get(0).path("conclusion").asText());
        }
    }
}
