package org.entcore.common.schema.structures;

import org.entcore.common.utils.Id;
import org.entcore.common.utils.IdObject;
import org.entcore.common.utils.ExternalId;
import org.entcore.common.schema.Source;
import org.entcore.common.schema.users.User;
import org.entcore.common.schema.utils.matchers.Matcher;
import org.entcore.common.schema.utils.matchers.NodeMatcher;
import org.entcore.common.schema.utils.matchers.IdentifierMatcher;
import org.entcore.common.schema.utils.matchers.SourceMatcher;
import org.entcore.common.schema.utils.matchers.UniversalMatcher;
import org.entcore.common.schema.utils.matchers.CompoundMatcher;


import org.entcore.common.neo4j.TransactionHelper;

import io.vertx.core.Promise;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class Structure implements IdObject {
    public final Id<Structure, String> id;
    public final ExternalId<Structure>externalId;

    private static final String USER_MANAGED_MARKER = "\"managedBy\":\"USER\"";
    private static final String STRUCTURE_MANAGED_MARKER = "\"managedBy\":\"STRUCTURE\"";

    public Structure(String id)
    {
        this(id, null);
    }

    public Structure(ExternalId externalId)
    {
        this(null, externalId);
    }

    public Structure(String id, ExternalId<Structure> externalId) {
        this.id = new Id<Structure, String>(id);
        this.externalId = externalId;
    }

    // Attach a user to a structure
    public static Future<JsonArray> attach(TransactionHelper tx, NodeMatcher<User> usersMatcher, NodeMatcher<Structure> structuresMatcher) {
        Promise<JsonArray> promise = Promise.promise();
        JsonObject params = new JsonObject();

        usersMatcher.setNodeName("u");
        structuresMatcher.setNodeName("s");
        String inSource = "$source";
        params.put("source", tx.source.toString());
        //TODO: Modify request to not attach to a removeFromStructure for other sources
        if(tx.source == Source.MANUAL)
            inSource = "CASE WHEN length([rs IN u.removedFromStructures WHERE rs = s.externalId]) > 0 THEN '" + Source.UNKNOWN + "' ELSE $source END";

        String profileMatch = "p.name = HEAD(u.profiles)";

        String query =
            "MATCH (s:Structure)<-[:DEPENDS]-(g:ProfileGroup)-[:HAS_PROFILE]->(p:Profile), " +
            "(u:User)-[:MERGED*0..1]->(uu:User) " +
            "WHERE " + structuresMatcher + " AND " + usersMatcher + " AND uu.mergedWith IS NULL AND " + profileMatch + " " +
            "WITH uu, g, s, " + inSource + " AS inSource " +
            "MERGE (uu)-[r:IN]->(g) " +
            "ON CREATE SET r.source = inSource ";

        if(tx.source == Source.MANUAL)
        {
            query +=
                "SET uu.structures = CASE WHEN s.externalId IN uu.structures THEN " +
                "uu.structures ELSE coalesce(uu.structures, []) + s.externalId END, " +
                "uu.removedFromStructures = [removedStruct IN uu.removedFromStructures WHERE removedStruct <> s.externalId] ";
        }

        query +=
            "WITH uu " +
            "OPTIONAL MATCH (uu)-[indpg:IN]-(:DefaultProfileGroup) " +
            "DELETE indpg ";

        usersMatcher.addParams(params);
        structuresMatcher.addParams(params);

        tx.add(query, params, promise);

        applyStructureManagedPreferences(tx, usersMatcher, structuresMatcher);

        return promise.future();
    }

    public static Future<JsonArray> attach(TransactionHelper tx, String userId, String structureId) {
        return attach(tx,
            new IdentifierMatcher<User>(new Id<User, String>(userId)),
            new IdentifierMatcher<Structure>(new Id<Structure, String>(structureId)));
    }

    public static Future<JsonArray> applyStructureManagedPreferences(TransactionHelper tx, NodeMatcher<User> usersMatcher, NodeMatcher<Structure> structuresMatcher) {
        Promise<JsonArray> promise = Promise.promise();

        usersMatcher.setNodeName("u");
        structuresMatcher.setNodeName("s");

        String query =
            "MATCH (s:Structure)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
            "WHERE " + structuresMatcher + " AND " + usersMatcher + " " +
            "OPTIONAL MATCH (u)-[:PREFERS]->(uac:UserAppConf) " +
            "WITH s, u, uac, " +
            "  (s.notificationTimezone IS NOT NULL AND s.notificationTimezone <> '' AND (uac IS NULL OR uac.timezone IS NULL OR NOT uac.timezone CONTAINS $userManagedMarker)) AS shouldUpdateTimezone, " +
            "  (s.notificationQuietHours IS NOT NULL AND s.notificationQuietHours <> '' AND (uac IS NULL OR uac.quietHours IS NULL OR NOT uac.quietHours CONTAINS $userManagedMarker)) AS shouldUpdateQuietHours, " +
            "  ((s.notificationTimezone IS NULL OR s.notificationTimezone = '') AND uac IS NOT NULL AND uac.timezone IS NOT NULL AND uac.timezone CONTAINS $structureManagedMarker) AS shouldClearTimezone, " +
            "  ((s.notificationQuietHours IS NULL OR s.notificationQuietHours = '') AND uac IS NOT NULL AND uac.quietHours IS NOT NULL AND uac.quietHours CONTAINS $structureManagedMarker) AS shouldClearQuietHours " +
            "WHERE shouldUpdateTimezone OR shouldUpdateQuietHours OR shouldClearTimezone OR shouldClearQuietHours " +
            "MERGE (u)-[:PREFERS]->(uac2:UserAppConf) " +
            "FOREACH (_ IN CASE WHEN shouldUpdateTimezone THEN [1] ELSE [] END | SET uac2.timezone = s.notificationTimezone) " +
            "FOREACH (_ IN CASE WHEN shouldUpdateQuietHours THEN [1] ELSE [] END | SET uac2.quietHours = s.notificationQuietHours) " +
            "FOREACH (_ IN CASE WHEN shouldClearTimezone THEN [1] ELSE [] END | SET uac2.timezone = null) " +
            "FOREACH (_ IN CASE WHEN shouldClearQuietHours THEN [1] ELSE [] END | SET uac2.quietHours = null) ";

        JsonObject params = new JsonObject()
            .put("userManagedMarker", USER_MANAGED_MARKER)
            .put("structureManagedMarker", STRUCTURE_MANAGED_MARKER);
        structuresMatcher.addParams(params);
        usersMatcher.addParams(params);
        tx.add(query, params, promise);

        return promise.future();
    }

    public static Future<JsonArray> applyStructureManagedPreferences(TransactionHelper tx, String userId, String structureId) {
        return applyStructureManagedPreferences(tx,
            new IdentifierMatcher<User>(new Id<User, String>(userId)),
            new IdentifierMatcher<Structure>(new Id<Structure, String>(structureId)));
    }

    // Detach a user from a structure
    public static Future<JsonArray> dettach(TransactionHelper tx, NodeMatcher<User> usersMatcher, NodeMatcher<Structure> structuresMatcher) {
        Promise<JsonArray> promise = Promise.promise();

        structuresMatcher.setNodeName("s");
        usersMatcher.setNodeName("u");
        Matcher sourceMatcher = tx.source == Source.MANUAL ? new UniversalMatcher() : new SourceMatcher("attached", Source.UNKNOWN, tx.source);
        CompoundMatcher fullMatcher = new CompoundMatcher(usersMatcher, structuresMatcher, sourceMatcher);

        String query =
            "MATCH (u:User)-[attached:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
            "WHERE " + fullMatcher + " " +  // Match users that are attached to this structure (but exclude people who are only in the manual groups)
            "WITH u, s " +
            "MATCH (u)-[rg:IN]-(g:Group)-[:DEPENDS*1..2]-(s) "; // Find the groups that the user is in that are related to the structure

        JsonObject params = new JsonObject();
        fullMatcher.addParams(params);
        sourceMatcher.addParams(params);
        String inSource = "$source";
        params.put("source", tx.source.toString());

        if(tx.source == Source.MANUAL) {
            query +=
                "WHERE not(g.name ENDS WITH 'AdminLocal') " + // Exclude admin local groups
                "WITH u, rg, g, s " +
			    "SET u.removedFromStructures = [rsId IN coalesce(u.removedFromStructures, []) WHERE rsId <> coalesce(s.externalId, '')] + coalesce(s.externalId, []), " + // Add the structure to the removed structures
                "u.structures = [sId IN u.structures WHERE sId <> s.externalId] " + // Remove user from the structure
                "WITH u, rg, g, s " +
                "OPTIONAL MATCH (s)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(pg:ProfileGroup)<-[rc:IN|COMMUNIQUE]-(u) SET u.classes = [cId IN u.classes WHERE cId <> c.externalId] " + // Remove user from the classes of the structure
                "DELETE rc " + // Remove the user from the class
                "WITH u, rg, g, s ";
        } else {
            query += "WHERE COALESCE(rg.source, 'UNKNOWN') IN ['UNKNOWN'," + inSource + "] "; // Exclude manual groups
        }

        query +=
            "OPTIONAL MATCH (u)-[c:COMMUNIQUE]-(g) " +
            "DELETE rg, c " + // Remove the user from the group
            "WITH u, s " +
            "MATCH (u)-[r:HAS_FUNCTION]->() " + // Find the functions of the user
            "WHERE s.id IN r.scope " +
            "OPTIONAL MATCH (s)-[:HAS_ATTACHMENT*1..]->(ps:Structure) " + // Find the parent structures
            "WITH u, s, r, COLLECT(DISTINCT ps.id) as parentStructures " + // Collect the parent structures
            "WITH u, s, r, [id IN r.scope WHERE id IN parentStructures] as parentADML " + // Check if the user has the ADML function in the parent structures
            "WHERE parentADML = [] " + // If the user does not have the ADML function in the parent structures
            "MATCH (s)<-[:HAS_ATTACHMENT*0..]-(cs:Structure)  " +      // Find the children structures
            "WITH u, s, r, COLLECT(DISTINCT cs.id) as allStructures " + // Collect the children structures
            "WITH u, r, [id IN r.scope WHERE id IN allStructures] as structureToRemove " + // Check if the user has the function in the children structures
            "WHERE structureToRemove <> [] SET r.scope = [sId IN r.scope WHERE NOT sId IN structureToRemove] " + // Remove the structure from the scope
            "WITH u, r, structureToRemove " +
            "MATCH (u)-[inOrComm:IN|COMMUNIQUE]-(fg:FunctionGroup)-[:DEPENDS]->(sd:Structure) " +
            "WHERE sd.id IN structureToRemove AND fg.externalId ENDS WITH 'ADMIN_LOCAL' DELETE inOrComm " + // Remove the user from the admin local groups of the structure
            "WITH r " +
            "WHERE size(r.scope) = 0 DELETE r"; // Remove scope if empty

        tx.add(query, params, promise);

        String dpgQuery =
            "MATCH (u:User) WHERE " + usersMatcher + " " +
            "OPTIONAL MATCH (u)-[:IN]->(pg:ProfileGroup) " +
            "WITH u, COUNT(pg) AS nbProfileGroups " +
            "WHERE nbProfileGroups = 0 " +
            "MATCH (dpg:DefaultProfileGroup)-[:HAS_PROFILE]->(p:Profile) " +
            "WHERE p.name = HEAD(u.profiles) " +
            "MERGE (u)-[:IN]->(dpg)"; // Add users with no structures left to the default groups

        tx.add(dpgQuery, params);

        return promise.future();
    }

    @Override
    public Id getId()
    {
        return this.id;
    }
}
