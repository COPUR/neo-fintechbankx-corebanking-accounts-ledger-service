terraform {
  required_version = ">= 1.6.0"

  required_providers {
    # Bounded to the 5.x line; the lock file pins the exact build.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.40"
    }
  }

  # Remote state is configured per environment at init time:
  #   terraform init -backend-config=environments/<env>.backend.hcl
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.tags
  }
}
