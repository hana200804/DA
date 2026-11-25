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
 * {@code @PreAuthorize("hasAuthority('…')")} trên từng endpoint (docs/07 §1), cùng quy tắc theo
 * tiền tố {@code /api/v1/admin/**} để thiếu quyền bị chặn trước khi kiểm dữ liệu. Không bật CORS vì
 * web đi qua proxy cùng origin (docs/backend/thiet-ke-trien-khai.md §5.1).
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
                .requestMatchers(HttpMethod.POST, API + "/auth/password/**")
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
                // Lát 6 · phê duyệt và sửa biển (docs/07 §8)
                .requestMatchers(API + "/admin/review-queue/**")
                .hasAuthority("review:decide")
                .requestMatchers(HttpMethod.GET, API + "/admin/signs/*")
                .hasAuthority("review:decide")
                .requestMatchers(
                    HttpMethod.POST,
                    API + "/admin/signs/*/approve",
                    API + "/admin/signs/*/reject",
                    API + "/admin/signs/*/keep")
                .hasAuthority("review:decide")
                .requestMatchers(
                    HttpMethod.POST, API + "/admin/signs", API + "/admin/signs/*/remove")
                .hasAuthority("sign:edit")
                .requestMatchers(HttpMethod.PATCH, API + "/admin/signs/*")
                .hasAuthority("sign:edit")
                .requestMatchers(API + "/admin/reports/**")
                .hasAuthority("report:resolve")
                .requestMatchers(HttpMethod.PUT, API + "/signs/*/rating")
                .hasAuthority("sign:rate")
                .requestMatchers(HttpMethod.POST, API + "/signs/*/reports")
                .hasAuthority("report:create")
                .requestMatchers(API + "/me/reports")
                .authenticated()
                .requestMatchers(API + "/places/**")
                .permitAll()
                // Lát 8 · quyền theo tiền tố /admin, chặn trước khi Spring đọc và kiểm dữ liệu, nên
                // người thiếu quyền nhận 403 chứ không phải 400; @PreAuthorize giữ làm lớp thứ hai.
                // Bảng tiền tố → quyền: docs/backend/ke-hoach/lat-1-tai-khoan.md.
                .requestMatchers(API + "/admin/users/**")
                .hasAuthority("user:manage")
                .requestMatchers(API + "/admin/audit-logs/**")
                .hasAuthority("audit:read")
                // Lát 2 · quản trị danh mục: chỉ cần catalog:manage, không cần review:decide.
                .requestMatchers(API + "/admin/sign-types/**", API + "/admin/sign-categories/**")
                .hasAuthority("catalog:manage")
                .requestMatchers(API + "/admin/stats/**")
                .hasAuthority("stats:read")
                .requestMatchers(API + "/admin/recognition/**")
                .hasAuthority("recognition:compare")
                // /admin còn lại cần review:decide. Đặt sau mọi quy tắc /admin cụ thể vì
                // Spring dùng quy tắc khớp đầu tiên. MODERATOR và ADMIN đều có review:decide.
                .requestMatchers(API + "/admin/**")
                .hasAuthority("review:decide")
                .anyRequest()
                .authenticated());
    return http.build();
  }
}
