DO $$ BEGIN
    IF EXISTS (
        SELECT 1
        FROM concession_items
        GROUP BY lower(name)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Resolve concession item names that differ only by case before applying V11';
    END IF;
END $$;

CREATE UNIQUE INDEX uq_concession_items_name_ci ON concession_items (lower(name));
