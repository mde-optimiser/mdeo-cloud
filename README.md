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

### Local Development (Docker Compose)
The easiest way to run the platform locally is using Docker Compose. The configuration is located in the `infra/` directory.

```bash
cd infra
# Start the full stack (Workbench, Backend, Langium Services, Execution Nodes, and Databases)
docker compose up --build
```
*You can also use `docker-compose-dev.yaml` for a more development-tailored setup.*

### Cloud / Production (Kubernetes)
For production deployments or scaling the execution nodes (such as spinning up multiple optimizer execution workers), Kubernetes manifests are available.

- Kubernetes deployment configurations can be found under the `infra/k8s/` directory.
- Apply these manifests to your cluster to provision the MDEO Cloud microservices, manage secrets, and configure load balancing.
