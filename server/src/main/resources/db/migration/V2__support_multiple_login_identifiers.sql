-- Preserve existing email bindings and their IDs, original spelling and audit timestamps.
ALTER TABLE identity_accounts RENAME COLUMN original_address TO original_identifier;
ALTER TABLE identity_accounts RENAME COLUMN canonical_address TO canonical_identifier;
ALTER TABLE identity_accounts ADD COLUMN identity_type VARCHAR(16);
UPDATE identity_accounts SET identity_type = 'EMAIL';
ALTER TABLE identity_accounts ALTER COLUMN identity_type SET NOT NULL;

ALTER TABLE identity_accounts
    DROP CONSTRAINT identity_accounts_user_unique,
    DROP CONSTRAINT identity_accounts_email_unique,
    DROP CONSTRAINT identity_accounts_ascii_check,
    DROP CONSTRAINT identity_accounts_canonical_check,
    ADD CONSTRAINT identity_accounts_type_check CHECK (identity_type IN ('EMAIL', 'PHONE')),
    ADD CONSTRAINT identity_accounts_user_type_unique UNIQUE (user_id, identity_type),
    ADD CONSTRAINT identity_accounts_identifier_unique UNIQUE (identity_type, canonical_identifier),
    ADD CONSTRAINT identity_accounts_email_check CHECK (
        identity_type <> 'EMAIL' OR (
            original_identifier ~ '^[!-~]+$'
            AND canonical_identifier = translate(original_identifier,
                'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
        )
    ),
    ADD CONSTRAINT identity_accounts_phone_check CHECK (
        identity_type <> 'PHONE' OR (
            original_identifier ~ '^[+][1-9][0-9]{1,14}$'
            AND original_identifier !~ '[^+0-9]'
            AND canonical_identifier = original_identifier
        )
    );
