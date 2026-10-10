-- LinkedIn company ids for the "Find your connections" button (2026-10-10), checked by hand by the user on LinkedIn.
-- Several ids may be given comma-separated ("1753,12345") for a company hiring under several pages. Samsung is left out on
-- purpose (hires under Samsung Electronics and Samsung R&D Institute pages; the button falls back to the name search).
UPDATE companies c
SET config = c.config || jsonb_build_object('linkedinCompanyId', v.id)
FROM (VALUES
    ('adobe', '1480'), ('amazon', '1586'), ('amex', '1277'), ('autodesk', '1879'), ('blackrock', '4764'),
    ('ciena', '3960'), ('cred', '14485479'), ('databricks', '3477522'), ('expedia', '2751'), ('freshworks', '1377014'),
    ('goldman-sachs', '1382'), ('groww', '10813156'), ('intel', '1053'), ('jpmorgan', '1068'), ('mastercard', '3015'),
    ('meesho', '10037698'), ('microsoft', '1035'), ('nvidia', '3608'), ('paypal', '1482'), ('paytm', '2340144'),
    ('phonepe', '10479149'), ('qualcomm', '2017'), ('razorpay', '3788927'), ('salesforce', '3185'),
    ('sarvam-ai', '101315625'), ('servicenow', '29352'), ('sprinklr', '399351'), ('texas-instruments', '1397'),
    ('visa', '2190'), ('wells-fargo', '1235'), ('workday', '17719'), ('zeta', '10355561'), ('zscaler', '234625')
) AS v (slug, id)
WHERE c.slug = v.slug;
