-- Raise default API rate limit for practical local/dev use.
-- Production can still lower via PUT /api/config.
UPDATE system_config
SET ip_requests_per_minute = 120,
    updated_at = NOW()
WHERE id = 1 AND ip_requests_per_minute <= 10;
