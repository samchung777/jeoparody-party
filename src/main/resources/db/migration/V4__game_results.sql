-- Final standings of finished games (live game state is kept in memory).
CREATE TABLE game_result (
    id          BIGINT    NOT NULL AUTO_INCREMENT,
    code        CHAR(4)   NOT NULL,
    started_at  TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NOT NULL,
    team_count  INT       NOT NULL,
    PRIMARY KEY (id),
    KEY idx_game_result_finished (finished_at)
);

CREATE TABLE game_result_team (
    game_result_id BIGINT      NOT NULL,
    place          INT         NOT NULL,
    team_name      VARCHAR(64) NOT NULL,
    score          INT         NOT NULL,
    PRIMARY KEY (game_result_id, place),
    CONSTRAINT fk_result_team_result FOREIGN KEY (game_result_id) REFERENCES game_result (id) ON DELETE CASCADE
);
