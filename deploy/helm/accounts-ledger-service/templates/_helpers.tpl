{{- define "accounts.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- /*
app.kubernetes.io/name is the service account name (platform contract; the
ExternalSecret admission policy matches it) on every pod, the migration Job's
included: mesh NetworkPolicies grant Aurora egress by that label only.
app.kubernetes.io/component tells the pods apart (service for the Deployment,
db-migration for the Job; cicd-templates 335a345), so the Service, PDB,
NetworkPolicy, topology spread and Deployment never select the Job pod.
*/ -}}
{{- define "accounts.baseLabels" -}}
app.kubernetes.io/name: {{ .Values.serviceAccount.name | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{- define "accounts.selectorLabels" -}}
{{ include "accounts.baseLabels" . }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "accounts.labels" -}}
{{ include "accounts.baseLabels" . }}
app.kubernetes.io/version: {{ include "accounts.version" . | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
fintechbankx.io/squad: {{ required "squad is required (corebanking)" .Values.squad | quote }}
{{- end -}}

{{- /*
The container image, by digest only: <repository>@sha256:<64 hex>, the
reference the helm-deploy job verifies with cosign (cicd-templates render
guard). A tag never reaches an image line.
*/ -}}
{{- define "accounts.image" -}}
{{- $repository := required "image.repository is required" .Values.image.repository -}}
{{- $digest := required "image.digest is required (sha256:<64 hex>; the pods run the image by digest)" .Values.image.digest -}}
{{- if regexMatch "[:@]" (regexReplaceAll "^[^/]*/" $repository "") -}}
{{- fail (printf "image.repository must not carry a tag or digest, got %q" $repository) -}}
{{- end -}}
{{- if not (regexMatch "^sha256:[0-9a-f]{64}$" $digest) -}}
{{- fail (printf "image.digest must be sha256:<64 lowercase hex>, got %q" $digest) -}}
{{- end -}}
{{- printf "%s@%s" $repository $digest -}}
{{- end -}}

{{- /*
Value of the version and app.kubernetes.io/version labels: image.tag when set,
else the first 12 hex digits of image.digest (a label value cannot hold ':').
image.tag must be a Kubernetes label value (at most 63 of [A-Za-z0-9._-],
alphanumeric at both ends) and never "latest", so the render fails here instead
of the API server refusing the Deployment, and no label claims a moving tag.
*/ -}}
{{- define "accounts.version" -}}
{{- if .Values.image.tag -}}
{{- $tag := toString .Values.image.tag -}}
{{- if or (gt (len $tag) 63) (not (regexMatch "^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$" $tag)) -}}
{{- fail (printf "image.tag must be a label value (at most 63 characters of A-Z a-z 0-9 . _ -, alphanumeric at both ends), got %q" $tag) -}}
{{- end -}}
{{- if eq (lower $tag) "latest" -}}
{{- fail "image.tag must not be latest (it only names the version labels; use the git SHA)" -}}
{{- end -}}
{{- $tag -}}
{{- else if .Values.image.digest -}}
{{- trunc 12 (trimPrefix "sha256:" .Values.image.digest) -}}
{{- else -}}
{{- .Chart.AppVersion -}}
{{- end -}}
{{- end -}}

{{- define "accounts.secretName" -}}
{{ include "accounts.name" . }}-db
{{- end -}}

{{- /*
Secrets Manager key for an ExternalSecret remoteRef. The platform ESO role may
read only secret:<env>/*, and the admission policy fintechbankx-externalsecret-scope
admits only keys under <env>/<service account>/, so every key must be
<env>/accounts-ledger-service/<name>
(e.g. dev/accounts-ledger-service/db-app).
Usage: include "accounts.remoteKey" (list "externalSecret.remoteSecretName" .Values.externalSecret.remoteSecretName)
*/ -}}
{{- define "accounts.migrationName" -}}
{{ include "accounts.name" . }}-db-migration
{{- end -}}

{{- define "accounts.remoteKey" -}}
{{- $field := index . 0 -}}
{{- $key := required (printf "%s is required" $field) (index . 1) -}}
{{- if not (regexMatch "^(dev|staging|prod)/accounts-ledger-service/[a-z0-9-]+$" $key) -}}
{{- fail (printf "%s must be <env>/accounts-ledger-service/<name> (env dev, staging or prod), got %q" $field $key) -}}
{{- end -}}
{{- $key -}}
{{- end -}}

{{- /*
Datasource and TLS guard (governance round 3, answer 2b; round 6 hardening
after the reviews of PR #14). The rules are the shared chart's fbx.guard,
vendored unchanged as _fbx_helpers.tpl (cicd-templates 73e1881, pinned by
sha256 in the README and in the deploy/helm job). accounts.validateTls passes
it this chart's values through an adapter dict (README "Vendoring the guard"):
config, extraEnv, envFrom and extraEnvFrom (not rendered here, mapped so a
value set by mistake is refused instead of ignored), no javaToolOptions value,
databaseCa (always mounted here), kafka.flavour as kafka.runtime, and the
ExternalSecret keys the templates fix (data: the service Secret, extraData: the
migration Secret) so their names and remote keys go through
fbx.validateKeyNames. fbx.guard then refuses or checks: every
spring.datasource|flyway|liquibase|r2dbc.*, spring.config.*, spring.profiles.*,
spring.ssl.*, *ssl.bundle*, *sslmode*, *sslrootcert*, jdbc_url, sslfactory,
sslhostnameverifier, sslpasswordcallback and fintechbankx.tls.* name in any
spelling or canonical form; DB_URL in any spelling outside config.DB_URL;
JVM options (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS) as literals
free of datasource, ssl, spring.config, spring.profiles, fintechbankx.tls,
security.protocol, jdk.tls, java.security.properties, '@', -XX:VMOptionsFile,
'$(' and '${'; every jdbc:postgresql: value parsed as PgJDBC reads it; every
*security.protocol value SASL_SSL with msk or SSL with strimzi and every
*endpoint.identification.algorithm value https; '$(' in extraEnv values;
envFrom, extraEnvFrom and externalSecret.dataFrom; and the key shapes.

accounts.guardEnv keeps only what fbx.guard does not do (cicd-templates 73e1881
covers the Kafka client TLS names, spring.data.mongodb.* and kafka in JVM
options, so the chart-local rules for those are gone):
  - JAVA_OPTS: not a JVM option name for fbx.guard. The image entrypoint does
    not read it today; it gets the same literal-value and fbx.validateJvmOptions
    rule so a later entrypoint change cannot open the route;
  - JVM option values (the four names) that mention fintechbankx:
    -Dfintechbankx.* is not an option a deployment may set (fbx.guard refuses
    only fintechbankx.tls.*).
Usage: include "accounts.validateTls" $   -- top of deployment.yaml and migration-job.yaml
*/ -}}
{{- define "accounts.validateTls" -}}
{{- $shared := dict "Values" (dict
      "config" .Values.config
      "extraEnv" (.Values.extraEnv | default list)
      "envFrom" (.Values.envFrom | default list)
      "extraEnvFrom" (.Values.extraEnvFrom | default list)
      "javaToolOptions" ""
      "databaseCa" (dict "enabled" true "mountPath" .Values.databaseCa.mountPath "key" .Values.databaseCa.key "configMapName" .Values.databaseCa.configMapName)
      "kafka" (dict "runtime" .Values.kafka.flavour)
      "externalSecret" (dict "enabled" .Values.externalSecret.enabled
        "data" (list
          (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" .Values.externalSecret.remoteSecretName))
        "extraData" (list
          (dict "secretKey" "DB_MIGRATION_USERNAME" "property" "username" "remoteSecretName" .Values.migration.remoteSecretName)
          (dict "secretKey" "DB_MIGRATION_PASSWORD" "property" "password" "remoteSecretName" .Values.migration.remoteSecretName))
        "dataFrom" list)) -}}
{{- include "fbx.guard" $shared -}}
{{- include "accounts.guardEnv" (list "config" .Values.config) -}}
{{- include "accounts.guardEnv" (list "extraEnv" (.Values.extraEnv | default list)) -}}
{{- end -}}

{{- define "accounts.isJavaOpts" -}}
{{- if eq (include "fbx.canonicalName" .) "java.opts" -}}true{{- end -}}
{{- end -}}

{{- define "accounts.validateJvmOptions" -}}
{{- if regexMatch "(?i)fintechbankx" (toString .value) -}}
{{- fail (printf "%s must not mention fintechbankx (a JVM system property would change the service's own settings past the chart's checks)" .where) -}}
{{- end -}}
{{- end -}}

{{- define "accounts.guardEnv" -}}
{{- $field := index . 0 -}}
{{- $entries := index . 1 -}}
{{- $items := list -}}
{{- if kindIs "map" $entries -}}
{{- range $name, $value := $entries -}}
{{- $items = append $items (dict "name" (toString $name) "hasValue" true "value" (toString $value)) -}}
{{- end -}}
{{- else if kindIs "slice" $entries -}}
{{- range $env := $entries -}}
{{- $items = append $items (dict "name" (toString (default "" $env.name)) "hasValue" (and (hasKey $env "value") (not (hasKey $env "valueFrom"))) "value" (toString (default "" $env.value))) -}}
{{- end -}}
{{- end -}}
{{- range $item := $items -}}
{{- $where := printf "%s.%s" $field $item.name -}}
{{- if include "accounts.isJavaOpts" $item.name -}}
{{- if not $item.hasValue -}}
{{- fail (printf "%s must set a literal value (valueFrom cannot be checked for JVM system properties)" $where) -}}
{{- end -}}
{{- include "fbx.validateJvmOptions" (dict "where" $where "value" $item.value) -}}
{{- end -}}
{{- if or (include "fbx.isJvmOptionsName" $item.name) (include "accounts.isJavaOpts" $item.name) -}}
{{- include "accounts.validateJvmOptions" (dict "where" $where "value" $item.value) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- /*
The Spring profile and Kafka protocol of the service pods, from kafka.flavour
only: msk -> kafka-msk + SASL_SSL (IAM), strimzi -> kafka-strimzi + SSL (mutual
TLS), through the vendored fbx.kafkaProfile. config and extraEnv may not name
SPRING_PROFILES_* and JVM options may not mention spring.profiles (fbx.guard),
so no deployment can switch on another application-<profile>.yml in the image,
the local profile included.
*/ -}}
{{- define "accounts.kafkaProfile" -}}
{{- if not (has (toString .Values.kafka.flavour) (list "msk" "strimzi")) -}}
{{- fail (printf "kafka.flavour must be msk or strimzi, got %q" (toString .Values.kafka.flavour)) -}}
{{- end -}}
{{- include "fbx.kafkaProfile" (dict "Values" (dict "kafka" (dict "runtime" .Values.kafka.flavour))) -}}
{{- end -}}

{{- define "accounts.kafkaSecurityProtocol" -}}
{{- if eq (toString .Values.kafka.flavour) "strimzi" -}}SSL{{- else -}}SASL_SSL{{- end -}}
{{- end -}}
