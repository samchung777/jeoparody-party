# jeoparody-party

A Jeopardy-style trivia night you run from a browser: a main **board** screen, a **host** console that
controls the game and scoring, and **player** pages that act as buzzers and drawing pads.

Built with Java 21 / Spring Boot, MySQL and plain HTML/CSS/JavaScript, and delivered through a CircleCI
pipeline to Google Cloud Run.

## Run it locally

Prerequisites: JDK 21, Docker Desktop (running), and the gcloud CLI signed in with
Application Default Credentials (used to call Gemini on Vertex AI):

```powershell
gcloud services enable aiplatform.googleapis.com
gcloud auth application-default login
gcloud auth application-default set-quota-project jeoparody-party
```

```powershell
docker compose up -d          # starts MySQL on localhost:3306
.\mvnw.cmd spring-boot:run    # macOS/Linux: ./mvnw spring-boot:run
```

Open http://localhost:8080. On first start the app imports questions from Open Trivia DB in the
background (about 1–2 minutes, because the API allows one request every 5 seconds).

Useful endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /api/categories` | Categories with clue counts per difficulty |
| `GET /api/categories/{id}/clues` | Sample clues for a category |
| `POST /api/admin/import` | Import more trivia (optional `?categories=9,22`) |
| `GET /api/admin/import` | Import progress |
| `GET /api/keywords` | Keywords with 5+ clues (eligible as board categories) |
| `POST /api/admin/enrich` | Convert remaining clues to Jeopardy format with Gemini |
| `GET /api/admin/enrich` | Conversion progress |
| `GET /actuator/health` | Health check |

## How clues become Jeopardy categories

After import, a background job sends clues to Gemini in batches of 20. For each clue it returns a
Jeopardy-style clue ("This planet is known as the Red Planet"), the response in question form
("What is Mars?") and 2–3 subject keywords. The current most-used keywords are fed back into each
prompt so Gemini reuses them and keywords build up enough clues to fill a board column. Set
`TRIVIA_ENRICHMENT_ENABLED=false` to turn this off (CI does).

## Playing a game

| Screen | URL | Who |
|---|---|---|
| Landing | `/` | Create a game or join one |
| Host console | `/host.html?game=CODE&key=…` | The host (secret link from "Create a game") |
| Board | `/board.html?game=CODE` | The TV / projector |
| Player | `/play.html?game=CODE` | One phone per team |

1. **Lobby** – teams join on their phones and draw their team name.
2. **Category vote** – each team picks up to 12 keywords from a random pool of 26; the top 12 become the
   Jeopardy and Double Jeopardy categories (alternating by rank), shown with their punny titles.
3. **Final category vote** – teams pick one of the remaining keywords for Final Jeopardy.
4. **Jeopardy / Double Jeopardy** – the host picks clues, opens the buzzers after reading, and rules
   right/wrong. One Daily Double in round one, two in round two; the team in control wagers on its phone.
5. **Final Jeopardy** – teams with a positive score wager, then write their response on the drawing pad.
   The host reveals them lowest score first. Results are saved to MySQL.

Live games are held in memory (single instance) and pushed to every screen over a WebSocket (`/ws`).

## Tests

```powershell
.\mvnw.cmd test      # unit tests only (no database)
.\mvnw.cmd verify    # unit + integration tests (needs `docker compose up -d`)
```

| Kind | Files | What they cover |
|---|---|---|
| Unit (Surefire) | `*Test.java` | Game rules, board building, Daily Doubles, Final Jeopardy, what each screen may see, Open Trivia DB / Gemini parsing |
| Integration (Failsafe) | `*IT.java` | Flyway migrations and SQL against real MySQL, clue feedback, a full game over WebSockets |

Integration tests use a separate `jeopardy_test` database (created automatically) and never call Open
Trivia DB or Gemini. Override the connection with `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`.
JUnit XML reports land in `target/surefire-reports` and `target/failsafe-reports`.

## Trivia data

Questions come from the [Open Trivia Database](https://opentdb.com), licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
