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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface SessionStore {

    long DEFAULT_SESSION_TIMEOUT = 30 * 60 * 1000;

    void getSession(String sessionId, Handler<AsyncResult<JsonObject>> handler);

    void listSessionsIds(String userId, Handler<AsyncResult<JsonArray>> handler);

    void getSessionByUserId(String userId, Handler<AsyncResult<JsonObject>> handler);

    void putSession(String userId, String sessionId, JsonObject infos, boolean secureLocation, Handler<AsyncResult<Void>> handler);

    void dropSession(String sessionId, Handler<AsyncResult<JsonObject>> handler);

    void addCacheAttribute(String sessionId, String key, Object value, Handler<AsyncResult<Void>> handler);

    void dropCacheAttribute(String sessionId, String key, Handler<AsyncResult<Void>> handler);

    void addCacheAttributeByUserId(String userId, String key, Object value, Handler<AsyncResult<Void>> handler);

    void dropCacheAttributeByUserId(String userId, String key, Handler<AsyncResult<Void>> handler);

    void getSessionsNumber(Handler<AsyncResult<Long>> handler);

    /**
     * Liste les sessions actuellement ouvertes, sous forme d'entrées allégées
     * (identité, profil, établissements, horodatages) et non de sessions complètes :
     * une session complète pèse plusieurs dizaines de kilo-octets (droits, applications,
     * widgets…) et en rapatrier des milliers depuis la grille écroulerait le noeud.
     * Destiné à la supervision (tableau de bord d'administration).
     *
     * <p>Implémentation par défaut : échec explicite. Seul {@link MapSessionStore} sait
     * énumérer ses sessions, grâce à l'index qu'il tient à jour. Le backend Redis, ajouté
     * par l'amont en 6.16, n'expose pas d'index équivalent : l'énumérer demanderait un SCAN
     * de l'espace de clés puis un GET par session. Plutôt que de renvoyer une liste vide —
     * que la supervision afficherait comme « aucune session ouverte », c'est-à-dire une
     * information fausse — on échoue franchement tant que ce backend n'est pas implémenté.</p>
     */
    default void listSessions(Handler<AsyncResult<JsonArray>> handler) {
        handler.handle(Future.failedFuture(new SessionException(
                "listSessions is not supported by " + getClass().getSimpleName())));
    }

    boolean inactivityEnabled();
}
