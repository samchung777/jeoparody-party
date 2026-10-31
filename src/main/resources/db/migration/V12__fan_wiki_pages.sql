-- Hosts can paste a fan wiki page link on the library page and get clues written from that one page.
ALTER TABLE research_task
    MODIFY COLUMN kind ENUM('stale_category', 'suggestion', 'manual', 'fan_wiki', 'fan_wiki_page') NOT NULL,
    ADD COLUMN page_url VARCHAR(500) NULL AFTER topic;
