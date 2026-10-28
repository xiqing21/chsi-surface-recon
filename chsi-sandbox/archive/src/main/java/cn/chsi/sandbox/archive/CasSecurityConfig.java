package cn.chsi.sandbox.archive;

import org.apereo.cas.client.session.SingleSignOutFilter;
import org.apereo.cas.client.validation.Cas30ServiceTicketValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.cas.web.CasAuthenticationEntryPoint;
import org.springframework.security.cas.web.CasAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;

/**
 * CAS 客户端配置，对齐线上的 j_spring_cas_security_check 形态。
 *
 * 会话管理显式设置成 migrateSession —— 这是 Spring Security 的默认值，
 * 也是测试点 3 要验证的：URL 重写路径下是否同样生效。
 */
@Configuration
@EnableWebSecurity
public class CasSecurityConfig {

    @Value("${cas.server:https://localhost:8443/cas}")
    private String casServer;

    @Value("${cas.service:http://localhost:8091/archive/j_spring_cas_security_check}")
    private String casService;

    @Bean
    public ServiceProperties serviceProperties() {
        ServiceProperties sp = new ServiceProperties();
        sp.setService(casService);
        sp.setSendRenew(false);
        return sp;
    }

    @Bean
    public CasAuthenticationEntryPoint casEntryPoint(ServiceProperties sp) {
        CasAuthenticationEntryPoint ep = new CasAuthenticationEntryPoint();
        ep.setLoginUrl(casServer + "/login");
        ep.setServiceProperties(sp);
        return ep;
    }

    @Bean
    public CasAuthenticationProvider casProvider(ServiceProperties sp) {
        CasAuthenticationProvider p = new CasAuthenticationProvider();
        p.setServiceProperties(sp);
        p.setTicketValidator(new Cas30ServiceTicketValidator(casServer));
        p.setKey("sandbox-cas-key");
        p.setAuthenticationUserDetailsService(ticket -> new User(
            "sandbox-user", "N/A", AuthorityUtils.createAuthorityList("ROLE_USER")));
        return p;
    }

    @Bean
    public CasAuthenticationFilter casFilter(ServiceProperties sp,
                                             CasAuthenticationProvider provider) {
        CasAuthenticationFilter f = new CasAuthenticationFilter();
        f.setServiceProperties(sp);
        f.setFilterProcessesUrl("/archive/j_spring_cas_security_check");
        // 必须显式给一个 AuthenticationManager，否则 afterPropertiesSet 报错
        f.setAuthenticationManager(a -> provider.authenticate(a));
        return f;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http,
                                     CasAuthenticationEntryPoint entry,
                                     CasAuthenticationProvider provider,
                                     CasAuthenticationFilter casFilter) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/public/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(entry))
            .authenticationProvider(provider)
            .addFilterAfter(casFilter, LogoutFilter.class)
            // 关键：会话固定防护。改这里就能复现不同策略下的行为。
            .sessionManagement(s -> s
                .sessionFixation(fix -> fix.migrateSession())
                .sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
