ALTER TABLE concession_items
    ADD COLUMN image_url VARCHAR(2048);

COMMENT ON COLUMN concession_items.image_url IS 'Optional product photo URL used by customer concession selection.';
