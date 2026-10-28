package org.entcore.common.neo4j;


import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sonde d'intégration manuelle : exerce {@link ArcadeDbRest} contre un serveur ArcadeDB réel.
 *
 * <p>Volontairement un {@code main} et non un test JUnit : elle exige un serveur joignable
 * et n'a donc pas sa place dans la construction automatique. Lancer un conteneur puis :</p>
 *
 * <pre>
 *   docker run --rm -p 2480:2480 \\
 *     -e ARCADEDB_SETTINGS="-Darcadedb.server.rootPassword=playwithdata" \\
 *     arcadedata/arcadedb:latest
 *   # puis créer la base : POST /api/v1/server {"command":"create database openent"}
 * </pre>
 */
public class ArcadeDbRestProbe {

    static final AtomicInteger ko = new AtomicInteger();

    static void check(String label, boolean ok, Object detail) {
        System.out.printf("%-46s %s%n", label, ok ? "OK" : "ECHEC  <- " + detail);
        if (!ok) ko.incrementAndGet();
    }

    public static void main(String[] args) throws Exception {
        final Vertx vertx = Vertx.vertx();
        final JsonObject config = new JsonObject()
                .put("database", "openent")
                .put("username", "root")
                .put("password", "playwithdata");
        final ArcadeDbRest db = new ArcadeDbRest(
                new URI[]{new URI("http://localhost:2480")}, false, vertx, 4, true, config);

        final CountDownLatch done = new CountDownLatch(1);

        // 0. nettoyage : la sonde doit pouvoir être relancée sans fausser les comptages
        db.execute("MATCH (u:Probe) DETACH DELETE u", new JsonObject(), r0 ->
        // 1. execute simple, lecture
        db.execute("RETURN 1 AS n", new JsonObject(), r1 -> {
            check("execute / lecture simple",
                    r1.getJsonArray("result") != null
                            && r1.getJsonArray("result").getJsonObject(0).getInteger("n") == 1, r1);

            // 2. execute avec paramètres, écriture
            db.execute("CREATE (u:Probe {id: $id, name: $name}) RETURN u.id AS id",
                    new JsonObject().put("id", "p1").put("name", "Bob"), r2 -> {
                check("execute / ecriture parametree",
                        r2.getJsonArray("result") != null
                                && "p1".equals(r2.getJsonArray("result").getJsonObject(0).getString("id")), r2);

                // 3. executeBatch
                final JsonArray batch = new JsonArray()
                        .add(new JsonObject().put("query", "CREATE (u:Probe {id: $id}) RETURN u.id AS id")
                                .put("params", new JsonObject().put("id", "p2")))
                        .add(new JsonObject().put("query", "MATCH (u:Probe) RETURN count(u) AS c")
                                .put("params", new JsonObject()));
                db.executeBatch(batch, r3 -> {
                    final JsonArray results = r3.getJsonArray("results");
                    check("executeBatch / 2 requetes + index",
                            results != null && results.size() == 2
                                    && results.getJsonObject(1).getLong("idx") == 1L, r3);

                    // 4. transaction non validée -> transactionId rendu
                    final JsonArray stmts = new JsonArray().add(new JsonObject()
                            .put("statement", "CREATE (u:Probe {id: $id}) RETURN u.id AS id")
                            .put("parameters", new JsonObject().put("id", "p3")));
                    db.executeTransaction(stmts, null, false, r4 -> {
                        final Integer txId = r4.getInteger("transactionId");
                        check("executeTransaction / ouverture sans commit", txId != null, r4);
                        if (txId == null) { done.countDown(); return; }

                        // 5. second statement dans la même transaction, puis commit
                        final JsonArray more = new JsonArray().add(new JsonObject()
                                .put("statement", "CREATE (u:Probe {id: $id}) RETURN u.id AS id")
                                .put("parameters", new JsonObject().put("id", "p4")));
                        db.executeTransaction(more, txId, true, r5 -> {
                            check("executeTransaction / reprise + commit",
                                    r5.getJsonArray("results") != null && !r5.containsKey("message"), r5);

                            // 6. les deux noeuds de la transaction sont bien persistés
                            db.execute("MATCH (u:Probe) WHERE u.id IN ['p3','p4'] RETURN count(u) AS c",
                                    new JsonObject(), r6 -> {
                                check("transaction / donnees persistees",
                                        r6.getJsonArray("result") != null
                                                && r6.getJsonArray("result").getJsonObject(0).getLong("c") == 2L, r6);

                                // 7. rollback : la donnée ne doit pas survivre
                                final JsonArray rb = new JsonArray().add(new JsonObject()
                                        .put("statement", "CREATE (u:Probe {id: 'p5'}) RETURN u.id AS id")
                                        .put("parameters", new JsonObject()));
                                db.executeTransaction(rb, null, false, r7 -> {
                                    final Integer rbId = r7.getInteger("transactionId");
                                    if (rbId == null) { check("rollback / ouverture", false, r7); done.countDown(); return; }
                                    db.rollbackTransaction(rbId, r8 -> {
                                        check("rollbackTransaction / appel", !r8.containsKey("message"), r8);
                                        db.execute("MATCH (u:Probe {id:'p5'}) RETURN count(u) AS c",
                                                new JsonObject(), r9 -> {
                                            check("rollback / donnee bien annulee",
                                                    r9.getJsonArray("result") != null
                                                            && r9.getJsonArray("result").getJsonObject(0).getLong("c") == 0L, r9);

                                            // 8. erreur Cypher -> contrat {message}
                                            db.execute("THIS IS NOT CYPHER", new JsonObject(), r10 -> {
                                                check("erreur syntaxe -> contrat {message}",
                                                        r10.containsKey("message"), r10);
                                                done.countDown();
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            });
        }));

        if (!done.await(60, TimeUnit.SECONDS)) {
            System.out.println("TIMEOUT : la sonde n'a pas terminé");
            ko.incrementAndGet();
        }
        db.close();
        vertx.close();
        System.out.println("\n" + (ko.get() == 0 ? "Toutes les sondes passent." : ko.get() + " sonde(s) en echec."));
        System.exit(ko.get() == 0 ? 0 : 1);
    }
}
