# Getting started

The fastest way to see MDEO Cloud is to start the published images with Docker Compose.

## Run it

```bash
git clone https://github.com/mde-optimiser/mdeo-cloud.git
cd mdeo-cloud
docker compose -f infra/docker-compose.yaml up -d
```

Open `http://localhost:4242` in a browser and sign in.

::: warning Default credentials
The bundled setup creates an administrator with username `admin` and password `admin`. Change it
before exposing the instance to anyone else, and see [Deployment](/guide/deployment) for a
configuration that does not ship with a known password.
:::

The quick-start compose file pulls prebuilt images and only exposes the workbench on port `4242`.
Everything else — the backend, the plugin services, the execution services and their databases
— runs on the internal network.

## First look around

1. **Create a project.** Projects own files, plugins and executions. A new project gets every plugin
   that an administrator marked as *default*, which by default is all of the bundled ones.
2. **Add files.** Use the file tree to create a `.mm` metamodel. The moment you save, the metamodel
   is parsed and validated, and a diagram view becomes available next to the text.
3. **Run something.** Files whose language supports it get a run action. Executions appear in the
   executions panel and stream their progress live.

The [walkthrough](/guide/walkthrough) does all of this with a concrete example.

## Building from source

To run the code in your checkout rather than published images:

```bash
docker compose -f infra/docker-compose-dev.yaml up --build
```

This build also exposes the internal ports, which is what you want while debugging:

| Port | Service |
| --- | --- |
| 4242 | Workbench |
| 8080 | Backend API |
| 3000–3008 | The nine plugin services, on the same ports the [Vite dev proxy](/develop/local-development) expects |
| 5432–5435 | PostgreSQL instances (backend, script, model-transformation, optimizer) |

For iterating on the frontend without Docker, see [Local development](/develop/local-development).

## Upgrading

::: tip After upgrading
The backend notices redeployed plugin services within a minute and fetches their manifests again.
If plugins fail to load right after an upgrade, wait a moment, or refresh them by hand
(**Settings → Plugins → Refresh**).
:::
