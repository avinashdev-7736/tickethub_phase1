package com.tickethub.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.error.GlobalExceptionHandler;
import com.tickethub.common.security.JwtAuthConverter;
import com.tickethub.common.security.SecurityErrorHandlers;
import com.tickethub.common.web.CorrelationIdFilter;
import com.tickethub.common.web.InternalApiKeyFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;

@AutoConfiguration(before = RestClientAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class CommonAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> reg = new FilterRegistrationBean<>(new CorrelationIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilter(
            @Value("${tickethub.internal.key:dev-internal-key}") String key, ObjectMapper mapper) {
        FilterRegistrationBean<InternalApiKeyFilter> reg = new FilterRegistrationBean<>(new InternalApiKeyFilter(key, mapper));
        reg.setOrder(10);
        return reg;
    }

    @Bean
    public JwtAuthConverter jwtAuthConverter() {
        return new JwtAuthConverter();
    }

    @Bean
    public SecurityErrorHandlers securityErrorHandlers(ObjectMapper mapper) {
        return new SecurityErrorHandlers(mapper);
    }

    /** Load-balanced (Eureka service-name) RestClient builder that forwards the correlation id. Clone before use. */
    @Bean
    @LoadBalanced
    @ConditionalOnMissingBean
    public RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        RestClient.Builder builder = configurer.configure(RestClient.builder());
        builder.requestInterceptor((request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
        return builder;
    }
}
