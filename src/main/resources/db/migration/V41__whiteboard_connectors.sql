-- V41: whiteboard connectors — a line/arrow between two elements of the same board.
-- A connector is a whiteboard_items row of type 'connector' that stores the two
-- endpoints instead of a position; clients render it from the endpoints' current
-- geometry, so it follows the elements when they move. Deleting either endpoint
-- deletes the connector (also done explicitly by WhiteboardController).

ALTER TABLE whiteboard_items ADD COLUMN from_item_id BIGINT REFERENCES whiteboard_items(id) ON DELETE CASCADE;
ALTER TABLE whiteboard_items ADD COLUMN to_item_id   BIGINT REFERENCES whiteboard_items(id) ON DELETE CASCADE;
-- none | end | start | both
ALTER TABLE whiteboard_items ADD COLUMN arrowhead    VARCHAR(10);

CREATE INDEX idx_whiteboard_items_from ON whiteboard_items(from_item_id);
CREATE INDEX idx_whiteboard_items_to   ON whiteboard_items(to_item_id);
CREATE INDEX idx_whiteboard_items_attachment ON whiteboard_items(attachment_id);
CREATE INDEX idx_whiteboards_org_updated ON whiteboards(organization_id, updated_at);
