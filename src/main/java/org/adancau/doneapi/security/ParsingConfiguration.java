package org.adancau.doneapi.security;

import org.springframework.context.annotation.*;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import tools.jackson.core.*;

@Configuration
public class ParsingConfiguration {
  @Bean
  JsonFactoryBuilderCustomizer boundedJson() {
    return builder -> builder
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
            .maxStringLength(32768).maxNumberLength(100).maxNameLength(1024).build());
  }
  @Bean
  JsonMapperBuilderCustomizer singleJsonDocument() {
    return builder -> builder.enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> boundedUploads() {
    return factory -> factory.addConnectorCustomizers(connector -> {
      connector.setProperty("disableUploadTimeout","false");
      connector.setProperty("connectionUploadTimeout","10000");
      connector.setProperty("maxSwallowSize","65536");
      connector.setProperty("maxParameterCount","50");
    });
  }
}
