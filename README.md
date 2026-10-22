# jeoparody-party

A Jeopardy-style trivia night you run from a browser: a main **board** screen, a **host** console that
controls the game and scoring, and **player** pages that act as buzzers and drawing pads.

Built with Java 21 / Spring Boot, MySQL and plain HTML/CSS/JavaScript, and delivered through a CircleCI
pipeline to Google Cloud Run.

## Run it locally

Prerequisites: JDK 21, Docker Desktop (running).

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
| `GET /actuator/health` | Health check |

## Trivia data

Questions come from the [Open Trivia Database](https://opentdb.com), licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
