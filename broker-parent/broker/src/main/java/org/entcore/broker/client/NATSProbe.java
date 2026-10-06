package org.entcore.broker.client;

import fr.wseduc.webutils.metrics.HealthCheckProbe;
import fr.wseduc.webutils.metrics.HealthCheckProbeResult;
import io.nats.client.Connection;
import io.nats.vertx.NatsClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

import static io.vertx.core.Future.succeededFuture;

/**
 * Health probe reporting whether the broker's NATS client(s) are connected.
 * <br>
 * Reads the {@link Connection.Status} of each {@link NatsClient} registered in
 * {@link NATSConnectionRegistry} directly, instead of round-tripping through the event bus
 * (which only verified that the local event bus was alive, not NATS itself).
 */
public class NATSProbe implements HealthCheckProbe {

    private Vertx vertx;

    @Override
    public Future<Void> init(final Vertx vertx, final JsonObject config) {
        this.vertx = vertx;
        return succeededFuture();
    }

    @Override
    public Future<HealthCheckProbeResult> probe() {
        final List<NatsClient> clients = NATSConnectionRegistry.getClients();
        if (clients.isEmpty()) {
            return succeededFuture(new HealthCheckProbeResult(getName(), false,
                new JsonObject().put("error", "No NATS client registered")));
        }

        final JsonObject connections = new JsonObject();
        boolean allConnected = true;
        for (final NatsClient client : clients) {
            final Connection connection = client.getConnection();
            final Connection.Status status = connection == null ? null : connection.getStatus();
            final String url = connection == null ? "unknown" : connection.getConnectedUrl();
            connections.put(url, status == null ? "UNKNOWN" : status.name());
            allConnected = allConnected && status == Connection.Status.CONNECTED;
        }

        return succeededFuture(new HealthCheckProbeResult(getName(), allConnected,
            allConnected ? null : new JsonObject().put("connections", connections)));
    }

    @Override
    public String getName() {
        return "nats";
    }

    @Override
    public Vertx getVertx() {
        return vertx;
    }
}
