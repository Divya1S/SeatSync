-- One database per service (schema-per-service isolation on a single dev instance).
CREATE DATABASE seatsync_auth;
CREATE DATABASE seatsync_catalog;
CREATE DATABASE seatsync_booking;
CREATE DATABASE seatsync_notification;
CREATE DATABASE seatsync_ai;

GRANT ALL PRIVILEGES ON DATABASE seatsync_auth TO seatsync;
GRANT ALL PRIVILEGES ON DATABASE seatsync_catalog TO seatsync;
GRANT ALL PRIVILEGES ON DATABASE seatsync_booking TO seatsync;
GRANT ALL PRIVILEGES ON DATABASE seatsync_notification TO seatsync;
GRANT ALL PRIVILEGES ON DATABASE seatsync_ai TO seatsync;

-- pgvector for the AI concierge's embeddings
\c seatsync_ai
CREATE EXTENSION IF NOT EXISTS vector;
