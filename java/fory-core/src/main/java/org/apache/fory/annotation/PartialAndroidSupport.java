package org.apache.fory.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个方法在 Android 平台上只是部分或条件性支持。
 *
 * <p>这意味着该方法可能在某些 Android API 级别、特定设备或特定执行分支下才能正常工作， 调用者需要仔细阅读说明或源码来确保其使用场景的兼容性。
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface PartialAndroidSupport {

  /**
   * 在哪个最低 Android API 级别以上才被支持。 默认为 -1，表示未指定或与此无关。
   *
   * @return 最低支持的 API level。
   */
  int minApiLevel() default -1;

  /**
   * 描述不支持或部分支持的具体原因、条件或分支情况。 例如："仅在硬件加速开启时有效" 或 "在软件渲染路径下会抛出异常"。
   *
   * @return 具体原因的描述文字。
   */
  String reason() default "该方法在 Android 上的所有分支和条件下不保证完全支持，请查阅文档或源码。";

  /** 指向更详细文档或 issue 的链接。 * @return 文档的 URL。 */
  String docUrl() default "";
}
