package org.entcore.common.audit;

import io.vertx.core.json.JsonObject;

/**
 * Entrée du journal d'audit des opérations sensibles (création/suppression/blocage de compte,
 * fonctions d'administration, droits, MFA, provisioning SCIM…).
 *
 * Format commun à toutes les sources : qui (actor, onBehalfOf), quoi (action), sur quoi (target),
 * état avant/après, d'où (source, ip, correlationId). Les valeurs before/after doivent être du JSON
 * simple (pas de dates Mongo) : elles entrent dans le hachage de la chaîne (voir {@link AuditChain}).
 */
public class AuditEvent {

    public enum Source { UI, SCIM, IMPORT, JOB, SYSTEM }

    public static final int DEFAULT_RETENTION_DAYS = 1825;

    private final JsonObject json = new JsonObject();

    private AuditEvent(String action) {
        json.put("action", action).put("date", System.currentTimeMillis());
    }

    public static AuditEvent of(String action) {
        return new AuditEvent(action);
    }

    public AuditEvent actor(String userId, String login) {
        json.put("actor", new JsonObject().put("id", userId).put("login", login));
        return this;
    }

    /** Utilisateur pour le compte duquel l'action est faite (connexion « en tant que », délégation). */
    public AuditEvent onBehalfOf(String userId) {
        json.put("onBehalfOf", userId);
        return this;
    }

    public AuditEvent target(String type, String id) {
        json.put("target", new JsonObject().put("type", type).put("id", id));
        return this;
    }

    public AuditEvent before(JsonObject before) {
        json.put("before", before);
        return this;
    }

    public AuditEvent after(JsonObject after) {
        json.put("after", after);
        return this;
    }

    public AuditEvent source(Source source) {
        json.put("source", source.name());
        return this;
    }

    public AuditEvent ip(String ip) {
        json.put("ip", ip);
        return this;
    }

    /** Identifiant permettant de relier l'entrée à sa cause (jti SCIM, id de requête, id d'import). */
    public AuditEvent correlationId(String correlationId) {
        json.put("correlationId", correlationId);
        return this;
    }

    public AuditEvent application(String application) {
        json.put("application", application);
        return this;
    }

    public AuditEvent status(int status) {
        json.put("status", status);
        return this;
    }

    public AuditEvent retentionDays(int days) {
        json.put("retention-days", days);
        return this;
    }

    public JsonObject toJson() {
        final JsonObject j = json.copy();
        if (!j.containsKey("source")) j.put("source", Source.SYSTEM.name());
        if (!j.containsKey("retention-days")) j.put("retention-days", DEFAULT_RETENTION_DAYS);
        return j;
    }
}
