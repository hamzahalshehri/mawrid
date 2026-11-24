<p align="center"><img src="public/logo-full.png" alt="Mawrid" width="320"></p>

# Mawrid: Local Supply Network & B2B/B2C Platform

CSC 305 Software Engineering project, Imam Abdulrahman Bin Faisal University.
Java 17 backend (AuthFilter/RBAC, services, DAO) + MySQL 8 + responsive web UI (works on phones).

Features: role-based access (Admin, Supplier, Merchant, Consumer), categorized catalog with supplier comparison,
quantity-based dynamic pricing, atomic stock deduction (`SELECT ... FOR UPDATE`), state-machine order tracking.

## Deploy: GitHub + Render
1. Create a GitHub repository and push this folder:
   git init && git add . && git commit -m "Mawrid" && git branch -M main
   git remote add origin https://github.com/<you>/mawrid.git && git push -u origin main
2. On render.com: New > Blueprint, pick the repository. Render reads `render.yaml` and creates:
   - `mawrid-db`: MySQL 8 as a private service with a 1 GB disk (paid plan; Render has no managed MySQL)
   - `mawrid`: the web service (free plan; it sleeps when idle, so the first visit can take about a minute)
3. When asked for ADMIN_PASSWORD, type a strong password.
4. Open https://mawrid.onrender.com (or the URL Render shows) on any phone.
Tables and demo data are created automatically on first start.

## Run locally
- Docker: `docker compose up --build`, then http://localhost:8080
- Manual: `mysql -u root -p < schema.sql`, then `mvn compile exec:java`. Tests: `mvn test`.
Env: DB_URL/DB_USER/DB_PASS or MYSQLHOST/MYSQLPORT/MYSQLDATABASE/MYSQLUSER/MYSQLPASSWORD, ADMIN_PASSWORD, PORT, SEED=false.

## Accounts (first start)
admin@mawrid.sa / ADMIN_PASSWORD (local default admin1234); najd@, gulf@, foods@ (Suppliers), noor@ (Merchant), sara@ (Consumer), all @mawrid.sa / demo1234.
Turn demo data off with SEED=false and change the demo passwords before real use.

## REST API (/api, JSON, header "Authorization: Bearer <token>")
GET /health | POST /register, /login, /logout | GET /categories, /products, /products/compare?key= | POST /pricing/quote
POST /orders | GET /orders, /orders/{id} | PUT /orders/{id}/status
GET/POST /supplier/products, PUT/DELETE /supplier/products/{id}
GET /admin/users, PUT /admin/users/{id}/status, POST /admin/categories, DELETE /admin/categories/{id}

## Docs
SRS, SDD, SPMP, proposal and status report are in `docs/`.
