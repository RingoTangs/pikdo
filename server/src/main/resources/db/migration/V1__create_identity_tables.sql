-- Once deployed, evolve this schema with a new versioned migration.
CREATE TABLE identity_users (
    id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_users_status_check CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- Only the current effective binding set is represented here. No release/history policy
-- is implied by these unique constraints; complete email syntax is checked by EmailAddress.
CREATE TABLE identity_accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    original_address VARCHAR(254) COLLATE "C" NOT NULL,
    canonical_address VARCHAR(254) COLLATE "C" NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_accounts_user_fk FOREIGN KEY (user_id)
        REFERENCES identity_users (id) ON DELETE RESTRICT,
    CONSTRAINT identity_accounts_user_unique UNIQUE (user_id),
    CONSTRAINT identity_accounts_email_unique UNIQUE (canonical_address),
    CONSTRAINT identity_accounts_ascii_check CHECK (original_address ~ '^[!-~]+$'),
    CONSTRAINT identity_accounts_canonical_check CHECK (
        canonical_address = translate(original_address,
            'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
    )
);

CREATE TABLE identity_password_credentials (
    user_id UUID PRIMARY KEY,
    password_hash TEXT NOT NULL,
    authentication_version BIGINT NOT NULL DEFAULT 0,
    -- Identifies future input processing, independently from hash algorithm parameters.
    -- No password policy or concrete format version is selected by this migration.
    password_input_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_password_credentials_user_fk FOREIGN KEY (user_id)
        REFERENCES identity_users (id) ON DELETE RESTRICT,
    CONSTRAINT identity_password_credentials_version_check CHECK (authentication_version >= 0),
    CONSTRAINT identity_password_credentials_input_check CHECK (length(password_input_version) > 0)
);
