/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.resolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fory.exception.InsecureException;

/** A class to record which classes are not allowed for serialization. */
public final class DisallowedList {

  // Embedded disallowed class names list - no external file dependency
  private static final String[] DISALLOWED_CLASSES = {
    "bsh.Interpreter",
    "bsh.XThis",
    "ch.qos.logback.core.db.DriverManagerConnectionSource",
    "ch.qos.logback.core.db.JNDIConnectionSource",
    "clojure.core",
    "clojure.main",
    "com.caucho.config.types.ResourceRef",
    "com.caucho.hessian.test.TestCons",
    "com.caucho.naming.QName",
    "com.ibm.jtc.jax.xml.bind.v2.runtime.unmarshaller.Base64Data",
    "com.ibm.xltxe.rnm1.xtq.bcel.util.ClassLoader",
    "com.mchange.v2.c3p0.impl.PoolBackedDataSourceBase",
    "com.mchange.v2.c3p0.JndiRefForwardingDataSource",
    "com.mchange.v2.c3p0.WrapperConnectionPoolDataSource",
    "com.mysql.cj.jdbc.MysqlConnectionPoolDataSource",
    "com.mysql.cj.jdbc.MysqlDataSource",
    "com.mysql.cj.jdbc.MysqlXADataSource",
    "com.mysql.jdbc.jdbc2.optional.MysqlDataSource",
    "com.mysql.jdbc.util.ServerController",
    "com.rometools.rome.feed.impl.EqualsBean",
    "com.rometools.rome.feed.impl.ToStringBean",
    "com.sun.corba.se.impl.activation.ServerManagerImpl",
    "com.sun.corba.se.impl.activation.ServerTableEntry",
    "com.sun.corba.se.impl.presentation.rmi.InvocationHandlerFactoryImpl.CustomCompositeInvocationHandlerImpl",
    "com.sun.corba.se.spi.orbutil.proxy.CompositeInvocationHandlerImpl",
    "com.sun.corba.se.spi.orbutil.proxy.LinkedInvocationHandler",
    "com.sun.jndi.ldap.LdapAttribute",
    "com.sun.jndi.rmi.registry.BindingEnumeration",
    "com.sun.jndi.toolkit.dir.LazySearchEnumerationImpl",
    "com.sun.org.apache.bcel.internal.util.ClassLoader",
    "com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl",
    "com.sun.org.apache.xpath.internal.objects.XString",
    "com.sun.org.apache.xpath.internal.XPathContext",
    "com.sun.rowset.JdbcRowSetImpl",
    "com.sun.syndication.feed.impl.EqualsBean",
    "com.sun.syndication.feed.impl.ObjectBean",
    "com.sun.syndication.feed.impl.ToStringBean",
    "com.sun.xml.internal.bind.v2.runtime.unmarshaller.Base64Data",
    "com.zaxxer.hikari.HikariConfig",
    "com.zaxxer.hikari.HikariDataSource",
    "groovy.lang.PropertyValue",
    "groovy.util.MapEntry",
    "java.beans.EventHandler",
    "java.beans.Expression",
    "java.lang.invoke.InvokeDynamic",
    "java.lang.invoke.MethodHandles.Lookup",
    "java.lang.MethodHandle",
    "java.lang.Process",
    "java.lang.ProcessBuilder",
    "java.lang.reflect.Constructor",
    "java.lang.reflect.Field",
    "java.lang.reflect.Method",
    "java.lang.Runtime",
    "java.lang.Shutdown",
    "java.lang.System",
    "java.lang.Thread",
    "java.lang.ThreadGroup",
    "java.lang.ThreadLocal",
    "java.lang.UNIXProcess",
    "java.lang.VarHandler",
    "java.net.Socket",
    "java.rmi.registry.Registry",
    "java.rmi.server.ObjID",
    "java.rmi.server.RemoteObjectInvocationHandler",
    "java.rmi.server.UnicastRemoteObject",
    "java.security.SignedObject",
    "java.util.ServiceLoader",
    "javassist.bytecode.annotation.Annotation",
    "javassist.bytecode.annotation.AnnotationImpl",
    "javassist.bytecode.annotation.AnnotationMemberValue",
    "javassist.tools.web.Viewer",
    "javassist.util.proxy.SerializedProxy",
    "javax.activation.MimeTypeParameterList",
    "javax.imageio.ImageIO",
    "javax.imageio.spi.ServiceRegistry",
    "javax.management.BadAttributeValueExpException",
    "javax.management.ImmutableDescriptor",
    "javax.management.MBeanServerInvocationHandler",
    "javax.management.openmbean.CompositeDataInvocationHandler",
    "javax.media.jai.remote.SerializableRenderedImage",
    "javax.naming.InitialContext",
    "javax.naming.ldap.Rdn",
    "javax.naming.spi.ContinuationContext.getEnvironment",
    "javax.naming.spi.ContinuationContext.getTargetContext",
    "javax.naming.spi.ObjectFactory",
    "javax.script.ScriptEngineManager",
    "javax.sound.sampled.AudioFileFormat",
    "javax.sound.sampled.AudioFormat",
    "javax.swing.UIDefaults",
    "javax.xml.transform.Templates",
    "net.bytebuddy.dynamic.loading.ByteArrayClassLoader",
    "oracle.jdbc.connector.OracleManagedConnectionFactory",
    "oracle.jdbc.pool.OracleDataSource",
    "org.apache.activemq.ActiveMQConnectionFactory",
    "org.apache.activemq.ActiveMQXAConnectionFactory",
    "org.apache.aries.transaction.jms.RecoverablePooledConnectionFactory",
    "org.apache.bcel.util.ClassLoader",
    "org.apache.carbondata.core.scan.expression.ExpressionResult",
    "org.apache.commons.beanutils.BeanComparator",
    "org.apache.commons.beanutils.BeanToPropertyValueTransformer",
    "org.apache.commons.codec.binary.Base64",
    "org.apache.commons.collections.functors.ChainedTransformer",
    "org.apache.commons.collections.functors.ConstantTransformer",
    "org.apache.commons.collections.functors.InstantiateTransformer",
    "org.apache.commons.collections.functors.InvokerTransformer",
    "org.apache.commons.collections.Transformer",
    "org.apache.commons.collections4.comparators.TransformingComparator",
    "org.apache.commons.collections4.functors.ChainedTransformer",
    "org.apache.commons.collections4.functors.ConstantTransformer",
    "org.apache.commons.collections4.functors.InstantiateTransformer",
    "org.apache.commons.collections4.functors.InvokerTransformer",
    "org.apache.commons.configuration.JNDIConfiguration",
    "org.apache.commons.configuration2.JNDIConfiguration",
    "org.apache.commons.dbcp.datasources.PerUserPoolDataSource",
    "org.apache.commons.dbcp.datasources.SharedPoolDataSource",
    "org.apache.commons.dbcp2.datasources.PerUserPoolDataSource",
    "org.apache.commons.dbcp2.datasources.SharedPoolDataSource",
    "org.apache.commons.fileupload.disk.DiskFileItem",
    "org.apache.ibatis.executor.loader.AbstractSerialStateHolder",
    "org.apache.ibatis.executor.loader.cglib.CglibProxyFactory",
    "org.apache.ibatis.executor.loader.CglibSerialStateHolder",
    "org.apache.ibatis.executor.loader.javassist.JavassistSerialStateHolder",
    "org.apache.ibatis.executor.loader.JavassistSerialStateHolder",
    "org.apache.ibatis.javassist.bytecode.annotation.Annotation",
    "org.apache.ibatis.javassist.bytecode.annotation.AnnotationImpl",
    "org.apache.ibatis.javassist.bytecode.annotation.AnnotationMemberValue",
    "org.apache.ibatis.javassist.tools.web.Viewer",
    "org.apache.ibatis.javassist.util.proxy.SerializedProxy",
    "org.apache.ignite.cache.jta.jndi.CacheJndiTmLookup",
    "org.apache.log.output.db.DefaultDataSource",
    "org.apache.log4j.receivers.db.DriverManagerConnectionSource",
    "org.apache.myfaces.context.servlet.FacesContextImpl",
    "org.apache.myfaces.context.servlet.FacesContextImplBase",
    "org.apache.myfaces.el.CompositeELResolver",
    "org.apache.myfaces.el.unified.FacesELContext",
    "org.apache.myfaces.view.facelets.el.ValueExpressionMethodExpression",
    "org.apache.openjpa.ee.JNDIManagedRuntime",
    "org.apache.openjpa.ee.RegistryManagedRuntime",
    "org.apache.shiro.jndi.JndiObjectFactory",
    "org.apache.shiro.realm.jndi.JndiRealmFactory",
    "org.apache.tomcat.dbcp.dbcp.BasicDataSource",
    "org.apache.tomcat.dbcp.dbcp.datasources.PerUserPoolDataSource",
    "org.apache.tomcat.dbcp.dbcp.datasources.SharedPoolDataSource",
    "org.apache.tomcat.dbcp.dbcp2.BasicDataSource",
    "org.apache.tomcat.dbcp.dbcp2.datasources.PerUserPoolDataSource",
    "org.apache.velocity.runtime.resource.ContentResource",
    "org.apache.velocity.runtime.resource.loader.DataSourceResourceLoader",
    "org.apache.velocity.runtime.resource.Resource",
    "org.apache.velocity.Template",
    "org.apache.wicket.util.upload.DiskFileItem",
    "org.apache.xalan.xsltc.trax.TemplatesImpl",
    "org.apache.xbean.naming.context.ContextUtil",
    "org.apache.xpath.XPathContext",
    "org.apache.zookeeper.Shell",
    "org.aspectj.apache.bcel.util.ClassLoader",
    "org.bouncycastle.asn1.ASN1Object",
    "org.bouncycastle.asn1.x509.X509Extensions",
    "org.codehaus.groovy.runtime.ConvertedClosure",
    "org.codehaus.groovy.runtime.GStringImpl",
    "org.codehaus.groovy.runtime.MethodClosure",
    "org.datanucleus.store.rdbms.datasource.dbcp.datasources.PerUserPoolDataSource;",
    "org.datanucleus.store.rdbms.datasource.dbcp.datasources.SharedPoolDataSource;",
    "org.eclipse.jetty.util.log.LoggerLog",
    "org.geotools.filter.ConstantExpression",
    "org.h2.value.ValueJavaObject",
    "org.h2.message.Trace",
    "org.h2.message.TraceObject",
    "org.h2.message.TraceSystem",
    "org.h2.message.TraceWriterAdapter",
    "org.h2.jdbcx.JdbcDataSource",
    "org.hibernate.engine.spi.TypedValue",
    "org.hibernate.tuple.component.AbstractComponentTuplizer",
    "org.hibernate.tuple.component.PojoComponentTuplizer",
    "org.hibernate.type.AbstractType",
    "org.hibernate.type.ComponentType",
    "org.hibernate.type.Type",
    "org.jboss.ejb3.proxy.handle.HomeHandleImpl",
    "org.jboss.ejb3.stateful.StatefulHandleImpl",
    "org.jboss.ejb3.stateless.StatelessHandleImpl",
    "org.jboss.interceptor.builder.InterceptionModelBuilder",
    "org.jboss.interceptor.builder.MethodReference",
    "org.jboss.interceptor.proxy.DefaultInvocationContextFactory",
    "org.jboss.interceptor.proxy.InterceptorMethodHandler",
    "org.jboss.interceptor.reader.ClassMetadataInterceptorReference",
    "org.jboss.interceptor.reader.DefaultMethodMetadata",
    "org.jboss.interceptor.reader.ReflectiveClassMetadata",
    "org.jboss.interceptor.reader.SimpleInterceptorMetadata",
    "org.jboss.interceptor.spi.instance.InterceptorInstantiator",
    "org.jboss.interceptor.spi.metadata.InterceptorReference",
    "org.jboss.interceptor.spi.metadata.MethodMetadata",
    "org.jboss.interceptor.spi.model.InterceptionModel",
    "org.jboss.interceptor.spi.model.InterceptionType",
    "org.jboss.proxy.ejb.handle.EntityHandleImpl",
    "org.jboss.proxy.ejb.handle.HomeHandleImpl",
    "org.jboss.proxy.ejb.handle.StatefulHandleImpl",
    "org.jboss.proxy.ejb.handle.StatelessHandleImpl",
    "org.jboss.resteasy.plugins.server.resourcefactory.JndiResourceFactory",
    "org.jboss.weld.interceptor.builder.InterceptionModelBuilder",
    "org.jboss.weld.interceptor.builder.MethodReference",
    "org.jboss.weld.interceptor.proxy.DefaultInvocationContextFactory",
    "org.jboss.weld.interceptor.proxy.InterceptorMethodHandler",
    "org.jboss.weld.interceptor.reader.ClassMetadataInterceptorReference",
    "org.jboss.weld.interceptor.reader.DefaultMethodMetadata",
    "org.jboss.weld.interceptor.reader.ReflectiveClassMetadata",
    "org.jboss.weld.interceptor.reader.SimpleInterceptorMetadata",
    "org.jboss.weld.interceptor.spi.instance.InterceptorInstantiator",
    "org.jboss.weld.interceptor.spi.metadata.InterceptorReference",
    "org.jboss.weld.interceptor.spi.metadata.MethodMetadata",
    "org.jboss.weld.interceptor.spi.model.InterceptionModel",
    "org.jboss.weld.interceptor.spi.model.InterceptionType",
    "org.mockito.internal.creation.cglib.AcrossJVMSerializationFeature",
    "org.mortbay.log.Slf4jLog",
    "org.mozilla.javascript.Context",
    "org.mozilla.javascript.IdScriptableObject",
    "org.mozilla.javascript.MemberBox",
    "org.mozilla.javascript.NativeError",
    "org.mozilla.javascript.NativeJavaMethod",
    "org.mozilla.javascript.NativeJavaObject",
    "org.mozilla.javascript.NativeObject",
    "org.mozilla.javascript.ScriptableObject",
    "org.python.core.PyBytecode",
    "org.python.core.PyFunction",
    "org.python.core.PyObject",
    "org.quartz.utils.JNDIConnectionProvider",
    "org.reflections.Reflections",
    "org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator",
    "org.springframework.aop.framework.AdvisedSupport",
    "org.springframework.aop.framework.JdkDynamicAopProxy",
    "org.springframework.aop.support.DefaultBeanFactoryPointcutAdvisor",
    "org.springframework.aop.target.SingletonTargetSource",
    "org.springframework.beans.BeanWrapperImpl",
    "org.springframework.beans.factory.BeanFactory",
    "org.springframework.beans.factory.config.MethodInvokingFactoryBean",
    "org.springframework.beans.factory.config.PropertyPathFactoryBean",
    "org.springframework.beans.factory.ObjectFactory",
    "org.springframework.beans.factory.support.DefaultListableBeanFactory",
    "org.springframework.core.SerializableTypeWrapper",
    "org.springframework.expression.spel.ast.Indexer",
    "org.springframework.expression.spel.ast.MethodReference",
    "org.springframework.jndi.JndiObjectTargetSource",
    "org.springframework.jndi.support.SimpleJndiBeanFactory",
    "org.springframework.orm.jpa.AbstractEntityManagerFactoryBean",
    "org.springframework.transaction.jta.JtaTransactionManager",
    "org.thymeleaf.standard.expression.Expression",
    "org.thymeleaf.standard.expression.StandardExpressionParser",
    "org.yaml.snakeyaml.tokens.DirectiveToken",
    "pstore.shaded.org.apache.commons.collections.functors.InvokerTransformer",
    "sun.print.PrintServiceLookupProvider",
    "sun.print.UnixPrintService",
    "sun.print.UnixPrintServiceLookup",
    "sun.rmi.server.UnicastRef",
    "sun.rmi.server.UnicastRef2",
    "sun.rmi.transport.LiveRef",
    "sun.rmi.transport.tcp.TCPEndpoint",
    "sun.swing.SwingLazyValue",
    "weblogic.ejb20.internal.LocalHomeHandleImpl",
    "weblogic.jms.common.ObjectMessageImpl",
    "com.atomikos.icatch.jta.RemoteClientUserTransaction"
  };

  private static final Set<String> DEFAULT_DISALLOWED_LIST_SET =
      Collections.unmodifiableSet(Arrays.stream(DISALLOWED_CLASSES).collect(Collectors.toSet()));

  private DisallowedList() {}

  /**
   * Get the disallowed class names as an immutable set.
   *
   * @return Set of disallowed class names
   */
  public static Set<String> getDisallowedClasses() {
    return DEFAULT_DISALLOWED_LIST_SET;
  }

  /**
   * Determine whether the current Class is in the default disallowed list.
   *
   * <p>Note that if Class exists in the disallowed list, {@link InsecureException} will be thrown.
   *
   * @param clsName Class Name that needs to be judged.
   * @throws InsecureException If the class is in the disallowed list.
   */
  public static void checkNotInDisallowedList(String clsName) {
    if (DEFAULT_DISALLOWED_LIST_SET.contains(clsName)) {
      throw new InsecureException(String.format("%s hit disallowed list", clsName));
    }
  }

  public static boolean contains(String className) {
      return DEFAULT_DISALLOWED_LIST_SET.contains(className);
  }
}
