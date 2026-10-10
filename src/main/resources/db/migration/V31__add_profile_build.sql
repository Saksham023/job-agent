-- "What you build" (2026-10-10): one phrase from the resume read, e.g. "building high-throughput microservices in Java and
-- Spring Boot". The referral message says "... years of experience <build>"; editable by the user like the other facts.
ALTER TABLE user_profiles ADD COLUMN build TEXT;
