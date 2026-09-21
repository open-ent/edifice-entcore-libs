package org.entcore.session;

import io.vertx.redis.client.*;

import fr.wseduc.webutils.Utils;
import fr.wseduc.webutils.collections.SharedDataHelper;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Response;
import org.entcore.common.redis.RedisClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.entcore.session.RedisResponseHelper.listResponse;
import static org.entcore.session.RedisResponseHelper.mapResponse;

public class RedisSessionStore extends AbstractSessionStore {

    private static final char JSONOBJECT_TYPE = 'J';
    private static final char LONG_TYPE = 'L';
    private static final char STRING_TYPE = 'S';
    private static final String CACHE = "cache";
    private static final String SESSINFO = "sessinfo";
    static final String SESSION_KEY = "session:";
    static final String LOGIN_INFO_KEY = "loginfo:";
    private static final String SESSION_NUMBER_KEY = "sessionnumber";
    private static final long LOG_SESSION_DELAY = 500L;
    private static final long NEAR_CACHE_DELAY = 60000L;
    private static final long CLEAR_NEAR_CACHE_INTERVAL = 1800 * 1000L;
    private RedisAPI redisAPI;
    /** PubSub client for Redis (must be different from redisAPI, c.f. #SRE-780).*/
    private RedisAPI redisPubSubAPI;
    private boolean nearCache;
    private long nearCacheDelay;
    private Map<String, Long> expire = new HashMap<>();
    private Map<String, JsonObject> sessions = new HashMap<>();

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

    public RedisSessionStore(Vertx vertx, Boolean cluster, JsonObject config) {
        super(vertx, config, cluster);
        final SharedDataHelper sharedDataHelper = SharedDataHelper.getInstance();
        sharedDataHelper.init(vertx);
        sharedDataHelper.<String, Object>getMulti("server", "redisConfig"
		).onSuccess(sessionMap -> initSessionRedis(config, sessionMap)
		).onFailure(ex -> logger.error("Error when start Session server super classes", ex));
    }

    private void initSessionRedis(JsonObject config, Map<String, Object> serverMap) {
        JsonObject redisConfig = config.getJsonObject("redisConfig");
        if (redisConfig == null) {
            final String redisConf = (String) serverMap.get("redisConfig");
            if (redisConf != null) {
                redisConfig = new JsonObject(redisConf);
            }
        }
        if(redisConfig == null) {
            throw new IllegalStateException("redisConfig is nowhere to be found");
        }

        final RedisOptions redisOptions = RedisClient.getOptions(redisConfig);
        final Redis redisClient = Redis.createClient(vertx, redisOptions);

        redisAPI = RedisAPI.api(redisClient);

        inactivity = new RedisActivityManager(redisAPI, sessionTimeout, prolongedSessionTimeout, config);


        if (config.getBoolean("listen-expired-session", false)) {
            final Redis redisPubSubClient = Redis.createClient(vertx, redisOptions);
            redisPubSubAPI = RedisAPI.api(redisPubSubClient);
            listenExpiredSession();
        }

        // TODO refactor CSRF to safely use near cache
        nearCache = config.getBoolean("near-cache", false);
        if (nearCache) {
            nearCacheDelay = Utils.getOrElse(config.getLong("near-cache-delay"), NEAR_CACHE_DELAY);
            vertx.setPeriodic(CLEAR_NEAR_CACHE_INTERVAL, l -> {
                final long now = System.currentTimeMillis();
                final long delay = nearCacheDelay * 3L;
                for (Map.Entry<String, Long> e: new ArrayList<Map.Entry<String, Long>>(expire.entrySet())) {
                    if ((now - e.getValue()) > delay) {
                        removeSessionInNearCache(e.getKey());
                    }
                }
            });
        } else {
            nearCacheDelay = 0L;
        }
    }

    private void removeSessionInNearCache(String sessionId) {
        expire.remove(sessionId);
        sessions.remove(sessionId);
    }

    private void listenExpiredSession() {
        // Enable expired event notifications on redis with : config set notify-keyspace-events Ex
        vertx.eventBus().consumer("io.vertx.redis.__keyevent@0__:expired", m -> {
            final JsonObject msg = (JsonObject) m.body();
            if (inactivityEnabled() && msg.getJsonObject("value") != null &&
                msg.getJsonObject("value").getString("message") != null &&
                msg.getJsonObject("value").getString("message").startsWith(SESSION_KEY)) {
                // dropMongoDbSession(sessionId); // Comment because now (202102) mongodb session not write if inactivityEnabled
                decrSessionNumber();
                // TODO if needed add session metadata avec expire 24h
                // TODO in this event drop session metadata and remove sessionid in login info list
            }
        });
        redisPubSubAPI.subscribe(asList("__keyevent@0__:expired"), ar -> {
            if (ar.failed()) {
                logger.error("Error when subscribe expired session", ar.cause());
            } else {
                logger.debug("Subscription to expired session started");
            }
        });
    }

    @Override
    public void addCacheAttribute(String sessionId, String key, Object value, Handler<AsyncResult<Void>> handler) {
        if (key == null || key.isEmpty() || value == null) {
            handler.handle(Future.succeededFuture());
            return;
        }
        final String v = valueWithType(value);
        redisAPI.exists(asList(SESSION_KEY + sessionId), ar -> {
            if (ar.succeeded() && ar.result().toInteger() == 1) {
                redisAPI.hset(asList(SESSION_KEY + sessionId, key, v), ar2 -> {
                    if (ar2.failed()) {
                        logger.error("Error adding attribute " + key + " in session cache  " + sessionId, ar2.cause());
                        handler.handle(Future.failedFuture(ar.cause()));
                    } else {
                        handler.handle(Future.succeededFuture());

                        final JsonObject session = sessions.get(sessionId);
                        final boolean secureLocation = (session != null && session.getJsonObject("sessionMetadata") != null ?
                            session.getJsonObject("sessionMetadata").getBoolean("secureLocation", false) : false);

                        ((RedisActivityManager) inactivity).setExpireSession(sessionId, null, secureLocation, arExpire -> {
                            if (arExpire.failed()) {
                                logger.error("Error when set expire", arExpire.cause());
                            }
                        });
                    }
                });
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    @Override
    public void addCacheAttributeByUserId(String userId, String key, Object value, Handler<AsyncResult<Void>> handler) {
        if (key == null || key.isEmpty() || value == null) {
            handler.handle(Future.succeededFuture());
            return;
        }
        final String v = valueWithType(value);
        listSessionsIds(userId, ar -> {
            if (ar.succeeded()) {
                final JsonArray list = ar.result();
                if (list != null) {
                    final Handler<AsyncResult<Response>> h = ar2 -> {
                        if (ar2.failed()) {
                            logger.error("Error adding attribute " + key + " in session cache user " + userId, ar2.cause());
                        }
                        else {
                            String sessionId = ar2.result().toString();

                            final JsonObject session = sessions.get(sessionId);
                            final boolean secureLocation = (session != null && session.getJsonObject("sessionMetadata") != null ?
                                session.getJsonObject("sessionMetadata").getBoolean("secureLocation", false) : false);

                            ((RedisActivityManager) inactivity).setExpireSession(sessionId, userId, secureLocation, arExpire -> {
                                if (arExpire.failed()) {
                                    logger.error("Error when set expire", arExpire.cause());
                                }
                            });
                        }
                    };

                    for (Object o: list) {
                        redisAPI.hset(asList(SESSION_KEY + o, key, v), h);
                    }
                }
                handler.handle(Future.succeededFuture());
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    private String valueWithType(Object value) {
        final String v;
        if (value instanceof JsonObject) {
            v = JSONOBJECT_TYPE + ((JsonObject)value).encode();
        } else if (value instanceof Long) {
            v = LONG_TYPE + value.toString();
        } else {
            v = STRING_TYPE + value.toString();
        }
        return v;
    }

    @Override
    public void dropCacheAttribute(String sessionId, String key, Handler<AsyncResult<Void>> handler) {
        if (key == null || key.isEmpty()) {
            handler.handle(Future.succeededFuture());
            return;
        }
        redisAPI.exists(asList(SESSION_KEY + sessionId), ar -> {
            if (ar.succeeded() && ar.result() != null && ar.result().toInteger() == 1) {
                redisAPI.hdel(asList(SESSION_KEY + sessionId, key), ar2 -> {
                    if (ar2.failed()) {
                        logger.error("Error deleting attribute " + key + " in session cache  " + sessionId, ar2.cause());
                        handler.handle(Future.failedFuture(ar.cause()));
                    } else {
                        handler.handle(Future.succeededFuture());
                    }
                });
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    @Override
    public void dropCacheAttributeByUserId(String userId, String key, Handler<AsyncResult<Void>> handler) {
        if (key == null || key.isEmpty()) {
            handler.handle(Future.succeededFuture());
            return;
        }
        listSessionsIds(userId, ar -> {
            if (ar.succeeded()) {
                final JsonArray list = ar.result();
                if (list != null) {
                    for (Object o: list) {
                        Handler<AsyncResult<Response>> h = ar2 -> {
                            if (ar2.failed()) {
                                logger.error("Error dropping attribute " + key + " in session cache user " + userId, ar2.cause());
                            }
                        };
                        redisAPI.hdel(asList(SESSION_KEY + o, key), h);
                    }
                }
                handler.handle(Future.succeededFuture());
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    @Override
    public void dropSession(String sessionId, Handler<AsyncResult<JsonObject>> handler) {
        getSession(sessionId, ar -> {
            if (ar.succeeded()) {
                final String userId = ar.result().getString("userId");
                removeCacheSession(userId, sessionId);
            }
            if (handler != null) {
                handler.handle(ar);
            }
            if (inactivityEnabled()) {
                // dropMongoDbSession(sessionId); // Comment because now (202102) mongodb session not write if inactivityEnabled
                decrSessionNumber();
            }
        });
    }

    private void decrSessionNumber() {
        redisAPI.decr(SESSION_NUMBER_KEY, arNumber -> {
            if (arNumber.failed()) {
                logger.error("Error when decrement session number", arNumber.cause());
            }
        });
    }

    @Override
    public void getSession(String sessionId, Handler<AsyncResult<JsonObject>> handler) {
        final long startGetSessionTime = System.currentTimeMillis();
        Long nearCacheTime;
        if (nearCache && (nearCacheTime = expire.get(sessionId)) != null && (startGetSessionTime - nearCacheTime) < nearCacheDelay) {
            final JsonObject session = sessions.get(sessionId);
            if (session != null) {
                final String userId = session.getString("userId");
                final boolean secureLocation = (session.getJsonObject("sessionMetadata") != null ?
                    session.getJsonObject("sessionMetadata").getBoolean("secureLocation", false) : false);
                activityCheck(sessionId, userId, secureLocation);
                handler.handle(Future.succeededFuture(session));
            } else {
                getRedisSession(sessionId, handler, startGetSessionTime);
            }
        } else {
            getRedisSession(sessionId, handler, startGetSessionTime);
        }
    }

    private void getRedisSession(String sessionId, Handler<AsyncResult<JsonObject>> handler, final long startGetSessionTime) {
        redisAPI.hgetall(SESSION_KEY + sessionId, ar -> {
            try {
                if (ar.succeeded() && ar.result() != null && !mapResponse(ar.result()).isEmpty()) {
                    final long endRedisTime = System.currentTimeMillis();
                    JsonObject result = mapResponse(ar.result());
                    final JsonObject session = new JsonObject((String) result.remove(SESSINFO));
                    final JsonObject cache = new JsonObject();
                    session.put(CACHE, cache);
                    if (result.size() > 0) {
                        for (String attr : result.fieldNames()) {
                            final String v = result.getString(attr);
                            final char type = v.charAt(0);
                            final Object value;
                            if (type == JSONOBJECT_TYPE) {
                                value = new JsonObject(v.substring(1));
                            } else if (type == LONG_TYPE) {
                                value = Long.parseLong(v.substring(1));
                            } else {
                                value = v.substring(1);
                            }
                            cache.put(attr, value);
                        }
                    }
                    final long endSessionTime = System.currentTimeMillis();
                    final long timeGetSessionDelay = endSessionTime - startGetSessionTime;
                    if (timeGetSessionDelay > LOG_SESSION_DELAY) {
                        logger.info("Session Redis time - total : " + timeGetSessionDelay + " ms, redis : " + (endRedisTime - startGetSessionTime) + " ms, substring : " + (endSessionTime - endRedisTime) + " ms.");
                    }
                    final String userId = session.getString("userId");
                    final boolean secureLocation = (session.getJsonObject("sessionMetadata") != null ?
                      session.getJsonObject("sessionMetadata").getBoolean("secureLocation", false) : false);
                    activityCheck(sessionId, userId, secureLocation);
                    if (nearCache) {
                        sessions.put(sessionId, session);
                        expire.put(sessionId, timeGetSessionDelay);
                    }
                    handler.handle(Future.succeededFuture(session));
                } else {
                    handler.handle(Future.failedFuture(ar.cause()));
                }
            } catch (Exception e) {
                logger.error("An error occurred while fetching session in Redis", e);
                if(ar == null) {
                    logger.error("Response from handler was null");
                } else {
                    logger.error("Response from handler is : " + ar);
                }
                handler.handle(Future.failedFuture(e));
            }
        });
    }

    private void activityCheck(String sessionId, String userId, boolean secureLocation) {
        if (inactivityEnabled()) {
            inactivity.updateLastActivity(sessionId, userId, secureLocation, ar -> {
                if (ar.failed()) {
                    logger.error("Error updating activity with redis impl", ar.cause());
                }
            });
        }
    }

    @Override
    public void getSessionByUserId(String userId, Handler<AsyncResult<JsonObject>> handler) {
        redisAPI.srandmember(asList(LOGIN_INFO_KEY + userId), ar -> {
            if (ar.succeeded() && ar.result() != null) {
                final String sessionId = ar.result().toString();
                getSession(sessionId, ar2 -> {
                    if (ar2.succeeded()) {
                        handler.handle(ar2);
                    } else {
                        redisAPI.srem(asList(LOGIN_INFO_KEY + userId, sessionId), ar3 -> {
                            if (ar3.succeeded()) {
                                getSessionByUserId(userId, handler);
                            } else {
                                handler.handle(Future.failedFuture(ar.cause()));
                            }
                        });
                    }
                });
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    @Override
    public void listSessionsIds(String userId, Handler<AsyncResult<JsonArray>> handler) {
        redisAPI.smembers(LOGIN_INFO_KEY + userId, ar -> {
            if (ar.succeeded() && ar.result() != null && listResponse(ar.result()).size() > 0) {
                checkSessionExists(LOGIN_INFO_KEY + userId, 0, listResponse(ar.result()), handler);
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    @Override
    public void listSessionsByUserId(String userId, Handler<AsyncResult<JsonArray>> handler) {
        if (userId == null || userId.trim().isEmpty()) {
            handler.handle(Future.failedFuture(new SessionException("Invalid userId")));
            return;
        }
        // On interroge l'ensemble directement plutôt que via listSessionsIds : celui-ci échoue
        // quand l'utilisateur n'a aucune session, alors qu'ici la liste vide est une réponse.
        redisAPI.smembers(LOGIN_INFO_KEY + userId, ar -> {
            if (ar.failed()) {
                logger.error("Error listing session ids of user " + userId, ar.cause());
                handler.handle(Future.failedFuture(new SessionException("Error listing sessions of user")));
                return;
            }
            final JsonArray sessionIds = listResponse(ar.result());
            final List<Future<JsonObject>> entries = new ArrayList<>();
            for (Object o : sessionIds) {
                if (o instanceof String) {
                    entries.add(sessionEntry((String) o, userId));
                }
            }
            if (entries.isEmpty()) {
                handler.handle(Future.succeededFuture(new JsonArray()));
                return;
            }
            Future.all(entries).onComplete(all -> {
                if (all.failed()) {
                    logger.error("Error reading sessions of user " + userId, all.cause());
                    handler.handle(Future.failedFuture(new SessionException("Error listing sessions of user")));
                    return;
                }
                final JsonArray result = new JsonArray();
                for (Future<JsonObject> entry : entries) {
                    if (entry.result() != null) {
                        result.add(entry.result());
                    }
                }
                handler.handle(Future.succeededFuture(result));
            });
        });
    }

    /**
     * Entrée allégée décrivant une session, pour {@link #listSessionsByUserId}. On lit le seul
     * champ {@code sessinfo} au lieu de passer par {@link #getSession} : celui-ci rapatrie tout
     * le cache et, surtout, prolonge la session — inspecter la liste de ses appareils ne doit pas
     * maintenir ces appareils connectés.
     *
     * <p>Renvoie {@code null} plutôt qu'un échec si la session a disparu : l'ensemble
     * {@code loginfo:} peut encore référencer une session expirée entre-temps.</p>
     */
    private Future<JsonObject> sessionEntry(String sessionId, String userId) {
        return redisAPI.hget(SESSION_KEY + sessionId, SESSINFO)
            .compose(response -> {
                if (response == null) {
                    return Future.succeededFuture((JsonObject) null);
                }
                final JsonObject session = new JsonObject(response.toString());
                final JsonObject metadata = session.getJsonObject("sessionMetadata", new JsonObject());
                final boolean secureLocation = metadata.getBoolean("secureLocation", false);
                final JsonObject entry = new JsonObject()
                        .put("sessionId", sessionId)
                        .put("userId", userId)
                        .put("login", session.getString("login"))
                        .put("displayName", session.getString("username"))
                        .put("profile", session.getString("type"))
                        .put("structures", session.getJsonArray("structures", new JsonArray()))
                        .put("structureNames", session.getJsonArray("structureNames", new JsonArray()))
                        .put("classNames", session.getJsonArray("realClassesNames", new JsonArray()))
                        .put("federated", Boolean.TRUE.equals(session.getBoolean("federated")))
                        .put("secureLocation", secureLocation);
                putIfNotNull(entry, "deviceId", metadata.getString("deviceId"));
                putIfNotNull(entry, "ip", metadata.getString("ip"));
                putIfNotNull(entry, "ua", metadata.getString("ua"));
                if (metadata.getLong("createdAt") != null) {
                    entry.put("createdAt", metadata.getLong("createdAt"));
                }
                final JsonObject functions = session.getJsonObject("functions");
                if (functions != null && !functions.isEmpty()) {
                    entry.put("functions", new JsonArray(new ArrayList<>(functions.fieldNames())));
                }
                if (!inactivityEnabled()) {
                    // Sans gestion d'inactivité le TTL n'est pas rafraîchi à chaque requête :
                    // en déduire une « dernière activité » afficherait une date fausse.
                    return Future.succeededFuture(entry);
                }
                final Promise<JsonObject> promise = Promise.promise();
                inactivity.getLastActivity(sessionId, secureLocation, lastActivity -> {
                    if (lastActivity.succeeded() && lastActivity.result() != null) {
                        entry.put("lastSeen", lastActivity.result());
                    }
                    promise.complete(entry);
                });
                return promise.future();
            })
            .recover(e -> {
                logger.warn("Error reading session " + sessionId + " of user " + userId, e);
                return Future.succeededFuture((JsonObject) null);
            });
    }

    private void checkSessionExists(String userKey, int idx, JsonArray result, Handler<AsyncResult<JsonArray>> handler) {
        if (result.size() > idx) {
            final String key = result.getString(idx);
            redisAPI.exists(asList(SESSION_KEY + key), ar -> {
                final int i;
                if (ar.failed() || ar.result().toInteger() < 1) {
                    result.remove(idx);
                    redisAPI.srem(asList(userKey, key), ar2 -> {
                        if (ar2.failed()) {
                            logger.error("Error removing not exists session key", ar2.cause());
                        }
                    });
                    i = idx;
                } else {
                    i = idx + 1;
                }
                checkSessionExists(userKey, i, result, handler);
            });
        } else {
            handler.handle(Future.succeededFuture(result));
        }
    }

    @Override
    public void putSession(String userId, String sessionId, JsonObject infos, boolean secureLocation, Handler<AsyncResult<Void>> handler) {
        infos.remove(CACHE);
        redisAPI.hset(asList(SESSION_KEY + sessionId, SESSINFO, infos.encode()), ar -> {
            if (ar.succeeded()) {
                redisAPI.sadd(asList(LOGIN_INFO_KEY + userId, sessionId), ar2 -> {
                    if (ar2.succeeded()) {
                        ((RedisActivityManager) inactivity).setExpireSession(sessionId, userId, secureLocation, arExpire -> {
                            if (arExpire.succeeded()) {
                                if (inactivityEnabled()) {
                                    redisAPI.incr(SESSION_NUMBER_KEY, arNumber -> {
                                        if (arNumber.failed()) {
                                            logger.error("Error when increment session number", arNumber.cause());
                                        }
                                    });
                                }
                                handler.handle(Future.succeededFuture());
                            } else {
                                logger.error("Error when set expire", arExpire.cause());
                                handler.handle(Future.failedFuture(arExpire.cause()));
                            }
                        });
                    } else {
                        logger.error("Error when sadd put session tx", ar2.cause());
                        handler.handle(Future.failedFuture(ar2.cause()));
                    }
                });
            } else {
                logger.error("Error when hset put session tx", ar.cause());
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    @Override
    protected void removeCacheSession(String userId, String sessionId) {
        redisAPI.del(asList(SESSION_KEY + sessionId), ar2 -> {
            if (ar2.succeeded()) {
                redisAPI.srem(asList(LOGIN_INFO_KEY + userId, sessionId), ar3 -> {
                    if (ar3.failed()) {
                        logger.error("Error when srem remove cache session tx", ar3.cause());
                    }
                });
            } else {
                logger.error("Error when del remove cache session tx", ar2.cause());
            }
        });
    }

    @Override
    public void getSessionsNumber(Handler<AsyncResult<Long>> handler) {
        redisAPI.get(SESSION_NUMBER_KEY, ar -> {
            if (ar.succeeded() && ar.result() != null) {
                final String r = ar.result().toString();
                if (r == null || r.isEmpty()) {
                    handler.handle(Future.succeededFuture(0L));
                    return;
                }
                try {
                    handler.handle(Future.succeededFuture(Long.valueOf(r)));
                } catch (NumberFormatException e) {
                    handler.handle(Future.failedFuture(e));
                }
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    @Override
    protected void updateTimerId(String userId, String sessionId, long timerId) {
        // Not used in this implementation
    }

}
