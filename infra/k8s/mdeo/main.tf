terraform {
  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 3.4"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.4"
    }
  }
}

provider "kubernetes" {
  config_path = var.kubeconfig
}

resource "kubernetes_namespace_v1" "mdeo" {
  metadata {
    name = var.namespace
    labels = {
      "app.kubernetes.io/managed-by" = "terraform"
      "app.kubernetes.io/part-of"    = "mdeo"
    }
  }
}

locals {
  ns = kubernetes_namespace_v1.mdeo.metadata[0].name

  # Relative plugin paths served via the workbench nginx proxy.
  # These must be relative paths so they work in the browser (the browser
  # cannot resolve internal k8s service hostnames). The backend uses
  # INTERNAL_PLUGIN_BASE_URL (http://workbench:80) as the prefix server-side.
  plugin_service_urls = "/plugin/metamodel,/plugin/model,/plugin/script,/plugin/model-transformation,/plugin/config,/plugin/config-optimization,/plugin/config-mdeo,/plugin/csv,/plugin/model-csv"
}
