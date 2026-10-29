package org.entcore.common.neo4j;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Nom de colonne rendu à un sommet qu'ArcadeDB renvoie à plat (projection d'un seul nœud).
 * Les requêtes sont celles de viescolaire ({@code DefaultClasseService.listClasses}).
 */
public class ArcadeDbRestColumnTest {

	@Test
	public void colonneSimple() {
		assertEquals("m", ArcadeDbRest.singleColumnOf("MATCH (m:Class) WHERE m.name IS NOT NULL RETURN m "));
	}

	@Test
	public void distinctEtParentheses() {
		assertEquals("m", ArcadeDbRest.singleColumnOf("MATCH (m:ManualGroup) RETURN distinct(m) "));
		assertEquals("s", ArcadeDbRest.singleColumnOf("MATCH (s:Structure) RETURN DISTINCT s ORDER BY s.name"));
	}

	@Test
	public void aliasEtPointVirgule() {
		assertEquals("m", ArcadeDbRest.singleColumnOf(
				"MATCH (m:Class) RETURN m UNION MATCH (s)--(c) WHERE (c:FunctionalGroup OR c:ManualGroup) RETURN c AS m;"));
	}

	@Test
	public void limiteEtSkip() {
		assertEquals("u", ArcadeDbRest.singleColumnOf("MATCH (u:User) RETURN u SKIP 10 LIMIT 5"));
	}

	@Test
	public void plusieursColonnes() {
		assertNull(ArcadeDbRest.singleColumnOf("MATCH (u:User) RETURN u, u.id AS id"));
		assertEquals("l", ArcadeDbRest.singleColumnOf("MATCH (u:User) RETURN collect(u.id, u.name) AS l"));
	}

	@Test
	public void sansReturn() {
		assertNull(ArcadeDbRest.singleColumnOf("MATCH (u:User) SET u.x = 1"));
		assertNull(ArcadeDbRest.singleColumnOf(null));
	}
}
