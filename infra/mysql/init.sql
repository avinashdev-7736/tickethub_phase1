-- One database per service. No service reads another service's database.
CREATE DATABASE IF NOT EXISTS event_db;
CREATE DATABASE IF NOT EXISTS booking_db;
CREATE DATABASE IF NOT EXISTS payment_db;
CREATE DATABASE IF NOT EXISTS notification_db;
CREATE DATABASE IF NOT EXISTS notification_replay_db;  -- used by the replay instance (scenario 12)
