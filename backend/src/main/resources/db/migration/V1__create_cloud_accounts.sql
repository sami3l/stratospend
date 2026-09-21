CREATE TABLE cloud_accounts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    external_account_id VARCHAR(120) NOT NULL,
    environment VARCHAR(20) NOT NULL,
    region VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_cloud_account_provider_external_id UNIQUE (provider, external_account_id)
);
