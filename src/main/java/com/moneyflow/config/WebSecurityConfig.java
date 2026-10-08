package com.moneyflow.config;

import com.moneyflow.adapter.auth.ApiKeyAuthenticationEntryPoint;
import com.moneyflow.adapter.auth.ApiKeyAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Every request needs a valid API key, except health and info (container health checks) and the API docs. Public so
 * controller slice tests in other packages can import it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiSecurityProperties.class)
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityProperties properties) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // API docs hold no secrets; calling the API from Swagger UI still needs the key.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // The container's error dispatch runs after the security context of the request is cleared.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                // Not a bean: Boot would also register it as a servlet filter and run it outside the security chain.
                .addFilterBefore(new ApiKeyAuthenticationFilter(properties.apiKey()), AnonymousAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new ApiKeyAuthenticationEntryPoint()))
                // Stateless API: the key is checked on every request, so no session, CSRF token, login form or cache.
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }
}
