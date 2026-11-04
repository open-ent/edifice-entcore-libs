package org.entcore.common.neo4j;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Texte d'instruction porté par les journaux. Une requête emballée ne laissait aucune trace
 * de ce qu'elle était ; elle doit maintenant se reconnaître sur une seule ligne.
 */
public class ArcadeDbRestStatementLogTest {

	@Test
	public void surUneSeuleLigne() {
		assertEquals("MATCH (u:User) RETURN u",
				ArcadeDbRest.summarize("MATCH (u:User)\n\tRETURN   u  "));
	}

	@Test
	public void sansInstruction() {
		assertEquals("(aucune)", ArcadeDbRest.summarize(null));
	}

	@Test
	public void instructionCourteIntacte() {
		final String courte = "MATCH (s:Structure {id: $id}) RETURN s.name as name";
		assertEquals(courte, ArcadeDbRest.summarize(courte));
	}

	@Test
	public void instructionLongueBornee() {
		final StringBuilder longue = new StringBuilder("MATCH (u:User) WHERE ");
		while (longue.length() < 2000) {
			longue.append("u.id <> 'x' AND ");
		}
		final String resume = ArcadeDbRest.summarize(longue.toString());

		assertTrue("le résumé reste borné", resume.length() < longue.length());
		assertTrue("il commence par l'instruction", resume.startsWith("MATCH (u:User) WHERE "));
		assertTrue("il dit la longueur écartée", resume.endsWith(" caractères)"));
	}
}
