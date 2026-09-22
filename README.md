# jdbc-capture-agent

Agent Java **passif** qui **observe** l'activité JDBC d'une application, sans jamais la modifier,
la bloquer ou rejouer une requête. On l'attache à n'importe quelle appli avec :

```bash
java -javaagent:jdbc-capture-agent.jar=out=./capture -jar mon-appli.jar
```

Il intercepte le retour de `Driver#connect` et `DataSource#getConnection`, enveloppe la `Connection`
dans un proxy dynamique (jusqu'aux `Statement` / `PreparedStatement` / `ResultSet`) et écrit une
**trace JSONL** (un événement par ligne) + un `summary.json`.

👉 But du projet : capturer l'activité JDBC d'un batch *legacy* et de sa réécriture Spring Batch,
rejouer les deux sur des bases identiques, puis comparer l'état final.

**Le même JAR tourne sur Java 8 et Java 25** (compilé en bytecode Java 8).

---

## 2 façons de l'essayer

| | Base | Docker requis ? | Pour quoi faire |
|---|---|---|---|
| **A. Démo rapide (spike)** | **H2 en mémoire** | ❌ non | Voir l'agent marcher en 10 s, sans rien installer |
| **B. Scénario réaliste** | **PostgreSQL** (Docker) | ✅ oui | Comparer *legacy* (JVM 8) vs *modern* (JVM 25 / Hikari) |

> H2 sert **uniquement** à la démo `spike-app` (pratique en local). Les deux applis d'exemple qui
> imitent un vrai batch (`sample-legacy`, `sample-modern`) écrivent sur **PostgreSQL**.

---

## Prérequis

- **JDK 25 (Temurin)** pour compiler — l'agent produit reste du bytecode Java 8.
- **Docker** (Rancher Desktop ici) uniquement pour la partie B.

---

## Construire

```bash
make build
```

Produit notamment l'agent **auto-suffisant** (Byte Buddy + JSqlParser embarqués et relocalisés) :

```
jdbc-capture-agent/target/jdbc-capture-agent.jar
```

---

## A. Démo rapide avec H2 (sans Docker)

```bash
# lance le spike (H2 en mémoire) AVEC l'agent, capture toutes les valeurs de lignes
java -javaagent:jdbc-capture-agent/target/jdbc-capture-agent.jar=out=./capture/demo,rows=all \
     -jar spike-app/target/spike-app.jar

# regarde le résultat
cat ./capture/demo/trace.jsonl        # 1 événement JSON par ligne
cat ./capture/demo/summary.json       # récap (tables, PK lues, requêtes distinctes)
```

Tu verras les événements `run_start`, `conn_open`, `query`, `rows_read`, `tx`, `touched`,
`conn_close`, `run_end`.

---

## B. Scénario réaliste avec PostgreSQL (Docker)

```bash
make pg-up                 # démarre PostgreSQL (docker compose)

make capture-legacy        # sample-legacy (JDBC brut) sous eclipse-temurin:8  -> ./capture/legacy/
make capture-modern        # sample-modern (Spring Boot/Hikari) sous eclipse-temurin:25 -> ./capture/modern/

# variantes
make capture-legacy SCENARIO=empty                       # cas "rien à traiter"
make capture-legacy OPTS="clock=2020-01-01T00:00:00Z"    # horloge figée (voir limites)

make overhead              # mesure le surcoût de l'agent (avec vs sans)
make test                  # lance les 6 tests d'intégration de bout en bout
make pg-down               # arrête PostgreSQL et nettoie
```

- `sample-legacy` — JDBC brut (`DriverManager`, prepared statements, batch, transaction manuelle
  avec un **rollback volontaire**).
- `sample-modern` — Spring Boot 3 / HikariCP, **même traitement mais SQL différent**.

> **Note Docker (cette machine) :** les bind-mounts de l'hôte échouent sous Rancher Desktop. Les
> scripts injectent donc les JARs dans les conteneurs par **tar-pipe** (stdin) et relisent la trace
> par stdout. Les conteneurs rejoignent le réseau `capnet` et joignent PostgreSQL sur l'hôte `postgres`.

---

## Options de l'agent

Une seule chaîne après `=`, options séparées par `,` :

| Option | Défaut | Rôle |
|--------|--------|------|
| `out=<dossier>` | `./capture` | Dossier de sortie (`trace.jsonl`, `summary.json`, `agent-errors.log`). |
| `rows=none\|sample:N\|all` | `sample:100` | Combien de **valeurs** de lignes capturer par `ResultSet` (les métadonnées + le compte sont toujours capturés). |
| `mask=<c1\|c2\|...>` | *(aucun)* | Colonnes dont la valeur est remplacée par un **hash stable** (même entrée → même sortie). Séparateur `\|`. |
| `clock=<ISO-8601>` | *(aucun)* | Fige l'horodatage **des événements de l'agent**. Voir limites. |
| `exclude=<regex>` | *(aucun)* | Regex (insensible à la casse) ; le SQL qui matche n'est pas capturé (ex. `BATCH_`). Sans virgule. |
| `queue=<N>` | `65536` | Capacité de la file asynchrone. Si pleine : les événements sont comptés (`dropped`) et jamais bloquants. |

Exemple :

```
-javaagent:jdbc-capture-agent.jar=out=./capture/legacy,rows=all,mask=email|ssn,clock=2020-01-01T00:00:00Z,exclude=BATCH_
```

---

## Ce qui est validé (6 tests d'intégration)

`make test` exécute réellement les deux applis en Docker (JVM 8 et 25, **même JAR d'agent**) et
vérifie :

1. **Pas de double comptage** côté HikariCP.
2. Écritures d'une transaction **rollback** identifiables et **non validées**.
3. **Horloge figée** identique sur les deux JVM (horodatage des événements agent).
4. Cas **« rien à traiter »** → trace sans écriture.
5. L'appli **démarre malgré une option d'agent invalide** (erreur loggée, pas de crash).
6. **Surcoût mesuré** et affiché.

> Si un prérequis manque (JAR non buildé, Docker absent), le test correspondant est **SKIP** — jamais
> faussement vert.

---

## Format de trace

Aperçu des types d'événements : `run_start`, `run_end`, `conn_open`, `conn_close`, `query`,
`rows_read` (+ `generated_keys`), `tx`, `touched`. Chaque événement porte un en-tête commun
(`ts`, `run`, `thread`, `conn`, `tx`).

📖 **Détail champ par champ + `summary.json` : [`docs/FORMAT-TRACE.md`](docs/FORMAT-TRACE.md).**

---

## Limites connues

- **`clock=` ne fige que les horodatages de l'agent**, pas l'horloge vue par l'application
  (`System.currentTimeMillis`, `new Date()`, `LocalDateTime.now()`…). Le faire imposerait
  d'instrumenter des classes du bootstrap JDK — interdit par le cahier des charges et risqué. C'est
  **documenté plutôt que contourné silencieusement**.
- **Analyse des tables (`touched`) et des clés primaires : best-effort** (JSqlParser + cache
  `DatabaseMetaData#getPrimaryKeys` ; SQL marqué `unparsed` en cas d'échec).
- **`command` n'est pas le vrai `argv`** de `main` (indisponible en `premain`) : on utilise
  `sun.java.command`.
- Les **paramètres liés ne sont pas masqués** (pas de mapping fiable paramètre→colonne) ; le masquage
  s'applique aux valeurs de colonnes des `ResultSet`.
- Les **chiffres de surcoût dépendent du workload** (et incluent du bruit Docker) — indicatifs.

Voir aussi [`DECISIONS.md`](DECISIONS.md) pour les choix techniques.
