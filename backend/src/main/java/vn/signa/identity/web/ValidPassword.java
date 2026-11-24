package vn.signa.identity.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import vn.signa.identity.internal.PasswordPolicy;

/** Mật khẩu mới theo {@link PasswordPolicy}: 8–72 ký tự, không quá 72 byte UTF-8. */
@Documented
@Constraint(validatedBy = ValidPassword.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface ValidPassword {

  String message() default "mật khẩu không hợp lệ";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<ValidPassword, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      String problem = PasswordPolicy.problem(value);
      if (problem == null) {
        return true;
      }
      context.disableDefaultConstraintViolation();
      context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
      return false;
    }
  }
}
