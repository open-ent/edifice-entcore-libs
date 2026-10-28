/* Copyright © "Open Digital Education", 2014
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

 *
 */

package org.entcore.common.neo4j;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.utils.ExceptionUtils;
import org.entcore.common.utils.StringUtils;

import java.net.URI;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Implémentation de {@link GraphDatabase} au-dessus de l'API REST d'ArcadeDB.
 *
 * <p>Objectif : permettre d'exécuter la base de code entcore contre ArcadeDB sans toucher
 * aux appelants, en conservant strictement le contrat de retour attendu par
 * {@link Neo4jResult} et {@link TransactionHelper}.</p>
 *
 * <p>Correspondance des API :</p>
 * <pre>
 *   Neo4j (3.x)                        ArcadeDB (26.x)
 *   POST /cypher                       POST /api/v1/query|command/{db}
 *   POST /transaction                  POST /api/v1/begin/{db}
 *   POST /transaction/{id}             POST /api/v1/command/{db}   + header arcadedb-session-id
 *   POST /transaction/{id}/commit      POST /api/v1/commit/{db}
 *   DELETE /transaction/{id}           POST /api/v1/rollback/{db}
 * </pre>
 *
 * <p>Écarts assumés, documentés ici pour la campagne de tests :</p>
 * <ul>
 *   <li>entcore identifie ses transactions par un {@code Integer}, ArcadeDB par un identifiant
 *       de session opaque : la correspondance est tenue en mémoire par cette classe. Elle est
 *       donc locale à l'instance, là où le {@code transactionId} Neo4j était porté par le serveur.</li>
 *   <li>{@code resetTransactionTimeout} n'a pas d'équivalent : ArcadeDB ne prolonge pas une
 *       transaction ouverte. L'appel est un no-op qui réussit.</li>
 *   <li>{@code unmanagedExtension} n'a pas d'équivalent et échoue explicitement.</li>
 *   <li>Les index legacy Lucene ({@code legacy-indexes}) ne sont pas créés : ils n'existent pas
 *       chez ArcadeDB, et le code qui s'en sert doit passer aux index de schéma.</li>
 * </ul>
 */
public class ArcadeDbRest implements GraphDatabase {

	private static final Logger logger = LoggerFactory.getLogger(ArcadeDbRest.class);

	/** En-tête portant l'identifiant de session, cf. {@code HttpSessionManager.ARCADEDB_SESSION_ID}. */
	static final String SESSION_HEADER = "arcadedb-session-id";

	private static final String DEFAULT_LANGUAGE = "opencypher";

	private final HttpClient httpClient;
	private final String database;
	private final String language;
	private final String authorizationHeader;
	private final boolean ro;

	/** entcore raisonne en identifiants entiers, ArcadeDB en identifiants de session. */
	private final AtomicInteger transactionSequence = new AtomicInteger();
	private final ConcurrentMap<Integer, String> sessions = new ConcurrentHashMap<>();

	private final Pattern writingClausesPattern = Pattern.compile(
			"(\\s+set\\s+|create\\s+|merge\\s+|delete\\s+|remove\\s+|foreach)", Pattern.CASE_INSENSITIVE);

	public ArcadeDbRest(URI[] uris, boolean ro, Vertx vertx, int poolSize, boolean keepAlive, JsonObject config) {
		final URI uri = uris[0];
		final HttpClientOptions options = new HttpClientOptions()
				.setDefaultHost(uri.getHost())
				.setDefaultPort(uri.getPort() > 0 ? uri.getPort() : 2480)
				.setMaxPoolSize(poolSize)
				.setKeepAlive(keepAlive);
		this.httpClient = vertx.createHttpClient(options);
		this.ro = ro;

		final JsonObject conf = config != null ? config : new JsonObject();
		this.database = conf.getString("database", databaseFromPath(uri));
		this.language = conf.getString("cypher-language", DEFAULT_LANGUAGE);

		if (conf.containsKey("username") && conf.containsKey("password")) {
			final String credentials = conf.getString("username") + ":" + conf.getString("password");
			this.authorizationHeader = "Basic " + new String(Base64.getEncoder().encode(credentials.getBytes()));
		} else {
			this.authorizationHeader = null;
		}

		if (conf.getJsonArray("legacy-indexes") != null && !conf.getJsonArray("legacy-indexes").isEmpty()) {
			logger.warn("ArcadeDB ne gère pas les index legacy Lucene : la configuration 'legacy-indexes' est ignorée. " +
					"Les requêtes utilisant START ... node_auto_index(...) doivent être reprises.");
		}
		logger.info("ArcadeDB graph engine enabled on " + uri + " [database=" + database + ", language=" + language + "]");
	}

	private static String databaseFromPath(URI uri) {
		final String path = uri.getPath();
		if (path == null || path.isEmpty() || "/".equals(path)) {
			return "openent";
		}
		final String trimmed = path.startsWith("/") ? path.substring(1) : path;
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	@Override
	public JsonObject getMetrics() {
		return new JsonObject()
				.put("arcadedb_open_transactions", sessions.size());
	}

	// ------------------------------------------------------------------ HTTP

	private Future<JsonObject> post(String path, JsonObject body, String sessionId) {
		return httpClient.request(HttpMethod.POST, path)
				.map(request -> {
					request.headers()
							.add("Content-Type", "application/json")
							.add("Accept", "application/json; charset=UTF-8");
					if (!StringUtils.isEmpty(authorizationHeader)) {
						request.headers().add("Authorization", authorizationHeader);
					}
					if (!StringUtils.isEmpty(sessionId)) {
						request.headers().add(SESSION_HEADER, sessionId);
					}
					return request;
				})
				.flatMap(request -> request.send(body == null ? "{}" : body.encode()))
				.flatMap(this::readResponse);
	}

	/**
	 * Normalise la réponse ArcadeDB. En cas d'erreur HTTP, remonte un objet portant
	 * {@code message}, seule convention d'erreur comprise par {@link Neo4jResult}.
	 */
	private Future<JsonObject> readResponse(HttpClientResponse response) {
		return response.body().map(buffer -> {
			final String raw = buffer.toString("UTF-8");
			if (logger.isDebugEnabled()) {
				logger.debug(raw);
			}
			final int status = response.statusCode();
			JsonObject json = null;
			try {
				json = raw.isEmpty() ? new JsonObject() : new JsonObject(raw);
			} catch (Exception e) {
				// réponse non JSON : on la remonte telle quelle dans le message d'erreur
			}
			if (status >= 200 && status < 300) {
				return json != null ? json.put("__sessionId", sessionIdOf(response)) : new JsonObject();
			}
			final String detail = json != null ? json.getString("detail", json.getString("error", raw)) : raw;
			return new JsonObject().put("message", response.statusMessage() + " : " + detail);
		});
	}

	private static String sessionIdOf(HttpClientResponse response) {
		final String id = response.getHeader(SESSION_HEADER);
		return id == null ? "" : id;
	}

	private static boolean isError(JsonObject response) {
		return response != null && response.containsKey("message");
	}

	/**
	 * ArcadeDB renvoie déjà les enregistrements sous forme d'objets {colonne: valeur},
	 * là où Neo4j sépare {@code columns} et {@code data}. Il n'y a donc pas de transposition
	 * à faire, seulement à extraire le tableau.
	 *
	 * <p>Chaque ligne est en revanche réemballée dans
	 * {@link fr.wseduc.webutils.collections.JsonObject}, comme le fait {@link Neo4jRest} :
	 * cette sous-classe rend la valeur par défaut quand la propriété vaut {@code null},
	 * là où {@code io.vertx.core.json.JsonObject.getBoolean(k, def)} rend {@code null}
	 * dès que la clé existe. Les appelants d'entcore écrivent {@code getBoolean("x", false)}
	 * et déréférencent le résultat sans contrôle — sans ce réemballage, toute colonne
	 * ramenée à {@code null} par un {@code OPTIONAL MATCH} provoque un NullPointerException
	 * (constaté sur {@code blockedProfile} à la connexion).</p>
	 */
	private static JsonArray rowsOf(JsonObject response) {
		final JsonArray result = response.getJsonArray("result");
		if (result == null) {
			return new fr.wseduc.webutils.collections.JsonArray();
		}
		final JsonArray out = new fr.wseduc.webutils.collections.JsonArray();
		for (Object row : result) {
			if (row instanceof JsonObject) {
				out.add(new fr.wseduc.webutils.collections.JsonObject(((JsonObject) row).getMap()));
			} else {
				out.add(row);
			}
		}
		return out;
	}

	private JsonObject commandBody(String statement, JsonObject parameters) {
		final JsonObject body = new JsonObject()
				.put("language", language)
				.put("command", statement);
		if (parameters != null && !parameters.isEmpty()) {
			body.put("params", parameters);
		}
		return body;
	}

	/** ArcadeDB expose /query pour la lecture seule et /command pour l'écriture. */
	private String endpointFor(String statement, boolean forceReadOnly) {
		final boolean readOnly = forceReadOnly || ro
				|| (statement != null && !writingClausesPattern.matcher(statement).find());
		return (readOnly ? "/api/v1/query/" : "/api/v1/command/") + database;
	}

	// --------------------------------------------------------------- execute

	@Override
	public void execute(String query, JsonObject params, Handler<JsonObject> handler) {
		try {
			post(endpointFor(query, false), commandBody(query, params), null)
					.onSuccess(response -> {
						if (isError(response)) {
							handler.handle(response);
						} else {
							handler.handle(new JsonObject().put("result", rowsOf(response)));
						}
					})
					.onFailure(th -> {
						logger.error("ArcadeDB execution failed", th);
						handler.handle(ExceptionUtils.exceptionToJson(th));
					});
		} catch (Exception e) {
			logger.error("ArcadeDB execution failed", e);
			handler.handle(ExceptionUtils.exceptionToJson(e));
		}
	}

	/**
	 * ArcadeDB n'a pas d'équivalent du {@code /batch} Neo4j (son endpoint /api/v1/batch est un
	 * import en masse, pas un lot d'instructions). Les requêtes sont donc enchaînées
	 * séquentiellement, en conservant l'index attendu par les appelants.
	 */
	@Override
	public void executeBatch(JsonArray queries, Handler<JsonObject> handler) {
		final JsonArray out = new fr.wseduc.webutils.collections.JsonArray();
		chainBatch(queries, 0, out, handler);
	}

	private void chainBatch(JsonArray queries, int index, JsonArray out, Handler<JsonObject> handler) {
		if (index >= queries.size()) {
			handler.handle(new JsonObject().put("results", out));
			return;
		}
		final JsonObject query = queries.getJsonObject(index);
		final String statement = query.getString("query", query.getString("statement"));
		final JsonObject params = query.getJsonObject("params", query.getJsonObject("parameters"));
		post(endpointFor(statement, false), commandBody(statement, params), null)
				.onSuccess(response -> {
					if (isError(response)) {
						handler.handle(response);
						return;
					}
					out.add(new JsonObject().put("result", rowsOf(response)).put("idx", (long) index));
					chainBatch(queries, index + 1, out, handler);
				})
				.onFailure(th -> {
					logger.error("ArcadeDB batch failed at statement " + index, th);
					handler.handle(ExceptionUtils.exceptionToJson(th));
				});
	}

	// ----------------------------------------------------------- transaction

	@Override
	public void executeTransaction(JsonArray statements, Integer transactionId,
								   boolean commit, Handler<JsonObject> handler) {
		executeTransaction(statements, transactionId, commit, true, false, handler);
	}

	@Override
	public void executeTransaction(JsonArray statements, Integer transactionId,
								   boolean commit, boolean allowRetry, boolean forceReadOnly,
								   Handler<JsonObject> handler) {
		if (transactionId != null) {
			final String sessionId = sessions.get(transactionId);
			if (sessionId == null) {
				handler.handle(new JsonObject().put("message",
						"Unknown ArcadeDB transaction id : " + transactionId));
				return;
			}
			runStatements(statements, transactionId, sessionId, commit, forceReadOnly, handler);
			return;
		}
		// Pas de transaction ouverte : on en ouvre une, quitte à la valider immédiatement.
		post("/api/v1/begin/" + database, new JsonObject(), null)
				.onSuccess(response -> {
					if (isError(response)) {
						handler.handle(response);
						return;
					}
					final String sessionId = response.getString("__sessionId", "");
					if (sessionId.isEmpty()) {
						handler.handle(new JsonObject().put("message",
								"ArcadeDB did not return a session id on begin"));
						return;
					}
					final int newId = transactionSequence.incrementAndGet();
					sessions.put(newId, sessionId);
					runStatements(statements, newId, sessionId, commit, forceReadOnly, handler);
				})
				.onFailure(th -> {
					logger.error("ArcadeDB begin transaction failed", th);
					handler.handle(ExceptionUtils.exceptionToJson(th));
				});
	}

	private void runStatements(JsonArray statements, int transactionId, String sessionId,
							   boolean commit, boolean forceReadOnly, Handler<JsonObject> handler) {
		final JsonArray results = new fr.wseduc.webutils.collections.JsonArray();
		chainStatements(statements, 0, results, sessionId, forceReadOnly, outcome -> {
			if (isError(outcome)) {
				// On libère la transaction pour ne pas laisser de session pendante côté serveur.
				rollbackQuietly(transactionId, sessionId);
				handler.handle(outcome);
				return;
			}
			if (!commit) {
				handler.handle(new JsonObject()
						.put("results", results)
						.put("transactionId", transactionId));
				return;
			}
			post("/api/v1/commit/" + database, new JsonObject(), sessionId)
					.onSuccess(response -> {
						sessions.remove(transactionId);
						if (isError(response)) {
							handler.handle(response);
						} else {
							handler.handle(new JsonObject().put("results", results));
						}
					})
					.onFailure(th -> {
						sessions.remove(transactionId);
						logger.error("ArcadeDB commit failed", th);
						handler.handle(ExceptionUtils.exceptionToJson(th));
					});
		});
	}

	private void chainStatements(JsonArray statements, int index, JsonArray results, String sessionId,
								 boolean forceReadOnly, Handler<JsonObject> done) {
		if (statements == null || index >= statements.size()) {
			done.handle(new JsonObject());
			return;
		}
		final JsonObject element = statements.getJsonObject(index);
		final String statement = element.getString("statement", element.getString("query"));
		final JsonObject parameters = element.getJsonObject("parameters", element.getJsonObject("params"));
		post(endpointFor(statement, forceReadOnly), commandBody(statement, parameters), sessionId)
				.onSuccess(response -> {
					if (isError(response)) {
						done.handle(response);
						return;
					}
					results.add(rowsOf(response));
					chainStatements(statements, index + 1, results, sessionId, forceReadOnly, done);
				})
				.onFailure(th -> {
					logger.error("ArcadeDB statement failed at index " + index, th);
					done.handle(ExceptionUtils.exceptionToJson(th));
				});
	}

	private void rollbackQuietly(int transactionId, String sessionId) {
		sessions.remove(transactionId);
		post("/api/v1/rollback/" + database, new JsonObject(), sessionId)
				.onFailure(th -> logger.warn("ArcadeDB rollback after failure did not succeed", th));
	}

	/**
	 * Sans équivalent chez ArcadeDB : une transaction ouverte n'a pas de délai prolongeable.
	 * L'appel réussit sans rien faire pour ne pas casser les appelants.
	 */
	@Override
	public void resetTransactionTimeout(int transactionId, Handler<JsonObject> handler) {
		if (!sessions.containsKey(transactionId)) {
			handler.handle(new JsonObject().put("message",
					"Unknown ArcadeDB transaction id : " + transactionId));
			return;
		}
		handler.handle(new JsonObject().put("results", new fr.wseduc.webutils.collections.JsonArray())
				.put("transactionId", transactionId));
	}

	@Override
	public void rollbackTransaction(int transactionId, Handler<JsonObject> handler) {
		final String sessionId = sessions.remove(transactionId);
		if (sessionId == null) {
			handler.handle(new JsonObject().put("message",
					"Unknown ArcadeDB transaction id : " + transactionId));
			return;
		}
		post("/api/v1/rollback/" + database, new JsonObject(), sessionId)
				.onSuccess(handler::handle)
				.onFailure(th -> {
					logger.error("ArcadeDB rollback failed", th);
					handler.handle(ExceptionUtils.exceptionToJson(th));
				});
	}

	@Override
	public void unmanagedExtension(String method, String uri, String body, Handler<JsonObject> handler) {
		handler.handle(new JsonObject().put("message",
				"Unmanaged extensions are a Neo4j server plugin mechanism and have no ArcadeDB equivalent : " + uri));
	}

	@Override
	public void close() {
		httpClient.close();
	}
}
