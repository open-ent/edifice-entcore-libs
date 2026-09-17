/* Copyright © "Open Digital Education", 2019
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

package org.entcore.session;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.ClusterManager;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MapSessionStore extends AbstractSessionStore {

    protected Map<String, String> sessions;
    protected Map<String, List<LoginInfo>> logins;
    /**
     * Index allégé des sessions ouvertes : sessionId -> JSON de quelques centaines d'octets
     * (identité, profil, établissements, horodatages). Il double la map {@code sessions}
     * uniquement pour permettre la supervision : parcourir {@code sessions} obligerait à
     * rapatrier chaque session complète (droits, applications, widgets…), soit plusieurs
     * dizaines de kilo-octets par utilisateur connecté.
     */
    protected Map<String, String> sessionsIndex;

    /** Délai minimal entre deux écritures de « vu à » pour une même session (cf. ActivityManager). */
    private static final long TOUCH_DELAY = 3 * 60000L;

    /**
     * Dernière écriture de « vu à » connue localement, par session. Purement local (non répliqué) :
     * il ne sert qu'à éviter une écriture dans la map partagée à chaque requête HTTP.
     */
    private final Map<String, Long> lastTouch = new ConcurrentHashMap<>();

    private static final class LoginInfo implements Serializable {
        long timerId;
        final String sessionId;

        private LoginInfo(long timerId, String sessionId) {
            this.timerId = timerId;
            this.sessionId = sessionId;
        }
    }

    public MapSessionStore(final Vertx vertx, final Boolean cluster, JsonObject config) {
        super(vertx, config, cluster);
        inactivity = new MapActivityManager(vertx, config, cluster);
        if ("true".equalsIgnoreCase(System.getenv("CLUSTERED_SESSION_MAP"))) {
            final ClusterManager cm = ((VertxInternal) vertx).getClusterManager();
            sessions = cm.getSyncMap("sessions");
            logins = cm.getSyncMap("logins");
            sessionsIndex = cm.getSyncMap("sessionsIndex");
            logger.info("Initialize session cluster maps.");
        } else {
            sessions = new HashMap<>();
            logins = new HashMap<>();
            sessionsIndex = new HashMap<>();
            logger.info("Initialize session hash maps.");
        }
    }

    @Override
    public void getSession(final String sessionId, final Handler<AsyncResult<JsonObject>> handler) {
        JsonObject session = null;
        try {
            session = unmarshal(sessions.get(sessionId));
        } catch (Exception e) {
            logger.warn("Error in deserializing hazelcast session " + sessionId);
            try {
                sessions.remove(sessionId);
            } catch (Exception e1) {
                logger.warn("Error getting object after removing hazelcast session " + sessionId);
            }
        }
        if (session != null) {
            final String userId = session.getString("userId");
            final boolean secureLocation = (session.getJsonObject("sessionMetadata") != null ? 
                    session.getJsonObject("sessionMetadata").getBoolean("secureLocation", false) : false);
            if (inactivityEnabled() && userId != null) {
                inactivity.updateLastActivity(sessionId, userId, secureLocation, ar -> {
                    if (ar.failed()) {
                        logger.error("Error when update last activity with session " + sessionId, ar.cause());
                    }
                });
            }
            touchIndex(sessionId);
            handler.handle(Future.succeededFuture(session));
        } else {
            handler.handle(Future.failedFuture(new SessionException("Session not found")));
        }
    }

    @Override
    public void listSessionsIds(String userId, Handler<AsyncResult<JsonArray>> handler) {
        final List<LoginInfo> loginInfos = logins.get(userId);
        if (loginInfos != null) {
            final JsonArray sessionIds = new JsonArray();
            for (LoginInfo loginInfo : loginInfos) {
                sessionIds.add(loginInfo.sessionId);
            }
            handler.handle(Future.succeededFuture(sessionIds));
        } else {
            handler.handle(Future.failedFuture(new SessionException("Login not found")));
        }
    }


    @Override
    public void getSessionByUserId(String userId, Handler<AsyncResult<JsonObject>> handler) {
        LoginInfo info = getLoginInfo(userId);
        if (info == null) {
            handler.handle(Future.failedFuture(new SessionException("User not found in session")));
            return;
        }
        JsonObject session = null;
        try {
            session = unmarshal(sessions.get(info.sessionId));
        } catch (Exception e) {
            logger.error("Error in deserializing hazelcast session " + info.sessionId, e);
        }
        if (session == null) {
            handler.handle(Future.failedFuture(new SessionException("Session not found")));
        } else {
            handler.handle(Future.succeededFuture(session));
        }
    }

    private LoginInfo getLoginInfo(String userId) {
        List<LoginInfo> loginInfos = logins.get(userId);
        if (loginInfos != null && !loginInfos.isEmpty()) {
            return loginInfos.get(loginInfos.size() - 1);
        }
        return null;
    }

    private LoginInfo getLoginInfo(String sessionId, String userId) {
        List<LoginInfo> loginInfos = logins.get(userId);
        LoginInfo loginInfo = null;
        if (loginInfos != null && sessionId != null) {
            for (LoginInfo i : loginInfos) {
                if (sessionId.equals(i.sessionId)) {
                    return loginInfo;
                }
            }
        }
        return null;
    }

    private JsonObject unmarshal(String s) {
        if (s != null) {
            return new JsonObject(s);
        }
        return null;
    }

    private void addLoginInfo(String userId, long timerId, String sessionId) {
        List<LoginInfo> loginInfos = logins.get(userId);
        if (loginInfos == null) {
            loginInfos = new ArrayList<>();
        }
        loginInfos.add(new LoginInfo(timerId, sessionId));
        logins.put(userId, loginInfos);
    }

    @Override
    public void putSession(String userId, String sessionId, JsonObject infos, boolean secureLocation,
            Handler<AsyncResult<Void>> handler) {
        long timerId = setTimer(userId, sessionId, secureLocation);

        try {
            sessions.put(sessionId, infos.encode());
            addLoginInfo(userId, timerId, sessionId);
            indexSession(sessionId, userId, infos, secureLocation);
            handler.handle(Future.succeededFuture());
        } catch (Exception e) {
            logger.error("Error putting session in hazelcast map", e);
            handler.handle(Future.failedFuture(new SessionException("Error putting session in hazelcast map")));
        }
    }

    private LoginInfo removeLoginInfo(String sessionId, String userId) {
        List<LoginInfo> loginInfos = logins.get(userId);
        LoginInfo loginInfo = null;
        if (loginInfos != null && sessionId != null) {
            boolean found = false;
            int idx = 0;
            for (LoginInfo i : loginInfos) {
                if (sessionId.equals(i.sessionId)) {
                    found = true;
                    break;
                }
                idx++;
            }
            if (found) {
                loginInfo = loginInfos.remove(idx);
                if (loginInfos.isEmpty()) {
                    logins.remove(userId);
                } else {
                    logins.put(userId, loginInfos);
                }
            }
        }
        return loginInfo;
    }

    @Override
    public void dropSession(String sessionId, Handler<AsyncResult<JsonObject>> handler) {
        JsonObject session = null;
        try {
            session = unmarshal(sessions.get(sessionId));
        } catch (Exception e) {
            try {
                sessions.remove(sessionId);
            } catch (Exception e1) {
                logger.error("In doDrop - Error getting object after removing hazelcast session " + sessionId, e);
            }
        }
        if (session != null) {
            JsonObject s = unmarshal(sessions.remove(sessionId));
            if (s != null) {
                final String userId = s.getString("userId");
                LoginInfo info = removeLoginInfo(sessionId, userId);
                if (info != null) {
                    vertx.cancelTimer(info.timerId);
                }
                if (handler != null) {
                    handler.handle(Future.succeededFuture(s));
                }
            } else {
                if (handler != null) {
                    handler.handle(Future.succeededFuture(session));
                }
            }
        } else {
            if (handler != null) {
                handler.handle(Future.failedFuture(new SessionException("Session not found when drop")));
            }
        }
        unindexSession(sessionId);
        if (inactivityEnabled()) {
            inactivity.removeLastActivity(sessionId, ar -> {
                if (ar.failed()) {
                    logger.error("Error when update last activity with session " + sessionId, ar.cause());
                }
            });
            dropMongoDbSession(sessionId);
        }

    }

    private JsonObject getSessionByUserId(String userId) {
        LoginInfo info = getLoginInfo(userId);
        if (info == null) { // disconnected user : ignore action
            return null;
        }
        JsonObject session = null;
        try {
            session = unmarshal(sessions.get(info.sessionId));
        } catch (Exception e) {
            logger.error("Error in deserializing hazelcast session " + info.sessionId, e);
        }
        if (session == null) {
            return null;
        }
        return session;
    }

    private JsonObject getSessionBySessionId(String sessionId) {
        JsonObject session = null;
        try {
            session = unmarshal(sessions.get(sessionId));
        } catch (Exception e) {
            logger.error("Error in deserializing hazelcast session " + sessionId, e);
        }
        if (session == null) {
            return null;
        }
        return session;
    }

    private void updateCacheAttributeByUserId(String userId, String key, Object value) throws SessionException {
        List<LoginInfo> infos = logins.get(userId);
        if (infos == null || infos.isEmpty()) {
            throw new SessionException("LoginInfo not found");
        }
        final List<LoginInfo> staleInfos = new ArrayList<>();
        for (LoginInfo info : infos) {
            try {
                JsonObject session = unmarshal(sessions.get(info.sessionId));
                if (session != null) {
                    session.getJsonObject("cache").put(key, value);
                    sessions.put(info.sessionId, session.encode());
                } else {
                    // Entrée logins orpheline (session expirée/évincée de la map sessions) :
                    // on la nettoie (self-heal) au lieu de logger une erreur récurrente.
                    staleInfos.add(info);
                    logger.debug("Stale session entry removed from logins map : " + info.sessionId);
                }
            } catch (Exception e) {
                logger.error("Error putting session in hazelcast map : " + info.sessionId, e);
            }
        }
        cleanStaleLogins(userId, infos, staleInfos);
    }

    private void removeCacheAttributeByUserId(String userId, String key) throws SessionException {
        List<LoginInfo> infos = logins.get(userId);
        if (infos == null || infos.isEmpty()) {
            throw new SessionException("LoginInfo not found");
        }
        final List<LoginInfo> staleInfos = new ArrayList<>();
        for (LoginInfo info : infos) {
            try {
                JsonObject session = unmarshal(sessions.get(info.sessionId));
                if (session != null) {
                    session.getJsonObject("cache").remove(key);
                    sessions.put(info.sessionId, session.encode());
                } else {
                    // Entrée logins orpheline (session expirée/évincée de la map sessions) :
                    // on la nettoie (self-heal) au lieu de logger une erreur récurrente.
                    staleInfos.add(info);
                    logger.debug("Stale session entry removed from logins map : " + info.sessionId);
                }
            } catch (Exception e) {
                logger.error("Error putting session in hazelcast map : " + info.sessionId, e);
            }
        }
        cleanStaleLogins(userId, infos, staleInfos);
    }

    /**
     * Retire de la map {@code logins} les entrées dont la session a disparu de la map
     * {@code sessions} (désync des deux maps Hazelcast), pour éviter le flot d'erreurs
     * « Error getting session in hazelcast map » et resynchroniser progressivement.
     */
    private void cleanStaleLogins(String userId, List<LoginInfo> infos, List<LoginInfo> staleInfos) {
        if (staleInfos.isEmpty()) {
            return;
        }
        infos.removeAll(staleInfos);
        if (infos.isEmpty()) {
            logins.remove(userId);
        } else {
            logins.put(userId, infos);
        }
    }

    @Override
    public void addCacheAttribute(String sessionId, String key, Object value, Handler<AsyncResult<Void>> handler) {
        final JsonObject session = getSessionBySessionId(sessionId);
        if (session == null) {
            handler.handle(Future.failedFuture(new SessionException("Session not found when add attribute : " + sessionId)));
            return;
        }

        session.getJsonObject("cache").put(key, value);
        try {
            sessions.put(sessionId, session.encode());
            handler.handle(Future.succeededFuture());
        } catch (Exception e) {
            logger.error("Error putting session in hazelcast map : " + sessionId, e);
            handler.handle(Future.failedFuture(new SessionException("Error putting session in hazelcast map: " + sessionId)));
        }
    }

    @Override
    public void dropCacheAttribute(String sessionId, String key, Handler<AsyncResult<Void>> handler) {
        // TODO Auto-generated method stub

    }

    @Override
    public void addCacheAttributeByUserId(String userId, String key, Object value, Handler<AsyncResult<Void>> handler) {
        JsonObject session = getSessionByUserId(userId);
        if (session == null) {
            handler.handle(Future.failedFuture(new SessionException("Session not found when add attribute : " + userId)));
            return;
        }

        try {
            updateCacheAttributeByUserId(userId, key, value);
            handler.handle(Future.succeededFuture());
        } catch (SessionException e) {
            handler.handle(Future.failedFuture(new SessionException("Session not found when update add attribute: " + userId)));
        }
    }

    @Override
    public void dropCacheAttributeByUserId(String userId, String key, Handler<AsyncResult<Void>> handler) {
        JsonObject session = getSessionByUserId(userId);
        if (session == null) {
            handler.handle(Future.failedFuture(new SessionException("Session not found when drop attribute : " + userId)));
            return;
        }

		try {
            removeCacheAttributeByUserId(userId, key);
            handler.handle(Future.succeededFuture());
        } catch (SessionException e) {
            handler.handle(Future.failedFuture(new SessionException("Session not found when update drop attribute: " + userId)));
        }
    }

    @Override
    protected void removeCacheSession(String userId, String sessionId) {
        logins.remove(userId);
        sessions.remove(sessionId);
        unindexSession(sessionId);
    }

    @Override
    public void getSessionsNumber(Handler<AsyncResult<Long>> handler) {
        handler.handle(Future.succeededFuture((long) sessionsIndex.size()));
    }

    @Override
    public void listSessions(Handler<AsyncResult<JsonArray>> handler) {
        final JsonArray result = new JsonArray();
        final List<String> stale = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : sessionsIndex.entrySet()) {
                final String sessionId = entry.getKey();
                // L'index et la map des sessions peuvent se désynchroniser (éviction, noeud perdu) :
                // une entrée sans session correspondante est purgée au lieu d'être affichée.
                if (!sessions.containsKey(sessionId)) {
                    stale.add(sessionId);
                    continue;
                }
                try {
                    final JsonObject entrySession = unmarshal(entry.getValue());
                    if (entrySession != null) {
                        result.add(entrySession);
                    }
                } catch (Exception e) {
                    logger.warn("Error deserializing session index entry " + sessionId, e);
                    stale.add(sessionId);
                }
            }
        } catch (Exception e) {
            logger.error("Error listing sessions", e);
            handler.handle(Future.failedFuture(new SessionException("Error listing sessions")));
            return;
        }
        for (String sessionId : stale) {
            unindexSession(sessionId);
        }
        handler.handle(Future.succeededFuture(result));
    }

    /**
     * Alimente l'index de supervision à partir de la session complète. On n'y recopie
     * que ce qui est affichable dans un tableau d'administration : ni droits, ni cache,
     * ni applications.
     */
    private void indexSession(String sessionId, String userId, JsonObject infos, boolean secureLocation) {
        try {
            final long now = System.currentTimeMillis();
            // Une re-création de session (recreate) réécrit la même entrée : on conserve
            // l'heure de connexion d'origine, sans quoi toutes les sessions paraîtraient neuves.
            long createdAt = now;
            final JsonObject previous = unmarshal(sessionsIndex.get(sessionId));
            if (previous != null && previous.getLong("createdAt") != null) {
                createdAt = previous.getLong("createdAt");
            }
            final JsonObject entry = new JsonObject()
                    .put("sessionId", sessionId)
                    .put("userId", userId)
                    .put("login", infos.getString("login"))
                    .put("displayName", infos.getString("username"))
                    .put("profile", infos.getString("type"))
                    .put("structures", infos.getJsonArray("structures", new JsonArray()))
                    .put("structureNames", infos.getJsonArray("structureNames", new JsonArray()))
                    .put("classNames", infos.getJsonArray("realClassesNames", new JsonArray()))
                    .put("federated", Boolean.TRUE.equals(infos.getBoolean("federated")))
                    .put("secureLocation", secureLocation)
                    .put("createdAt", createdAt)
                    .put("lastSeen", now);
            final JsonObject functions = infos.getJsonObject("functions");
            if (functions != null && !functions.isEmpty()) {
                entry.put("functions", new JsonArray(new ArrayList<>(functions.fieldNames())));
            }
            sessionsIndex.put(sessionId, entry.encode());
            lastTouch.put(sessionId, now);
        } catch (Exception e) {
            // L'index n'est qu'un confort de supervision : son échec ne doit jamais
            // empêcher l'ouverture d'une session.
            logger.warn("Error indexing session " + sessionId, e);
        }
    }

    private void unindexSession(String sessionId) {
        try {
            sessionsIndex.remove(sessionId);
        } catch (Exception e) {
            logger.warn("Error removing session index entry " + sessionId, e);
        }
        lastTouch.remove(sessionId);
    }

    /**
     * Rafraîchit le « vu à » de la session, au plus une fois toutes les {@link #TOUCH_DELAY}
     * millisecondes : {@code getSession} est appelé à chaque requête authentifiée, une écriture
     * systématique dans la map partagée coûterait un aller-retour réseau par requête.
     */
    private void touchIndex(String sessionId) {
        final long now = System.currentTimeMillis();
        final Long last = lastTouch.get(sessionId);
        if (last != null && (last + TOUCH_DELAY) > now) {
            return;
        }
        lastTouch.put(sessionId, now);
        try {
            final JsonObject entry = unmarshal(sessionsIndex.get(sessionId));
            if (entry != null) {
                sessionsIndex.put(sessionId, entry.put("lastSeen", now).encode());
            } else {
                // Session fermée depuis un autre noeud : on relâche la trace locale,
                // sans quoi lastTouch grossirait indéfiniment.
                lastTouch.remove(sessionId);
            }
        } catch (Exception e) {
            logger.warn("Error touching session index entry " + sessionId, e);
        }
    }

    @Override
    protected void updateTimerId(String userId, String sessionId, long timerId) {
        final LoginInfo loginInfo = getLoginInfo(sessionId, userId);
        if (loginInfo != null) {
            loginInfo.timerId = timerId;
        }
    }

}
