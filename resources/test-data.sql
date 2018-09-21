CREATE DATABASE IF NOT EXISTS events;
USE events;

INSERT association (tp1, tp2) VALUES ("foo", "bar"), ("bar", "foo");
INSERT INTO events (id, tp, keyhash, bodyhash, cng, ts, ttl)
VALUES ("1", "foo", "xx", "yy", 1, 1000, NULL),
       ("2", "foo", "xx", "zz", 1, 1001, NULL),
       ("3", "foo", "xx", "tt", 1, 1002, 1010),
       ("4", "foo", "xx", "yy", -1, 1003, NULL),
       ("5", "bar", "xx", "dd", 1, 1004, NULL);

INSERT INTO event_bodies (event_id, content) VALUES ("1", "1"), ("3", "3"), ("4", "1"), ("5", "5");
INSERT INTO small_event_bodies (event_id, content) VALUES ("2", "2");
