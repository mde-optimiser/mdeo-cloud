# MDEO Cloud (mdeo-cloud)

Welcome to **MDEO Cloud**, a scalable, cloud-native platform for Model-Driven Engineering Optimization. 

> **Note:** This project forks and incorporates parts of the original [MDEOptimiser](https://github.com/mde-optimiser/mde_optimiser). It builds upon its foundation to deliver a robust, distributed, and web-based execution platform for model optimizations and transformations.

## 🏗️ Architecture Overview

MDEO Cloud relies on a modern, distributed architecture composed of several key components:

1. **Frontend Web Workbench (`app/`)**
   - A modular web-based workbench that serves as the primary user interface.
   - Built around multiple integrated packages including language editors, metamodel designers, and transformation configuration panels.
   - Integrates closely with [Langium](https://langium.org/) to provide intelligent, browser-based language servers for custom DSLs.

2. **Backend Platform (`platform/`)**
   - A multi-module application responsible for core logic, model parsing, execution, and optimization algorithms.
   - Modules are logically separated by domain: `metamodel`, `model-transformation`, `script`, `optimizer`, and their respective execution engines.
   - Employs a distributed execution model where computationally heavy tasks (like optimization and model transformation) run on dedicated execution nodes (`optimizer-execution`, `script-execution`, etc.).

3. **Storage & Infrastructure**
   - **PostgreSQL**: Used as the primary data store, heavily segregated across domains (e.g., dedicated databases for backend, scripts, model transformations, and optimizers) to ensure data isolation and scalability.
   - Inter-service communication connects the frontend IDE with backend APIs and language services in a containerized environment.

## 💻 Supported DSLs

MDEO Cloud supports multiple custom Domain-Specific Languages (DSLs) built with [Langium](https://langium.org/) to cover the full lifecycle of model-driven engineering optimization. The integrated web workbench provides rich language support for:

- **Metamodel Language**: Define the structure, constraints, and semantics of your models.
- **Model Language**: Instantiate models based on your defined metamodels.
- **Model-Transformation Language**: Define rules to transform models and explore the search space during optimization.
- **Config Language**: Setup optimization parameters, specify objectives, and configure the execution environment.
- **Script Language**: Write custom logic and expressions to guide the optimization process.

## 🚀 Deployment

MDEO Cloud is designed to be easily deployable in both local development environments and scalable cloud infrastructures.

### Docker Compose Setups
Three Docker Compose setups are available in `infra/`, each aimed at a different workflow.

#### Quick Start
Use `infra/docker-compose.yaml` when you want the fastest local startup from published images with no extra environment configuration. It only exposes the workbench on port `4242`.

```bash
docker compose up -d
```

Override `MDEO_IMAGE_PREFIX` if you publish the images under another registry owner, for example `ghcr.io/your-user`. Override `MDEO_IMAGE_TAG` to pin a different release.

#### Development
Use `infra/docker-compose-dev.yaml` when you are changing code locally and want Compose to build the containers from the current checkout. This setup also exposes the internal service and database ports that are useful during debugging.

```bash
docker compose -f infra/docker-compose-dev.yaml up --build
```

> [!NOTE]
> **Default credentials**
>
> - **Username:** `admin`
> - **Password:** `admin`

#### Production
Use `infra/docker-compose-prod.yaml` for a production-style deployment from published images. This setup keeps configuration externalized through environment variables and is intended for managed hosts or VM deployments where you provide a populated env file.

```bash
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/docker-compose-prod.yaml up -d
```

`docker-compose-prod.yaml` uses the same published image naming scheme as the Kubernetes setup: `${MDEO_IMAGE_PREFIX}/mdeo-<service>:${MDEO_IMAGE_TAG}`.

### Cloud / Production (Kubernetes)
For production deployments or scaling the execution nodes (such as spinning up multiple optimizer execution workers), Kubernetes manifests are available.

- Kubernetes deployment configurations can be found under the `infra/k8s/` directory.
- Apply these manifests to your cluster to provision the MDEO Cloud microservices, manage secrets, and configure load balancing.
