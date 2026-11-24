package vn.signa.identity.web;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;

/**
 * Spring Security không trạng thái: phiên nằm trong Redis, kiểm tra quyền bằng
 * {@code @PreAuthorize("hasAuthority('…')")} trên từng endpoint (docs/07 §1). Không bật CORS vì web
 * đi qua proxy cùng origin (docs/backend/thiet-ke-trien-khai.md §5.1).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

  private static final String API = "/api/v1";

  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      SessionService sessions,
      RolePermissions rolePermissions,
      SecurityErrorWriter errors)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .addFilterBefore(
            new SessionAuthenticationFilter(sessions, rolePermissions, errors),
            AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(a ->
            a.dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .requestMatchers(HttpMethod.POST, API + "/auth/register", API + "/auth/login")
                .permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**")
                .permitAll()
                .requestMatchers(
                    API + "/openapi.json",
                    API + "/openapi.json/**",
                    API + "/swagger-ui.html",
                    API + "/swagger-ui/**")
                .permitAll()
                // Đặt sẵn cho lát 2–4: tra cứu danh mục, bản đồ, nhận diện cho khách.
                .requestMatchers(
                    HttpMethod.GET,
                    API + "/sign-types/**",
                    API + "/sign-categories/**",
                    API + "/signs/**",
                    API + "/recognition/**")
                .permitAll()
                .requestMatchers(HttpMethod.POST, API + "/recognition/detect")
                .permitAll()
                .requestMatchers(HttpMethod.POST, API + "/submissions")
                .hasAuthority("submission:create")
                .requestMatchers(API + "/admin/reports/**")
                .hasAuthority("report:resolve")
                .requestMatchers(HttpMethod.PUT, API + "/signs/*/rating")
                .hasAuthority("sign:rate")
                .requestMatchers(HttpMethod.POST, API + "/signs/*/reports")
                .hasAuthority("report:create")
                .requestMatchers(API + "/me/reports")
                .authenticated()
                .anyRequest()
                .authenticated());
    return http.build();
  }
}
