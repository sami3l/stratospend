# Product roadmap

## Milestone 1 — Foundation and cloud accounts

- [x] Define the first domain model and PostgreSQL migration
- [x] Implement Cloud Account CRUD API
- [x] Add validation and problem responses
- [x] Build account list and registration interface
- [x] Add activation/deactivation and automated tests

## Milestone 2 — Cloud resource inventory

- [x] Resource entity and account relationship
- [x] Resource CRUD, filtering, pagination and sorting (resources are marked inactive instead of deleted)
- [x] Resource inventory table and details view, with resource editing
- [x] Seed/demo data importer (`demo` Spring profile)

## Milestone 3 — Cost tracking

- [ ] Daily cost records and CSV import
- [ ] Monthly totals and comparisons
- [ ] Cost by provider and service
- [ ] Dashboard charts

## Milestone 4 — Budgets and recommendations

- [ ] Monthly budgets and thresholds
- [ ] Alert lifecycle
- [ ] Rule-based recommendations and estimated savings

## Milestone 5 — Authentication and stabilization

- [ ] JWT authentication and roles
- [ ] Complete integration tests
- [ ] OpenAPI documentation
- [ ] Responsive and accessibility review

After the application, we will sequentially implement Docker, GitHub Actions, code quality, supply-chain security, Kubernetes, GitOps, observability and optional AWS infrastructure.
