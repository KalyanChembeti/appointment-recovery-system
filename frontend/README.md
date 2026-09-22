# Appointment Recovery System frontend

React 18, TypeScript, and Vite provide a separately built frontend beside the Spring Boot
backend. Keeping the builds separate avoids coupling this first UI stage to a single-JAR
deployment before the production topology is chosen.

## Local development

Run the backend on port 8080, then:

```bash
npm install
npm run dev
```

Vite serves the UI on `http://localhost:5173` and proxies relative `/api/**` requests to
the backend. Useful checks are:

```bash
npm run test:run
npm run lint
npm run build
```

Authentication state is currently held in memory. Refresh restoration requires a future
backend current-session endpoint, as recorded in `docs/DEFERRED_FOLLOWUPS.md`.
