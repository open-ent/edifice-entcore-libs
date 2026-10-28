# Banc de comparaison Neo4j / ArcadeDB

`graph-benchmark.py` compare quatre configurations sur une forme de graphe ENT
(structures, groupes de profil, utilisateurs) :

| Configuration | Ce qu'elle représente |
|---|---|
| Neo4j 3.1.9 / Cypher 2.3 | la production actuelle du monolithe, pin `cypher.default_language_version=2.3` |
| Neo4j 3.1.9 / Cypher 3.1 | la même base **sans** le pin : c'est l'« étape 0 » |
| Neo4j 5.21 | la cible d'open-ent |
| ArcadeDB 26.11 | le candidat Apache 2.0 |

Les quatre dialectes sont rendus depuis une requête neutre, ce qui évite d'avantager
un moteur par une formulation mieux adaptée.

## Lancer

```bash
docker run -d --name bench-neo4j31  --memory=3g --cpus=4 -p 7475:7474 ghcr.io/open-ent/neo4j:3.1.9
docker run -d --name bench-neo4j521 --memory=3g --cpus=4 -p 7476:7474 \
  -e NEO4J_AUTH=neo4j/benchpassword ghcr.io/open-ent/open-ent/neo4j:5.21.0-apoc
docker run -d --name bench-arcadedb --memory=3g --cpus=4 -p 2481:2480 \
  -e JAVA_OPTS="-Xmx1500m" -e ARCADEDB_SETTINGS="-Darcadedb.server.rootPassword=benchpassword" \
  arcadedata/arcadedb:latest

python3 graph-benchmark.py --users 100000 --structures 100 --iters 50
```

Les conteneurs sont bornés à ressources égales : sans `--memory`, ArcadeDB prend 75 %
de la RAM de l'hôte et la comparaison n'a plus de sens.

## Ce que le banc mesure, et ce qu'il ne mesure pas

Il mesure la latence de requêtes représentatives de l'annuaire, vues du client HTTP,
sur un jeu synthétique, à froid puis après chauffe.

Il ne mesure **pas** la charge réelle d'une plateforme, la concurrence, la montée en
charge sur plusieurs Go, ni la stabilité dans la durée. Il sert à décider s'il vaut la
peine d'aller plus loin, pas à conclure.

**Le plancher protocolaire est mesuré et reporté** (`RETURN 1`), parce que sans lui les
résultats se lisent de travers : sur les requêtes légères, la quasi-totalité du temps est
du round-trip HTTP et non du moteur.

## Résultats — 100 000 utilisateurs, 100 structures, 28/10/2026

Latence médiane en ms. Conteneurs à 3 Go / 4 cœurs chacun.

| | Neo4j 3.1.9 C2.3 | Neo4j 3.1.9 C3.1 | Neo4j 5.21 | ArcadeDB 26.11 |
|---|---|---|---|---|
| **plancher protocolaire** | 0.55 | 0.54 | 1.69 | 0.41 |
| Q1 lookup par id indexé | 0.45 | 0.49 | 1.84 | 0.42 |
| Q2 1 saut | 0.54 | 0.51 | 1.70 | 0.45 |
| Q3 2 sauts | 0.53 | 0.52 | 1.65 | 0.43 |
| Q4 3 sauts | 1.01 | 0.93 | 1.71 | 0.62 |
| Q6 recherche par préfixe | 2.24 | 1.73 | 1.62 | 0.62 |
| **Q5 agrégat par structure** | 103.34 | 73.44 | **17.08** | 20.54 |
| **Q8 parcours global + agrégat** | 133.80 | 108.39 | **19.36** | 31.10 |
| **Q9 paires dans les groupes** | 21863 | 12396 | **1214** | 7819 |
| **Q10 20 000 lignes rendues** | 53.85 | 57.26 | 135.87 | **19.34** |
| **Q7 écriture simple** | 5.69 | 5.51 | 3.13 | **0.76** |
| **Q11 écriture en lot (1000)** | 9.69 | 10.04 | 7.34 | **3.25** |
| chargement de 100 000 users | 10.2 s | — | 6.7 s | 4.8 s |

### Lecture

**Q1 à Q4 et Q6 ne disent rien du moteur.** Elles sont à leur plancher protocolaire : le
coût moteur mesuré sur un lookup indexé est de 0,02 à 0,04 ms pour les trois serveurs. Si
Neo4j 5.21 y paraît plus lent, c'est son endpoint REST (1,69 ms de plancher contre 0,41 à
0,55 ms) et non son moteur. À noter : le monolithe entcore passe effectivement par REST,
mais open-ent utilise Bolt, où cette pénalité ne s'applique pas.

**Sur le travail de graphe réel, Neo4j 5.21 domine nettement.** Sur la requête la plus
lourde, il est **6,4 fois plus rapide qu'ArcadeDB** (1 214 ms contre 7 819 ms) et **10 fois
plus rapide que la 3.1.9** sans pin. L'écart se retrouve, atténué, sur les agrégats.

**ArcadeDB gagne sur l'écriture et la restitution.** Écriture simple 4,1 fois plus rapide
que Neo4j 5.21, écriture en lot 2,3 fois, restitution de 20 000 lignes 7 fois, chargement
initial le plus rapide des trois.

**Le pin Cypher 2.3 coûte cher, et c'est mesuré.** À base et données identiques, le seul
fait de retirer le pin fait gagner 29 % sur l'agrégat (103 → 73 ms), 19 % sur le parcours
global, et **43 % sur la requête lourde** (21,9 s → 12,4 s). C'est le gain le moins cher
du dossier : il ne demande aucun changement de base.

### Ce que ça implique

ArcadeDB n'est pas un gain de performance face à Neo4j 5 : il est sensiblement plus lent
sur le parcours de graphe profond, qui est précisément ce qu'on attend d'une base de
graphe. Il est en revanche très supérieur à la 3.1.9 actuelle, et meilleur que Neo4j 5 en
écriture.

Le choix ne se joue donc pas sur la performance brute mais sur le couple
licence / fonctions : ArcadeDB apporte clustering et backup à chaud sous Apache 2.0, au
prix d'un moteur de traversée plus lent.

---

# Second banc : les requêtes réelles, sur les données réelles

`entcore-queries-benchmark.py` ne mesure pas des requêtes représentatives mais **les
requêtes qu'entcore émet**, contre la base de l'ENT (≈700 000 nœuds, ≈2,58 M relations,
514 600 utilisateurs). Mesuré le 28/10/2026 en local, Neo4j 3.1.9 et ArcadeDB 26.10.2 sur
la même machine.

## Un préalable qui change tout : les index

L'importeur Neo4j d'ArcadeDB ne crée qu'un index par type, sur l'identifiant de tête de
ligne. Les 61 index de Neo4j — `User.login`, `User.externalId`, `Structure.UAI`, tous les
`externalId` — sont perdus, et la DDL d'entcore ne les rétablit pas : sa transaction
s'interrompt à la première contrainte refusée. Mesuré **sans** eux :

| | neo4j-2.3 | arcadedb |
|---|---|---|
| connexion : recherche du compte | 3,81 | **147,02** |
| recherche par préfixe de nom | 2,08 | **32,37** |

C'est un balayage de 514 000 utilisateurs, pas une faiblesse du moteur. Les chiffres
ci-dessous sont pris après `scripts/ops/arcadedb-sync-indexes.py`, qui rejoue le schéma de
Neo4j en DDL ArcadeDB : 55 index posés, toutes les contraintes d'unicité acceptées.

## Résultats — latence médiane en ms

| requête | neo4j-2.3 | neo4j-3.1 | arcadedb | lignes |
|---|---|---|---|---|
| plancher protocolaire | 0,78 | 0,84 | **0,32** | 1 |
| connexion : recherche du compte | 1,21 | 0,80 | **0,50** | 1 |
| session : construction du profil | 1,17 | 1,08 | **0,62** | 1 |
| session : droits et applications | 1,19 | 1,16 | **0,59** | 1 |
| annuaire : liste d'un établissement | **33,81** | 35,58 | 2607,18 | 594 |
| annuaire : tous les établissements | 69,42 | 74,72 | **23,88** | 518 |
| messagerie : utilisateurs visibles | 3,88 | 3,72 | **2,99** | 254 |
| recherche par préfixe de nom | 2,28 | 1,96 | **1,63** | 100 |
| écriture : marquage d'un compte | 4,30 | 4,26 | **0,86** | 1 |

ArcadeDB gagne sur huit requêtes sur neuf, d'un facteur 1,2 à 5. Et perd d'un facteur **77**
sur la neuvième.

## La neuvième : un défaut de plan, pas de moteur

Le banc synthétique ne voyait pas ce cas. Décomposition de la requête fautive :

| | ms |
|---|---|
| les 593 utilisateurs de l'établissement | 1,8 |
| + `OPTIONAL MATCH` de leurs classes | **2 519** |
| le même motif en `MATCH` (non optionnel) | 5,5 |

Ni l'agrégat ni le `DISTINCT` n'y sont pour quelque chose : retirés, le coût ne bouge pas.
Et le même `OPTIONAL MATCH` sur un seul utilisateur trouvé par son index coûte 1,5 ms.

Le départage :

| gauche de l'`OPTIONAL MATCH` | ms |
|---|---|
| flux issu d'un parcours de graphe | 2 539 |
| `UNWIND` d'identifiants + accès indexé | **39,8** |

**ArcadeDB ne pilote pas le second motif depuis les sommets déjà liés quand ils viennent
d'un parcours.** Même jeu, mêmes 593 utilisateurs, même motif : 64 fois plus lent. Le
planner de Neo4j fusionne les deux motifs en un seul parcours (5,5 ms) ; celui d'ArcadeDB
ne le fait que lorsque les deux motifs sont dans le même `MATCH`.

## Le contournement, et son coût

La compréhension de motif est équivalente à `OPTIONAL MATCH` + `COLLECT` — elle rend `[]`
quand rien ne correspond — et elle, le planner la traite bien :

```cypher
RETURN u.id AS id, [ (u)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class) | c.id ] AS classes
```

**8,5 ms contre 2 519.** Mais `OPTIONAL MATCH` est l'idiome central de l'annuaire d'entcore :
la seule requête `DefaultSchoolService.userList` en compte sept. Les réécrire n'est pas une
retouche, et le résultat ne serait plus du Cypher portable au même sens — la compréhension
de motif n'existe plus dans Neo4j 5.

## Ce que le banc permet de conclure, et ce qu'il ne permet pas

Il mesure la latence de requêtes réelles, sur des données réelles, à froid puis après
chauffe, un client à la fois. Il ne dit rien de la concurrence, de la tenue dans la durée,
ni du comportement sous charge d'écriture soutenue.

Les deux bases ne sont pas identiques au nœud près : 700 049 nœuds / 2 581 018 relations
côté Neo4j local, 700 624 / 2 573 584 pour l'instantané de production restauré dans
ArcadeDB — 0,08 % d'écart en nœuds, 0,29 % en relations. Sans effet sur ces ordres de
grandeur.

---

# Troisième banc : les routes de l'ENT, de bout en bout

Les deux bancs précédents interrogent les moteurs en direct : ils mesurent le moteur, pas
l'ENT. `scripts/ops/ent-routes-benchmark.py` (dépôt parent) passe par HTTP, donc par
entcore, son adaptateur, la session et la sérialisation — là où se logent les écarts de
**forme**, invisibles quand on interroge la base.

Deux de ces écarts ont été trouvés ainsi, et aucun des deux ne se voyait autrement : les
colonnes nulles qui bloquaient la connexion, et la représentation des nœuds qui laissait
`/directory/user/{id}` sans réponse au-delà de 60 s — et faisait attendre trois secondes
au tableau de bord, par le `AbortSignal.timeout(3000)` de sa route `/api/me`.

Le banc ne compare pas deux moteurs dans une même exécution : il mesure l'ENT tel qu'il
tourne. Pour comparer, on le lance une fois par moteur, en basculant `GRAPH_ENGINE`.

## Résultats — latence médiane en ms, 10 itérations

Mesuré le 28/10/2026, même machine, mêmes données, les deux moteurs indexés à l'identique.
Le rapport est `neo4j / arcadedb` : au-dessus de 1, ArcadeDB est plus rapide.

| route | neo4j-2.3 | arcadedb | rapport |
|---|---|---|---|
| connexion | 137,2 | **57,5** | 2,39 |
| identité (userbook) | 4,8 | **2,5** | 1,92 |
| fiche utilisateur | 7,5 | **4,5** | 1,68 |
| tableau de bord : `/api/me` | 25,0 | **16,2** | 1,55 |
| annuaire : établissements | 23,7 | **9,6** | 2,47 |
| annuaire : utilisateurs d'un étab. | **89,2** | 2570,4 | 0,03 |
| registre : applications | 6,7 | **4,5** | 1,52 |
| registre : widgets | 7,7 | **3,8** | 2,02 |
| registre : rôles | 5,8 | **4,0** | 1,45 |
| fil de nouveautés | 32,0 | **27,9** | 1,15 |

Neuf routes sur dix sont de 1,15 à 2,4 fois plus rapides sur ArcadeDB. La dixième est 29
fois plus lente — c'est le même `OPTIONAL MATCH` piloté depuis un parcours que la section
précédente isole, vu cette fois du bout de la chaîne.

Le rapport bout en bout est plus resserré que le rapport moteur à moteur, et c'est normal :
HTTP, session et sérialisation sont communs aux deux, et diluent l'écart. C'est aussi ce
qui rend ce banc le plus honnête des trois quant au ressenti.
