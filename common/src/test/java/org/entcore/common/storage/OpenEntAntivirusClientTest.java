/*
 * Copyright © Open ENT, 2026
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public
 * License as published by the Free Software Foundation, version 3 of the License.
 */

package org.entcore.common.storage;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.entcore.common.storage.impl.OpenEntAntivirusClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vérifie le contrat entre l'ENT et le service antivirus : c'est {@code blocked} qui décide
 * du refus d'une pièce jointe, et un service en panne ne bloque l'upload que si
 * {@code failOnError} le demande.
 */
@RunWith(VertxUnitRunner.class)
public class OpenEntAntivirusClientTest {

	private Vertx vertx;
	private int port;
	private Path file;
	/** Réponse que le faux service renverra à la prochaine requête /scan/path. */
	private final AtomicReference<JsonObject> nextResponse = new AtomicReference<>();
	private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);

	@Before
	public void setUp(TestContext context) throws Exception {
		vertx = Vertx.vertx();
		file = Files.createTempFile("antivirus-test", ".txt");
		Files.write(file, "contenu".getBytes());
		final Async async = context.async();
		vertx.createHttpServer().requestHandler(request -> {
			if ("/config".equals(request.path())) {
				request.response().putHeader("Content-Type", "application/json")
						.end(new JsonObject().put("config", new JsonObject().put("enabled", true)).encode());
				return;
			}
			request.bodyHandler(body -> request.response()
					.setStatusCode(nextStatus.get())
					.putHeader("Content-Type", "application/json")
					.end(nextResponse.get() == null ? "{}" : nextResponse.get().encode()));
		}).listen(0).onSuccess(server -> {
			port = server.actualPort();
			async.complete();
		}).onFailure(context::fail);
	}

	@After
	public void tearDown(TestContext context) throws Exception {
		Files.deleteIfExists(file);
		vertx.close().onComplete(context.asyncAssertSuccess());
	}

	private AntivirusClient client(boolean failOnError) {
		return new OpenEntAntivirusClient(vertx, new JsonObject()
				.put("url", "http://localhost:" + port)
				.put("mode", "path")
				.put("failOnError", failOnError)
				.put("timeout", 5000L));
	}

	private JsonObject metadata() {
		return new JsonObject().put("filename", "piece.pdf").put("content-type", "application/pdf").put("size", 7L);
	}

	@Test
	public void cleanFileIsNotBlocked(TestContext context) {
		nextStatus.set(200);
		nextResponse.set(new JsonObject().put("verdict", "clean").put("blocked", false));
		client(false).scanBeforeUpload(file.toString(), metadata()).onComplete(context.asyncAssertSuccess(verdict -> {
			context.assertEquals("clean", verdict.getVerdict());
			context.assertFalse(verdict.isBlocked());
		}));
	}

	@Test
	public void infectedFileIsBlocked(TestContext context) {
		nextStatus.set(200);
		nextResponse.set(new JsonObject().put("verdict", "infected").put("virus", "Eicar-Test-Signature").put("blocked", true));
		client(false).scanBeforeUpload(file.toString(), metadata()).onComplete(context.asyncAssertSuccess(verdict -> {
			context.assertTrue(verdict.isInfected());
			context.assertTrue(verdict.isBlocked());
			context.assertEquals("Eicar-Test-Signature", verdict.getVirus());
		}));
	}

	/** Mode « détection seule » : infecté mais accepté, c'est le service qui en décide. */
	@Test
	public void infectedFileIsNotBlockedInDetectOnlyMode(TestContext context) {
		nextStatus.set(200);
		nextResponse.set(new JsonObject().put("verdict", "infected").put("virus", "X").put("blocked", false));
		client(false).scanBeforeUpload(file.toString(), metadata()).onComplete(context.asyncAssertSuccess(verdict -> {
			context.assertTrue(verdict.isInfected());
			context.assertFalse(verdict.isBlocked());
		}));
	}

	@Test
	public void serviceErrorDoesNotBlockByDefault(TestContext context) {
		nextStatus.set(500);
		nextResponse.set(new JsonObject());
		client(false).scanBeforeUpload(file.toString(), metadata()).onComplete(context.asyncAssertSuccess(verdict -> {
			context.assertTrue(verdict.isError());
			context.assertFalse(verdict.isBlocked());
		}));
	}

	/** failOnError : refuser plutôt que d'accepter un fichier non analysé. */
	@Test
	public void unreachableServiceBlocksWhenFailOnError(TestContext context) {
		final AntivirusClient client = new OpenEntAntivirusClient(vertx, new JsonObject()
				// Port fermé : aucune connexion possible.
				.put("url", "http://localhost:1")
				.put("mode", "path")
				.put("failOnError", true)
				.put("timeout", 2000L)
				.put("connectTimeout", 500));
		client.scanBeforeUpload(file.toString(), metadata()).onComplete(context.asyncAssertSuccess(verdict -> {
			context.assertTrue(verdict.isError());
			context.assertTrue(verdict.isBlocked());
		}));
	}

	@Test
	public void legacyClientDoesNotSupportBlockingScan(TestContext context) {
		context.assertTrue(client(false).supportsBlockingScan());
		context.assertFalse(AntivirusClient.ScanVerdict.notScanned().isBlocked());
	}
}
