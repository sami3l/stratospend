.PHONY: database test frontend backend

database:
	docker compose up -d database

test:
	cd backend && mvn verify
	cd frontend && npm test

backend:
	cd backend && mvn spring-boot:run

frontend:
	cd frontend && npm run dev
