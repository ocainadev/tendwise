package dev.ocaina.tendwise.shared;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Swagger UI metadata, plus an "Authorize" button that sends the X-Api-Key header. */
@Configuration
@OpenAPIDefinition(
    info = @Info(title = "Tendwise business-service", version = "v1"),
    security = @SecurityRequirement(name = "apiKey"))
@SecurityScheme(
    name = "apiKey",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.HEADER,
    paramName = ApiKeyFilter.HEADER)
public class OpenApiConfig {}
