package org.example;

import static org.example.ServiceConstants.CONFIG_GRP_MESSAGE;
import static org.example.ServiceConstants.CONFIG_GRP_PATTERN;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AcsConfiguration(@Pattern(regexp = CONFIG_GRP_PATTERN, message = CONFIG_GRP_MESSAGE)
                               @JsonProperty("configuration-group-prefix") String configurationGroupPrefix,
                               @NotBlank @JsonProperty("github-repo-owner") String githubRepoOwner,
                               @NotBlank @JsonProperty("source-github-repo-name") String sourceGithubRepoName,
                               @NotBlank @JsonProperty("source-github-repo-branch") String sourceGithubRepoBranch,
                               @NotBlank @JsonProperty("cicd-github-repo-name") String cicdGithubRepoName,
                               @NotBlank @JsonProperty("cicd-github-repo-branch") String cicdGithubRepoBranch,
                               @NotBlank @JsonProperty("manual-approval-notify-email") String approvalNotifyEmail) {

}
