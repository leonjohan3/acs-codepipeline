# Overview
A solution that allows developers to store the Spring externalized configuration (application.yaml or .properties) in a GitHub repo that automatically gets
sync'ed to AWS AppConfig.

# Background
The twelve-factor app principles recommend strict separation of config from code because config could vary substantially across deploys/environments, 
but code not. Best practice suggests keeping your microservice's configuration seperate from your source code, keeping your configuration in a dedicated Git 
repository, and keeping this repository in sync with AWS AppConfig.

This GitHub repo and the other two repos (see below), work together to provide the ability for Java Spring Boot configurations, e.g. `application.yaml` to be 
stored in a GitHub repo (unrelated to the source code) that is synchronized to AWS AppConfig (centralized configuration storage). 

It provides a central place to manage external properties or configuration for applications across all environments.

The latest configuration for the specific application and environment is later retrieved from AWS AppConfig when the microservice/application is started/re-started.

When deploying a Spring Boot microservice/application on AWS, and using e.g. the AppConfig agent as a source for the config, one cannot make use of the rich 
functionality provided by the Spring Boot [Externalized Configuration](<https://docs.spring.io/spring-boot/reference/features/external-config.html>), but
when using this solution, the Spring Boot Externalized Configuration can be utilised in the normal way. 

Although these 3 repos are Spring Boot focused, they could easily be adapted for other types of applications (Java/Non-Java) to store externalised configuration
in AWS AppConfig.

# Note
This solution uses the concept of a "config group prefix" to keep the configs separate for different teams/departments within the same AWS account/region. 
It must be letters and/or digits and must have a length of 3 characters, e.g. `acs` - short for "application configuration store". When a new team is ready
to start using this solution, they need to select an unused "config group prefix". This value is then configured in the `config-example.yaml` file before
creating the AWS CodePipeline for the team's config. This is done so that teams do not affect each-other and for cost allocation. This might not make
much sense at this time, but it is an important concept to be aware of, so make sure to re-read this later on.

# Also visit the other related GitHub repos
- [application-configuration-store](<https://github.com/leonjohan3/application-configuration-store/blob/main/README.md>)
- [application-configuration-store-cicd](<https://github.com/leonjohan3/application-configuration-store-cicd/blob/main/README.md>)

# How are the 3 GitHub repos related?
![How are the 3 GitHub repos related](https://github.com/leonjohan3/acs-codepipeline/blob/main/images/how-are-the-3-repos-related.png)

# Getting started deploying to your AWS Account (below instructions suitable for AWS CloudShell)
1. Duplicate the 3 repos to your GiHub account (do not fork). 
   See [Duplicating a repository](<https://docs.github.com/en/repositories/creating-and-managing-repositories/duplicating-a-repository?platform=linux>) 
   on how to accomplish that. The new 3 repos will be unrelated/disconnected from the original repos and evolve on their own.
2. Clone the new `acs-codepipeline` repo on your local disk
3. Install the AWS CLI (see resources section below) - might already be installed.
4. Configure your environment making sure it is pointing to the correct AWS account and region (the easiest will be to give the AWS user 
   temporary full AWS admin-like access for this install). The AWS CodePipeline (to deploy the configs to AppConfig) uses roles with least privileges
   created by this install.
5. Run the following commands to ensure the defaults are correct:
   `aws sts get-caller-identity`  (to get the current AWS account number), and
   `aws ec2 describe-availability-zones --output text --query 'AvailabilityZones[0].[RegionName]'`  (to get the current region)
6. Install Java 21: `sudo yum install java-21-amazon-corretto-devel`
7. Install Node.js (version 14.15.0 or later) - might already be installed
8. Install AWS cdk cli: `sudo npm install -g aws-cdk@2`
9. Bootstrap the AWS cdk (if you have not done this before - see resources below), run e.g.: `cdk bootstrap aws://123456789012/us-east-1`. But use the appropriate
   AWS account number and region.
10. Edit the `config-example.yaml` replacing the current values with what is relevant for your environment. The `configuration-group-prefix` can remain as is for 
    now.
11. Create a GiHub personal access token (classic) with the following GitHub scopes: `repo` and `admin:repo_hook` - see resources below.
12. Save this token as a "plaintext" secret in AWS Secret Manager under the name `github-token` 
13. Change directory to the root folder of the `acs-codepipeline` repo.
14. Run the following command: `cdk -c config-file-name=config-example.yaml diff`. This will display all the AWS resources (including the roles) that will be 
    created.
15. Run the following command to deploy the AWS CodePipeline that will use the `application-configuration-store-cicd` repo to sync 
    the `application-configuration-store` repo to AWS AppConfig: `cdk -c config-file-name=config-example.yaml deploy`
16. Accept the subscription invite email sent by AWS for the manual approval stage.
17. Visit the AWS CodePipeline in the AWS Console to ensure that the steps to "Manual Approval" had been successful.
18. Have a look at the log output of the previous stage to view the proposed changes that will be deployed once approval has been done. When happy, 
    do the manual approval.
19. Make sure the last step of the pipeline (Deploy) is successful.
20. Visit the AWS AppConfig in the AWS Console to ensure that the configurations had been created correctly.
21. From here onwards, any config changes made to the `application-configuration-store` (as configured in the "config-example.yaml" as `source-github-repo-name`), 
    will automatically start the AWS CodePipeline on every push (to the branch as configured in the "config-example.yaml" as `source-github-repo-branch`).
22. Optionally create the GitHub repositories (similar to `application-configuration-store`) and then repeat this procedure as necessary to create the 
    AWS CodePipelines for the other teams/departments.

# What does the AWS CodePipeline look like
![What does the completed AWS CodePipeline look like](https://github.com/leonjohan3/acs-codepipeline/blob/main/images/what-does-the-completed-aws-codepipeline-look-like.png)

# How to use the AppConfig configuration when deploying a microservice/application?
The configuration (e.g. application.yaml) is now available in AWS AppConfig, but how does the microservice/application get to use it?
A standalone executable is available to fetch the latest config from AppConfig using the application name and environment (e.g. prod, dev, test).
The executable is implemented in golang and needs to be built, uploaded to the Docker image (if using Docker) and run in the microservice/application
startup script, see: [getLatestConfig executable](<https://github.com/leonjohan3/application-configuration-store-cicd/tree/main/getLatestConfig>)

# How to deal with sensitive config like DB passwords, private keys, etc.
The simplest way to keep these out of your GitHub repo, is to store them in AWS Secrets, and then use Spring Cloud AWS
[Secrets Manager Integration](<https://docs.awspring.io/spring-cloud-aws/docs/3.0.0/reference/html/index.html#spring-cloud-aws-secrets-manager>)

E.g.: when using a "plaintext" secret `application.yaml` with e.g. Spring profile set to `prod`
```yaml
spring:
  config:
    import: aws-secretsmanager:/my/${spring.profiles.active}/first-secret;/my/${spring.profiles.active}/second-secret
```
then:
```yaml
datasource:
  password: ${first-secret}
```
or:
```java
@Value("${first-secret}")
private String mySecret;
```

# What are the benefits of using this solution over [Spring Cloud Config](<https://docs.spring.io/spring-cloud-config/reference/>)
1. The AWS AppConfig service is serverless and managed by AWS.
2. The costs should be less, as there is no Spring Boot application required to serve the config that needs to be hosted on ECS/EKS/EC2.
3. This solution is more robust as there is less moving parts and points of possible failure.

# What are the AWS cost for using this solution?
- With AWS AppConfig, you pay each time you request configuration data from AWS AppConfig via API calls (every time your microservice/application starts 
  or re-starts). At the time of writing this was US$0.0008 each time the config is fetched from AppConfig. E.g. if you have 200 microservices/applications 
  re-starting 3 times a day: 200 x 3 x $0.0008 x 30days = US$14.40 per month.
- There is also a cost associated with the AWS CodeBuild when the CodePipeline needs to sync the GitHub repo with AppConfig: it is based on build duration, and 
  at the time of writing, US$0.005 per minute (with 100 build minutes free per month when on the free tier). E.g. when a pipeline runs 10 times a day and takes 
  on average 10 minutes to perform the deploy, the costs are 10 x 10 x $0.005 x 30days = US$15.00 per month.

# AWS Technologies used
1. Secrets Manager
2. S3
3. AppConfig
4. Cloudwatch
5. cdk (that uses CloudFormation behind the scenes)
6. CodePipeline and CodeBuild
7. SNS
8. IAM roles and policies
9. AWS SDK for Java

# FAQ
1. Why was Java selected to implement the AWS cdk code (and not Python or Node) ?

   Because the solution is predominantly aimed at Java Spring Boot developers, it would be easier for them to maintain the IaC in a language familiar to them.
2. Why was AWS CodePipeline and not GitHub Actions used to implement the CI/CD steps?

   The AWS cdk could be utilized to create the AWS CodePipeline, and CodePipeline integrates much easier with the other AWS services and supports roles using least 
   privilege. 
3. Why is there a manual approval step?

   Configuration changes, when specified incorrectly, could cause downtime. This additional QA step was introduced to ensure we only deploy configuration changes
   we intend to.
4. Why is this solution not available as a working program? Why does the GitHub repos need to be duplicated before it can be used?

   Every organisation/team might use the solution differently, making slight changes as they need. E.g. disabling the manual approval step in the non-prod
   environments (which requires a code change). It is also important for the team to not treat this solution as a black box, but to understand the code and 
   how everything fits together, as this will greatly assist in future troubleshooting.
5. I am done playing around. How do I clean up all resources to ensure there are no AWS charges?

   Locate the stacks created in AWS Cloudformation, disable termination protection, and delete the stacks. To re-create deleted stacks, just follow 
   the "Getting started" instructions above.
6. When running in CloudShell, at times I get error: `Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)`

   This is suspected to be a memory issue with CloudShell - re-run the command until it finishes successfully. This only happens in the CloudShell console, 
   and not in the pipeline build.
7. The $HOME folder on CloudShell is only 1GB in size. How do I prevent running out of disk space?
   
   The first step is to move the "caches" to the `/tmp` folder. Do this by adding the following line to your "$HOME/.gradle/gradle.properties":
   `GRADLE_WRAPPER_DISTS_HOME=/tmp`. Additionally, and optionally, create a symlink from "$HOME/.cache" to "/tmp/.cache" then add the command `mkdir -p /tmp/.cache`
   to the "$HOME/.bash_profile" (as this folder in "/tmp" will not exist on newly provided CloudShell sessions). Only changes to your $HOME folder is persisted 
   and made available in future CloudShell sessions.

# Resources
- [What is AWS AppConfig](<https://docs.aws.amazon.com/appconfig/latest/userguide/what-is-appconfig.html>)
- [AppConfig Quotas and Limitations](<https://docs.aws.amazon.com/appconfig/latest/userguide/appconfig-free-form-configurations-creating.html#appconfig-creating-configuration-and-profile-quotas>)
- [AppConfig Pricing](<https://aws.amazon.com/systems-manager/pricing/#AppConfig>)
- [AWS CodeBuild Pricing](<https://aws.amazon.com/codebuild/pricing/>)
- [The twelve-factor app - Config](<https://12factor.net/config>)
- [Install AWS CLI](<https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html>)
- [GitHub Creating a personal access token](<https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic>)
- [GitHub Duplicating a repository](<https://docs.github.com/en/repositories/creating-and-managing-repositories/duplicating-a-repository?platform=linux>)
- [AWS cdk Bootstrapping](<https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html>)
- [Spring Boot Externalized Configuration](<https://docs.spring.io/spring-boot/reference/features/external-config.html>)
- [Spring Cloud Config](<https://docs.spring.io/spring-cloud-config/reference/>)
- [Spring Cloud AWS](<https://docs.awspring.io/spring-cloud-aws/docs/3.0.0/reference/html/index.html#spring-cloud-aws-secrets-manager>)
- [AWS CodePipeline - GitHub Token](<https://docs.aws.amazon.com/cdk/api/v2/java/software/amazon/awscdk/services/codepipeline/actions/package-summary.html#github-heading>)
