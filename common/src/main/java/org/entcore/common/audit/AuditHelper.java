package org.entcore.common.audit;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * Point d'entrée des modules pour écrire dans le journal d'audit. L'écriture (chaînage, persistance)
 * est faite par un écrivain unique, dans infra, à l'adresse {@link #ADDRESS} : les modules ne
 * touchent jamais directement la collection.
 */
public final class AuditHelper {

    public static final String ADDRESS = "entcore.audit";
    private static final Logger log = LoggerFactory.getLogger(AuditHelper.class);

    private AuditHelper() {}

    public static Future<Long> publish(EventBus eb, AuditEvent event) {
        final Promise<Long> promise = Promise.promise();
        final JsonObject message = new JsonObject().put("action", "append").put("event", event.toJson());
        eb.request(ADDRESS, message, ar -> {
            final JsonObject body = ar.succeeded() ? (JsonObject) ar.result().body() : null;
            if (body != null && "ok".equals(body.getString("status"))) {
                promise.complete(body.getLong("seq"));
            } else {
                final String cause = ar.failed() ? ar.cause().getMessage()
                        : (body != null ? body.getString("message") : "no.reply");
                // L'audit ne doit pas bloquer l'opération métier, mais sa perte doit se voir.
                log.error("[Audit] entry lost : " + message.encode() + " : " + cause);
                promise.fail(cause);
            }
        });
        return promise.future();
    }
}
