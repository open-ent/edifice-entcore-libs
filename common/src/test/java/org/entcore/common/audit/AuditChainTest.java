package org.entcore.common.audit;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AuditChainTest {

    private JsonObject entry() {
        return AuditEvent.of("ADD_FUNCTION")
                .actor("u1", "admin.local")
                .target("User", "u2")
                .after(new JsonObject().put("function", "ADMIN_LOCAL").put("scope", new JsonArray().add("s1")))
                .source(AuditEvent.Source.UI)
                .toJson()
                .put("date", 1700000000000L)
                .put("seq", 1L)
                .put("prevHash", AuditChain.GENESIS);
    }

    @Test
    public void hashDoesNotDependOnFieldOrderOrIntegerWidth() {
        final JsonObject e = entry();
        final JsonObject reordered = new JsonObject();
        // Ordre inverse et seq relu comme Integer (cas d'un aller-retour Mongo).
        final java.util.List<String> keys = new java.util.ArrayList<>(e.fieldNames());
        java.util.Collections.reverse(keys);
        for (String k : keys) reordered.put(k, e.getValue(k));
        reordered.put("seq", 1).put("_id", "mongo-id").put("expireAt", new JsonObject().put("$date", "2031-01-01"));
        assertEquals(AuditChain.hash(AuditChain.GENESIS, e), AuditChain.hash(AuditChain.GENESIS, reordered));
    }

    @Test
    public void hashChangesWhenContentIsModified() {
        final JsonObject e = entry();
        final String original = AuditChain.hash(AuditChain.GENESIS, e);
        e.getJsonObject("after").put("function", "SUPER_ADMIN");
        assertNotEquals(original, AuditChain.hash(AuditChain.GENESIS, e));
    }

    @Test
    public void hashChangesWithPreviousHash() {
        final JsonObject e = entry();
        assertNotEquals(AuditChain.hash(AuditChain.GENESIS, e), AuditChain.hash("abc", e));
    }
}
