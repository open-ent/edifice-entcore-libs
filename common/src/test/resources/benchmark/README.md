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
