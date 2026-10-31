-- A one-time fan-wiki research pass for every category that has an allow-listed fan wiki.
ALTER TABLE research_task
    MODIFY COLUMN kind ENUM('stale_category', 'suggestion', 'manual', 'fan_wiki') NOT NULL;
