CREATE TABLE cloud_resources (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL,
    external_resource_id VARCHAR(512) NOT NULL,
    name VARCHAR(120) NOT NULL,
    category VARCHAR(20) NOT NULL,
    provider_service VARCHAR(80) NOT NULL,
    region VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_cloud_resource_account FOREIGN KEY (cloud_account_id) REFERENCES cloud_accounts (id),
    CONSTRAINT uk_cloud_resource_account_external_id UNIQUE (cloud_account_id, external_resource_id)
);
