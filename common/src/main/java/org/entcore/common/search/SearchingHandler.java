/*
 * Copyright © "Open Digital Education", 2014
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

package org.entcore.common.search;

import fr.wseduc.bus.SingleConsumerExecutor;
import fr.wseduc.webutils.Either;
import org.entcore.common.utils.ResilientSingleConsumerExecutor;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.List;

/**
 * Created by dbreyton on 23/02/2016.
 */
public class SearchingHandler implements Handler<Message<JsonObject>> {

	private static final Logger log = LoggerFactory.getLogger(SearchingHandler.class);
	private SearchingEvents searchingEvents;
	private final EventBus eb;
	// ResilientSingleConsumerExecutor et non SingleConsumerExecutor : un échec d'acquisition du
	// verrou partagé ne doit pas faire disparaître la réponse de cette source de recherche, sinon
	// l'agrégateur attend indéfiniment et la recherche globale reste sur « Chargement… ».
	private final SingleConsumerExecutor executor = new ResilientSingleConsumerExecutor(5000L, 10000L);
	private String appName;

	public SearchingHandler(EventBus eb) {
		this(new LogSearchingEvents(), eb);
	}

	public SearchingHandler(SearchingEvents searchingEvents, EventBus eb) {
		this.eb = eb;
		this.searchingEvents = searchingEvents;
		this.appName = searchingEvents.getClass().getSimpleName();
	}

	@Override
	public void handle(final Message<JsonObject> message) {
		final JsonArray searchWords = message.body().getJsonArray("searchWords");
		final String userId = message.body().getString("userId", "");
		final Integer page =  message.body().getInteger("page", 0);
		final Integer limit =  message.body().getInteger("limit", 0);
		final String searchId = message.body().getString("searchId", "");
		final JsonArray groupIds = message.body().getJsonArray("groupIds", new JsonArray());
		final JsonArray columnsHeader = message.body().getJsonArray("columnsHeader", new JsonArray());
		final List<String> appFilters = message.body().getJsonArray("appFilters", new JsonArray()).getList();
		final String locale = message.body().getString("locale", "fr");
		executor.ensureSingle("search_engine_" + searchId + "_" + appName, () -> {
			searchingEvents.searchResource(appFilters, userId, groupIds, searchWords, page, limit, columnsHeader, locale, new Handler<Either<String, JsonArray>>() {
				@Override
				public void handle(Either<String, JsonArray> event) {
					if (event.isRight()) {
						final String address = "search." + searchId;
						final JsonObject message = new JsonObject().put("application", searchingEvents.getClass().getSimpleName());
						message.put("results", event.right().getValue());
						eb.request(address, message, new DeliveryOptions().setSendTimeout(5000l),
								(Handler<AsyncResult<Message<JsonObject>>>) res -> {
									if (res != null && res.succeeded()) {
										if (!"ok".equals(res.result().body().getString("message"))) {
											log.error(res.result().body().getString("message"));
										}
									}
								});
					} else {
						log.error("Failure of the research module : " + searchingEvents.getClass().getSimpleName() +
								"; message : " + event.left().getValue());
						// Même en cas d'échec de la source (ex. index $text Mongo absent / collection inexistante),
						// on répond à l'agrégateur avec un résultat vide afin qu'il considère cette source comme
						// « traitée » (0 résultat) et poursuive l'agrégation, au lieu d'attendre indéfiniment.
						// Sinon la recherche globale reste bloquée sur « Chargement… » (cf. anomalie 36 searchengine).
						final String address = "search." + searchId;
						final JsonObject message = new JsonObject()
								.put("application", searchingEvents.getClass().getSimpleName())
								.put("results", new JsonArray());
						eb.request(address, message, new DeliveryOptions().setSendTimeout(5000l),
								(Handler<AsyncResult<Message<JsonObject>>>) res -> {
									// réponse best-effort : rien à traiter côté source
								});
					}
				}
			});
		});
	}

	public void setSearchingEvents(SearchingEvents searchingEvents) {
		this.searchingEvents = searchingEvents;
		this.appName = searchingEvents.getClass().getSimpleName();
	}

}
