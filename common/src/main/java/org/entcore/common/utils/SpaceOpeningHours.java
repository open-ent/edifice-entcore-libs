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

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Horaires d'utilisation des espaces d'échange et de publication — lecture partagée.
 *
 * <p>Un établissement peut restreindre les heures auxquelles ses élèves <b>écrivent</b> :
 * hors plage, ils relisent mais n'envoient plus. La règle est administrée au même endroit
 * pour tous les espaces (collection Mongo {@code messaging.hours}, cf. le contrôleur du
 * module {@code conversation}), et <b>appliquée</b> par chaque espace concerné.
 *
 * <p><b>Portées.</b> Un horaire porte la liste des espaces auxquels il s'applique, dans son
 * champ {@code scopes}. Un document <b>sans</b> ce champ ne vaut que pour les messageries —
 * c'est le comportement historique, et c'est ce qui rend la montée de version sans effet sur
 * les horaires déjà enregistrés en production.
 *
 * <pre>
 * { _id: "global" | &lt;structureId&gt;,
 *   enabled: true, days: [1,2,3,4,5], start: "08:00", end: "18:00",
 *   scopes: ["messaging", "blog", "forum"] }   // absent =&gt; ["messaging"]
 * </pre>
 *
 * <p><b>Deux règles qui ne changent pas d'un espace à l'autre :</b>
 * <ul>
 *   <li>seuls les <b>élèves</b> sont restreints — aucun autre profil ne l'est jamais ;</li>
 *   <li>un élève rattaché à plusieurs établissements est autorisé dès qu'<b>au moins un</b>
 *       d'entre eux est ouvert : on ne pénalise pas un élève pour un rattachement qu'il n'a
 *       pas choisi.</li>
 * </ul>
 *
 * <p>La lecture est mise en cache et rafraîchie périodiquement : le chemin d'écriture d'un
 * message ou d'un billet ne doit pas dépendre d'un aller-retour Mongo.
 */
public class SpaceOpeningHours {

    public static final String COLLECTION = "messaging.hours";
    public static final String GLOBAL_ID = "global";
    public static final String STUDENT_PROFILE = "Student";

    /** Les deux messageries — portée par défaut d'un horaire qui n'en déclare aucune. */
    public static final String SCOPE_MESSAGING = "messaging";
    /** Publication et commentaires du blog. */
    public static final String SCOPE_BLOG = "blog";
    /** Messages des espaces de discussion (forum). */
    public static final String SCOPE_FORUM = "forum";

    public static final List<String> KNOWN_SCOPES =
            Arrays.asList(SCOPE_MESSAGING, SCOPE_BLOG, SCOPE_FORUM);

    private static final Set<String> DEFAULT_SCOPES = new HashSet<>(Arrays.asList(SCOPE_MESSAGING));

    private static final SpaceOpeningHours INSTANCE = new SpaceOpeningHours();

    private final MongoDb mongo = MongoDb.getInstance();
    private volatile JsonObject global = null;
    private final Map<String, JsonObject> byStructure = new ConcurrentHashMap<>();
    private ZoneId zone = ZoneId.of("Europe/Paris");
    private boolean featureEnabled = true;
    private volatile boolean started = false;

    public static SpaceOpeningHours getInstance() {
        return INSTANCE;
    }

    /**
     * À appeler une fois au démarrage du module : charge le cache et planifie son
     * rafraîchissement. Appels suivants sans effet — plusieurs modules du même processus
     * peuvent l'appeler sans se marcher dessus.
     */
    public synchronized void init(Vertx vertx, JsonObject conf) {
        if (started) {
            return;
        }
        started = true;
        long refreshMs = 60000L;
        if (conf != null) {
            final String tz = conf.getString("messaging-hours-timezone");
            if (tz != null) {
                try {
                    this.zone = ZoneId.of(tz);
                } catch (RuntimeException ignored) {
                    // fuseau invalide en configuration : on garde Europe/Paris
                }
            }
            this.featureEnabled = conf.getBoolean("messaging-hours-enabled", true);
            refreshMs = conf.getLong("messaging-hours-refresh-ms", 60000L);
        }
        // Premier chargement DIFFÉRÉ : init() est appelé pendant le start() synchrone du
        // module, juste après un super.start() asynchrone — MongoDb n'a pas encore son
        // EventBus, et un find() immédiat ferait échouer le déploiement. On défère au
        // prochain tick, le timer périodique servant de filet.
        vertx.setTimer(1, id -> reload());
        vertx.setPeriodic(refreshMs, id -> reload());
    }

    /**
     * Recharge le cache depuis Mongo. Défensif : si MongoDb n'est pas encore prêt, on
     * ignore — le prochain rafraîchissement réessaiera.
     */
    public void reload() {
        try {
            doReload();
        } catch (RuntimeException e) {
            // MongoDb pas encore initialisé
        }
    }

    private void doReload() {
        mongo.find(COLLECTION, new JsonObject(), msg -> {
            final JsonObject body = msg.body();
            if (body == null || !"ok".equals(body.getString("status"))) return;
            final JsonArray results = body.getJsonArray("results", new JsonArray());
            JsonObject g = null;
            final Map<String, JsonObject> map = new HashMap<>();
            for (Object o : results) {
                if (!(o instanceof JsonObject)) continue;
                final JsonObject doc = (JsonObject) o;
                final String id = doc.getString("_id");
                if (GLOBAL_ID.equals(id)) g = doc;
                else if (id != null) map.put(id, doc);
            }
            this.global = g;
            this.byStructure.clear();
            this.byStructure.putAll(map);
        });
    }

    /** Horaire effectif d'un établissement : sa surcharge si elle existe, sinon le défaut global. */
    public JsonObject effective(String structureId) {
        final JsonObject s = byStructure.get(structureId);
        return (s != null) ? s : global;
    }

    public JsonObject getGlobal() {
        return global;
    }

    public JsonObject getStructureOverride(String structureId) {
        return byStructure.get(structureId);
    }

    public ZoneId zone() {
        return zone;
    }

    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    /**
     * Portées déclarées par un horaire. Un horaire sans champ {@code scopes} — ce que sont
     * tous ceux enregistrés avant l'ouverture de la fonction aux autres espaces — ne vaut que
     * pour les messageries.
     */
    public static Set<String> scopesOf(JsonObject schedule) {
        if (schedule == null) return DEFAULT_SCOPES;
        final JsonArray declared = schedule.getJsonArray("scopes");
        if (declared == null || declared.isEmpty()) return DEFAULT_SCOPES;
        final Set<String> scopes = new HashSet<>();
        for (Object o : declared) {
            if (o instanceof String) scopes.add((String) o);
        }
        return scopes.isEmpty() ? DEFAULT_SCOPES : scopes;
    }

    /**
     * Un horaire est-il ouvert pour cette portée, à cet instant ? Ouvert si l'horaire est
     * absent, désactivé, ou ne couvre pas la portée demandée — la fermeture doit être
     * explicite.
     */
    private boolean isOpen(JsonObject schedule, String scope, ZonedDateTime now) {
        if (schedule == null) return true;
        if (!schedule.getBoolean("enabled", false)) return true;
        if (!scopesOf(schedule).contains(scope)) return true;
        final JsonArray days = schedule.getJsonArray("days");
        final int dow = now.getDayOfWeek().getValue(); // 1=lundi … 7=dimanche
        if (days != null && !days.isEmpty() && !days.contains(dow)) return false;
        final LocalTime start = parseTime(schedule.getString("start"), LocalTime.MIN);
        final LocalTime end = parseTime(schedule.getString("end"), LocalTime.MAX);
        final LocalTime t = now.toLocalTime();
        return !t.isBefore(start) && t.isBefore(end);
    }

    /**
     * L'utilisateur peut-il écrire dans cet espace maintenant ? Seuls les élèves sont
     * restreints, et ils sont autorisés dès qu'au moins un de leurs établissements est ouvert.
     */
    public boolean isWriteAllowed(String scope, String profile, List<String> structures, ZonedDateTime now) {
        if (!featureEnabled) return true;
        if (!STUDENT_PROFILE.equals(profile)) return true;
        if (structures == null || structures.isEmpty()) return isOpen(global, scope, now);
        for (String s : structures) {
            if (isOpen(effective(s), scope, now)) return true;
        }
        return false;
    }

    public boolean isWriteAllowed(String scope, String profile, List<String> structures) {
        return isWriteAllowed(scope, profile, structures, ZonedDateTime.now(zone));
    }

    /** Statut destiné au bandeau client : ouvert/fermé + l'horaire applicable à cette portée. */
    public JsonObject statusFor(String scope, String profile, List<String> structures) {
        final ZonedDateTime now = ZonedDateTime.now(zone);
        final boolean restricted = featureEnabled && STUDENT_PROFILE.equals(profile);
        final boolean open = isWriteAllowed(scope, profile, structures, now);
        return new JsonObject()
                .put("scope", scope)
                .put("open", open)
                .put("restricted", restricted)
                .put("schedule", restricted ? displaySchedule(scope, structures) : null)
                .put("now", now.toString());
    }

    /**
     * Horaire à afficher : le premier établissement dont l'horaire est activé <i>et</i> couvre
     * la portée demandée, sinon le global s'il la couvre.
     */
    private JsonObject displaySchedule(String scope, List<String> structures) {
        if (structures != null) {
            for (String s : structures) {
                final JsonObject e = effective(s);
                if (e != null && e.getBoolean("enabled", false) && scopesOf(e).contains(scope)) return e;
            }
        }
        return (global != null && global.getBoolean("enabled", false) && scopesOf(global).contains(scope))
                ? global : null;
    }

    private static LocalTime parseTime(String hhmm, LocalTime def) {
        if (hhmm == null) return def;
        try {
            final String[] p = hhmm.split(":");
            return LocalTime.of(Integer.parseInt(p[0].trim()), p.length > 1 ? Integer.parseInt(p[1].trim()) : 0);
        } catch (RuntimeException e) {
            return def;
        }
    }
}
