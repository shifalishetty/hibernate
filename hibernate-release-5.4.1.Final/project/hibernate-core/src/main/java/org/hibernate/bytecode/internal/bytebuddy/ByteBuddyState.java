/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.bytecode.internal.bytebuddy;

import static net.bytebuddy.matcher.ElementMatchers.isFinalizer;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isVirtual;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static org.hibernate.bytecode.spi.ClassLoadingStrategyHelper.resolveClassLoadingStrategy;
import static org.hibernate.internal.CoreLogging.messageLogger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.function.Function;

import org.hibernate.HibernateException;
import org.hibernate.bytecode.spi.BasicProxyFactory;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.proxy.ProxyConfiguration;
import org.hibernate.proxy.ProxyFactory;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.TypeCache;
import net.bytebuddy.asm.AsmVisitorWrapper.ForDeclaredMethods;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.DynamicType.Unloaded;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * A utility to hold all ByteBuddy related state, as in the current version of
 * Hibernate the Bytecode Provider state is held in a static field, yet ByteBuddy
 * is able to benefit from some caching and general state reuse.
 */
public final class ByteBuddyState {

	private static final CoreMessageLogger LOG = messageLogger( ByteBuddyState.class );

	private static final boolean DEBUG = false;

	private final ByteBuddy byteBuddy;

	private final ForDeclaredMethods getDeclaredMethodMemberSubstitution;
	private final ForDeclaredMethods getMethodMemberSubstitution;

	private final ProxyDefinitionHelpers proxyDefinitionHelpers;

	/**
	 * It will be easier to maintain the cache and its state when it will no longer be static
	 * in Hibernate ORM 6+.
	 * Opted for WEAK keys to avoid leaking the classloader in case the SessionFactory isn't closed.
	 * Avoiding Soft keys as they are prone to cause issues with unstable performance.
	 */
	private final TypeCache<TypeCache.SimpleKey> proxyCache;
	private final TypeCache<TypeCache.SimpleKey> basicProxyCache;

	ByteBuddyState() {
		this.byteBuddy = new ByteBuddy().with( TypeValidation.DISABLED );

		this.proxyCache = new TypeCache.WithInlineExpunction<TypeCache.SimpleKey>( TypeCache.Sort.WEAK );
		this.basicProxyCache = new TypeCache.WithInlineExpunction<TypeCache.SimpleKey>( TypeCache.Sort.WEAK );

		if ( System.getSecurityManager() != null ) {
			this.getDeclaredMethodMemberSubstitution = getDeclaredMethodMemberSubstitution();
			this.getMethodMemberSubstitution = getMethodMemberSubstitution();
		}
		else {
			this.getDeclaredMethodMemberSubstitution = null;
			this.getMethodMemberSubstitution = null;
		}

		this.proxyDefinitionHelpers = new ProxyDefinitionHelpers();
	}

	/**
	 * Load a proxy as generated by the {@link ProxyFactory}.
	 *
	 * @param referenceClass The main class to proxy - might be an interface.
	 * @param cacheKey The cache key.
	 * @param makeProxyFunction A function building the proxy.
	 * @return The loaded proxy class.
	 */
	public Class<?> loadProxy(Class<?> referenceClass, TypeCache.SimpleKey cacheKey,
			Function<ByteBuddy, DynamicType.Builder<?>> makeProxyFunction) {
		return load( referenceClass, proxyCache, cacheKey, makeProxyFunction );
	}

	/**
	 * Load a proxy as generated by the {@link BasicProxyFactory}.
	 *
	 * @param referenceClass The main class to proxy - might be an interface.
	 * @param cacheKey The cache key.
	 * @param makeProxyFunction A function building the proxy.
	 * @return The loaded proxy class.
	 */
	Class<?> loadBasicProxy(Class<?> referenceClass, TypeCache.SimpleKey cacheKey,
			Function<ByteBuddy, DynamicType.Builder<?>> makeProxyFunction) {
		return load( referenceClass, basicProxyCache, cacheKey, makeProxyFunction );
	}

	/**
	 * Load a class generated by ByteBuddy.
	 *
	 * @param referenceClass The main class to proxy - might be an interface.
	 * @param makeClassFunction A function building the class.
	 * @return The loaded generated class.
	 */
	public Class<?> load(Class<?> referenceClass, Function<ByteBuddy, DynamicType.Builder<?>> makeClassFunction) {
		return make( makeClassFunction.apply( byteBuddy ) )
				.load( referenceClass.getClassLoader(), resolveClassLoadingStrategy( referenceClass ) )
				.getLoaded();
	}

	/**
	 * Rewrite a class, used by the enhancer.
	 * <p>
	 * WARNING: Returns null if rewriteClassFunction returns a null builder. Do not use if you expect the original
	 * content.
	 *
	 * @param typePool the ByteBuddy TypePool
	 * @param className The original class name.
	 * @param rewriteClassFunction The function used to rewrite the class.
	 * @return The rewritten content of the class or null if rewriteClassFunction returns a null builder.
	 */
	public byte[] rewrite(TypePool typePool, String className,
			Function<ByteBuddy, DynamicType.Builder<?>> rewriteClassFunction) {
		DynamicType.Builder<?> builder = rewriteClassFunction.apply( byteBuddy );
		if ( builder == null ) {
			return null;
		}

		return make( typePool, builder ).getBytes();
	}

	/**
	 * Returns the proxy definition helpers to reuse when defining proxies.
	 * <p>
	 * These elements are shared as they are immutable.
	 *
	 * @return The proxy definition helpers.
	 */
	public ProxyDefinitionHelpers getProxyDefinitionHelpers() {
		return proxyDefinitionHelpers;
	}

	/**
	 * Wipes out all known caches used by ByteBuddy. This implies it might trigger the need
	 * to re-create some helpers if used at runtime, especially as this state is shared by
	 * multiple SessionFactory instances, but at least ensures we cleanup anything which is no
	 * longer needed after a SessionFactory close.
	 * The assumption is that closing SessionFactories is a rare event; in this perspective the cost
	 * of re-creating the small helpers should be negligible.
	 */
	void clearState() {
		proxyCache.clear();
		basicProxyCache.clear();
	}

	private Class<?> load(Class<?> referenceClass, TypeCache<TypeCache.SimpleKey> cache,
			TypeCache.SimpleKey cacheKey, Function<ByteBuddy, DynamicType.Builder<?>> makeProxyFunction) {
		return cache.findOrInsert(
				referenceClass.getClassLoader(),
				cacheKey,
				() -> make( makeProxyFunction.apply( byteBuddy ) )
						.load( referenceClass.getClassLoader(), resolveClassLoadingStrategy( referenceClass ) )
						.getLoaded(),
				cache );
	}

	private Unloaded<?> make(DynamicType.Builder<?> builder) {
		return make( null, builder );
	}

	private Unloaded<?> make(TypePool typePool, DynamicType.Builder<?> builder) {
		if ( System.getSecurityManager() != null ) {
			builder = builder.visit( getDeclaredMethodMemberSubstitution );
			builder = builder.visit( getMethodMemberSubstitution );
		}

		Unloaded<?> unloadedClass;
		if ( typePool != null ) {
			unloadedClass = builder.make( typePool );
		}
		else {
			unloadedClass = builder.make();
		}

		if ( DEBUG ) {
			try {
				unloadedClass.saveIn( new File( System.getProperty( "java.io.tmpdir" ) + "/bytebuddy/" ) );
			}
			catch (IOException e) {
				LOG.warn( "Unable to save generated class %1$s", unloadedClass.getTypeDescription().getName(), e );
			}
		}

		if ( System.getSecurityManager() != null ) {
			// we authorize the proxy class to access the method lookup dispatcher
			HibernateMethodLookupDispatcher.registerAuthorizedClass( unloadedClass.getTypeDescription().getName() );
		}

		return unloadedClass;
	}

	private static ForDeclaredMethods getDeclaredMethodMemberSubstitution() {
		// this should only be called if the security manager is enabled, thus the privileged calls
		return MemberSubstitution.relaxed()
				.method( ElementMatchers.is( AccessController.doPrivileged( new GetDeclaredMethodAction( Class.class,
						"getDeclaredMethod", String.class, Class[].class ) ) ) )
				.replaceWith(
						AccessController.doPrivileged( new GetDeclaredMethodAction( HibernateMethodLookupDispatcher.class,
								"getDeclaredMethod", Class.class, String.class, Class[].class ) ) )
				.on( ElementMatchers.isTypeInitializer() );
	}

	private static ForDeclaredMethods getMethodMemberSubstitution() {
		// this should only be called if the security manager is enabled, thus the privileged calls
		return MemberSubstitution.relaxed()
				.method( ElementMatchers.is( AccessController.doPrivileged( new GetDeclaredMethodAction( Class.class,
						"getMethod", String.class, Class[].class ) ) ) )
				.replaceWith(
						AccessController.doPrivileged( new GetDeclaredMethodAction( HibernateMethodLookupDispatcher.class,
								"getMethod", Class.class, String.class, Class[].class ) ) )
				.on( ElementMatchers.isTypeInitializer() );
	}

	private static class GetDeclaredMethodAction implements PrivilegedAction<Method> {
		private final Class<?> clazz;
		private final String methodName;
		private final Class<?>[] parameterTypes;

		private GetDeclaredMethodAction(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
			this.clazz = clazz;
			this.methodName = methodName;
			this.parameterTypes = parameterTypes;
		}

		@Override
		public Method run() {
			try {
				Method method = clazz.getDeclaredMethod( methodName, parameterTypes );

				return method;
			}
			catch (NoSuchMethodException e) {
				throw new HibernateException( "Unable to prepare getDeclaredMethod()/getMethod() substitution", e );
			}
		}
	}

	/**
	 * Shared proxy definition helpers. They are immutable so we can safely share them.
	 */
	public static class ProxyDefinitionHelpers {

		private final ElementMatcher<? super MethodDescription> groovyGetMetaClassFilter;
		private final ElementMatcher<? super MethodDescription> virtualNotFinalizerFilter;
		private final ElementMatcher<? super MethodDescription> hibernateGeneratedMethodFilter;
		private final MethodDelegation delegateToInterceptorDispatcherMethodDelegation;
		private final FieldAccessor.PropertyConfigurable interceptorFieldAccessor;

		private ProxyDefinitionHelpers() {
			this.groovyGetMetaClassFilter = isSynthetic().and( named( "getMetaClass" )
					.and( returns( td -> "groovy.lang.MetaClass".equals( td.getName() ) ) ) );
			this.virtualNotFinalizerFilter = isVirtual().and( not( isFinalizer() ) );
			this.hibernateGeneratedMethodFilter = nameStartsWith( "$$_hibernate_" ).and( isVirtual() );

			PrivilegedAction<MethodDelegation> delegateToInterceptorDispatcherMethodDelegationPrivilegedAction =
					new PrivilegedAction<MethodDelegation>() {

				@Override
				public MethodDelegation run() {
					return MethodDelegation.to( ProxyConfiguration.InterceptorDispatcher.class );
				}
			};

			this.delegateToInterceptorDispatcherMethodDelegation = System.getSecurityManager() != null
					? AccessController.doPrivileged( delegateToInterceptorDispatcherMethodDelegationPrivilegedAction )
					: delegateToInterceptorDispatcherMethodDelegationPrivilegedAction.run();

			PrivilegedAction<FieldAccessor.PropertyConfigurable> interceptorFieldAccessorPrivilegedAction =
					new PrivilegedAction<FieldAccessor.PropertyConfigurable>() {

				@Override
				public FieldAccessor.PropertyConfigurable run() {
					return FieldAccessor.ofField( ProxyConfiguration.INTERCEPTOR_FIELD_NAME )
							.withAssigner( Assigner.DEFAULT, Assigner.Typing.DYNAMIC );
				}
			};

			this.interceptorFieldAccessor = System.getSecurityManager() != null
					? AccessController.doPrivileged( interceptorFieldAccessorPrivilegedAction )
					: interceptorFieldAccessorPrivilegedAction.run();
		}

		public ElementMatcher<? super MethodDescription> getGroovyGetMetaClassFilter() {
			return groovyGetMetaClassFilter;
		}

		public ElementMatcher<? super MethodDescription> getVirtualNotFinalizerFilter() {
			return virtualNotFinalizerFilter;
		}

		public ElementMatcher<? super MethodDescription> getHibernateGeneratedMethodFilter() {
			return hibernateGeneratedMethodFilter;
		}

		public MethodDelegation getDelegateToInterceptorDispatcherMethodDelegation() {
			return delegateToInterceptorDispatcherMethodDelegation;
		}

		public FieldAccessor.PropertyConfigurable getInterceptorFieldAccessor() {
			return interceptorFieldAccessor;
		}
	}
}
