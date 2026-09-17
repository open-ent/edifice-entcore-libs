/*
 * Copyright © "Open Digital Education", 2017
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.

 */

package org.entcore.common.storage;


import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.LoggerFactory;

import org.entcore.common.storage.impl.HttpAntivirusClient;
import org.entcore.common.storage.impl.OpenEntAntivirusClient;

import fr.wseduc.webutils.Utils;

import java.util.Optional;

import static fr.wseduc.webutils.Utils.isNotEmpty;

public interface AntivirusClient {

	void scan(String path);

	void scan(String path, Handler<AsyncResult<Void>> handler);

	void scanS3(String id, String bucket);

	void scanS3(String id, String bucket, Handler<AsyncResult<Void>> handler);

	/**
	 * Analyse BLOQUANTE d'un fichier qui vient d'être écrit sur le stockage, appelée avant
	 * que l'upload ne soit acquitté au module appelant : c'est ce qui permet de refuser une
	 * pièce jointe infectée au lieu de la remplacer après coup.
	 *
	 * Les implémentations historiques (service ODE, {@code scan(path)} en « tire et oublie »)
	 * ne savent pas faire : elles renvoient un verdict neutre et l'upload suit son cours.
	 *
	 * @param path     chemin du fichier sur le stockage
	 * @param metadata métadonnées de l'upload (name, filename, content-type, size)
	 * @return le verdict ; ne doit jamais échouer, un incident se traduit par
	 *         {@link ScanVerdict#error(String)} et c'est l'appelant qui décide d'en tenir
	 *         compte ou non.
	 */
	default Future<ScanVerdict> scanBeforeUpload(String path, JsonObject metadata) {
		return Future.succeededFuture(ScanVerdict.notScanned());
	}

	/** Le client sait-il rendre un verdict bloquant ? */
	default boolean supportsBlockingScan() {
		return false;
	}

	/**
	 * Verdict d'une analyse. {@link #isBlocked()} est le seul champ qui décide du refus :
	 * un fichier peut être infecté sans être bloqué (mode « détection seule », piloté depuis
	 * le dashboard).
	 */
	final class ScanVerdict {

		public static final String CLEAN = "clean";
		public static final String INFECTED = "infected";
		public static final String SKIPPED = "skipped";
		public static final String DISABLED = "disabled";
		public static final String ERROR = "error";
		public static final String NOT_SCANNED = "not-scanned";

		private final String verdict;
		private final String virus;
		private final boolean blocked;
		private final String reason;

		private ScanVerdict(String verdict, String virus, boolean blocked, String reason) {
			this.verdict = verdict;
			this.virus = virus;
			this.blocked = blocked;
			this.reason = reason;
		}

		public static ScanVerdict notScanned() {
			return new ScanVerdict(NOT_SCANNED, null, false, null);
		}

		public static ScanVerdict error(String reason) {
			return new ScanVerdict(ERROR, null, false, reason);
		}

		public static ScanVerdict fromResponse(JsonObject response) {
			final String verdict = response.getString("verdict", ERROR);
			return new ScanVerdict(
					verdict,
					response.getString("virus"),
					response.getBoolean("blocked", false),
					response.getString("reason"));
		}

		public String getVerdict() {
			return verdict;
		}

		public String getVirus() {
			return virus;
		}

		/** Le fichier doit-il être refusé ? */
		public boolean isBlocked() {
			return blocked;
		}

		public boolean isInfected() {
			return INFECTED.equals(verdict);
		}

		public boolean isError() {
			return ERROR.equals(verdict);
		}

		public String getReason() {
			return reason;
		}

		@Override
		public String toString() {
			return "ScanVerdict{" + verdict + (virus != null ? " (" + virus + ")" : "")
					+ (blocked ? ", bloqué" : "") + (reason != null ? ", " + reason : "") + "}";
		}
	}

	static Future<Optional<AntivirusClient>> create(Vertx vertx){
		final Promise<Optional<AntivirusClient>> promise = Promise.promise();
		vertx.sharedData().<String, String>getLocalAsyncMap("server")
			.compose(serverMap -> serverMap.get("file-system"))
			.onSuccess(s ->
				promise.complete(create(vertx, Utils.isNotEmpty(s) ?  new JsonObject(s) : new JsonObject()))
			).onFailure(promise::fail);
        return promise.future();
	}

	static Optional<AntivirusClient> create(Vertx vertx, JsonObject fs){
		try{
			if (fs != null && !fs.isEmpty()) {
				final JsonObject antivirus = fs.getJsonObject("antivirus");
				if (antivirus != null) {
					// Service antivirus dédié d'Open ENT (services/antivirus) : analyse bloquante,
					// politique pilotée depuis le dashboard. On exige une URL absolue : une
					// variable d'environnement non substituée ne doit pas donner un client cassé.
					final String url = antivirus.getString("url", "").trim();
					if (url.startsWith("http://") || url.startsWith("https://")) {
						return Optional.of(new OpenEntAntivirusClient(vertx, antivirus));
					}
					if (isNotEmpty(url)) {
						LoggerFactory.getLogger(AntivirusClient.class)
								.warn("URL d'antivirus ignorée (attendu http(s)://...) : " + url);
					}
					// Service ODE historique : analyse asynchrone après upload.
					final String h = antivirus.getString("host");
					final String c = antivirus.getString("credential");
					if (isNotEmpty(h) && isNotEmpty(c)) {
						final AntivirusClient av = new HttpAntivirusClient(vertx, h, c);
						return Optional.ofNullable(av);
					}
				}
			}
		} catch(Exception e){
			LoggerFactory.getLogger(AntivirusClient.class).warn("Could not create antivirus client: ", e);
		}
		return Optional.empty();
	}
}
