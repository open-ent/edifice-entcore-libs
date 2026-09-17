/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with ENT Core is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of ENT Core, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.entcore.common.folders;

import org.entcore.common.user.UserInfos;

import fr.wseduc.webutils.Either;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface QuotaService {
	void notifySmallAmountOfFreeSpace(String userId);

	void incrementStorage(String userId, Long size, int threshold, Handler<Either<String, JsonObject>> handler);

	void decrementStorage(String userId, Long size, int threshold, Handler<Either<String, JsonObject>> handler);

	void quotaAndUsage(String userId, Handler<Either<String, JsonObject>> handler);

	void quotaAndUsageStructure(String structureId, Handler<Either<String, JsonObject>> handler);

	void quotaAndUsageGlobal(Handler<Either<String, JsonObject>> handler);

	void update(JsonArray users, long quota, Handler<Either<String, JsonArray>> handler);

	/**
	 * Met à jour le quota de tous les utilisateurs d'un profil, pour toutes les structures
	 * rattachées à un département (regroupement dérivé de codeDepartement/departement/zipCode).
	 */
	void updateByProfileAndDepartment(String profile, String departmentCode, long quota,
			Handler<Either<String, JsonArray>> handler);

	/**
	 * Départements où l'utilisateur peut appliquer un quota multi-établissements
	 * (SuperAdmin : tous ; ADML : ceux où son périmètre couvre tous les établissements).
	 */
	void getAllowedDepartments(UserInfos user, Handler<Either<String, JsonArray>> handler);

	void updateQuotaDefaultMax(String profile, Long defaultQuota, Long maxQuota,
			Handler<Either<String, JsonObject>> handler);

	void getDefaultMaxQuota(Handler<Either<String, JsonArray>> handler);

	void init(String userId);

	/**
	 * Seuil d'alerte d'occupation du stockage propre à un établissement, en pourcentage, ou
	 * {@code null} s'il n'en a pas fixé — le seuil de plate-forme ({@code alertStorage})
	 * s'applique alors.
	 */
	void getStorageAlertThreshold(String structureId, Handler<Either<String, JsonObject>> handler);

	/**
	 * Fixe (ou retire, avec {@code threshold} à {@code null}) le seuil d'alerte d'un
	 * établissement. Prend effet au prochain calcul d'occupation, sans reprise de l'existant :
	 * le drapeau d'alerte d'une personne est recalculé à son prochain dépôt ou retrait de
	 * fichier.
	 */
	void setStorageAlertThreshold(String structureId, Integer threshold, Handler<Either<String, JsonObject>> handler);

}
