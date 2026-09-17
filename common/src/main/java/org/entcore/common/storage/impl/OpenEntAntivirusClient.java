/*
 * Copyright © Open ENT, 2026
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public
 * License as published by the Free Software Foundation, version 3 of the License.
 */

package org.entcore.common.storage.impl;

import fr.wseduc.webutils.DefaultAsyncResult;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.storage.AntivirusClient;

import java.net.URI;

import static fr.wseduc.webutils.Utils.isNotEmpty;

/**
 * Client du service antivirus dédié d'Open ENT (voir {@code services/antivirus} dans
 * open-ent-mods) : une API HTTP devant ClamAV, qui rend un verdict SYNCHRONE et porte la
 * politique d'analyse (activation, blocage ou détection seule, plafonds, exclusions).
 *
 * <p>La politique n'est volontairement pas dans {@code ent-core.yaml} : elle est éditée
 * depuis le dashboard (/admin/configuration/openent, onglet « Antivirus ») et appliquée par
 * le service lui-même, sans redémarrage de l'ENT. Ici on ne configure que le raccordement :
 *
 * <pre>
 * file-system:
 *   antivirus:
 *     url: http://antivirus:3550
 *     mode: path          # path (volume partagé, défaut) | stream
 *     failOnError: false  # true = refuser l'upload quand le service est injoignable
 *     timeout: 35000
 * </pre>
 *
 * <p>Deux modes : {@code path} — le service lit le fichier sur le volume de stockage monté
 * chez lui, rien ne transite sur le réseau ; {@code stream} — le contenu est poussé dans le
 * corps de la requête, pour un stockage non partagé.
 */
public class OpenEntAntivirusClient implements AntivirusClient {

	private static final Logger log = LoggerFactory.getLogger(OpenEntAntivirusClient.class);

	/** Durée de vie du cache de la politique : borne le délai de prise en compte d'un
	 *  changement fait dans le dashboard, sans un appel HTTP de plus par upload. */
	private static final long CONFIG_CACHE_MS = 30_000L;

	private final Vertx vertx;
	private final HttpClient httpClient;
	private final String basePath;
	private final boolean streamMode;
	private final boolean failOnError;
	private final long timeout;

	private volatile boolean cachedEnabled = true;
	/** Plafond de taille de la politique ; -1 = inconnu (service pas encore interrogé). */
	private volatile long cachedMaxBytes = -1L;
	private volatile long cachedConfigAt = 0L;

	public OpenEntAntivirusClient(Vertx vertx, JsonObject conf) {
		this.vertx = vertx;
		final URI uri = URI.create(conf.getString("url").trim());
		final boolean ssl = "https".equalsIgnoreCase(uri.getScheme());
		final int port = uri.getPort() > 0 ? uri.getPort() : (ssl ? 443 : 80);
		final String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
		this.basePath = path;
		this.streamMode = "stream".equalsIgnoreCase(conf.getString("mode", "path"));
		this.failOnError = conf.getBoolean("failOnError", false);
		this.timeout = conf.getLong("timeout", 35_000L);
		this.httpClient = vertx.createHttpClient(new HttpClientOptions()
				.setDefaultHost(uri.getHost())
				.setDefaultPort(port)
				.setSsl(ssl)
				.setMaxPoolSize(conf.getInteger("poolSize", 16))
				.setConnectTimeout(conf.getInteger("connectTimeout", 5_000))
				.setIdleTimeout((int) (this.timeout / 1000) + 10)
				.setKeepAlive(true));
		log.info("Antivirus Open ENT configuré sur " + uri + " (mode " + (streamMode ? "stream" : "path")
				+ ", failOnError=" + failOnError + ")");
	}

	@Override
	public boolean supportsBlockingScan() {
		return true;
	}

	@Override
	public Future<ScanVerdict> scanBeforeUpload(String path, JsonObject metadata) {
		if (!isNotEmpty(path)) {
			return Future.succeededFuture(ScanVerdict.notScanned());
		}
		return refreshPolicy().compose(enabled -> {
			if (Boolean.FALSE.equals(enabled)) {
				return Future.succeededFuture(ScanVerdict.notScanned());
			}
			// En mode flux, inutile d'envoyer un fichier que le service va refuser d'analyser :
			// on économise la copie réseau (et la mémoire du service) sur les gros fichiers.
			if (streamMode && cachedMaxBytes > 0 && metadata != null) {
				final Long size = metadata.getLong("size");
				if (size != null && size > cachedMaxBytes) {
					return Future.succeededFuture(ScanVerdict.notScanned());
				}
			}
			return streamMode ? scanStream(path, metadata) : scanPath(path, metadata);
		});
	}

	/**
	 * Rafraîchit (avec cache) la politique du service : analyse active, plafond de taille.
	 * Sert à éviter l'aller-retour — et surtout, en mode flux, la copie du fichier — quand
	 * l'analyse est désactivée ou le fichier hors plafond. Le service reste la référence :
	 * en cas d'incertitude on l'appelle et c'est lui qui répond.
	 */
	private Future<Boolean> refreshPolicy() {
		final long now = System.currentTimeMillis();
		if (now - cachedConfigAt < CONFIG_CACHE_MS) {
			return Future.succeededFuture(cachedEnabled);
		}
		return httpClient.request(new RequestOptions()
						.setMethod(HttpMethod.GET)
						.setURI(basePath + "/config")
						.setTimeout(5_000L))
				.compose(req -> req.send())
				.compose(resp -> resp.body().map(body -> {
					if (resp.statusCode() == 200) {
						final JsonObject conf = new JsonObject(body.toString()).getJsonObject("config", new JsonObject());
						cachedEnabled = conf.getBoolean("enabled", true);
						final Integer maxMB = conf.getInteger("maxFileSizeMB");
						cachedMaxBytes = maxMB == null ? -1L : maxMB.longValue() * 1024L * 1024L;
					} else {
						cachedEnabled = true;
						cachedMaxBytes = -1L;
					}
					cachedConfigAt = System.currentTimeMillis();
					return cachedEnabled;
				}))
				.recover(th -> {
					// Service injoignable : on tente quand même l'analyse, c'est scanPath/scanStream
					// qui produira le verdict d'erreur et appliquera failOnError.
					cachedEnabled = true;
					cachedMaxBytes = -1L;
					cachedConfigAt = System.currentTimeMillis();
					return Future.succeededFuture(true);
				});
	}

	private Future<ScanVerdict> scanPath(String path, JsonObject metadata) {
		final JsonObject payload = new JsonObject()
				.put("path", path)
				.put("filename", filename(metadata))
				.put("contentType", metadata == null ? null : metadata.getString("content-type"))
				.put("size", metadata == null ? null : metadata.getLong("size"))
				.put("app", metadata == null ? null : metadata.getString("application"))
				.put("userId", metadata == null ? null : metadata.getString("owner"));

		return httpClient.request(new RequestOptions()
						.setMethod(HttpMethod.POST)
						.setURI(basePath + "/scan/path")
						.setTimeout(timeout))
				.compose(req -> {
					req.putHeader("Content-Type", "application/json");
					return req.send(payload.encode());
				})
				.compose(this::toVerdict)
				.otherwise(th -> onFailure(path, th));
	}

	private Future<ScanVerdict> scanStream(String path, JsonObject metadata) {
		return vertx.fileSystem().open(path, new OpenOptions().setRead(true))
				.compose(file -> httpClient.request(new RequestOptions()
								.setMethod(HttpMethod.POST)
								.setURI(basePath + "/scan/stream")
								.setTimeout(timeout))
						.compose(req -> {
							req.putHeader("Content-Type", "application/octet-stream");
							req.putHeader("X-Openent-Filename", header(filename(metadata)));
							if (metadata != null) {
								req.putHeader("X-Openent-Content-Type", header(metadata.getString("content-type")));
								req.putHeader("X-Openent-App", header(metadata.getString("application")));
								req.putHeader("X-Openent-User", header(metadata.getString("owner")));
							}
							return req.send(file);
						})
						// Le descripteur doit être refermé quel que soit le sort de la requête.
						.onComplete(ar -> file.close()))
				.compose(this::toVerdict)
				.otherwise(th -> onFailure(path, th));
	}

	private Future<ScanVerdict> toVerdict(io.vertx.core.http.HttpClientResponse resp) {
		return resp.body().map(body -> {
			if (resp.statusCode() != 200) {
				return ScanVerdict.error("HTTP " + resp.statusCode() + " du service antivirus");
			}
			try {
				return ScanVerdict.fromResponse(new JsonObject(body.toString()));
			} catch (Exception e) {
				return ScanVerdict.error("réponse illisible du service antivirus : " + e.getMessage());
			}
		});
	}

	/**
	 * Service injoignable ou en erreur. Par défaut on laisse passer (l'ENT ne doit pas être
	 * mis à genoux par une panne du scanner) mais {@code failOnError: true} permet de choisir
	 * l'inverse — refuser plutôt que d'accepter un fichier non analysé.
	 */
	private ScanVerdict onFailure(String path, Throwable th) {
		log.error("Analyse antivirus impossible pour " + path + " : " + th.getMessage());
		if (failOnError) {
			return ScanVerdict.fromResponse(new JsonObject()
					.put("verdict", ScanVerdict.ERROR)
					.put("blocked", true)
					.put("reason", th.getMessage()));
		}
		return ScanVerdict.error(th.getMessage());
	}

	private static String filename(JsonObject metadata) {
		if (metadata == null) return "";
		final String filename = metadata.getString("filename");
		return isNotEmpty(filename) ? filename : metadata.getString("name", "");
	}

	/** Les en-têtes HTTP n'acceptent pas les octets non ASCII ni les retours à la ligne. */
	private static String header(String value) {
		if (value == null) return "";
		return value.replaceAll("[^\\x20-\\x7E]", "_");
	}

	// --- API historique : l'analyse asynchrone « après coup » n'a plus lieu d'être ici,
	// --- scanBeforeUpload rend déjà un verdict avant l'acquittement de l'upload.

	@Override
	public void scan(String path) {
		scan(path, e -> {});
	}

	@Override
	public void scan(String path, Handler<AsyncResult<Void>> handler) {
		scanBeforeUpload(path, null).onComplete(res -> handler.handle(new DefaultAsyncResult<>((Void) null)));
	}

	@Override
	public void scanS3(String id, String bucket) {
		scanS3(id, bucket, e -> {});
	}

	@Override
	public void scanS3(String id, String bucket, Handler<AsyncResult<Void>> handler) {
		// Le service dédié analyse un chemin ou un flux : il n'a pas d'accès S3. Un stockage
		// S3 doit utiliser le mode « stream » depuis l'appelant.
		log.warn("scanS3 non pris en charge par le service antivirus Open ENT (id=" + id + ", bucket=" + bucket + ")");
		handler.handle(new DefaultAsyncResult<>((Void) null));
	}
}
