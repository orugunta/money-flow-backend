package com.moneyflow.config;

import com.moneyflow.adapter.auth.ApiKeyAuthenticationFilter;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document served at /v3/api-docs, with Swagger UI at /swagger-ui.html. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "Money Flow API",
                version = "v1",
                description = "Calculates monthly income, spending and balance from an account's bank transactions "
                        + "and sends one summary per month to the Balance API."),
        security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME))
@SecurityScheme(
        name = OpenApiConfig.API_KEY_SCHEME,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = ApiKeyAuthenticationFilter.API_KEY_HEADER)
class OpenApiConfig {

    static final String API_KEY_SCHEME = "apiKey";
}
