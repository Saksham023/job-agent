-- Two tiers per search: candidates from position low_priority_from on are the LOW-PRIORITY tier (likely NO: a senior
-- title with no years stated, embedded work without C/C++, main languages the candidate lacks). They stay in the list
-- (nothing is dropped) but are judged only after the main tier is done. NULL = one tier (searches before V15).
ALTER TABLE searches
    ADD COLUMN low_priority_from INTEGER;