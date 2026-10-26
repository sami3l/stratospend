.PHONY: database test frontend backend backend-demo

database:
	docker compose up -d database

test:
	cd backend && mvn verify
	cd frontend && npm test

backend:
	cd backend && mvn spring-boot:run

backend-demo:
	cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=demo

frontend:
	cd frontend && npm run dev
