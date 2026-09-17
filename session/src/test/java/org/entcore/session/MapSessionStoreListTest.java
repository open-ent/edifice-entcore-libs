package org.entcore.session;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Couvre l'index de supervision des sessions ({@code listSessions}) sur le magasin non
 * distribué : ouverture, contenu de l'entrée, fermeture, et purge des entrées orphelines.
 */
@RunWith(VertxUnitRunner.class)
public class MapSessionStoreListTest {

    private static Vertx vertx;

    @BeforeClass
    public static void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterClass
    public static void tearDown() {
        vertx.close();
    }

    private static JsonObject sessionInfos(String userId, String login) {
        return new JsonObject()
                .put("userId", userId)
                .put("login", login)
                .put("username", "Jean Dupont")
                .put("type", "Teacher")
                .put("structures", new JsonArray().add("struct-1"))
                .put("structureNames", new JsonArray().add("Collège Victor Hugo"))
                .put("realClassesNames", new JsonArray().add("6eA"))
                .put("functions", new JsonObject().put("ADMIN_LOCAL", new JsonObject()))
                .put("cache", new JsonObject())
                // Volumineux et confidentiel : ne doit jamais ressortir dans l'index.
                .put("authorizedActions", new JsonArray().add(new JsonObject().put("name", "secret")));
    }

    @Test
    public void listSessionsReturnsLightweightEntries(TestContext context) {
        final Async async = context.async();
        final MapSessionStore store = new MapSessionStore(vertx, false, new JsonObject());
        store.putSession("user-1", "session-1", sessionInfos("user-1", "jean.dupont"), true, put -> {
            context.assertTrue(put.succeeded());
            store.listSessions(list -> {
                context.assertTrue(list.succeeded());
                final JsonArray sessions = list.result();
                context.assertEquals(1, sessions.size());
                final JsonObject entry = sessions.getJsonObject(0);
                context.assertEquals("session-1", entry.getString("sessionId"));
                context.assertEquals("user-1", entry.getString("userId"));
                context.assertEquals("jean.dupont", entry.getString("login"));
                context.assertEquals("Jean Dupont", entry.getString("displayName"));
                context.assertEquals("Teacher", entry.getString("profile"));
                context.assertEquals(new JsonArray().add("struct-1"), entry.getJsonArray("structures"));
                context.assertEquals(new JsonArray().add("ADMIN_LOCAL"), entry.getJsonArray("functions"));
                context.assertTrue(entry.getBoolean("secureLocation"));
                context.assertNotNull(entry.getLong("createdAt"));
                context.assertNotNull(entry.getLong("lastSeen"));
                context.assertNull(entry.getValue("authorizedActions"));
                context.assertNull(entry.getValue("cache"));
                async.complete();
            });
        });
    }

    @Test
    public void droppedSessionDisappearsFromList(TestContext context) {
        final Async async = context.async();
        final MapSessionStore store = new MapSessionStore(vertx, false, new JsonObject());
        store.putSession("user-1", "session-1", sessionInfos("user-1", "jean.dupont"), false, put ->
            store.putSession("user-2", "session-2", sessionInfos("user-2", "marie.martin"), false, put2 ->
                store.dropSession("session-1", dropped -> {
                    context.assertTrue(dropped.succeeded());
                    store.listSessions(list -> {
                        context.assertTrue(list.succeeded());
                        context.assertEquals(1, list.result().size());
                        context.assertEquals("session-2", list.result().getJsonObject(0).getString("sessionId"));
                        async.complete();
                    });
                })));
    }

    @Test
    public void recreatedSessionKeepsItsInitialLoginTime(TestContext context) {
        final Async async = context.async();
        final MapSessionStore store = new MapSessionStore(vertx, false, new JsonObject());
        store.putSession("user-1", "session-1", sessionInfos("user-1", "jean.dupont"), false, put ->
            store.listSessions(first -> {
                final long createdAt = first.result().getJsonObject(0).getLong("createdAt");
                vertx.setTimer(5, t ->
                    store.putSession("user-1", "session-1", sessionInfos("user-1", "jean.dupont"), false, put2 ->
                        store.listSessions(second -> {
                            context.assertEquals(createdAt, second.result().getJsonObject(0).getLong("createdAt"));
                            async.complete();
                        })));
            }));
    }

    @Test
    public void orphanIndexEntryIsPurged(TestContext context) {
        final Async async = context.async();
        final MapSessionStore store = new MapSessionStore(vertx, false, new JsonObject());
        store.putSession("user-1", "session-1", sessionInfos("user-1", "jean.dupont"), false, put -> {
            // Désynchronisation des deux maps (éviction, noeud perdu) : l'entrée d'index
            // ne correspond plus à aucune session et doit être purgée, pas affichée.
            store.sessions.remove("session-1");
            store.listSessions(list -> {
                context.assertTrue(list.succeeded());
                context.assertEquals(0, list.result().size());
                context.assertEquals(0, store.sessionsIndex.size());
                async.complete();
            });
        });
    }
}
