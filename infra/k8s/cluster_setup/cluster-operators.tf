# Cluster-level operators – applied once per cluster before deploying mdeo.

# ── NGINX Gateway Fabric ──────────────────────────────────────────────────────
# Installs the NGINX Gateway Fabric controller and creates the `nginx`
# GatewayClass that the mdeo Gateway resource references.
resource "helm_release" "nginx_gateway_fabric" {
  name             = "nginx-gateway-fabric"
  repository       = "oci://ghcr.io/nginx/charts"
  chart            = "nginx-gateway-fabric"
  namespace        = "nginx-gateway"
  create_namespace = true
  wait             = true
  wait_for_jobs    = true

  set = [
    {
      name  = "nginxGateway.image.pullPolicy"
      value = "Always"
    },
  ]
}

# ── CloudNativePG Operator ────────────────────────────────────────────────────
# Installs the CNPG operator and registers all postgresql.cnpg.io CRDs.
# The mdeo deployment (../mdeo) assumes these CRDs exist at plan time, so this
# must be applied before running `tofu apply` in ../mdeo.
resource "helm_release" "cnpg_operator" {
  name             = "cnpg"
  repository       = "https://cloudnative-pg.github.io/charts"
  chart            = "cloudnative-pg"
  namespace        = "cnpg-system"
  create_namespace = true
  wait             = true
  wait_for_jobs    = true
}
