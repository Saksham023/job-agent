-- Other technical families a job also belongs to (recall first): "Staff SDE - AI Engineer" is DATA_ML, also
-- SOFTWARE_ENGINEERING. Matching filters on family OR secondary_families. Filled by the extractor from
-- version 4; rows extracted earlier keep an empty array until the next rebuild.
ALTER TABLE job_requirements
    ADD COLUMN secondary_families TEXT[] NOT NULL DEFAULT '{}';

-- The match filter uses array overlap (&&), which a GIN index serves.
CREATE INDEX job_requirements_secondary_families_idx ON job_requirements USING GIN (secondary_families);