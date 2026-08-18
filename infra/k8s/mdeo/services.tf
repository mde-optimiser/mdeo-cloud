locals {
  # Each entry: name → { port, env }
  # All env values must be strings.
  js_services = {
    "service-metamodel" = {
      port = 3000
      env = {
        PORT                  = "3000"
        HOST                  = "0.0.0.0"
        BACKEND_API_URL       = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES = tostring(var.max_langium_instances)
      }
    }

    "service-model" = {
      port = 3000
      env = {
        PORT                  = "3000"
        HOST                  = "0.0.0.0"
        BACKEND_API_URL       = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES = tostring(var.max_langium_instances)
      }
    }

    "service-script" = {
      port = 3000
      env = {
        PORT                         = "3000"
        HOST                         = "0.0.0.0"
        BACKEND_API_URL              = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES        = tostring(var.max_langium_instances)
        SCRIPT_EXECUTION_SERVICE_URL = "http://script-execution:8080"
      }
    }

    "service-model-transformation" = {
      port = 3000
      env = {
        PORT                                       = "3000"
        HOST                                       = "0.0.0.0"
        BACKEND_API_URL                            = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES                      = tostring(var.max_langium_instances)
        MODEL_TRANSFORMATION_EXECUTION_SERVICE_URL = "http://model-transformation-execution:8080"
      }
    }

    "service-config" = {
      port = 3000
      env = {
        PORT                  = "3000"
        HOST                  = "0.0.0.0"
        BACKEND_API_URL       = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES = tostring(var.max_langium_instances)
      }
    }

    "service-config-optimization" = {
      port = 3000
      env = {
        PORT                  = "3000"
        HOST                  = "0.0.0.0"
        BACKEND_API_URL       = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES = tostring(var.max_langium_instances)
      }
    }

    "service-config-mdeo" = {
      port = 3000
      env = {
        PORT                           = "3000"
        HOST                           = "0.0.0.0"
        BACKEND_API_URL                = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES          = tostring(var.max_langium_instances)
        OPTIMIZER_EXECUTION_SERVICE_URL = "http://optimizer-execution:8080"
      }
    }

    "service-csv" = {
      port = 3000
      env = {
        PORT                  = "3000"
        HOST                  = "0.0.0.0"
        BACKEND_API_URL       = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES = tostring(var.max_langium_instances)
      }
    }

    "service-model-csv" = {
      port = 3000
      env = {
        PORT                  = "3000"
        HOST                  = "0.0.0.0"
        BACKEND_API_URL       = "http://backend:8080/api"
        MAX_LANGIUM_INSTANCES = tostring(var.max_langium_instances)
      }
    }

    "workbench" = {
      port = 80
      env = {
        PLUGIN_METAMODEL_SERVICE              = "http://service-metamodel:3000"
        PLUGIN_MODEL_SERVICE                  = "http://service-model:3000"
        PLUGIN_SCRIPT_SERVICE                 = "http://service-script:3000"
        PLUGIN_MODEL_TRANSFORMATION_SERVICE   = "http://service-model-transformation:3000"
        PLUGIN_CONFIG_SERVICE                 = "http://service-config:3000"
        PLUGIN_CONFIG_OPTIMIZATION_SERVICE    = "http://service-config-optimization:3000"
        PLUGIN_CONFIG_MDEO_SERVICE            = "http://service-config-mdeo:3000"
        PLUGIN_CSV_SERVICE                    = "http://service-csv:3000"
        PLUGIN_MODEL_CSV_SERVICE              = "http://service-model-csv:3000"
        # No path component, deliberately: nginx's proxy_pass treats a URI
        # here as replacing the matched location prefix rather than being
        # appended to it, so an /api suffix here would turn /api/... into
        # /api... and /git/... into /api.... Both compose files already get
        # this right by leaving it origin-only; this one did not.
        BACKEND_SERVICE                       = "http://backend:8080"
      }
    }
  }
}

resource "kubernetes_service_v1" "js" {
  for_each = local.js_services

  metadata {
    name      = each.key
    namespace = local.ns
    labels = {
      "app.kubernetes.io/name"    = each.key
      "app.kubernetes.io/part-of" = "mdeo"
    }
  }

  spec {
    selector = {
      "app.kubernetes.io/name" = each.key
    }

    port {
      name        = "http"
      port        = each.value.port
      target_port = each.value.port
      protocol    = "TCP"
    }
  }
}

resource "kubernetes_deployment_v1" "js" {
  for_each = local.js_services

  metadata {
    name      = each.key
    namespace = local.ns
    labels = {
      "app.kubernetes.io/name"    = each.key
      "app.kubernetes.io/part-of" = "mdeo"
    }
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        "app.kubernetes.io/name" = each.key
      }
    }

    template {
      metadata {
        labels = {
          "app.kubernetes.io/name"    = each.key
          "app.kubernetes.io/part-of" = "mdeo"
        }
      }

      spec {
        container {
          name              = each.key
          image             = "${var.image_registry}/${var.image_owner}/mdeo-${each.key}:${var.app_version}"
          image_pull_policy = "Always"

          port {
            container_port = each.value.port
            protocol       = "TCP"
          }

          dynamic "env" {
            for_each = each.value.env
            content {
              name  = env.key
              value = env.value
            }
          }

          liveness_probe {
            http_get {
              path = "/"
              port = each.value.port
            }
            initial_delay_seconds = 30
            period_seconds        = 10
            failure_threshold     = 20
            timeout_seconds       = 10
          }

          readiness_probe {
            http_get {
              path = "/"
              port = each.value.port
            }
            initial_delay_seconds = 15
            period_seconds        = 10
            failure_threshold     = 10
            timeout_seconds       = 10
          }
        }
      }
    }
  }
}
