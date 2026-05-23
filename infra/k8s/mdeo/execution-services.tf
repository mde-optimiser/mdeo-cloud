locals {
  execution_services = {
    "script-execution" = {
      port         = 8080
      db_key       = "postgres-script"
      cluster_name = "postgres-script"
    }

    "model-transformation-execution" = {
      port         = 8080
      db_key       = "postgres-model-transformation"
      cluster_name = "postgres-model-transformation"
    }
  }
}

resource "kubernetes_service_v1" "execution" {
  for_each = local.execution_services

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

resource "kubernetes_deployment_v1" "execution" {
  for_each = local.execution_services

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

          env {
            name  = "SERVER_PORT"
            value = tostring(each.value.port)
          }

          env {
            name  = "DATABASE_URL"
            value = "jdbc:postgresql://${each.value.cluster_name}-rw:5432/mdeo"
          }

          env {
            name  = "DATABASE_USER"
            value = var.database_user
          }

          env {
            name = "DATABASE_PASSWORD"
            value_from {
              secret_key_ref {
                name = kubernetes_secret_v1.db_credentials[each.value.db_key].metadata[0].name
                key  = "password"
              }
            }
          }

          env {
            name  = "BACKEND_API_URL"
            value = "http://backend:8080/api"
          }

          env {
            name  = "EXECUTION_TIMEOUT_MS"
            value = tostring(var.execution_timeout_ms)
          }

          liveness_probe {
            http_get {
              path = "/health"
              port = each.value.port
            }
            initial_delay_seconds = 90
            period_seconds        = 10
            failure_threshold     = 20
            timeout_seconds       = 10
          }

          readiness_probe {
            http_get {
              path = "/health"
              port = each.value.port
            }
            initial_delay_seconds = 60
            period_seconds        = 10
            failure_threshold     = 10
            timeout_seconds       = 10
          }
        }
      }
    }
  }

  depends_on = [kubernetes_manifest.db_cluster]
}

# ---------------------------------------------------------------------------
# optimizer-execution — StatefulSet (multi-node)
# ---------------------------------------------------------------------------

resource "kubernetes_service_v1" "optimizer_execution_headless" {
  metadata {
    name      = "optimizer-execution-headless"
    namespace = local.ns
    labels = {
      "app.kubernetes.io/name"    = "optimizer-execution"
      "app.kubernetes.io/part-of" = "mdeo"
    }
  }

  spec {
    cluster_ip = "None"

    selector = {
      "app.kubernetes.io/name" = "optimizer-execution"
    }

    port {
      name        = "http"
      port        = 8080
      target_port = 8080
      protocol    = "TCP"
    }
  }
}

resource "kubernetes_service_v1" "optimizer_execution" {
  metadata {
    name      = "optimizer-execution"
    namespace = local.ns
    labels = {
      "app.kubernetes.io/name"    = "optimizer-execution"
      "app.kubernetes.io/part-of" = "mdeo"
    }
  }

  spec {
    selector = {
      "app.kubernetes.io/name" = "optimizer-execution"
    }

    port {
      name        = "http"
      port        = 8080
      target_port = 8080
      protocol    = "TCP"
    }
  }
}

resource "kubernetes_stateful_set_v1" "optimizer_execution" {
  metadata {
    name      = "optimizer-execution"
    namespace = local.ns
    labels = {
      "app.kubernetes.io/name"    = "optimizer-execution"
      "app.kubernetes.io/part-of" = "mdeo"
    }
  }

  spec {
    replicas     = var.optimizer_execution_replicas
    service_name = kubernetes_service_v1.optimizer_execution_headless.metadata[0].name

    selector {
      match_labels = {
        "app.kubernetes.io/name" = "optimizer-execution"
      }
    }

    template {
      metadata {
        labels = {
          "app.kubernetes.io/name"    = "optimizer-execution"
          "app.kubernetes.io/part-of" = "mdeo"
        }
      }

      spec {
        container {
          name              = "optimizer-execution"
          image             = "${var.image_registry}/${var.image_owner}/mdeo-optimizer-execution:${var.app_version}"
          image_pull_policy = "Always"

          resources {
            requests = {
              cpu = tostring(var.optimizer_worker_threads)
            }
          }

          port {
            container_port = 8080
            protocol       = "TCP"
          }

          env {
            name  = "SERVER_PORT"
            value = "8080"
          }

          env {
            name  = "DATABASE_URL"
            value = "jdbc:postgresql://postgres-optimizer-rw:5432/mdeo"
          }

          env {
            name  = "DATABASE_USER"
            value = var.database_user
          }

          env {
            name = "DATABASE_PASSWORD"
            value_from {
              secret_key_ref {
                name = kubernetes_secret_v1.db_credentials["postgres-optimizer"].metadata[0].name
                key  = "password"
              }
            }
          }

          env {
            name  = "BACKEND_API_URL"
            value = "http://backend:8080/api"
          }

          env {
            name  = "WORKER_THREADS"
            value = tostring(var.optimizer_worker_threads)
          }

          env {
            name  = "SCRIPT_TIMEOUT_MS"
            value = tostring(var.optimizer_script_timeout_ms)
          }

          env {
            name  = "TRANSFORMATION_TIMEOUT_MS"
            value = tostring(var.optimizer_transformation_timeout_ms)
          }

          # NODE_ID is derived from the StatefulSet pod ordinal via the entrypoint.
          # PEERS and NODE_URL are computed dynamically at startup, excluding the local node.
          command = ["/bin/sh", "-c"]
          args    = ["export NODE_ID=$${HOSTNAME##*-}; PEERS=''; for i in $(seq 0 $((${var.optimizer_execution_replicas} - 1))); do if [ \"$i\" != \"$NODE_ID\" ]; then [ -n \"$PEERS\" ] && PEERS=\"$PEERS,\"; PEERS=\"$${PEERS}http://optimizer-execution-$${i}.optimizer-execution-headless.${local.ns}.svc.cluster.local:8080\"; fi; done; export PEERS; export NODE_URL=\"http://optimizer-execution-$${NODE_ID}.optimizer-execution-headless.${local.ns}.svc.cluster.local:8080\"; exec java --add-opens java.xml/org.xml.sax.helpers=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED -jar /app/optimizer-execution.jar"]

          liveness_probe {
            http_get {
              path = "/health"
              port = 8080
            }
            initial_delay_seconds = 90
            period_seconds        = 10
            failure_threshold     = 20
            timeout_seconds       = 10
          }

          readiness_probe {
            http_get {
              path = "/health"
              port = 8080
            }
            initial_delay_seconds = 60
            period_seconds        = 10
            failure_threshold     = 10
            timeout_seconds       = 10
          }
        }
      }
    }
  }

  depends_on = [kubernetes_manifest.db_cluster]
}
