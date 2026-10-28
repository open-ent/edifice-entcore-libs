#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Banc de comparaison Neo4j / ArcadeDB sur les requêtes réelles d'entcore,
contre les données de l'ENT (≈700 000 nœuds, ≈2,58 M relations).

Trois configurations :
  Neo4j 3.1.9 C2.3  le serveur 3.1.9 avec le pin cypher.default_language_version=2.3,
                    c'est-à-dire la production telle qu'elle tourne aujourd'hui
  Neo4j 3.1.9 C3.1  LE MÊME serveur 3.1.9, pin levé par un préfixe CYPHER 3.1 : c'est
                    l'« étape 0 », et elle ne change rien d'autre
  ArcadeDB 26.10.2  requêtes portées — ce que l'ENT local envoie

Les deux premières lignes ne sont pas deux serveurs : c'est une seule base 3.1.9,
interrogée dans deux versions du langage.

Les requêtes sont celles qu'entcore émet réellement, dans leurs deux formes :
la forme héritée (Cypher 2.3, ce que la production envoie aujourd'hui) et la
forme portée sur la branche feature/opencypher-arcadedb.

Prérequis : les deux moteurs doivent porter les MÊMES index, sinon le banc
mesure un balayage contre un accès indexé. ArcadeDB les reçoit par
scripts/ops/arcadedb-sync-indexes.py.

Usage :
  python3 entcore-queries-benchmark.py [chauffe] [itérations]
"""

U = "407118a8-968d-4b32-9253-2153043f6572"
S = "d462770d-efe6-4743-960b-3de69e8b21ce"

REQUETES = [

 dict(nom="plancher protocolaire", params={},
      origine="(aucune) — mesure le coût du round-trip HTTP seul",
      heritee="RETURN 1 AS un"),

 dict(nom="connexion : recherche du compte", params={"login": "adminopenent3"},
      origine="OAuthDataHandler.getUserIdNeo4j",
      heritee=("MATCH (n:User) WHERE n.login={login} "
               "OPTIONAL MATCH (p:Profile) WHERE HAS(n.profiles) AND p.name = head(n.profiles) "
               "RETURN DISTINCT n.id as userId, n.password as password, p.blocked as blockedProfile, "
               "n.otp as otp, n.blocked as blockedUser, head(n.profiles) as profile"),
      portee=("MATCH (n:User) WHERE n.login=$login "
              "OPTIONAL MATCH (p:Profile) WHERE n.profiles IS NOT NULL AND p.name = head(n.profiles) "
              "RETURN DISTINCT n.id as userId, n.password as password, p.blocked as blockedProfile, "
              "n.otp as otp, n.blocked as blockedUser, head(n.profiles) as profile")),

 dict(nom="session : construction du profil", params={"id": U},
      origine="AuthManager.generateSessionInfos (requête principale)",
      heritee=("MATCH (n:User {id : {id}}) WHERE HAS(n.login) "
               "OPTIONAL MATCH n-[:IN]->(mgroup: ManualGroup)-[:DEPENDS]->(mStruct:Structure) "
               "WITH n, COLLECT(distinct {id: mgroup.id, name: mgroup.name}) as manualGroups "
               "OPTIONAL MATCH n-[:IN]->(gp:Group) "
               "OPTIONAL MATCH (gp:ProfileGroup)-[:DEPENDS]->(s:Structure) "
               "OPTIONAL MATCH gp-[:DEPENDS]->(c:Class) "
               "WITH n, manualGroups, COLLECT(distinct [c.id, c.name]) as classes, "
               "COLLECT(distinct [s.id, s.name, s.UAI]) as structures, COLLECT(distinct gp.id) as groupsIds "
               "OPTIONAL MATCH n-[rf:HAS_FUNCTION]->(f:Function) "
               "OPTIONAL MATCH n<-[:RELATED]-(child:User) "
               "RETURN distinct n.login as login, n.displayName as username, classes, structures, "
               "manualGroups, groupsIds, COLLECT(distinct [f.externalId, rf.scope]) as functions, "
               "COLLECT(distinct child.id) as children"),
      portee=("MATCH (n:User {id : $id}) WHERE n.login IS NOT NULL "
              "OPTIONAL MATCH (n)-[:IN]->(mgroup: ManualGroup)-[:DEPENDS]->(mStruct:Structure) "
              "WITH n, COLLECT(distinct {id: mgroup.id, name: mgroup.name}) as manualGroups "
              "OPTIONAL MATCH (n)-[:IN]->(gp:Group) "
              "OPTIONAL MATCH (gp:ProfileGroup)-[:DEPENDS]->(s:Structure) "
              "OPTIONAL MATCH (gp)-[:DEPENDS]->(c:Class) "
              "WITH n, manualGroups, COLLECT(distinct [c.id, c.name]) as classes, "
              "COLLECT(distinct [s.id, s.name, s.UAI]) as structures, COLLECT(distinct gp.id) as groupsIds "
              "OPTIONAL MATCH (n)-[rf:HAS_FUNCTION]->(f:Function) "
              "OPTIONAL MATCH (n)<-[:RELATED]-(child:User) "
              "RETURN distinct n.login as login, n.displayName as username, classes, structures, "
              "manualGroups, groupsIds, COLLECT(distinct [f.externalId, rf.scope]) as functions, "
              "COLLECT(distinct child.id) as children")),

 dict(nom="session : droits et applications", params={"id": U},
      origine="AuthManager.generateSessionInfos (query2)",
      heritee=("MATCH (u:User {id : {id}}) WHERE HAS(u.login) "
               "MATCH (u)-[:IN]->()-[:AUTHORIZED]->(r:Role) WITH distinct r AS r "
               "MATCH (r)-[:AUTHORIZE]->(a:Action)<-[:PROVIDE]-(app:Application) "
               "RETURN DISTINCT COLLECT(distinct [a.name,a.type]) as authorizedActions, "
               "COLLECT(distinct [app.name,app.address]) as apps"),
      portee=("MATCH (u:User {id : $id}) WHERE u.login IS NOT NULL "
              "MATCH (u)-[:IN]->()-[:AUTHORIZED]->(r:Role) WITH distinct r AS r "
              "MATCH (r)-[:AUTHORIZE]->(a:Action)<-[:PROVIDE]-(app:Application) "
              "RETURN DISTINCT COLLECT(distinct [a.name,a.type]) as authorizedActions, "
              "COLLECT(distinct [app.name,app.address]) as apps")),

 dict(nom="annuaire : liste d'un établissement", params={"structureId": S},
      origine="DefaultSchoolService.userList (593 lignes)",
      heritee=("MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s:Structure {id:{structureId}}) "
               "OPTIONAL MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(class: Class) "
               "WITH distinct u, s, CASE WHEN class IS NULL THEN [] ELSE COLLECT(distinct {id: class.id, name: class.name}) END as classes "
               "OPTIONAL MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(struct: Structure) "
               "WITH distinct u, classes, COLLECT (distinct {id: struct.id, name: struct.name}) as structures "
               "OPTIONAL MATCH u-[:IN]->(fgroup: FunctionalGroup) "
               "WITH distinct u, classes, structures, CASE WHEN fgroup IS NULL THEN [] ELSE COLLECT(distinct fgroup.name) END as functionalGroups "
               "RETURN DISTINCT u.id as id, u.login as login, u.firstName as firstName, u.lastName as lastName, "
               "u.displayName as displayName, classes, structures, functionalGroups ORDER BY lastName, firstName"),
      portee=("MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s:Structure {id:$structureId}) "
              "OPTIONAL MATCH (u)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(class: Class) "
              "WITH distinct u, s, CASE WHEN class IS NULL THEN [] ELSE COLLECT(distinct {id: class.id, name: class.name}) END as classes "
              "OPTIONAL MATCH (u)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(struct: Structure) "
              "WITH distinct u, classes, COLLECT (distinct {id: struct.id, name: struct.name}) as structures "
              "OPTIONAL MATCH (u)-[:IN]->(fgroup: FunctionalGroup) "
              "WITH distinct u, classes, structures, CASE WHEN fgroup IS NULL THEN [] ELSE COLLECT(distinct fgroup.name) END as functionalGroups "
              "RETURN DISTINCT u.id as id, u.login as login, u.firstName as firstName, u.lastName as lastName, "
              "u.displayName as displayName, classes, structures, functionalGroups ORDER BY lastName, firstName")),

 dict(nom="annuaire : tous les établissements", params={},
      origine="DefaultSchoolService.listAdmin (536 lignes)",
      heritee=("MATCH (s:Structure) OPTIONAL MATCH (s)<-[:BELONGS]-(c:Class) "
               "RETURN s.id as id, s.UAI as UAI, s.name as name, s.externalId as externalId, "
               "COLLECT(distinct {id: c.id, name: c.name}) as classes ORDER BY name")),

 dict(nom="messagerie : utilisateurs visibles", params={"userId": "f5c0dd5d-f6d9-4b3e-b11c-eed64e925958"},
      origine="DefaultCommunicationService.visibleUsers (parcours de groupes)",
      heritee=("MATCH (n:User {id:{userId}})-[:COMMUNIQUE]->(g:Group)-[:COMMUNIQUE]->(m:User) "
               "WHERE NOT(HAS(m.deleteDate)) RETURN DISTINCT m.id as id, m.displayName as displayName "
               "ORDER BY displayName LIMIT 500"),
      portee=("MATCH (n:User {id:$userId})-[:COMMUNIQUE]->(g:Group)-[:COMMUNIQUE]->(m:User) "
              "WHERE m.deleteDate IS NULL RETURN DISTINCT m.id as id, m.displayName as displayName "
              "ORDER BY displayName LIMIT 500")),

 dict(nom="recherche par préfixe de nom", params={"p": "mart"},
      origine="recherche d'annuaire, sur displayNameSearchField",
      heritee=("MATCH (u:User) WHERE u.displayNameSearchField STARTS WITH {p} "
               "RETURN u.id as id, u.displayName as displayName LIMIT 100"),
      portee=("MATCH (u:User) WHERE u.displayNameSearchField STARTS WITH $p "
              "RETURN u.id as id, u.displayName as displayName LIMIT 100")),

 dict(nom="écriture : marquage d'un compte", params={"id": U},
      origine="SET sur une propriété du compte (dernière connexion)",
      heritee="MATCH (u:User {id:{id}}) SET u.benchMarker = timestamp() RETURN u.benchMarker as t",
      portee="MATCH (u:User {id:$id}) SET u.benchMarker = timestamp() RETURN u.benchMarker as t"),
]


import base64, json, statistics, sys, time, urllib.error, urllib.request

NEO = "http://localhost:7474/db/data/transaction/commit"
ARC = "http://localhost:2480/api/v1"
AUTH = "Basic " + base64.b64encode(b"root:openentarcade").decode()
ECRITURE = ("SET ", "CREATE", "MERGE", "DELETE", "REMOVE")


def appel_neo4j(requete, params, version):
    corps = json.dumps({"statements": [
        {"statement": "CYPHER %s %s" % (version, requete), "parameters": params}]}).encode()
    r = urllib.request.Request(NEO, corps, {"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=600) as rep:
        charge = json.load(rep)
    if charge.get("errors"):
        raise RuntimeError(charge["errors"][0].get("message", "")[:140])
    return len(charge["results"][0]["data"])


def appel_arcadedb(requete, params):
    ecrit = any(m.lower() in requete.lower() for m in ECRITURE)
    chemin = "/command/openent" if ecrit else "/query/openent"
    corps = {"language": "opencypher", "command": requete}
    if params:
        corps["params"] = params
    r = urllib.request.Request(ARC + chemin, json.dumps(corps).encode(),
                               {"Content-Type": "application/json", "Authorization": AUTH})
    try:
        with urllib.request.urlopen(r, timeout=600) as rep:
            return len(json.load(rep)["result"])
    except urllib.error.HTTPError as e:
        d = json.loads(e.read().decode())
        raise RuntimeError(str(d.get("detail", d.get("error", "")))[:140])


# Les deux premières configurations sont LE MÊME SERVEUR Neo4j 3.1.9. Ce qui les
# sépare est la version du langage Cypher, forcée par requête : la base est épinglée
# par `cypher.default_language_version=2.3`, et C3.1 lève ce pin sans rien changer
# d'autre. D'où des étiquettes qui portent les deux versions, celle du serveur et
# celle du dialecte — les confondre ferait croire à deux serveurs différents.
MOTEURS = [
    ("Neo4j 3.1.9 C2.3", lambda q, p: appel_neo4j(q, p, "2.3"), "heritee"),
    ("Neo4j 3.1.9 C3.1", lambda q, p: appel_neo4j(q, p, "3.1"), "portee"),
    ("ArcadeDB 26.10.2", appel_arcadedb,                        "portee"),
]


def mesurer(fn, requete, params, chauffe, iterations):
    for _ in range(chauffe):
        fn(requete, params)
    temps = []
    lignes = 0
    for _ in range(iterations):
        t = time.perf_counter()
        lignes = fn(requete, params)
        temps.append((time.perf_counter() - t) * 1000)
    return statistics.median(temps), min(temps), lignes


def main():
    chauffe = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    iterations = int(sys.argv[2]) if len(sys.argv) > 2 else 15
    resultats = []
    for cas in REQUETES:
        ligne = {"nom": cas["nom"], "origine": cas["origine"], "mesures": {}}
        for etiquette, fn, dialecte in MOTEURS:
            requete = cas.get(dialecte) or cas["heritee"]
            try:
                med, mini, lignes = mesurer(fn, requete, cas["params"], chauffe, iterations)
                ligne["mesures"][etiquette] = {"median": med, "min": mini, "lignes": lignes}
            except Exception as e:
                ligne["mesures"][etiquette] = {"erreur": str(e)}
            sys.stderr.write("  %-36s %-10s ok\n" % (cas["nom"], etiquette))
            sys.stderr.flush()
        resultats.append(ligne)
    json.dump(resultats, open("resultats-entcore-queries.json", "w"), ensure_ascii=False, indent=1)

    largeur = max(len(r["nom"]) for r in resultats)
    print("\nLatence médiane en ms — %d itérations après %d de chauffe\n" % (iterations, chauffe))
    print("%-*s %18s %18s %18s   %s"
          % (largeur, "requête", MOTEURS[0][0], MOTEURS[1][0], MOTEURS[2][0], "lignes"))
    print("-" * (largeur + 74))
    for r in resultats:
        cells = []
        for e in (m[0] for m in MOTEURS):
            m = r["mesures"][e]
            cells.append("%18.2f" % m["median"] if "median" in m else "%18s" % "échec")
        lignes = next((str(m["lignes"]) for m in r["mesures"].values() if "lignes" in m), "?")
        print("%-*s %s %s %s   %s" % (largeur, r["nom"], cells[0], cells[1], cells[2], lignes))
    for r in resultats:
        for e, m in r["mesures"].items():
            if "erreur" in m:
                print("\n%s / %s : %s" % (r["nom"], e, m["erreur"]))


if __name__ == "__main__":
    main()
