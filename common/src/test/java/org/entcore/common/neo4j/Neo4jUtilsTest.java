package org.entcore.common.neo4j;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** La clause SET générée utilise les paramètres OpenCypher (`$nom`) : l'ancienne syntaxe `{nom}` est refusée par ArcadeDB. */
public class Neo4jUtilsTest {

	@Test
	public void setPropertiesUsesOpenCypherParameters() {
		final JsonObject json = new JsonObject().put("displayName", "A").put("mobile", "06");
		assertEquals("u.displayName = $displayName, u.mobile = $mobile ", Neo4jUtils.nodeSetPropertiesFromJson("u", json));
	}

	@Test
	public void ignoredAttributesAreSkippedAndNamesKeepOnlyWordCharacters() {
		final JsonObject json = new JsonObject().put("id", "1").put("home-phone", "0102");
		assertEquals("u.homephone = $homephone ", Neo4jUtils.nodeSetPropertiesFromJson("u", json, "id"));
	}

	@Test
	public void emptyJsonGivesBlankClause() {
		assertEquals(" ", Neo4jUtils.nodeSetPropertiesFromJson("u", new JsonObject()));
	}
}
