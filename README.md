# Overview
Project to create AWS CodePipeline and related resources for application-configuration-store-cicd

# Useful commands
- `cdk ls`          list all stacks in the app
- `cdk synth`       emits the synthesized CloudFormation template
- `cdk deploy`      deploy this stack to your default AWS account/region
- `cdk diff`        compare deployed stack with current state
- `cdk docs`        open CDK documentation

# Resources
- [](<https://docs.aws.amazon.com/codebuild/latest/userguide/use-codebuild-agent.html>)
- [](<>)
- [](<>)

# Todo
- do all TODOs
- support parms: like git repo and config group prefix
- https://docs.aws.amazon.com/cdk/v2/guide/best-practices.html
- expire objects in S3 bucket after 7 days
- CODEBUILD_SRC_DIR
- CODEBUILD_SRC_DIR_Artifact_Checkout_Source_GitHub_CI_CD_Source
- aws sts get-caller-identity (to get the current AWS account number)
- aws ec2 describe-availability-zones --output text --query 'AvailabilityZones[0].[RegionName]' (to get the current region)

            .buildSpec(BuildSpec.fromObject(Map.of(
                "version", "0.2",
                "phases", Map.of(
                    "build", Map.of(
                        "commands", List.of("env"))))))