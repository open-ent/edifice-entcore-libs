package org.entcore.broker.client;

import io.nats.vertx.NatsClient;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks the {@link NatsClient} instances managed by the running {@link BrokerClient}, so that
 * other components (e.g. {@link NATSProbe}) can read the live NATS connection state directly
 * instead of round-tripping through the event bus.
 */
public class NATSConnectionRegistry {

    private static final List<NatsClient> clients = new CopyOnWriteArrayList<>();

    private NATSConnectionRegistry() {
    }

    public static void register(final NatsClient client) {
        clients.add(client);
    }

    public static void unregister(final NatsClient client) {
        clients.remove(client);
    }

    public static List<NatsClient> getClients() {
        return Collections.unmodifiableList(clients);
    }
}
