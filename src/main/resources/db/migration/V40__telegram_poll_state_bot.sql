-- Each Telegram bot numbers its updates on its own, so the stored position belongs to ONE bot (2026-10-11). The bot's id
-- is the number before ':' in its token (public; the part after ':' is the secret). A different bot starts from 0, so a
-- position left by another bot (a test bot, an old token) can never make the new bot's messages look already handled.
ALTER TABLE telegram_poll_state ADD COLUMN bot_id BIGINT;
