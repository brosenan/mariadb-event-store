DELIMITER //
CREATE DATABASE IF NOT EXISTS events
//
USE events
//

/*
 * Events table
 */
CREATE TABLE IF NOT EXISTS events (
  id VARCHAR(32) PRIMARY KEY,
  tp VARCHAR(50),
  keyhash BINARY(32),
  bodyhash BINARY(32),
  cng INTEGER,
  ts BIGINT,
  ttl BIGINT
)
//

/*
 * Index events by their type, key hash and the event timestamp
 */
CREATE INDEX IF NOT EXISTS event_by_type_and_key
ON events (tp, keyhash, ts)
//

/*
 * Bodies of events (where the body size is 256 bytes or more)
 */
CREATE TABLE IF NOT EXISTS event_bodies (
  event_id VARCHAR(32) PRIMARY KEY,
  content BLOB,
  FOREIGN KEY (event_id)
    REFERENCES events (id)
    ON DELETE CASCADE
)
//

/*
 * Bodies of events (where the body size is 255 bytes or less)
 */
CREATE TABLE IF NOT EXISTS small_event_bodies (
  event_id VARCHAR(32) PRIMARY KEY,
  content BINARY(255),
  FOREIGN KEY (event_id)
    REFERENCES events (id)
    ON DELETE CASCADE
)
//

/*
 * Association between types (rules and facts)
 */
CREATE TABLE IF NOT EXISTS association (
  tp1 VARCHAR(50),
  tp2 VARCHAR(50),
  PRIMARY KEY (tp1, tp2)
)
//

/*
 * A view unifying small and large bodies.
 */
CREATE VIEW IF NOT EXISTS all_bodies AS
SELECT event_id, content FROM event_bodies
UNION
SELECT event_id, content FROM small_event_bodies
//

/*
 * All events related to a given type and key.
 */
CREATE VIEW IF NOT EXISTS related_events AS
SELECT association.tp1, events.keyhash, events.id, events.ts, all_bodies.content
FROM events, association, all_bodies
WHERE events.tp = association.tp2
AND events.id = all_bodies.event_id
ORDER BY ts DESC
//

/*
 * The state, as provided by an accumulation of all cng fields of all events.
 */
CREATE VIEW IF NOT EXISTS accum_state AS
SELECT tp, keyhash, bodyhash, SUM(cng) as cnt
FROM events
GROUP BY tp, keyhash, bodyhash
//

/*
 * Perform compaction.
 * Remove all events that contribute to state that has an accumulated change of 0
 */
CREATE PROCEDURE IF NOT EXISTS compaction (IN now BIGINT)
BEGIN
DELETE FROM events
WHERE CONCAT(tp, ":", keyhash, ":", bodyhash) IN (SELECT CONCAT(tp, ":", keyhash, ":", bodyhash)
              	 	  	        FROM accum_state
					WHERE cnt = 0);
DELETE FROM events
WHERE ttl < now;
END
//
