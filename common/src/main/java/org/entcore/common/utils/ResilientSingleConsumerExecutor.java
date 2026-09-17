/*
 * Copyright © "Open ENT", 2026
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>.
 */

package org.entcore.common.utils;

import fr.wseduc.bus.SingleConsumerExecutor;
import fr.wseduc.webutils.collections.SharedDataHelper;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * Variante d'entreprise de {@link SingleConsumerExecutor} : le verrou partagé reste une
 * optimisation, jamais une condition d'exécution.
 *
 * <p>La variante {@code Runnable} de l'implémentation amont (web-utils 3.4.3) abandonne la
 * tâche EN SILENCE dès que {@code getLock()} échoue — elle suppose qu'un échec de verrou
 * signifie « une autre instance s'en occupe ». Cette hypothèse ne tient pas sur nos
 * plateformes : le verrouillage partagé y échoue de façon sporadique sans aucune contention
 * réelle (panne constatée le 2026-09-07 — l'export /archive restait bloqué indéfiniment sur
 * son spinner, aucune trace d'erreur). Le travail était alors purement et simplement perdu.</p>
 *
 * <p>Ici, un échec d'acquisition est journalisé en {@code warn} puis la tâche est exécutée
 * quand même. Le risque résiduel de double traitement est acceptable : les noms de verrou
 * utilisés par {@code RepositoryHandler} et {@code SearchingHandler} contiennent tous un
 * identifiant unique par requête (exportId, importId, searchId, hash du corps du message),
 * donc une collision authentique y est structurellement quasi impossible — à l'inverse de la
 * perte de travail, elle, observée en production.</p>
 *
 * <p>Le délai d'acquisition amont (500 ms) est également trop court pour un cluster sous
 * charge ; les appelants passent 5 s.</p>
 */
public class ResilientSingleConsumerExecutor extends SingleConsumerExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientSingleConsumerExecutor.class);

    private final SharedDataHelper sharedData = SharedDataHelper.getInstance();
    private final long lockTimeout;
    private final long releaseDelay;

    public ResilientSingleConsumerExecutor(long lockTimeout, long releaseDelay) {
        super(lockTimeout, releaseDelay);
        this.lockTimeout = lockTimeout;
        this.releaseDelay = releaseDelay;
    }

    @Override
    public void ensureSingle(String lockName, Runnable action) {
        sharedData.getLock(lockName, lockTimeout)
            .onSuccess(lock -> {
                try {
                    action.run();
                } finally {
                    sharedData.releaseLockAfterDelay(lock, releaseDelay);
                }
            })
            .onFailure(th -> {
                log.warn("Could not acquire lock " + lockName
                        + " — running the task anyway rather than dropping it silently.", th);
                action.run();
            });
    }
}
