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

variable "ssh_host_key" {
  type        = string
  description = "OpenSSH-format private key for the git-over-SSH server's host key. If null, a key pair is generated automatically and stored in Terraform state."
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

variable "git_oauth_authorize_path" {
  type        = string
  description = "Path of the browser-facing git authorization screen. Both mounted and advertised in the setup commands the workbench shows, so the two cannot drift apart."
  default     = "/oauth/authorize"
}

variable "git_oauth_token_path" {
  type        = string
  description = "Path where git credential helpers exchange an authorization code for an access token."
  default     = "/api/oauth/token"
}

variable "git_ssh_public_host" {
  type        = string
  description = "Host clients should use in an SSH clone URL, when that is not the host the workbench is served from. Null means they are the same."
  nullable    = true
  default     = null
}

variable "git_ssh_publicly_reachable" {
  type        = bool
  description = "Whether the git SSH port is reachable by clients. The in-cluster service port is pod-to-pod only by default, so this stays false until SSH is actually exposed - otherwise the workbench would advertise an SSH clone URL that nobody can reach."
  default     = false
}

variable "trusted_proxy_hops" {
  type        = number
  description = <<-EOT
    How many reverse proxies sit between clients and the backend, used to resolve a request's real
    client address out of X-Forwarded-For for the authentication rate limiter (see
    platform/backend/.../plugins/ClientAddress.kt). Zero, the default, trusts the header not at all
    and keys on the direct peer, which groups every user of a proxied deployment into one bucket but
    can never be spoofed.

    Setting this wrong in the *high* direction is what lets a caller forge its own address, so raise
    it only once the hop count is known for certain. In this deployment it is not uniform: /api goes
    Gateway -> backend, while /git goes Gateway -> workbench nginx -> backend, and whether the
    Gateway appends to X-Forwarded-For at all depends on the controller in use. Set 1 only if the
    Gateway is known to append it.
  EOT
  default     = 0
}
