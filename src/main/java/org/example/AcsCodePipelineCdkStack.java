package org.example;

import static java.nio.charset.Charset.defaultCharset;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import lombok.SneakyThrows;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildSpec;
import software.amazon.awscdk.services.codebuild.CloudWatchLoggingOptions;
import software.amazon.awscdk.services.codebuild.ComputeType;
import software.amazon.awscdk.services.codebuild.LinuxArmLambdaBuildImage;
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
import software.amazon.awscdk.services.logs.ILogGroup;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.constructs.Construct;

public final class AcsCodePipelineCdkStack extends Stack {

    private static final String STS_ASSUME_ROLE = "sts:AssumeRole";
    private static final String ARN_AWS_IAM = "arn:aws:iam::";
    private static final String TEMPLATES_FOLDER = "src/main/resources/templates/";

    public AcsCodePipelineCdkStack(final Construct scope, final String identity, final StackProps props, final AcsConfiguration acsConfiguration) {
        super(scope, identity, props);

        final var codePipelineBucket = Bucket.Builder.create(this, "TheCodePipelineBucket")
            .removalPolicy(RemovalPolicy.DESTROY)
            .autoDeleteObjects(true)
            .lifecycleRules(List.of(LifecycleRule.builder()
                .expiration(Duration.days(14))
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
            .owner(acsConfiguration.githubRepoOwner())
            .repo(acsConfiguration.sourceGithubRepoName())
            .branch(acsConfiguration.sourceGithubRepoBranch())
            .build();

        final var cicdSourceAction = GitHubSourceAction.Builder.create()
            .actionName("GitHub_CI_CD_Source")
            .trigger(GitHubTrigger.NONE)
            .output(cicdSourceOutput)
            .oauthToken(SecretValue.secretsManager("github-token"))
            .owner(acsConfiguration.githubRepoOwner())
            .repo(acsConfiguration.cicdGithubRepoName())
            .branch(acsConfiguration.cicdGithubRepoBranch())
            .build();

        codePipeline.addStage(StageOptions.builder()
            .stageName("Checkout_Source")
            .actions(List.of(cicdSourceAction, sourceAction))
            .build());

        populateBuildspecFilesWithGroupPrefix(acsConfiguration.configurationGroupPrefix());

        final var diffCodeBuildLogGroup = createCodeBuildLogGroup("TheDiffCodeBuildLogGroup");

        final var diffCodeBuild = createCodeBuild("TheDiffCodeBuild",
            "buildspec-diff.yml",
            "Display planned AppConfig updates in build log",
            diffCodeBuildLogGroup);

        final var diffCodeBuildRole = diffCodeBuild.getRole();

        if (Objects.nonNull(diffCodeBuildRole)) {
            diffCodeBuildRole.addToPrincipalPolicy(
                PolicyStatement.Builder.create().actions(List.of(STS_ASSUME_ROLE))
                    .resources(List.of(ARN_AWS_IAM + getAccount() + ":role/cdk-*-lookup-role-*")).build());
            diffCodeBuildRole.addToPrincipalPolicy(
                PolicyStatement.Builder.create().actions(List.of(STS_ASSUME_ROLE))
                    .resources(List.of(ARN_AWS_IAM + getAccount() + ":role/cdk-*-deploy-role-*")).build());
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
            .notifyEmails(List.of(acsConfiguration.approvalNotifyEmail()))
            .additionalInformation("Please review the planned changes. Click on the 'View details' of the 'Get_Diffs' stage above")
            .build();

        codePipeline.addStage(StageOptions.builder()
            .stageName("Approve_Deploy")
            .actions(List.of(approvalAction))
            .build());

        final var updateCodeBuildLogGroup = createCodeBuildLogGroup("TheUpdateCodeBuildLogGroup");

        final var updateCodeBuild = createCodeBuild("TheUpdateCodeBuild",
            "buildspec-update.yml",
            "Deploy planned AppConfig updates",
            updateCodeBuildLogGroup);

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

    @SneakyThrows
    private void populateBuildspecFilesWithGroupPrefix(final String configurationGroupPrefix) {

        final var cfg = new Configuration(Configuration.VERSION_2_3_33);
        cfg.setDirectoryForTemplateLoading(new File(TEMPLATES_FOLDER));
        final var root = new HashMap<String, Object>();
        root.put("configGroupPrefix", configurationGroupPrefix);

        try (var templateFiles = Files.walk(Path.of(TEMPLATES_FOLDER), 1)) {
            templateFiles.filter(path -> path.toFile().isFile()).forEach(path -> {
                try {
                    final var template = cfg.getTemplate(path.getFileName().toString());

                    try (var out = new OutputStreamWriter(Files.newOutputStream(Path.of("build", path.getFileName().toString())), defaultCharset())) {
                        template.process(root, out);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(path.toString(), e);
                } catch (TemplateException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private LogGroup createCodeBuildLogGroup(final String identity) {
        return LogGroup.Builder.create(this, identity)
            .retention(RetentionDays.TWO_WEEKS)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();
    }

    private PipelineProject createCodeBuild(final String identity, final String buildSpecFilename, final String description, final ILogGroup logGroup) {
        return PipelineProject.Builder.create(this, identity)
            .environment(BuildEnvironment.builder()
                .buildImage(LinuxArmLambdaBuildImage.AMAZON_LINUX_2023_CORRETTO_21)
                .computeType(ComputeType.LAMBDA_4GB)
                .build())
            .grantReportGroupPermissions(false)
            .buildSpec(BuildSpec.fromAsset("build/" + buildSpecFilename))
            .concurrentBuildLimit(1)
            .description(description)
            .logging(LoggingOptions.builder()
                .cloudWatch(CloudWatchLoggingOptions.builder()
                    .logGroup(logGroup)
                    .build())
                .build())
            .build();
    }
}
