#!/usr/bin/env python3
"""
Banc de comparaison Neo4j / ArcadeDB sur une forme de graphe ENT.

Ce que ce banc mesure : la latence de requêtes représentatives de l'annuaire
entcore, vues depuis le client HTTP, sur un jeu synthétique, à ressources
conteneur égales.

Ce qu'il ne mesure pas : la charge réelle d'une plateforme, la concurrence,
la montée en charge, le comportement à chaud sur plusieurs Go de données, ni
la stabilité dans la durée. Il sert à décider s'il vaut la peine d'aller plus
loin, pas à conclure.
"""
import argparse, base64, json, re, statistics, sys, time, urllib.error, urllib.request

# --------------------------------------------------------------- dialectes

def render(q, dialect):
    """Rend une requête neutre dans le dialecte visé.

    Les marqueurs sont traités par expression régulière, dans un seul passage
    par marqueur : un remplacement naïf de ')%' casserait les autres marqueurs.
    """
    out = q

    # %EXISTS(expr)% -> has(expr) | exists(expr) | expr IS NOT NULL
    def _exists(m):
        inner = m.group(1).strip()
        if dialect == "cypher23":
            return f"has({inner})"
        if dialect == "cypher31":
            return f"exists({inner})"
        return f"{inner} IS NOT NULL"
    out = re.sub(r'%EXISTS\((.*?)\)%', _exists, out)

    # %PREFIX(champ, valeur)% -> =~ 'valeur.*' (2.3, pas de STARTS WITH) | STARTS WITH
    def _prefix(m):
        field, value = m.group(1).strip(), m.group(2).strip()
        if dialect == "cypher23":
            return f"{field} =~ ({value} + '.*')"
        return f"{field} STARTS WITH {value}"
    out = re.sub(r'%PREFIX\(([^,]+),\s*(.*?)\)%', _prefix, out)

    # paramètres : $x pour Cypher 5 / openCypher, {x} pour 2.3 et 3.1
    if dialect in ("cypher23", "cypher31"):
        out = re.sub(r'\$([a-zA-Z_][a-zA-Z0-9_]*)', r'{\1}', out)
    return out

# ----------------------------------------------------------------- moteurs

class Engine:
    def __init__(self, name, dialect):
        self.name, self.dialect = name, dialect
    def _post(self, url, body, headers):
        req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                     headers={**headers, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=300) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else {}
    def seed_key(self):
        raise NotImplementedError
    def run(self, q, params=None, write=False):
        raise NotImplementedError

class Neo4jEngine(Engine):
    def __init__(self, name, dialect, url, auth=None, prefix=""):
        super().__init__(name, dialect)
        self.url, self.prefix = url, prefix
        self.headers = {}
        if auth:
            self.headers["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
    def seed_key(self):
        return self.url
    def run(self, q, params=None, write=False):
        stmt = self.prefix + render(q, self.dialect)
        body = {"statements": [{"statement": stmt, "parameters": params or {}}]}
        res = self._post(self.url, body, self.headers)
        if res.get("errors"):
            raise RuntimeError(res["errors"][0].get("message", str(res["errors"])))
        data = res["results"][0]["data"]
        return [d["row"] for d in data]

class ArcadeEngine(Engine):
    def __init__(self, name, base, db, auth):
        super().__init__(name, "opencypher")
        self.base, self.db = base, db
        self.headers = {"Authorization": "Basic " + base64.b64encode(auth.encode()).decode()}
        # la base doit exister avant toute requête
        try:
            self._post(f"{base}/api/v1/server", {"command": f"create database {db}"}, self.headers)
        except Exception:
            pass   # existe déjà
    def seed_key(self):
        return f"{self.base}/{self.db}"
    def run(self, q, params=None, write=False):
        stmt = render(q, self.dialect)
        endpoint = "command" if write else "query"
        body = {"language": "opencypher", "command": stmt}
        if params:
            body["params"] = params
        res = self._post(f"{self.base}/api/v1/{endpoint}/{self.db}", body, self.headers)
        return res.get("result", [])

# ------------------------------------------------------------- jeu d'essai

SCHEMA = {
    "cypher23":   ["CREATE INDEX ON :User(id)", "CREATE INDEX ON :Group(id)",
                   "CREATE INDEX ON :Structure(id)", "CREATE INDEX ON :User(displayNameSearchField)"],
    "cypher31":   ["CREATE INDEX ON :User(id)", "CREATE INDEX ON :Group(id)",
                   "CREATE INDEX ON :Structure(id)", "CREATE INDEX ON :User(displayNameSearchField)"],
    "cypher5":    ["CREATE INDEX user_id IF NOT EXISTS FOR (n:User) ON (n.id)",
                   "CREATE INDEX group_id IF NOT EXISTS FOR (n:Group) ON (n.id)",
                   "CREATE INDEX struct_id IF NOT EXISTS FOR (n:Structure) ON (n.id)",
                   "CREATE INDEX user_dnsf IF NOT EXISTS FOR (n:User) ON (n.displayNameSearchField)"],
    "opencypher": ["CREATE INDEX FOR (n:User) ON (n.id)", "CREATE INDEX FOR (n:Group) ON (n.id)",
                   "CREATE INDEX FOR (n:Structure) ON (n.id)",
                   "CREATE INDEX FOR (n:User) ON (n.displayNameSearchField)"],
}

NAMES = ["Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand",
         "Leroy", "Moreau", "Simon", "Laurent", "Lefebvre", "Michel", "Garcia", "David"]

def seed(engine, n_struct, n_users, batch, log):
    log(f"  purge")
    engine.run("MATCH (n) DETACH DELETE n", write=True)

    log(f"  {n_struct} structures, {n_struct*3} groupes de profil")
    structs = [{"id": f"s{i}", "name": f"Etablissement {i}", "UAI": f"0{750000+i}X"} for i in range(n_struct)]
    engine.run("UNWIND $rows AS r CREATE (s:Structure {id:r.id, name:r.name, UAI:r.UAI})",
               {"rows": structs}, write=True)
    groups = []
    for i in range(n_struct):
        for p in ("Student", "Teacher", "Relative"):
            groups.append({"id": f"g{i}-{p}", "name": f"{p} {i}", "sid": f"s{i}", "filter": p})
    engine.run("UNWIND $rows AS r MATCH (s:Structure {id:r.sid}) "
               "CREATE (g:Group {id:r.id, name:r.name, filter:r.filter})-[:DEPENDS]->(s)",
               {"rows": groups}, write=True)

    log(f"  {n_users} utilisateurs par lots de {batch}")
    profiles = ("Student", "Teacher", "Relative")
    for start in range(0, n_users, batch):
        rows = []
        for k in range(start, min(start + batch, n_users)):
            si = k % n_struct
            p = profiles[k % 3]
            last = NAMES[k % len(NAMES)]
            rows.append({"id": f"u{k}", "dn": f"Prenom{k} {last}",
                         "dnsf": f"prenom{k}{last.lower()}",
                         "login": f"prenom{k}.{last.lower()}",
                         "gid": f"g{si}-{p}"})
        engine.run("UNWIND $rows AS r MATCH (g:Group {id:r.gid}) "
                   "CREATE (u:User {id:r.id, displayName:r.dn, displayNameSearchField:r.dnsf, "
                   "login:r.login, activationCode:r.login})-[:IN]->(g)",
                   {"rows": rows}, write=True)

    log(f"  index")
    for ddl in SCHEMA[engine.dialect]:
        try:
            engine.run(ddl, write=True)
        except Exception as e:
            log(f"    (index ignoré : {str(e)[:60]})")
    time.sleep(3)

# ---------------------------------------------------------------- requêtes

QUERIES = [
    ("Q1 lookup par id indexe",
     "MATCH (u:User {id:$id}) RETURN u.displayName AS n", lambda i: {"id": f"u{i*37%20000}"}, False),
    ("Q2 1 saut : groupes d'un user",
     "MATCH (u:User {id:$id})-[:IN]->(g:Group) RETURN g.id AS id", lambda i: {"id": f"u{i*37%20000}"}, False),
    ("Q3 2 sauts : structures d'un user",
     "MATCH (u:User {id:$id})-[:IN]->(:Group)-[:DEPENDS]->(s:Structure) RETURN DISTINCT s.id AS id",
     lambda i: {"id": f"u{i*37%20000}"}, False),
    ("Q4 3 sauts : pairs visibles",
     "MATCH (u:User {id:$id})-[:IN]->(g:Group)<-[:IN]-(p:User) RETURN count(p) AS c",
     lambda i: {"id": f"u{i*37%20000}"}, False),
    ("Q5 agregat par structure",
     "MATCH (s:Structure)<-[:DEPENDS]-(:Group)<-[:IN]-(u:User) "
     "RETURN s.id AS id, count(u) AS c ORDER BY c DESC LIMIT 10", lambda i: {}, False),
    ("Q6 recherche par prefixe",
     "MATCH (u:User) WHERE %PREFIX(u.displayNameSearchField, $q)% RETURN u.id AS id LIMIT 20",
     lambda i: {"q": f"prenom{i%10}"}, False),
    ("Q7 ecriture : user + 2 liens",
     "MATCH (g:Group {id:$g1}), (h:Group {id:$g2}) "
     "CREATE (u:User {id:$id, displayName:$dn})-[:IN]->(g) CREATE (u)-[:IN]->(h) RETURN u.id AS id",
     lambda i: {"id": f"w{i}", "dn": f"Ecrit {i}", "g1": "g0-Student", "g2": "g1-Teacher"}, True),

    # --- requêtes lourdes : au-dessus du plancher protocolaire, elles mesurent le moteur ---
    ("Q8 parcours global + agregat",
     "MATCH (s:Structure)<-[:DEPENDS]-(g:Group)<-[:IN]-(u:User) "
     "WITH s.id AS sid, g.filter AS profil, count(u) AS c "
     "RETURN sid, profil, c ORDER BY c DESC LIMIT 50", lambda i: {}, False, 8),
    ("Q9 paires dans les groupes",
     "MATCH (u:User)-[:IN]->(g:Group)<-[:IN]-(p:User) RETURN count(p) AS c", lambda i: {}, False, 4),
    ("Q10 gros jeu de resultats",
     "MATCH (u:User) RETURN u.id AS id, u.displayName AS dn LIMIT 20000", lambda i: {}, False, 8),
    ("Q11 ecriture en lot (1000)",
     "UNWIND $rows AS r CREATE (u:Bulk {id:r.id, dn:r.dn}) RETURN count(u) AS c",
     lambda i: {"rows": [{"id": f"b{i}-{k}", "dn": f"Lot {k}"} for k in range(1000)]}, True, 6),
]

def protocol_floor(engine, n=80):
    """Round-trip d'un RETURN 1 : ce que coûte le protocole, hors moteur."""
    for _ in range(20):
        engine.run("RETURN 1 AS n")
    t = []
    for _ in range(n):
        t0 = time.perf_counter()
        engine.run("RETURN 1 AS n")
        t.append((time.perf_counter() - t0) * 1000)
    return statistics.median(t)

def bench(engine, iters, warmup, log):
    results = {}
    for entry in QUERIES:
        label, q, mkparams, write = entry[0], entry[1], entry[2], entry[3]
        n = entry[4] if len(entry) > 4 else iters          # itérations propres aux requêtes lourdes
        w = min(warmup, max(2, n // 3))
        try:
            for i in range(w):
                engine.run(q, mkparams(i), write=write)
            times = []
            for i in range(n):
                t0 = time.perf_counter()
                engine.run(q, mkparams(w + i), write=write)
                times.append((time.perf_counter() - t0) * 1000)
            times.sort()
            results[label] = {
                "p50": statistics.median(times),
                "p95": times[min(len(times) - 1, int(len(times) * 0.95))],
                "moy": statistics.fmean(times),
            }
        except Exception as e:
            results[label] = {"erreur": str(e)[:70]}
            log(f"    {label} : ECHEC {str(e)[:70]}")
    return results

# -------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=20000)
    ap.add_argument("--structures", type=int, default=20)
    ap.add_argument("--iters", type=int, default=60)
    ap.add_argument("--warmup", type=int, default=15)
    ap.add_argument("--batch", type=int, default=1000)
    ap.add_argument("--skip-seed", action="store_true")
    args = ap.parse_args()

    engines = [
        Neo4jEngine("Neo4j 3.1.9 / Cypher 2.3", "cypher23",
                    "http://localhost:7475/db/data/transaction/commit", prefix="CYPHER 2.3 "),
        Neo4jEngine("Neo4j 3.1.9 / Cypher 3.1", "cypher31",
                    "http://localhost:7475/db/data/transaction/commit", prefix="CYPHER 3.1 "),
        Neo4jEngine("Neo4j 5.21", "cypher5",
                    "http://localhost:7476/db/neo4j/tx/commit", auth="neo4j:benchpassword"),
        ArcadeEngine("ArcadeDB 26.11", "http://localhost:2481", "bench", "root:benchpassword"),
    ]

    def log(m): print(m, flush=True)

    all_results = {}
    seeded = set()   # les deux moteurs 3.1.9 partagent un serveur : on ne charge qu'une fois
    for e in engines:
        log(f"\n=== {e.name} ===")
        try:
            target = e.seed_key()
            if not args.skip_seed and target not in seeded:
                t0 = time.perf_counter()
                seed(e, args.structures, args.users, args.batch, log)
                log(f"  chargement : {time.perf_counter()-t0:.1f} s")
                seeded.add(target)
            elif target in seeded:
                log("  jeu de donnees deja charge sur ce serveur")
            floor = protocol_floor(e)
            log(f"  plancher protocolaire (RETURN 1) : {floor:.2f} ms")
            res = bench(e, args.iters, args.warmup, log)
            res["__floor"] = {"p50": floor, "p95": floor, "moy": floor}
            all_results[e.name] = res
        except Exception as ex:
            log(f"  moteur indisponible : {str(ex)[:160]}")
            all_results[e.name] = {}

    # rapport
    print("\n" + "=" * 136)
    print(f"Latence mediane (p50) en ms — {args.users} users, {args.structures} structures, "
          f"{args.iters} iterations")
    print("=" * 136)
    names = [e.name for e in engines]
    print(f"{'requete':<32}" + "".join(f"{n:>26}" for n in names))
    print("-" * 136)
    print(f"{'plancher protocolaire':<32}" + "".join(
        f"{all_results.get(n, {}).get('__floor', {}).get('p50', float('nan')):>26.2f}" for n in names))
    print("-" * 136)
    for entry in QUERIES:
        label = entry[0]
        cells = []
        for n in names:
            r = all_results.get(n, {}).get(label)
            if not r:
                cells.append("n/a")
            elif "erreur" in r:
                cells.append("ECHEC")
            else:
                cells.append(f"{r['p50']:.2f}")
        print(f"{label:<32}" + "".join(f"{c:>26}" for c in cells))
    print("-" * 136)
    print(f"{'p95 agregee (somme)':<32}", end="")
    for n in names:
        rs = [r for k, r in all_results.get(n, {}).items() if "p95" in r and k != "__floor"]
        print(f"{(sum(r['p95'] for r in rs) if rs else 0):>25.2f} " if rs else f"{'n/a':>26}", end="")
    print()
    json.dump(all_results, open("bench-results.json", "w"), indent=2, ensure_ascii=False)
    print("\nDetail ecrit dans bench-results.json")

if __name__ == "__main__":
    main()
