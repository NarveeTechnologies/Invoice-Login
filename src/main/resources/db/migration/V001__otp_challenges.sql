-- Invoice OTP challenges — the authoritative schema for one-time passcodes.
--
-- Flyway owns this table outright. It is deliberately NOT mapped as a JPA
-- entity: Invoice-Login runs spring.jpa.hibernate.ddl-auto=update against a
-- schema that Invoice-Service also builds, and an entity here would hand
-- Hibernate joint authority over the one table whose shape carries security
-- meaning. The repository is JdbcTemplate for that reason, and because
-- verification needs SELECT ... FOR UPDATE and single-statement conditional
-- updates that JPA expresses badly.
--
-- Replaces the legacy `otp` table (email, otp, expiry_time), which stored the
-- passcode in plaintext, carried no purpose, and had no attempt ceiling. That
-- table is dropped at the end of this migration; its rows are worthless after
-- the two-minute TTL that produced them, so there is nothing to migrate.

CREATE TABLE IF NOT EXISTS otp_challenges (
    id                BIGSERIAL PRIMARY KEY,

    -- HMAC-SHA256 of the normalised identifier under the server pepper. The
    -- address itself is never stored: it is needed only to send the message,
    -- inside the same request that generates it.
    identifier_hash   CHAR(64)     NOT NULL,

    -- LOGIN / REGISTRATION / ACCOUNT_NUMBER_CHANGE / PASSWORD_RESET.
    -- Every lookup binds identifier_hash + purpose, so a passcode minted for
    -- one flow cannot be spent in another.
    purpose           VARCHAR(32)  NOT NULL,

    -- HMAC-SHA256 over purpose|identifier|code under the same pepper. Binding
    -- purpose and identifier into the MAC means a stolen hash cannot be
    -- replayed against a different address or flow even if the code repeats.
    code_hash         CHAR(64)     NOT NULL,

    expires_at        TIMESTAMPTZ  NOT NULL,
    consumed_at       TIMESTAMPTZ,
    attempt_count     INTEGER      NOT NULL DEFAULT 0,
    max_attempts      INTEGER      NOT NULL,

    -- Set when the challenge is retired without being spent: SUPERSEDED by a
    -- resend, or EXHAUSTED by hitting max_attempts. Kept rather than deleted so
    -- the rate limiter and the audit trail can still see it.
    invalidated_at    TIMESTAMPTZ,
    invalidated_reason VARCHAR(32),

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    ip_hash           CHAR(64),
    user_agent_hash   CHAR(64),
    correlation_id    UUID         NOT NULL,

    -- Whether the identifier resolved to a real account. Recorded so the
    -- response can stay uniform (no account enumeration) while operators can
    -- still answer "why did this address never receive anything".
    account_exists    BOOLEAN      NOT NULL,

    CONSTRAINT otp_challenges_purpose_chk
        CHECK (purpose IN ('LOGIN', 'REGISTRATION', 'ACCOUNT_NUMBER_CHANGE', 'PASSWORD_RESET')),
    CONSTRAINT otp_challenges_attempts_chk
        CHECK (attempt_count >= 0 AND attempt_count <= max_attempts),
    CONSTRAINT otp_challenges_invalidated_chk
        CHECK ((invalidated_at IS NULL) = (invalidated_reason IS NULL))
);

-- Verification path: newest live challenge for an identifier and purpose.
CREATE INDEX IF NOT EXISTS idx_otp_challenges_lookup
    ON otp_challenges (identifier_hash, purpose, created_at DESC);

-- Rate limiter: requests per identifier per window.
CREATE INDEX IF NOT EXISTS idx_otp_challenges_identifier_window
    ON otp_challenges (identifier_hash, created_at DESC);

-- Rate limiter: requests per source IP per window. Partial — a null ip_hash
-- carries no rate-limit signal and would only bloat the index.
CREATE INDEX IF NOT EXISTS idx_otp_challenges_ip_window
    ON otp_challenges (ip_hash, created_at DESC)
    WHERE ip_hash IS NOT NULL;

-- Retention sweep.
CREATE INDEX IF NOT EXISTS idx_otp_challenges_created_at
    ON otp_challenges (created_at);

COMMENT ON TABLE otp_challenges IS
    'One-time passcode challenges. Codes are stored only as a peppered HMAC; '
    'plaintext codes and plaintext identifiers are never persisted.';

DROP TABLE IF EXISTS otp;
