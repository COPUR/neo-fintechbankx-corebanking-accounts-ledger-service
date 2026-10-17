# AWS resources owned by svc-acc-accounts-ledger: its own Aurora PostgreSQL
# cluster (db_acc_accounts_ledger_<env>), encryption key, credentials and the
# IRSA role its pods use. Shared platform pieces (log group, SSM parameters,
# runtime secret) come from the platform microservice-base module.
#
# microservice-base (ref=main) names its runtime secret
# "<env>/<slug>/runtime" since terraform-modules #11 (it was
# "<env>-<slug>/runtime", outside the secret:<env>/* path the platform ESO role
# may read). This stack passes no runtime_secret_name and reads no runtime
# secret, so it takes the module default.

locals {
  service_id   = "svc-acc-accounts-ledger"
  service_slug = "accounts-ledger-service"
  name         = "${var.environment}-${local.service_slug}"
  database     = "db_acc_accounts_ledger_${var.environment}"

  tags = merge({
    Service            = local.service_id
    BoundedContext     = "accounts"
    OwningSquad        = "core-banking-accounts"
    Environment        = var.environment
    DataClassification = "confidential"
    ManagedBy          = "terraform"
  }, var.tags)
}

module "service_base" {
  source = "git::https://github.com/COPUR/fintechbankx-platform-delivery-iac-terraform-modules.git//modules/microservice-base?ref=main"

  service_name           = "Core Banking Accounts Ledger Service"
  service_slug           = local.service_slug
  environment            = var.environment
  database_engine        = "aurora-postgresql"
  cache_engine           = "none"
  identity_provider_url  = var.identity_provider_url
  observability_endpoint = var.observability_endpoint
  parameter_prefix       = "/fintechbankx"
  log_retention_days     = var.environment == "prod" ? 365 : 30
  tags                   = local.tags
}

# --- Encryption -------------------------------------------------------------

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

# Key policy: the account root administers the key, so access is granted by IAM
# policies in this account. The pods' role gets no KMS grant. External Secrets
# Operator decrypts the db-app and db-migration secrets through the platform's tag-based grant on
# keys tagged fintechbankx.io/secrets = "true" (platform contract), so this
# module needs no ESO role ARN.
data "aws_iam_policy_document" "database_key" {
  statement {
    sid       = "AccountRootAdministersTheKey"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "database" {
  description             = "Encrypts ${local.database} storage, snapshots, logs and credentials"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.database_key.json

  tags = {
    "fintechbankx.io/secrets" = "true"
  }
}

resource "aws_kms_alias" "database" {
  name          = "alias/${local.name}-db"
  target_key_id = aws_kms_key.database.key_id
}

# --- Network ----------------------------------------------------------------

resource "aws_db_subnet_group" "database" {
  name       = "${local.name}-db"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "database" {
  name        = "${local.name}-db"
  description = "PostgreSQL access for ${local.service_id} only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "postgres_from_workload" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = var.workload_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from ${local.service_id} pods"
}

# --- Aurora PostgreSQL (Serverless v2, Multi-AZ) ---------------------------

resource "aws_rds_cluster_parameter_group" "database" {
  name   = "${local.name}-aurora-pg16"
  family = "aurora-postgresql16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
}

resource "aws_rds_cluster" "database" {
  cluster_identifier                  = "${local.name}-aurora"
  engine                              = "aurora-postgresql"
  engine_mode                         = "provisioned"
  engine_version                      = var.aurora_engine_version
  database_name                       = local.database
  master_username                     = "accounts_admin"
  manage_master_user_password         = true
  master_user_secret_kms_key_id       = aws_kms_key.database.key_id
  db_subnet_group_name                = aws_db_subnet_group.database.name
  vpc_security_group_ids              = [aws_security_group.database.id]
  db_cluster_parameter_group_name     = aws_rds_cluster_parameter_group.database.name
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.database.arn
  iam_database_authentication_enabled = true
  backup_retention_period             = var.backup_retention_days
  preferred_backup_window             = "01:00-02:00"
  preferred_maintenance_window        = "sun:03:00-sun:04:00"
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${local.name}-aurora-final"
  enabled_cloudwatch_logs_exports     = ["postgresql"]

  serverlessv2_scaling_configuration {
    min_capacity = var.aurora_min_capacity
    max_capacity = var.aurora_max_capacity
  }

  # auto_minor_version_upgrade moves the running minor version; do not let the
  # next plan try to downgrade it. Major upgrades are changed here deliberately.
  lifecycle {
    ignore_changes = [engine_version]
  }
}

resource "aws_rds_cluster_instance" "database" {
  count                                 = var.aurora_instance_count
  identifier                            = "${local.name}-aurora-${count.index + 1}"
  cluster_identifier                    = aws_rds_cluster.database.id
  instance_class                        = "db.serverless"
  engine                                = aws_rds_cluster.database.engine
  engine_version                        = aws_rds_cluster.database.engine_version
  db_subnet_group_name                  = aws_db_subnet_group.database.name
  publicly_accessible                   = false
  auto_minor_version_upgrade            = true
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.database.arn
  performance_insights_retention_period = 7
  promotion_tier                        = count.index
}

# Runtime credential (role accounts_ledger_app, DML only: V3 grants). The
# DBA bootstrap in docs/migration creates the role and writes
# {"username", "password"} here; Terraform never sees the value. Same shape as
# terraform-modules aurora-postgresql app_secret_name (<env>/<slug>/<name>,
# 019a842), the migration secret next to it. Neither secret is tagged
# fintechbankx.io/value-in-state: Terraform never writes or reads their values,
# so the PR plan role (terraform-modules f8202f0) only describes them.
resource "aws_secretsmanager_secret" "app_database" {
  # <env>/<service account>/...: the platform ESO role may read only
  # secret:<env>/*, so "<env>-<slug>/db-app" would be refused (same shape as
  # <env>/accounts-ledger-service/db-migration).
  name                    = "${var.environment}/${local.service_slug}/db-app"
  description             = "Application database credential for ${local.service_id}"
  kms_key_id              = aws_kms_key.database.arn
  recovery_window_in_days = 7
}

# Schema owner credential (role accounts_ledger_owner, owns
# sc_acc_accounts_ledger; Flyway only). Created and filled by the DBA
# bootstrap like the runtime one; synced only by the chart's
# pre-install/pre-upgrade migration Job (Helm value migration.remoteSecretName),
# never by the service pods. aurora-postgresql migration_secret_name.
resource "aws_secretsmanager_secret" "migration_database" {
  name                    = "${var.environment}/${local.service_slug}/db-migration"
  description             = "Schema owner (Flyway migration) credential for ${local.service_id}"
  kms_key_id              = aws_kms_key.database.arn
  recovery_window_in_days = 7
}

# --- IRSA: the pods' AWS identity -------------------------------------------

data "aws_iam_policy_document" "irsa_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.eks_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${var.kubernetes_namespace}:${var.kubernetes_service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "workload" {
  name               = "${local.name}-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust.json
}

# The pods never read Secrets Manager or KMS: External Secrets Operator syncs
# the credentials into a Kubernetes Secret. The role keeps SSM parameters and
# (below) MSK produce rights only.
data "aws_iam_policy_document" "workload" {
  statement {
    sid       = "ReadOwnParameters"
    actions   = ["ssm:GetParameter", "ssm:GetParametersByPath"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/fintechbankx/${var.environment}/${local.service_slug}/*"]
  }
}

# --- MSK: IAM client auth, scoped to this service's own topics --------------
# Platform contract: Kafka on AWS is MSK with IAM auth via the IRSA role.
# TODO: switch to the terraform-modules `msk-client-access` module once it is
# published on main.

locals {
  msk_topic_arn_prefix = var.msk_cluster_arn == "" ? "" : replace(var.msk_cluster_arn, ":cluster/", ":topic/")
}

data "aws_iam_policy_document" "msk" {
  count = var.msk_cluster_arn == "" ? 0 : 1

  # WriteDataIdempotently is a cluster action (the producer runs with
  # enable.idempotence=true): it is evaluated against the cluster ARN, so on a
  # topic ARN it would never match.
  statement {
    sid = "ConnectToCluster"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
      "kafka-cluster:WriteDataIdempotently",
    ]
    resources = [var.msk_cluster_arn]
  }

  statement {
    sid = "ProduceOwnTopics"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:WriteData",
    ]
    # The one aggregate topic (ADR-019); no DLQ, dead-letter topics belong to consumers.
    resources = ["${local.msk_topic_arn_prefix}/evt.acc.account.v1"]
  }

  # No consumer-group statement: this service consumes no topic. No
  # transactional-id statement: it uses no Kafka transactions (no
  # transactional.id / transaction-id-prefix). If it ever does, grant
  # Describe/AlterTransactionalId on replace(cluster ARN, ":cluster/",
  # ":transactional-id/"), never on the :group/ prefix.
}

resource "aws_iam_role_policy" "msk" {
  count  = var.msk_cluster_arn == "" ? 0 : 1
  name   = "${local.name}-msk-produce"
  role   = aws_iam_role.workload.id
  policy = data.aws_iam_policy_document.msk[0].json
}

resource "aws_iam_role_policy" "workload" {
  name   = "${local.name}-least-privilege"
  role   = aws_iam_role.workload.id
  policy = data.aws_iam_policy_document.workload.json
}

# --- Alarms -----------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "aurora_capacity" {
  alarm_name          = "${local.name}-aurora-acu-high"
  alarm_description   = "Aurora is near its max ACUs; raise aurora_max_capacity or look for a runaway query."
  namespace           = "AWS/RDS"
  metric_name         = "ACUUtilization"
  dimensions          = { DBClusterIdentifier = aws_rds_cluster.database.cluster_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
  ok_actions          = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}

# Connection budget: every replica at full pool (HPA maxReplicas x DB_POOL_MAX,
# 12 x 10 = 120 by default) plus headroom for the migration Job
# and DBA sessions. The alarm fires only above that budget, i.e. on a
# connection leak or an unexpected client, not under normal peak load. Keep
# the variables in step with the Helm values (autoscaling.maxReplicas,
# config.DB_POOL_MAX), and keep the budget below Aurora's max_connections at
# aurora_min_capacity (Serverless v2 sizes it from capacity).
locals {
  db_connection_budget = var.service_max_replicas * var.db_pool_max + var.db_connection_headroom
}

resource "aws_cloudwatch_metric_alarm" "aurora_connections" {
  alarm_name          = "${local.name}-aurora-connections-high"
  alarm_description   = "Connections above the pool budget (${var.service_max_replicas} replicas x ${var.db_pool_max} + ${var.db_connection_headroom}): leak or unexpected client."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBClusterIdentifier = aws_rds_cluster.database.cluster_identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = local.db_connection_budget
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}
