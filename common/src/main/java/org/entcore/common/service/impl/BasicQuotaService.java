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

package org.entcore.common.service.impl;

import static org.entcore.common.neo4j.Neo4jResult.validResultHandler;
import static org.entcore.common.neo4j.Neo4jResult.validUniqueResultHandler;

import java.util.ArrayList;
import java.util.List;

import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.collections.Joiner;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4jResult;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class BasicQuotaService implements org.entcore.common.folders.QuotaService {

	protected final Neo4j neo4j = Neo4j.getInstance();
	private static final Logger log = LoggerFactory.getLogger(BasicQuotaService.class);

	private static final String NOT_IMPLEMENTED = "Not implemented";

	public BasicQuotaService() {
	}

	@Override
	public void notifySmallAmountOfFreeSpace(String userId) {
		if( log.isWarnEnabled() ) {
			log.warn( NOT_IMPLEMENTED );
		}
	}

	@Override
	public void incrementStorage(String userId, Long size, int threshold, final Handler<Either<String, JsonObject>> handler) {
		handler.handle(new Either.Left<String, JsonObject>(NOT_IMPLEMENTED));
	}

	@Override
	public void decrementStorage(String userId, Long size, int threshold, Handler<Either<String, JsonObject>> handler) {
		handler.handle(new Either.Left<String, JsonObject>(NOT_IMPLEMENTED));
	}

	@Override
	public void quotaAndUsage(String userId, Handler<Either<String, JsonObject>> handler) {
		String query = "MATCH (u:UserBook { userid : {userId}}) " + "RETURN u.quota as quota, u.storage as storage ";
		JsonObject params = new JsonObject().put("userId", userId);
		neo4j.execute(query, params, validUniqueResultHandler(handler));
	}

	@Override
	public void quotaAndUsageStructure(String structureId, Handler<Either<String, JsonObject>> handler) {
		String query = "MATCH (s:Structure {id : {structureId}})<-[:DEPENDS]-(:ProfileGroup)"
				+ "<-[:IN]-(:User)-[:USERBOOK]->(u:UserBook) "
				+ "RETURN sum(u.quota) as quota, sum(u.storage) as storage ";
		JsonObject params = new JsonObject().put("structureId", structureId);
		neo4j.execute(query, params, validUniqueResultHandler(handler));

	}

	@Override
	public void quotaAndUsageGlobal(Handler<Either<String, JsonObject>> handler) {
		String query = "MATCH (u:UserBook) " + "RETURN sum(u.quota) as quota, sum(u.storage) as storage ";
		JsonObject params = new JsonObject();
		neo4j.execute(query, params, validUniqueResultHandler(handler));
	}

	@Override
	public void update(JsonArray users, long quota, Handler<Either<String, JsonArray>> handler) {
		String query = "MATCH (u:UserBook)<-[:USERBOOK]-(:User)-[:IN]->(:ProfileGroup)-[:HAS_PROFILE]->(p:Profile) "
				+ "WHERE u.userid IN {users} AND u.storage <= {quota} AND {quota} <= coalesce(p.maxQuota, 1073741824) "
				+ "SET u.quota = {quota}, u.alertSize = false " + "RETURN u.userid as id ";
		JsonObject params = new JsonObject().put("users", users).put("quota", quota);
		neo4j.execute(query, params, validResultHandler(handler));
	}

	@Override
	public void updateByProfileAndDepartment(String profile, String departmentCode, long quota,
			Handler<Either<String, JsonArray>> handler) {
		String query = "MATCH (s:Structure) "
				+ "WHERE coalesce(s.codeDepartement, s.departement, substring(s.zipCode, 0, 2)) = {departmentCode} "
				+ "AND coalesce(s.structureType, 'ETABLISSEMENT') = 'ETABLISSEMENT' "
				+ "MATCH (s)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User)-[:USERBOOK]->(ub:UserBook) "
				+ "MATCH (u)-[:IN]->(:ProfileGroup)-[:HAS_PROFILE]->(p:Profile {name : {profile}}) "
				+ "WHERE ub.storage <= {quota} AND {quota} <= coalesce(p.maxQuota, 1073741824) "
				+ "SET ub.quota = {quota}, ub.alertSize = false "
				+ "RETURN DISTINCT u.id as id ";
		JsonObject params = new JsonObject()
				.put("departmentCode", departmentCode)
				.put("profile", profile)
				.put("quota", quota);
		neo4j.execute(query, params, validResultHandler(handler));
	}

	@Override
	public void getAllowedDepartments(UserInfos user, Handler<Either<String, JsonArray>> handler) {
		boolean superAdmin = user.getFunctions() != null
				&& user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN);
		String query;
		JsonObject params = new JsonObject();
		if (superAdmin) {
			query = "MATCH (s:Structure) "
					+ "WHERE coalesce(s.structureType, 'ETABLISSEMENT') = 'ETABLISSEMENT' "
					+ "WITH coalesce(s.codeDepartement, s.departement, substring(s.zipCode, 0, 2)) AS departement, "
					+ "count(s) AS totalEtablissements "
					+ "RETURN departement, totalEtablissements ORDER BY departement ";
		} else {
			UserInfos.Function adminLocal = user.getFunctions() == null ? null
					: user.getFunctions().get(DefaultFunctions.ADMIN_LOCAL);
			if (adminLocal == null || adminLocal.getScope() == null) {
				handler.handle(new Either.Right<String, JsonArray>(new JsonArray()));
				return;
			}
			query = "MATCH (s:Structure) "
					+ "WHERE coalesce(s.structureType, 'ETABLISSEMENT') = 'ETABLISSEMENT' "
					+ "WITH coalesce(s.codeDepartement, s.departement, substring(s.zipCode, 0, 2)) AS departement, "
					+ "collect(s.id) AS ids "
					+ "WHERE ALL(sid IN ids WHERE sid IN {structures}) "
					+ "RETURN departement, size(ids) AS totalEtablissements ORDER BY departement ";
			params.put("structures", new JsonArray(adminLocal.getScope()));
		}
		neo4j.execute(query, params, validResultHandler(handler));
	}

	@Override
	public void updateQuotaDefaultMax(String profile, Long defaultQuota, Long maxQuota,
			Handler<Either<String, JsonObject>> handler) {
		if (defaultQuota == null && maxQuota == null) {
			handler.handle(new Either.Left<String, JsonObject>("invalid.params"));
			return;
		}
		JsonObject params = new JsonObject().put("profile", profile);
		List<String> p = new ArrayList<>();
		if (maxQuota != null) {
			p.add("p.maxQuota = {maxQuota}");
			params.put("maxQuota", maxQuota);
		}
		if (defaultQuota != null) {
			p.add("p.defaultQuota = {defaultQuota}");
			params.put("defaultQuota", defaultQuota);
		}
		String query = "MATCH (p:Profile { name : {profile}}) " + "SET " + Joiner.on(", ").join(p)
				+ " RETURN p.id as id ";
		neo4j.execute(query, params, validUniqueResultHandler(handler));
	}

	@Override
	public void getDefaultMaxQuota(Handler<Either<String, JsonArray>> handler) {
		String query = "MATCH (p:Profile) RETURN p.name as name, coalesce(p.maxQuota, 1073741824) as maxQuota";
		neo4j.execute(query, new JsonObject(), validResultHandler(handler));
	}

	@Override
	public void init(final String userId) {
		String query = "MATCH (n:User {id : {userId}})-[:IN]->(:ProfileGroup)-[:HAS_PROFILE]->(p:Profile) "
				+ "WITH n, sum(CASE WHEN has(p.defaultQuota) THEN p.defaultQuota ELSE 104857600 END) as quota "
				+ "MERGE (n)-[:USERBOOK]->(m:UserBook) "
				+ "SET m.userid = {userId}, m.quota = quota, m.storage = 0, m.alertSize = false ";
		JsonObject params = new JsonObject().put("userId", userId);
		neo4j.execute(query, params, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> message) {
				if (!"ok".equals(message.body().getString("status"))) {
					log.error("Error initializing quota for user " + userId + " : "
							+ message.body().getString("message"));
				}
			}
		});
	}

	@Override
	public void getStorageAlertThreshold(String structureId, Handler<Either<String, JsonObject>> handler) {
		final String query = "MATCH (s:Structure {id: {structureId}}) "
				+ "RETURN s.id as structureId, s.name as name, s.storageAlertThreshold as threshold";
		neo4j.execute(query, new JsonObject().put("structureId", structureId),
				Neo4jResult.validUniqueResultHandler(handler));
	}

	@Override
	public void setStorageAlertThreshold(String structureId, Integer threshold,
			Handler<Either<String, JsonObject>> handler) {
		// REMOVE plutôt que SET à null : une propriété absente est ce que lit MIN() côté
		// calcul d'occupation (cf. DefaultQuotaService.incrementStorage), et c'est aussi ce
		// qui distingue « pas de surcharge » d'une surcharge à zéro.
		final String query = (threshold == null)
				? "MATCH (s:Structure {id: {structureId}}) REMOVE s.storageAlertThreshold "
						+ "RETURN s.id as structureId, s.name as name, null as threshold"
				: "MATCH (s:Structure {id: {structureId}}) SET s.storageAlertThreshold = {threshold} "
						+ "RETURN s.id as structureId, s.name as name, s.storageAlertThreshold as threshold";
		final JsonObject params = new JsonObject().put("structureId", structureId);
		if (threshold != null) {
			params.put("threshold", threshold);
		}
		neo4j.execute(query, params, Neo4jResult.validUniqueResultHandler(handler));
	}

}
