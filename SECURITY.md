# Local demonstration boundary

Capital Lab is a local educational simulator. It has no user authentication or production banking integration. The dashboard binds to `127.0.0.1`, validates the Host and Origin on browser mutations, requires JSON and a custom request header, caps request sizes, and serves a restrictive content security policy. These controls do not make it suitable for shared or internet-facing deployment.

Use only a dedicated local demo database. The Compose credentials are intentionally public local-demo values. Runs create fresh schemas and persist evidence under ignored `build/dashboard-runs/`; the dashboard never deletes existing schemas. A server session permits at most 100 runs. Restarting the server resets its in-memory run registry, not database history.

Before any hosted version, implement authentication, tenant isolation, per-user run budgets, restricted database roles, lifecycle cleanup, request/resource timeouts, secrets management, and deployment-specific review. Do not expose this local server through a public tunnel.

If you identify a defect, provide a minimal synthetic reproduction without credentials or personal data. Use private reporting when the repository host offers it; no separate private reporting address is configured.
