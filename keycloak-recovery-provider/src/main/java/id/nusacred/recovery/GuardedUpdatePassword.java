package id.nusacred.recovery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.requiredactions.UpdatePassword;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.util.JsonSerialization;
import org.keycloak.connections.jpa.JpaConnectionProvider;

/** Replaces UPDATE_PASSWORD, including email reset and authenticated required actions.
 * Administrative credential writes must remain restricted to the offline bootstrap operator.
 */
public final class GuardedUpdatePassword extends UpdatePassword {
    private static final String NOTE = "nusacred-recovery-operation";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Override public int order() { return 100; }
    @Override public RequiredActionProvider create(KeycloakSession session) { return new GuardedUpdatePassword(); }

    private static Map<String, Object> coordinate(String action, String subject, String operation, Long revision) throws Exception {
        String origin = System.getenv("RECOVERY_COORDINATOR_ORIGIN");
        String issuer = System.getenv("RECOVERY_ISSUER");
        if (!("http://custodian:8080".equals(origin) || "http://recovery-fault:8080".equals(origin)) || issuer == null || !issuer.startsWith("https://")) {
            throw new IllegalStateException("Recovery coordinator configuration is missing");
        }
        byte[] secret = Files.readString(Path.of(System.getenv("RECOVERY_SECRET_FILE"))).trim().getBytes(StandardCharsets.US_ASCII);
        if (secret.length < 43) throw new IllegalStateException("Recovery service secret is too short");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("issuer", issuer); values.put("subject", subject); values.put("operation", operation);
        if ("begin".equals(action)) values.put("idp_write_fenced", true);
        if (revision != null) values.put("revision", revision);
        String body = JsonSerialization.writeValueAsString(values);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String path = "/internal/recoveries/" + action;
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal((timestamp + "\n" + path + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("X-Recovery-Time", timestamp)
            .header("X-Recovery-Signature", signature).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<java.io.InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var stream = response.body()) { bytes = stream.readNBytes(4097); }
        if (response.statusCode() != 200 || bytes.length > 4096) throw new IllegalStateException("Recovery acknowledgement unavailable");
        @SuppressWarnings("unchecked") Map<String, Object> result = JsonSerialization.readValue(bytes, Map.class);
        if (!operation.equals(result.get("operation"))) throw new IllegalStateException("Recovery acknowledgement mismatch");
        return result;
    }

    @Override public void processAction(RequiredActionContext context) {
        // Keycloak validates the email action token / authenticated required-action session before entering here.
        if (!"competency-proof-p2".equals(context.getRealm().getName()) || context.getUser() == null) {
            context.failure(); return;
        }
        var auth = context.getAuthenticationSession();
        String operation = auth.getAuthNote(NOTE);
        if (operation == null) {
            // Stable across rollback of the same validated flow, even if its auth note was lost.
            // Separate authentication sessions remain distinct and cannot supersede a locked recovery.
            String identity = context.getRealm().getId() + "\n" + context.getUser().getId() + "\n"
                + auth.getParentSession().getId() + "\n" + auth.getTabId();
            try {
                operation = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception impossible) { context.failure(); return; }
            auth.setAuthNote(NOTE, operation);
        }
        final String op = operation;
        final String subject = context.getUser().getId();
        try {
            // PostgreSQL transaction-scoped fencing serializes every guarded password write
            // for this realm/account, including older browser flows resuming after a newer reset.
            // On rollback/crash PostgreSQL releases it. No application SQLite transaction is held.
            byte[] identityHash = MessageDigest.getInstance("SHA-256").digest(
                (context.getRealm().getId() + "\n" + subject).getBytes(StandardCharsets.UTF_8));
            long lockKey = ByteBuffer.wrap(identityHash).getLong();
            var entityManager = context.getSession().getProvider(JpaConnectionProvider.class).getEntityManager();
            entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:account_lock)")
                .setParameter("account_lock", lockKey).setHint("jakarta.persistence.query.timeout", 10000)
                .getSingleResult();
            Map<String, Object> ack = coordinate("begin", subject, op, null);
            long revision = ((Number) ack.get("revision")).longValue();
            // The database acknowledgement above is mandatory before the upstream password write.
            super.processAction(context);
            if (context.getStatus() != RequiredActionContext.Status.SUCCESS) return;
            context.getSession().sessions().removeUserSessions(context.getRealm(), context.getUser());
            // Committed with the password and revocation; an offline reconciler can verify this marker.
            // The realm user profile must restrict this attribute to administrators.
            context.getUser().setSingleAttribute("nusacred_recovery_commit", op + ":" + revision);
            // End this reset flow; a new browser login is required and same-second tokens remain denied.
            auth.setAuthNote(AuthenticationManager.END_AFTER_REQUIRED_ACTIONS, "true");
            context.getSession().getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
                @Override protected void commitImpl() {
                    try { coordinate("complete", subject, op, revision); }
                    catch (Exception ignored) {
                        // An ambiguous/lost callback deliberately leaves the persistent gate closed.
                        System.getLogger(GuardedUpdatePassword.class.getName()).log(System.Logger.Level.ERROR,
                            "Recovery completion acknowledgement unavailable; reconciliation required");
                    }
                }
                @Override protected void rollbackImpl() { /* The independent registry gate stays closed. */ }
            });
        } catch (Exception ignored) {
            context.getSession().getTransactionManager().setRollbackOnly();
            context.challenge(context.form().setError("Recovery is temporarily unavailable. Please try again later.")
                .createErrorPage(jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE));
        }
    }
}
