package org.apache.fory.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({
  ElementType.METHOD,
  ElementType.CONSTRUCTOR,
  ElementType.TYPE,
})
public @interface NotForAndroid {
  String reason() default "This API is not supported or is unsafe on the Android platform.";
}
