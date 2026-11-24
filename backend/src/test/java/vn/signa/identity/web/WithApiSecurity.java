package vn.signa.identity.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;

/** Nạp chuỗi Security thật trong lát MVC, thay các phụ thuộc tra phiên và quyền bằng mock. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({SecurityConfig.class, SecurityErrorWriter.class, AccessDeniedAdvice.class})
@MockitoBean(types = {SessionService.class, RolePermissions.class})
public @interface WithApiSecurity {}
