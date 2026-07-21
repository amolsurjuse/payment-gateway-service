package com.electrahub.paymentgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayJsonConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayJsonConfiguration.class);

    @Test
    void registersAnObjectMapperWhenTheApplicationDoesNotProvideOne() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ObjectMapper.class));
    }
}
