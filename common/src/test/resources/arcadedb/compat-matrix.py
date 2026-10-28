import json, base64, urllib.request, urllib.error

AUTH = "Basic " + base64.b64encode(b"root:playwithdata").decode()
URL  = "http://localhost:2480/api/v1/command/openent"

def run(cypher, params=None):
    body = {"language": "opencypher", "command": cypher}
    if params:
        body["params"] = params
    req = urllib.request.Request(URL, data=json.dumps(body).encode(),
                                 headers={"Authorization": AUTH, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return True, json.loads(r.read().decode()).get("result")
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode())
            return False, payload.get("detail") or payload.get("error") or str(e)
        except Exception:
            return False, str(e)
    except Exception as e:
        return False, str(e)

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
]

ok = fail = 0
print(f"{'construction':<24}{'verdict':<10}detail")
print("-" * 92)
for t in TESTS:
    label, cypher = t[0], t[1]
    params = t[2] if len(t) > 2 else None
    good, detail = run(cypher, params)
    if label == "prep":
        continue
    if good:
        ok += 1
        print(f"{label:<24}{'OK':<10}")
    else:
        fail += 1
        msg = str(detail).replace("\n", " ")[:62]
        print(f"{label:<24}{'ECHEC':<10}{msg}")
print("-" * 92)
print(f"{ok} OK / {fail} en echec sur {ok+fail} constructions")
