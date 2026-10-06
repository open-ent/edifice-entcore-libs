/*
 * Copyright © "Open Digital Education", 2016
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

package org.entcore.probes;

import java.util.Map;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import fr.wseduc.webutils.collections.SharedDataHelper;
import io.vertx.core.json.JsonObject;
import org.entcore.common.http.BaseServer;

public class Probes extends BaseServer {

	@Override
	public void start(final Promise<Void> startPromise) throws Exception {
		final Promise<Void> promise = Promise.promise();
		super.start(promise);
		promise.future()
				.compose(init -> SharedDataHelper.getInstance().<String, Object>getLocalMulti("server", "skins", "skin-levels"))
				.compose(probesConfigMap -> initProbes(probesConfigMap))
				.onComplete(startPromise);
	}

	public Future<Void> initProbes(final Map<String, Object> probesConfigMap) {
		vertx.eventBus().consumer("probes.eventbus.remote", message -> {
			log.debug("Received a probes eventbus test message from " + message.headers());
			message.reply(new JsonObject().put("status", "ok"));
		});
		return Future.succeededFuture();
	}


}
