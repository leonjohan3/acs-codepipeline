package com.myorg;

import java.util.List;
import java.util.Objects;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildSpec;
import software.amazon.awscdk.services.codebuild.Cache;
import software.amazon.awscdk.services.codebuild.CloudWatchLoggingOptions;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.LocalCacheMode;
import software.amazon.awscdk.services.codebuild.LoggingOptions;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.PipelineType;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubTrigger;
import software.amazon.awscdk.services.codepipeline.actions.ManualApprovalAction;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.constructs.Construct;

public final class AcsCodePipelineCdkStack extends Stack {

    private static final String BUILD_IMAGE_ID = "aws/codebuild/amazonlinux2-x86_64-standard:5.0";
    private static final String STS_ASSUME_ROLE = "sts:AssumeRole";
    private static final String ARN_AWS_IAM = "arn:aws:iam::";

    public AcsCodePipelineCdkStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        final var codePipelineBucket = Bucket.Builder.create(this, "TheCodePipelineBucket")
            .removalPolicy(RemovalPolicy.DESTROY)
            .autoDeleteObjects(true)
            .lifecycleRules(List.of(LifecycleRule.builder()
                .expiration(Duration.days(7))
                .build()))
            .build();

        final var codePipeline = Pipeline.Builder.create(this, "TheCodePipeline")
            .crossAccountKeys(false)
            .enableKeyRotation(false)
            .pipelineType(PipelineType.V1)
            .artifactBucket(codePipelineBucket)
            .build();

        final var sourceOutput = new Artifact(codePipelineBucket.getBucketName());
        final var cicdSourceOutput = new Artifact();

        final var sourceAction = GitHubSourceAction.Builder.create()
            .actionName("GitHub_Source")
            .output(sourceOutput)
            .oauthToken(SecretValue.secretsManager("github-token"))
            .owner("leonjohan3")
            .repo("application-configuration-store")
            .branch("main")
            .build();

        final var cicdSourceAction = GitHubSourceAction.Builder.create()
            .actionName("GitHub_CI_CD_Source")
            .trigger(GitHubTrigger.NONE)
            .output(cicdSourceOutput)
            .oauthToken(SecretValue.secretsManager("github-token"))
            .owner("leonjohan3")
            .repo("application-configuration-store-cicd")
            .branch("feature/initial-impl")
            .build();

        codePipeline.addStage(StageOptions.builder()
            .stageName("Checkout_Source")
            .actions(List.of(cicdSourceAction, sourceAction))
            .build());

        final var diffCodeBuildLogGroup = LogGroup.Builder.create(this, "TheDiffCodeBuildLogGroup")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        final var diffCodeBuild = PipelineProject.Builder.create(this, "TheDiffCodeBuild")
            .environment(BuildEnvironment.builder()
                .buildImage(LinuxBuildImage.fromCodeBuildImageId(BUILD_IMAGE_ID))
                .build())
            .timeout(Duration.minutes(15))
            .grantReportGroupPermissions(false)
            .buildSpec(BuildSpec.fromSourceFilename("buildspec-diff.yml"))
            .concurrentBuildLimit(1)
            .queuedTimeout(Duration.minutes(5))
            .description("Display planned AppConfig updates in build log")
            .cache(Cache.local(LocalCacheMode.DOCKER_LAYER, LocalCacheMode.CUSTOM, LocalCacheMode.SOURCE))
            .logging(LoggingOptions.builder()
                .cloudWatch(CloudWatchLoggingOptions.builder()
                    .logGroup(diffCodeBuildLogGroup)
                    .build())
                .build())
            .build();

        final var diffCodeBuildRole = diffCodeBuild.getRole();

        if (Objects.nonNull(diffCodeBuildRole)) {
            diffCodeBuildRole.addToPrincipalPolicy(
                PolicyStatement.Builder.create().actions(List.of(STS_ASSUME_ROLE))
                    .resources(List.of(ARN_AWS_IAM + getAccount() + ":role/cdk-*-lookup-role-*")).build());
            diffCodeBuildRole.addToPrincipalPolicy(
                PolicyStatement.Builder.create().actions(List.of("appconfig:Get*", "appconfig:List*"))
                    .resources(List.of("*")).build());
        }

        final var diffCodeBuildOutput = new Artifact();

        final var diffBuildAction = CodeBuildAction.Builder.create()
            .actionName("Get_Diffs")
            .input(sourceOutput)
            .extraInputs(List.of(cicdSourceOutput))
            .project(diffCodeBuild)
            .outputs(List.of(diffCodeBuildOutput))
            .build();

        codePipeline.addStage(StageOptions.builder()
            .stageName("Get_Diffs")
            .actions(List.of(diffBuildAction))
            .build());

        final var approvalAction = ManualApprovalAction.Builder.create()
            .actionName("Approve_Deploy")
            .notifyEmails(List.of("leonjohan3@gmail.com"))
            .additionalInformation("Please review the planned changes. Click on the 'View details' of the 'Get_Diffs' stage above")
            .build();

        codePipeline.addStage(StageOptions.builder()
            .stageName("Approve_Deploy")
            .actions(List.of(approvalAction))
            .build());

        final var updateCodeBuildLogGroup = LogGroup.Builder.create(this, "TheUpdateCodeBuildLogGroup")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        final var updateCodeBuild = PipelineProject.Builder.create(this, "TheUpdateCodeBuild")
            .environment(BuildEnvironment.builder()
                .buildImage(LinuxBuildImage.fromCodeBuildImageId(BUILD_IMAGE_ID))
                .build())
            .timeout(Duration.minutes(15))
            .grantReportGroupPermissions(false)
            .buildSpec(BuildSpec.fromSourceFilename("buildspec-update.yml"))
            .concurrentBuildLimit(1)
            .queuedTimeout(Duration.minutes(5))
            .description("Deploy planned AppConfig updates")
            .cache(Cache.local(LocalCacheMode.DOCKER_LAYER, LocalCacheMode.CUSTOM, LocalCacheMode.SOURCE))
            .logging(LoggingOptions.builder()
                .cloudWatch(CloudWatchLoggingOptions.builder()
                    .logGroup(updateCodeBuildLogGroup)
                    .build())
                .build())
            .build();

        final var updateCodeBuildRole = updateCodeBuild.getRole();

        if (Objects.nonNull(updateCodeBuildRole)) {
            updateCodeBuildRole.addToPrincipalPolicy(
                PolicyStatement.Builder.create().actions(List.of(STS_ASSUME_ROLE))
                    .resources(List.of(ARN_AWS_IAM + getAccount() + ":role/cdk-*-deploy-role-*")).build());
            updateCodeBuildRole.addToPrincipalPolicy(
                PolicyStatement.Builder.create().actions(List.of(STS_ASSUME_ROLE))
                    .resources(List.of(ARN_AWS_IAM + getAccount() + ":role/cdk-*-file-publishing-role-*")).build());
            updateCodeBuildRole.addToPrincipalPolicy(PolicyStatement.Builder.create().actions(List.of("appconfig:*")).resources(List.of("*")).build());
        }

        final var updateBuildAction = CodeBuildAction.Builder.create()
            .actionName("Update_Diffs")
            .input(diffCodeBuildOutput)
            .extraInputs(List.of(cicdSourceOutput))
            .project(updateCodeBuild)
            .build();

        codePipeline.addStage(StageOptions.builder()
            .stageName("Update_Diffs")
            .actions(List.of(updateBuildAction))
            .build());
    }
}
