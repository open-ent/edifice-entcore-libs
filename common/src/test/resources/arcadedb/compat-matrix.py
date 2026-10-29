"""Matrice de compatibilite Cypher : ArcadeDB **et** Neo4j 3.1.

Tant que la bascule n'est pas faite, un meme artefact doit tourner sur les deux moteurs :
la production reste sur Neo4j pendant que le code porte y est deploye. Une construction
qui ne passe que d'un cote est donc un defaut, pas une curiosite.

Cette matrice ne testait qu'ArcadeDB, et c'est ainsi que `EXISTS { }` s'est glisse dans le
socle : accepte par ArcadeDB, refuse par Neo4j 3.1 (c'est une forme Neo4j 5).

    ARCADEDB_URL=http://localhost:2480 ARCADEDB_AUTH=root:motdepasse \\
    NEO4J_URL=http://localhost:7474   NEO4J_AUTH=neo4j:motdepasse \\
    python3 compat-matrix.py

Sortie 1 des qu'une construction n'est pas acceptee par les deux moteurs.
Attention : Neo4j doit tourner SANS `cypher.default_language_version=2.3`, sans quoi il
refuse `$parametre` et la matrice est illisible.
"""

import json, base64, os, sys, urllib.request, urllib.error

ARCADE_URL  = os.environ.get("ARCADEDB_URL", "http://localhost:2480").rstrip("/")
ARCADE_BASE = os.environ.get("ARCADEDB_DATABASE", "openent")
ARCADE_AUTH = os.environ.get("ARCADEDB_AUTH", "root:playwithdata")
NEO4J_URL   = os.environ.get("NEO4J_URL", "http://localhost:7474").rstrip("/")
NEO4J_AUTH  = os.environ.get("NEO4J_AUTH", "neo4j:openent")


def _basic(ident):
    return "Basic " + base64.b64encode(ident.encode()).decode()


def _poster(url, corps, ident):
    req = urllib.request.Request(
        url, data=json.dumps(corps).encode(),
        headers={"Authorization": _basic(ident), "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def arcade(cypher, params=None):
    """ArcadeDB : /command accepte lecture comme ecriture."""
    corps = {"language": "opencypher", "command": cypher}
    if params:
        corps["params"] = params
    try:
        _poster("%s/api/v1/command/%s" % (ARCADE_URL, ARCADE_BASE), corps, ARCADE_AUTH)
        return True, ""
    except urllib.error.HTTPError as e:
        try:
            p = json.loads(e.read().decode())
            return False, p.get("detail") or p.get("error") or str(e)
        except Exception:
            return False, str(e)
    except Exception as e:
        return False, str(e)


def neo4j(cypher, params=None):
    corps = {"statements": [{"statement": cypher, "parameters": params or {}}]}
    try:
        d = _poster("%s/db/data/transaction/commit" % NEO4J_URL, corps, NEO4J_AUTH)
    except Exception as e:
        return False, str(e)
    if d.get("errors"):
        return False, d["errors"][0].get("message", "").split("\n")[0]
    return True, ""


TESTS = [
  # --- préparation ---
  ("prep", "MATCH (n:Compat) DETACH DELETE n"),
  ("prep", "CREATE (a:Compat {id:'a', name:'Alice', age:30, tags:['x','y'], email:'a@b.fr'})"),
  ("prep", "CREATE (b:Compat {id:'b', name:'Bob', age:40, tags:[]})"),
  ("prep", "MATCH (a:Compat {id:'a'}), (b:Compat {id:'b'}) CREATE (a)-[:IN_GROUP {since:2020}]->(b)"),

  # --- fonctions ---
  ("count()",            "MATCH (n:Compat) RETURN count(n) AS c"),
  ("collect()",          "MATCH (n:Compat) RETURN collect(n.id) AS ids"),
  ("head()",             "MATCH (n:Compat {id:'a'}) RETURN head(n.tags) AS h"),
  ("last()",             "MATCH (n:Compat {id:'a'}) RETURN last(n.tags) AS l"),
  ("coalesce()",         "MATCH (n:Compat {id:'b'}) RETURN coalesce(n.email, 'defaut') AS e"),
  ("size() sur liste",   "MATCH (n:Compat {id:'a'}) RETURN size(n.tags) AS s"),
  ("length() sur liste", "MATCH (n:Compat {id:'a'}) RETURN length(n.tags) AS s"),
  ("length() sur chemin","MATCH p=(a:Compat)-[:IN_GROUP]->(b) RETURN length(p) AS l"),
  ("labels()",           "MATCH (n:Compat {id:'a'}) RETURN labels(n) AS lb"),
  ("id()",               "MATCH (n:Compat {id:'a'}) RETURN id(n) AS i"),
  ("type()",             "MATCH ()-[r:IN_GROUP]->() RETURN type(r) AS t"),
  ("toLower()",          "RETURN toLower('ABC') AS v"),
  ("toString()",         "RETURN toString(42) AS v"),
  ("split()",            "RETURN split('a,b,c', ',') AS v"),
  ("substring()",        "RETURN substring('abcdef', 1, 3) AS v"),
  ("replace()",          "RETURN replace('abc', 'b', 'X') AS v"),
  ("timestamp()",        "RETURN timestamp() AS v"),
  ("sum()",              "MATCH (n:Compat) RETURN sum(n.age) AS v"),
  ("any()",              "MATCH (n:Compat {id:'a'}) RETURN any(t IN n.tags WHERE t = 'x') AS v"),
  ("all()",              "MATCH (n:Compat {id:'a'}) RETURN all(t IN n.tags WHERE t <> 'z') AS v"),
  ("exists(prop) [2.3]", "MATCH (n:Compat) WHERE exists(n.email) RETURN count(n) AS c"),
  ("IS NOT NULL",        "MATCH (n:Compat) WHERE n.email IS NOT NULL RETURN count(n) AS c"),
  ("filter() [2.3]",     "MATCH (n:Compat {id:'a'}) RETURN filter(t IN n.tags WHERE t='x') AS v"),
  ("comprehension",      "MATCH (n:Compat {id:'a'}) RETURN [t IN n.tags WHERE t='x'] AS v"),
  ("extract() [2.3]",    "MATCH (n:Compat {id:'a'}) RETURN extract(t IN n.tags | toUpper(t)) AS v"),
  ("comprehension map",  "MATCH (n:Compat {id:'a'}) RETURN [t IN n.tags | toUpper(t)] AS v"),
  ("EXISTS { }",         "MATCH (n:Compat) WHERE EXISTS { MATCH (n)-[:IN_GROUP]->() } RETURN count(n) AS c"),

  # --- clauses ---
  ("OPTIONAL MATCH",     "MATCH (n:Compat {id:'b'}) OPTIONAL MATCH (n)-[:NOPE]->(x) RETURN n.id AS id, x AS x"),
  ("DISTINCT",           "MATCH (n:Compat) RETURN DISTINCT n.id AS id"),
  ("WITH + agregat",     "MATCH (n:Compat) WITH count(n) AS c WHERE c > 0 RETURN c"),
  ("CASE WHEN",          "MATCH (n:Compat) RETURN CASE WHEN n.age > 35 THEN 'vieux' ELSE 'jeune' END AS v"),
  ("ORDER BY / LIMIT",   "MATCH (n:Compat) RETURN n.id AS id ORDER BY n.age DESC LIMIT 1"),
  ("STARTS WITH",        "MATCH (n:Compat) WHERE n.name STARTS WITH 'Al' RETURN count(n) AS c"),
  ("ENDS WITH",          "MATCH (n:Compat) WHERE n.name ENDS WITH 'ce' RETURN count(n) AS c"),
  ("CONTAINS",           "MATCH (n:Compat) WHERE n.name CONTAINS 'li' RETURN count(n) AS c"),
  ("IN sur parametre",   "MATCH (n:Compat) WHERE n.id IN $ids RETURN count(n) AS c", {"ids": ["a", "b"]}),
  ("UNWIND",             "UNWIND [1,2,3] AS x RETURN sum(x) AS v"),
  ("UNION",              "MATCH (n:Compat {id:'a'}) RETURN n.id AS id UNION MATCH (n:Compat {id:'b'}) RETURN n.id AS id"),
  ("MERGE",              "MERGE (n:Compat {id:'c'}) RETURN n.id AS id"),
  ("MERGE ON CREATE/MATCH","MERGE (n:Compat {id:'c'}) ON CREATE SET n.k='neuf' ON MATCH SET n.k='revu' RETURN n.k AS v"),
  ("SET",                "MATCH (n:Compat {id:'c'}) SET n.flag = true RETURN n.flag AS v"),
  ("REMOVE",             "MATCH (n:Compat {id:'c'}) REMOVE n.flag RETURN n.id AS id"),
  ("FOREACH",            "MATCH (n:Compat {id:'a'}) FOREACH (t IN n.tags | SET n.touched = true)"),
  ("DETACH DELETE",      "MATCH (n:Compat {id:'c'}) DETACH DELETE n"),
  ("relation variable",  "MATCH (a:Compat)-[r:IN_GROUP]->(b) RETURN r.since AS s"),
  ("chemin variable *",  "MATCH (a:Compat {id:'a'})-[:IN_GROUP*1..3]->(b) RETURN count(b) AS c"),
  ("projection de map",  "MATCH (n:Compat {id:'a'}) RETURN {id: n.id, nom: n.name} AS m"),
  ("CREATE INDEX",       "CREATE INDEX ON :Compat(id)"),
  ("CREATE CONSTRAINT",  "CREATE CONSTRAINT ON (n:Compat) ASSERT n.id IS UNIQUE"),
  # --- formes qui divergent entre moteurs (c'est pour elles que la matrice existe) ---
  ("exists(motif) WHERE",   "MATCH (n:Compat) WHERE exists((n)-[:IN_GROUP]->()) RETURN count(n) AS c"),
  ("exists(motif) RETURN",  "MATCH (n:Compat) RETURN exists((n)-[:IN_GROUP]->()) AS v"),
  ("exists(motif) WITH",    "MATCH (n:Compat) WITH n, exists((n)-[:IN_GROUP]->()) AS v RETURN count(v) AS c"),
  ("motif nu WHERE",        "MATCH (n:Compat) WHERE (n)-[:IN_GROUP]->() RETURN count(n) AS c"),
  ("motif nu RETURN",       "MATCH (n:Compat) RETURN CASE WHEN (n)-[:IN_GROUP]->() THEN 1 ELSE 0 END AS v"),
  ("EXISTS { } RETURN",     "MATCH (n:Compat) RETURN CASE WHEN EXISTS { (n)-[:IN_GROUP]->() } THEN 1 ELSE 0 END AS v"),
  ("OPTIONAL MATCH + count>0", "MATCH (n:Compat) OPTIONAL MATCH (n)-[:IN_GROUP]->(g) WITH n, count(g) > 0 AS v RETURN count(n) AS c"),
  ("$parametre",            "MATCH (n:Compat) WHERE n.id = $cle RETURN count(n) AS c", {"cle": "a"}),
  ("{parametre} [2.3]",     "MATCH (n:Compat) WHERE n.id = {cle} RETURN count(n) AS c", {"cle": "a"}),
  ("HAS() [2.3]",           "MATCH (n:Compat) WHERE HAS(n.email) RETURN count(n) AS c"),
  ("ALL() sur une chaine",  "MATCH (n:Compat {id:'a'}) RETURN ALL(x IN n.name WHERE x <> 'z') AS v"),
  ("scalaire IN liste",     "MATCH (n:Compat {id:'a'}) RETURN n.name IN ['Alice','Bob'] AS v"),
]

# Divergences connues, avec leur raison. La matrice ne sert pas a les faire disparaitre —
# certaines sont irreductibles — mais a signaler toute divergence NOUVELLE, c'est-a-dire
# une forme employee par le code et qu'un seul des deux moteurs accepte.
DIVERGENCES_CONNUES = {
    # Heritage Cypher 2.3 : converti partout dans le socle, garde ici comme temoin.
    "{parametre} [2.3]":   "remplace par $parametre",
    "HAS() [2.3]":         "remplace par IS [NOT] NULL",
    "filter() [2.3]":      "remplace par une comprehension [x IN l WHERE ...]",
    "extract() [2.3]":     "remplace par une comprehension [x IN l | ...]",
    "length() sur liste":  "remplace par size()",
    "ALL() sur une chaine":"ecrire `scalaire IN liste` ; Neo4j coercait la chaine, pas ArcadeDB",
    # Test d'existence : aucune forme ne couvre les trois positions sur les deux moteurs.
    "EXISTS { }":          "forme Neo4j 5 ; en WHERE, utiliser exists(motif)",
    "EXISTS { } RETURN":   "forme Neo4j 5 ; en projection, utiliser exists(motif)",
    "motif nu RETURN":     "ArcadeDB refuse les motifs en projection ; utiliser exists(motif)",
    "exists(motif) WITH":  "ArcadeDB refuse les motifs dans un WITH ; "
                           "utiliser OPTIONAL MATCH + count(x) > 0",
    # DDL : jamais envoye tel quel, ArcadeDbRest le traduit (cf. MIGRATION-ARCADEDB.md).
    "CREATE INDEX":        "traduit par ArcadeDbRest",
    "CREATE CONSTRAINT":   "traduit par ArcadeDbRest",
}

VERDICTS = {(True, True): "les deux", (True, False): "ArcadeDB seul",
            (False, True): "Neo4j seul", (False, False): "aucun"}

communes = divergentes = 0
ecarts = []
divergents = set()
print("%-28s%-16s%s" % ("construction", "verdict", "detail"))
print("-" * 100)
for t in TESTS:
    label, cypher = t[0], t[1]
    params = t[2] if len(t) > 2 else None
    if label == "prep":
        arcade(cypher, params)
        neo4j(cypher, params)
        continue
    a, da = arcade(cypher, params)
    n, dn = neo4j(cypher, params)
    verdict = VERDICTS[(a, n)]
    detail = ""
    if not a:
        detail = "arcade: " + str(da).replace("\n", " ")[:54]
    elif not n:
        detail = "neo4j: " + str(dn).replace("\n", " ")[:54]
    print("%-28s%-16s%s" % (label, verdict, detail))
    if a and n:
        communes += 1
    else:
        divergentes += 1
        divergents.add(label)
        if label not in DIVERGENCES_CONNUES:
            ecarts.append((label, verdict, detail))

print("-" * 100)
print("%d construction(s) acceptee(s) par les deux moteurs, %d divergente(s)"
      % (communes, divergentes))
resolues = [l for l in DIVERGENCES_CONNUES
            if l in {t[0] for t in TESTS} and l not in {e[0] for e in ecarts}
            and l not in divergents]
if ecarts:
    print("\nDIVERGENCE NOUVELLE — forme acceptee par un seul moteur, a proscrire :")
    for label, verdict, detail in ecarts:
        print("  %-28s %-16s %s" % (label, verdict, detail))
    sys.exit(1)
if resolues:
    print("\nDivergences connues desormais sans objet (entree a retirer) : %s"
          % ", ".join(sorted(resolues)))
print("\nAucune divergence nouvelle. Formes a proscrire dans le code :")
for label, raison in sorted(DIVERGENCES_CONNUES.items()):
    print("  %-24s %s" % (label, raison))
