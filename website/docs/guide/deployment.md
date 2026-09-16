# Deployment

Three Docker Compose setups live in `infra/`, plus Kubernetes manifests for production.

## Quick start

`infra/docker-compose.yaml` starts published images with no configuration and exposes only the
workbench on port `4242`.

```bash
docker compose -f infra/docker-compose.yaml up -d
```

Override `MDEO_IMAGE_PREFIX` to pull from another registry owner and `MDEO_IMAGE_TAG` to pin a
release.

## Development

`infra/docker-compose-dev.yaml` builds every image from the current checkout and additionally exposes
the internal service and database ports.

```bash
docker compose -f infra/docker-compose-dev.yaml up --build
```

It also starts three `optimizer-execution` nodes wired together as peers, so distributed search can be
exercised locally.

## Production

`infra/docker-compose-prod.yaml` runs published images and keeps configuration external.

```bash
cp infra/.env.example infra/.env
# edit infra/.env
docker compose --env-file infra/.env -f infra/docker-compose-prod.yaml up -d
```

Images follow the naming scheme `${MDEO_IMAGE_PREFIX}/mdeo-<service>:${MDEO_IMAGE_TAG}`.

### Settings that matter

| Variable | Why it matters |
| --- | --- |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | The bootstrap administrator. Change both. |
| `DATABASE_*`, `*_DB_USER`, `*_DB_PASSWORD` | Each domain has its own database; give each its own credentials. |
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `JWT_ISSUER` | Signing material for the tokens services use to call the backend. |
| `JWT_EXECUTION_EXPIRATION_SECONDS` | Must outlive your longest optimisation run, or the node cannot report its final state. |
| `CORS_ALLOWED_HOSTS` | The hosts allowed to reach the API. |
| `COOKIE_SECURE`, `COOKIE_SAMESITE` | Leave at the secure defaults behind TLS. |
| `PLUGIN_BASE_URL`, `INTERNAL_PLUGIN_BASE_URL` | How plugin URLs are resolved for the browser and for the backend respectively. |
| `DEFAULT_PLUGIN_URLS` | Comma-separated plugin URLs registered at startup. |
| `EXECUTION_TIMEOUT_MS` | Upper bound on a single execution. |
| `FILE_DATA_COMPUTATION_TIMEOUT_SECONDS` | How long the backend waits for a plugin to compute file data. Default 300. |
| `FILE_DATA_COMPUTATION_BINDING_SECONDS` | How long a running computation, and the token the plugin computes with, stays valid. Defaults to the computation timeout. |
| `PLUGIN_REQUEST_TIMEOUT_SECONDS` | How long a one-shot request to a plugin may take. Default 300. |
| `EXECUTION_START_TIMEOUT_SECONDS`, `EXECUTION_READ_TIMEOUT_SECONDS` | How long starting an execution, and reading, cancelling or deleting one, may take. Defaults 300 and 60. |
| `PLUGIN_MANIFEST_CHECK_SECONDS` | How often every plugin is asked whether its manifest changed. Default 60; 0 checks only on the plugins' answers. |
| `PLUGIN_MANIFEST_TIMEOUT_SECONDS`, `SERVICE_CONNECT_TIMEOUT_SECONDS` | How long fetching a plugin manifest, and opening any connection to a service, may take. Defaults 30 and 10. |

The full list is in `infra/.env.example`.

## Kubernetes

Manifests are under `infra/k8s/`. They provision the backend, the plugin services, the execution
services and their secrets, and are the way to scale out: `optimizer-execution` is designed to run as
several replicas that discover each other through a peer list, each contributing worker threads to a
single run.

## Scaling notes

- **Optimiser nodes** are the component to add when runs are too slow. The `resources` block of a
  config file caps how much of the available capacity a single run may take (`threads`, `nodes`,
  `threadsPerNode`).
- **Plugin services** are stateless apart from a pool of Langium instances. `MAX_LANGIUM_INSTANCES`
  trades memory for the ability to serve concurrent requests with different plugin sets.
- **Databases** are already split per domain, so a heavy optimisation run does not contend with
  workbench traffic.

## After an upgrade

Plugin manifests reference versioned static assets, and a stale manifest makes plugin loading fail
in the workbench. The backend keeps them current by itself: every answer of a plugin service carries
a fingerprint of its manifest, and the backend also asks every plugin for it once a minute
(`PLUGIN_MANIFEST_CHECK_SECONDS`). When a redeployed service reports a different fingerprint, the
backend fetches its manifest again in the background — at most a minute after the deploy.

::: warning
Plugin services built on an older `@mdeo/service-common`, which send no fingerprint, still need a
manual refresh (**Settings → Plugins**) after an upgrade.
:::
