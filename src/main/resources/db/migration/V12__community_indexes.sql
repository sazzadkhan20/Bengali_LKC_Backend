-- Community feed: per-author history, batched reply/report lookups and per-user activity.
CREATE INDEX IF NOT EXISTS idx_post_author_created ON community_post (author_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_visible_created ON community_post (created_at DESC, id DESC) WHERE NOT hidden;
CREATE INDEX IF NOT EXISTS idx_post_reply_post ON post_reply (post_id, created_at);
CREATE INDEX IF NOT EXISTS idx_post_reply_author ON post_reply (author_id);
CREATE INDEX IF NOT EXISTS idx_post_report_post ON post_report (post_id);
