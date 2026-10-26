-- Lets cost_records reference a resource together with its account, so a cost can never
-- point at a resource that belongs to a different account. Always true because id is unique.
ALTER TABLE cloud_resources
    ADD CONSTRAINT uk_cloud_resource_id_account UNIQUE (id, cloud_account_id);

CREATE TABLE cost_records (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL,
    -- Empty for costs that belong to no single resource, such as support, tax or data transfer.
    cloud_resource_id BIGINT,
    provider_service VARCHAR(80) NOT NULL,
    usage_date DATE NOT NULL,
    -- Exact decimal; negative for credits and refunds.
    amount NUMERIC(19, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_cost_record_account FOREIGN KEY (cloud_account_id) REFERENCES cloud_accounts (id),
    CONSTRAINT fk_cost_record_resource_account FOREIGN KEY (cloud_resource_id, cloud_account_id)
        REFERENCES cloud_resources (id, cloud_account_id),
    CONSTRAINT ck_cost_record_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- NULLS NOT DISTINCT makes an empty resource count as one value, so costs without a resource cannot be duplicated.
    CONSTRAINT uk_cost_record_account_resource_service_date
        UNIQUE NULLS NOT DISTINCT (cloud_account_id, cloud_resource_id, provider_service, usage_date)
);

-- Monthly totals filter by date across all accounts; the unique index already serves account-first queries.
CREATE INDEX idx_cost_records_usage_date ON cost_records (usage_date);
