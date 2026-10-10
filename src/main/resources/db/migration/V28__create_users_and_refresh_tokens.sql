-- Accounts (2026-10-10): everyone signs in to use the app; USER sees the job board, ADMIN also the /admin side.
-- The first admin is made by hand: sign up, then UPDATE users SET role = 'ADMIN' WHERE email = '...'.
CREATE TABLE users (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          TEXT        NOT NULL UNIQUE,                  -- stored trimmed and lower case
    password_hash  TEXT        NOT NULL,                         -- "{bcrypt}$2a$12$...", never the password
    role           TEXT        NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ
);

-- Refresh tokens: the browser holds the random token in an HttpOnly cookie, we keep only its SHA-256 hash. Every
-- refresh replaces the token (rotation); presenting an already replaced token again means it was copied, so all of
-- that user's sessions end. family = one sign-in and the tokens that followed from it.
CREATE TABLE refresh_tokens (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   TEXT        NOT NULL UNIQUE,
    family       UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  BIGINT      REFERENCES refresh_tokens (id)
);

CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
