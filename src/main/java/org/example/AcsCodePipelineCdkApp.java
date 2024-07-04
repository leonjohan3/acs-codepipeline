package org.example;

import static jakarta.validation.Validation.buildDefaultValidatorFactory;
import static org.apache.commons.lang3.Validate.notBlank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.validation.ConstraintViolationException;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import lombok.experimental.UtilityClass;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

@UtilityClass
public class AcsCodePipelineCdkApp {

    private static final String GROUP_PREFIX = "app.config.group.prefix";

    public static void main(final String[] args) throws IOException {
        final var app = new App();

        final String configFileName = (String) app.getNode().tryGetContext("config-file-name");
        final var configuration = createConfiguration(configFileName);

        new AcsCodePipelineCdkStack(app, "acs-codepipeline-" + configuration.configurationGroupPrefix(), StackProps.builder()
            .description("create AWS CodePipeline and related resources for application-configuration-store-cicd for configuration group: "
                + configuration.configurationGroupPrefix())
            .tags(Map.of(GROUP_PREFIX, configuration.configurationGroupPrefix(), "app", "application-configuration-store"))
            .terminationProtection(true)
            .build(), configuration);

        app.synth();
    }

    private static AcsConfiguration createConfiguration(final String configFileName) throws IOException {
        notBlank(configFileName, "configFileName must not be null or blank");

        try (var validatorFactory = buildDefaultValidatorFactory()) {

            final var validator = validatorFactory.getValidator();
            final var mapper = new ObjectMapper(new YAMLFactory());
            final var configuration = mapper.readValue(new File(configFileName), AcsConfiguration.class);
            final var validationResults = validator.validate(configuration);

            if (!validationResults.isEmpty()) {
                throw new ConstraintViolationException(validationResults);
            }
            return configuration;
        }
    }
}

