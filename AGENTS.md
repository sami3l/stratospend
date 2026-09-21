# StratoSpend development instructions

## Project approach

StratoSpend is developed feature by feature. Complete and verify the application before starting the DevSecOps implementation.

The developer is learning and wants to participate. Do not implement an entire milestone without explaining and dividing it into small tasks.

## Backend architecture

Use package-by-layer organization:

- `model`
- `repository`
- `service`
- `controller`
- `dto`
- `exception`
- `common`

Do not create packages organized by feature names such as `cloudaccount` or `cloudresource`.

Controllers must contain only HTTP concerns. Business rules belong in services. Database access belongs in repositories. Do not expose JPA entities directly through the API.

## Database

- PostgreSQL is the production database.
- Manage schema changes exclusively through Flyway.
- Never edit an existing migration after it has been applied.
- Create a new migration for every schema change.
- Use H2 only for automated tests.

## Frontend

- Use Next.js, React and TypeScript.
- Keep API calls separate from UI components.
- Include loading, empty, success and error states.
- Preserve the existing responsive design.

## Security

- Never commit `.env` files, passwords, cloud credentials or tokens.
- Do not collect permanent AWS, Azure or GCP access keys.
- Cloud integrations will eventually use short-lived, read-only authorization.

## Working rules

- Inspect existing code before making changes.
- Present a short implementation plan before editing.
- Work on one small task at a time.
- Explain important architectural decisions.
- Run relevant tests after every task.
- Do not commit or push changes.
- Do not modify unrelated files.
- Report every changed file and any remaining problem.