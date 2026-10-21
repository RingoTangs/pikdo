-- Extend the effective binding set without changing existing EMAIL/PHONE rows or unique keys.
ALTER TABLE identity_accounts
    DROP CONSTRAINT identity_accounts_type_check,
    ADD CONSTRAINT identity_accounts_type_check CHECK (identity_type IN ('EMAIL', 'PHONE', 'USERNAME')),
    ADD CONSTRAINT identity_accounts_username_check CHECK (
        identity_type <> 'USERNAME' OR (
            length(original_identifier) BETWEEN 3 AND 32
            AND original_identifier ~ '^[A-Za-z][A-Za-z0-9_]{2,31}$'
            AND original_identifier !~ '[^A-Za-z0-9_]'
            AND canonical_identifier = translate(original_identifier,
                'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
            AND canonical_identifier NOT IN ('admin', 'system', 'support')
        )
    );
