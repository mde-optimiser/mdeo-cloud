variable "kubeconfig" {
  type        = string
  description = "Path to the kubeconfig file used to connect to the cluster"
  default     = "./kubeconfig.yaml"
}

variable "namespace" {
  type        = string
  description = "Kubernetes namespace to deploy all mdeo resources into"
  default     = "mdeo"
}

variable "storage_class" {
  type        = string
  description = "Storage class to use for persistent volumes (null = cluster default)"
  nullable    = true
  default     = null
}

variable "image_registry" {
  type        = string
  description = "Container image registry"
  default     = "ghcr.io"
}

variable "image_owner" {
  type        = string
  description = "Registry owner/organisation (e.g. 'myorg' → images pulled from ghcr.io/myorg/mdeo-<service>)"
  default     = "mde-optimiser"
}

variable "app_version" {
  type        = string
  description = "Docker image tag to deploy (e.g. 'latest', 'v1.2.3')"
  default     = "latest"
}

variable "app_endpoint" {
  type        = string
  description = "Public base URL of the application (e.g. 'http://localhost' or 'https://mdeo.example.com')"
  default     = "http://localhost"
}

variable "admin_username" {
  type        = string
  description = "Backend admin username"
  default     = "admin"
}

variable "admin_password" {
  type        = string
  description = "Backend admin password"
  sensitive   = true
}

variable "database_user" {
  type        = string
  description = "PostgreSQL username used for all databases"
  default     = "mdeo"
}

variable "execution_timeout_ms" {
  type        = number
  description = "Timeout in milliseconds for script/model-transformation/optimizer execution"
  default     = 300000
}

variable "session_max_idle_seconds" {
  type        = number
  description = "Idle timeout (in seconds) before a backend session cookie expires. The cookie Max-Age is refreshed on every authenticated request (sliding window)."
  default     = 604800
}

variable "session_max_absolute_seconds" {
  type        = number
  description = "Absolute maximum session lifetime in seconds, measured from login time. Regardless of activity, the user is forced to re-login after this duration."
  default     = 15768000
}

variable "session_encryption_key" {
  type        = string
  description = "64 hex-character (32-byte) HMAC key used to sign session cookies. If null, a random key is generated and stored in Terraform state."
  sensitive   = true
  nullable    = true
  default     = null
}

variable "jwt_private_key" {
  type        = string
  description = "RSA private key for JWT signing, in PEM (PKCS#8) or raw Base64-DER format. If null (and jwt_public_key is also null), a 2048-bit key pair is generated automatically."
  sensitive   = true
  nullable    = true
  default     = null
}

variable "jwt_public_key" {
  type        = string
  description = "RSA public key for JWT verification, in PEM (PKIX) or raw Base64-DER format. Must be set together with jwt_private_key, or left null to use the auto-generated pair."
  sensitive   = true
  nullable    = true
  default     = null
}

variable "max_langium_instances" {
  type        = number
  description = "Maximum number of Langium worker instances per JS service"
  default     = 5
}

variable "gateway_class_name" {
  type        = string
  description = "GatewayClass name to use for the mdeo Gateway (e.g. 'nginx', 'cilium', 'traefik')"
  default     = "nginx"
}

variable "gateway_https_listener" {
  type        = bool
  description = "Whether to add an HTTPS (port 443) listener to the Gateway (null = auto-detect from app_endpoint scheme). Set to false when TLS is terminated by an external reverse proxy so the gateway only needs the HTTP listener even if app_endpoint is https://..."
  nullable    = true
  default     = null
}

variable "gateway_annotations" {
  type        = map(string)
  description = "Annotations to add to the Gateway's infrastructure (propagated to the generated LoadBalancer Service). Use this to pass provider-specific hints, e.g. { \"metallb.universe.tf/loadBalancerIPs\" = \"1.2.3.4\" }."
  nullable    = true
  default     = null
}

variable "optimizer_execution_replicas" {
  type        = number
  description = "Number of optimizer-execution replicas (StatefulSet pods)"
  default     = 3
}

variable "optimizer_worker_threads" {
  type        = number
  description = "Number of worker threads per optimizer-execution pod"
  default     = 1
}

variable "optimizer_script_timeout_ms" {
  type        = number
  description = "Default per-script evaluation timeout in milliseconds for optimizer-execution pods"
  default     = 1000
}

variable "optimizer_transformation_timeout_ms" {
  type        = number
  description = "Default per-transformation timeout in milliseconds for optimizer-execution pods"
  default     = 1000
}
