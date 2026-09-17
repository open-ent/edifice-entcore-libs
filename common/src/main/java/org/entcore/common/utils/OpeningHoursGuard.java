/*
 * Copyright © "Open Digital Education", 2015
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

package org.entcore.common.utils;

import fr.wseduc.webutils.http.Renders;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;

/**
 * Garde d'écriture aux horaires d'utilisation, à poser à l'entrée d'une route qui
 * <b>écrit</b> dans un espace soumis à horaires (cf. {@link SpaceOpeningHours}).
 *
 * <p>Elle fait trois choses en une : résoudre l'utilisateur, refuser s'il est hors plage,
 * et passer la main au traitement normal sinon. Le refus est un <b>403</b> portant le code
 * {@code opening.hours.closed} et l'horaire applicable, pour que le client sache afficher
 * « fermé jusqu'à telle heure » plutôt qu'une erreur muette.
 *
 * <p>Seules les écritures sont gardées : hors plage, on relit toujours. C'est la même règle
 * que pour les messageries — lecture seule, pas extinction.
 */
public final class OpeningHoursGuard {

    /** Code d'erreur renvoyé au client quand l'espace est fermé. */
    public static final String CLOSED_ERROR = "opening.hours.closed";

    private OpeningHoursGuard() {}

    /**
     * Exécute {@code onAllowed} si l'utilisateur courant peut écrire dans cet espace
     * maintenant. Sinon répond 403 (fermé) ou 401 (pas de session) et n'appelle rien.
     *
     * @param scope portée concernée, cf. les constantes {@code SCOPE_*} de
     *              {@link SpaceOpeningHours}
     */
    public static void ifWriteAllowed(final EventBus eb, final HttpServerRequest request,
                                      final String scope, final Handler<UserInfos> onAllowed) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) {
                Renders.unauthorized(request);
                return;
            }
            final SpaceOpeningHours hours = SpaceOpeningHours.getInstance();
            if (hours.isWriteAllowed(scope, user.getType(), user.getStructures())) {
                onAllowed.handle(user);
                return;
            }
            final JsonObject status = hours.statusFor(scope, user.getType(), user.getStructures());
            Renders.renderJson(request, new JsonObject()
                    .put("error", CLOSED_ERROR)
                    .put("schedule", status.getJsonObject("schedule")), 403);
        });
    }
}
