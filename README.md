# CloudCost Monitor

CloudCost Monitor is the application at the center of the `fullstack-devsecops-platform` learning project. We are building the product first, one vertical feature at a time. The DevSecOps platform will be added only after the application is stable.

## Current milestone — Foundation and cloud accounts

- Spring Boot and Next.js project foundations
- PostgreSQL schema managed by Flyway
- Cloud account creation, listing, lookup, update and activation/deactivation
- AWS, Azure and GCP provider model
- Development, staging and production environments
- Backend validation and consistent API errors
- Responsive interface with loading, empty and error states
- Backend API tests and frontend component tests

Real cloud credentials are deliberately out of scope. Accounts are metadata records only.

## Project structure

```text
backend/    Spring Boot REST API
frontend/   Next.js application
docs/       Domain decisions and milestone roadmap
```

The backend is organized by technical layer: `model`, `repository`, `service`, `controller`, `dto`, `exception`, and `common`.

## Run locally

Prerequisites: Java 17, Maven, Node.js 24, npm and PostgreSQL (or Docker for the database only).

```bash
cp .env.example .env
docker compose up -d database

cd backend
mvn spring-boot:run

cd frontend
npm install
npm run dev
```

Open <http://localhost:3000>. The API is available at <http://localhost:8080/api/v1/cloud-accounts>.

## Tests

```bash
cd backend && mvn verify
cd frontend && npm run lint && npm test && npm run build
```

See [docs/ROADMAP.md](docs/ROADMAP.md) for the feature sequence.
