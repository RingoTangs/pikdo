CREATE TABLE identity_email_challenges (
    id UUID PRIMARY KEY,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('REGISTER', 'LOGIN')),
    canonical_email VARCHAR(254) COLLATE "C" NOT NULL,
    original_email VARCHAR(254) COLLATE "C" NOT NULL,
    flow_digest BYTEA NOT NULL CHECK (octet_length(flow_digest) = 32),
    code_digest BYTEA NOT NULL CHECK (octet_length(code_digest) = 32),
    eligible BOOLEAN NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    account_id UUID,
    user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (attempts >= 0 AND attempts <= max_attempts),
    CHECK (NOT eligible OR purpose <> 'LOGIN' OR (account_id IS NOT NULL AND user_id IS NOT NULL))
);
CREATE INDEX identity_email_challenges_flow ON identity_email_challenges (flow_digest, canonical_email, purpose);
CREATE INDEX identity_email_challenges_expiry ON identity_email_challenges (expires_at);

-- Each scope has an independent shared budget. Subject keys are server-keyed digests.
CREATE TABLE identity_auth_rate_limits (
    scope VARCHAR(24) NOT NULL,
    subject_digest BYTEA NOT NULL CHECK (octet_length(subject_digest) = 32),
    window_started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    next_allowed_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (scope, subject_digest)
);
CREATE INDEX identity_auth_rate_limits_expiry ON identity_auth_rate_limits (expires_at);
