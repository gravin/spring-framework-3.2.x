/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 * Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final String CONSTRUCTOR_PROPERTIES_CLASS_NAME = "java.beans.ConstructorProperties";

	private static final boolean constructorPropertiesAnnotationAvailable =
			ClassUtils.isPresent(CONSTRUCTOR_PROPERTIES_CLASS_NAME, ConstructorResolver.class.getClassLoader());

	private final AbstractAutowireCapableBeanFactory beanFactory;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(
			final String beanName, final RootBeanDefinition mbd, Constructor<?>[] chosenCtors, final Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);
        // 整个方法就是为了获取 constructorToUse, argsToUse 以实例化bean
		Constructor<?> constructorToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			/**
			 * 先看缓存中有没有，如有，取缓存，此处和下面这一段相对应
			 *
			 * 在非explicitArgs调用时，即从mbd里取参数，处理完毕放入缓存
			 *
			 * 			if (explicitArgs == null) {
			 * 				argsHolderToUse.storeCache(mbd, constructorToUse);
			 *          }
			 *
 			 */

			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		if (constructorToUse == null) {
			// Need to resolve the constructor.
			// 判断bean配置是否有autowire='constructor'
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);

			/***
			 * resolvedValues 用途
			 *
			 * beanDefinition中constructorArgumentValues里面的value值为RuntimeBeanReference,TypedStringValue etc
			 * 此为它们转化后对应的value,如TypedStringValue转化为String,RuntimeBeanReference转化为Bean对象
			 *
			 * resolvedValues ValueHoder中
			 * type,name还是拷贝的beanDefinition中指定的type,name,
			 * value则是转化后的,
			 * source为beanDefinition中constructorArgumentValues里面的原始ValueHolder
			 *
			 * @see #resolveConstructorArguments(String, RootBeanDefinition, BeanWrapper, ConstructorArgumentValues, ConstructorArgumentValues)
			 * @see BeanDefinitionValueResolver#resolveValueIfNecessary(Object, Object)
			 *
			 */
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				// 把原始配置参数值转化为转化后的配置参数值，并计算出参数的最小个数
				// 计算indexedArgumentValues.size() + genericArgumentValues.size() 配置参数总大小再与索引中最大的比较，取更大的索引+1
				// 此处看起来有点问题，感觉这里大于等于才对
				//  		if (index > minNrOfArgs) {
				//				minNrOfArgs = index + 1;
				//			}
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			// Take specified constructors, if any.
			Constructor<?>[] candidates = chosenCtors;
			if (candidates == null) {
				Class<?> beanClass = mbd.getBeanClass();
				try {
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}
			// 对给定的构造函数排序：先按方法修饰符排序：先按是公有构造器排前面，再按参数数量倒序
			AutowireUtils.sortConstructors(candidates);
			// 在下面的遍历中，把现有的constructorToUse的typeDiffWeight放在此参数中，每一个候选者都与其比较选择更小的匹配权重。
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor<?>> ambiguousConstructors = null;
			List<Exception> causes = null;

			/**
			 *  遍历所有构造函数候选者，找出符合条件的构造函数
			 *  Candidate:
			 *  	public examples.ExampleBean(int,java.lang.String)}
			 *  	public examples.ExampleBean(java.lang.String,java.lang.String)}
			 *
			 *  配置文件:
			 *      <bean id="exampleBean" class="examples.ExampleBean">
			 *         <constructor-arg value="7500000x"/>
			 *         <constructor-arg  value="42"/>
			 *     </bean>
			 *
			 *  遍历每一个Candidate
			 *
			 */
			for (int i = 0; i < candidates.length; i++) {
				Constructor<?> candidate = candidates[i];
				Class<?>[] paramTypes = candidate.getParameterTypes();

				if (constructorToUse != null && argsToUse.length > paramTypes.length) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.

					/**
					 *
					 * 	如果已经找到了constructorToUse，当发现参数值数目大于当前候选构造器参数数目时，说明不需要再找了
					 * （由于是按参数数目大小倒序的，其余的候选构造器要么是私有，要么参数数量比这少，故不需再找。如果是相同，则要进行下面的权重比较）
					 *
					 *  	public examples.ExampleBean(int,java.lang.String)}                        1
					 *  	private examples.ExampleBean(java.lang.String,java.lang.String)}		  2
					 *		public examples.ExampleBean(long,java.lang.String)}						  3
					 *		public examples.ExampleBean(double,java.lang.String)}					  4
					 *
					 *  此机制使得：
					 *      如上面构造函数为上面4个，那么最终选择的是构造函数2，因为其rawTypeDiffWeight最小
					 *  但是如果现有构造函数再加一个
					 *     public examples.ExampleBean(java.lang.String)}						      5
					 *  尽管构造函数5匹配不上，但是构造函数2肯定匹配不上了。因为此处的短路机制，使得private类型的2不在遍历范围内。
					 */
					break;
				}
				// 前面已经根据原始配置参数值计算出了能适用的构造器的最小参数值，比这还小，肯定是不适用。
				if (paramTypes.length < minNrOfArgs) {
					continue;
				}

				ArgumentsHolder argsHolder;
				if (resolvedValues != null) {
					try {
						String[] paramNames = null;
						// 如果没有用ConstructorProperties注解，则使有asm到局部变量表里去取变量名，此处如果编译器版本不同会取不到，jdk8就不能使用asm4
						if (constructorPropertiesAnnotationAvailable) {
							paramNames = ConstructorPropertiesChecker.evaluate(candidate, paramTypes.length);
						}
						if (paramNames == null) {
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						// 此处是关键部分，根据候选构造器参数顺序，参数类型，参数名称获取配置文件中的参数值配置
						argsHolder = createArgumentArray(
								beanName, mbd, resolvedValues, bw, paramTypes, paramNames, candidate, autowiring);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (this.beanFactory.logger.isTraceEnabled()) {
							this.beanFactory.logger.trace(
									"Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						/**
						 *
						 * createArgumentArray 可能会抛UnsatisfiedDependencyException 异常，这是由于配置项中的值无法转换为Candidate中某一参数类型引起的。
						 * 抛出UnsatisfiedDependencyException异常说明该Candidate对配置中的值不适用。
						 * 如果是最后一个候选者且无其它合适的候选者，直接抛出该异常
						 * 如果还有其它候选者，则将异常保存，尝试其它候选构造器
						 *
						 */
						if (i == candidates.length - 1 && constructorToUse == null) {
							if (causes != null) {
								for (Exception cause : causes) {
									this.beanFactory.onSuppressedException(cause);
								}
							}
							throw ex;
						}
						else {
							// Swallow and try next constructor.
							if (causes == null) {
								causes = new LinkedList<Exception>();
							}
							causes.add(ex);
							continue;
						}
					}
				}
				else {
					// Explicit arguments given -> arguments length must match exactly.
					if (paramTypes.length != explicitArgs.length) {
						continue;
					}
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				if (typeDiffWeight < minTypeDiffWeight) {
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<Constructor<?>>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			if (constructorToUse == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			if (explicitArgs == null) {
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		try {
			Object beanInstance;

			if (System.getSecurityManager() != null) {
				final Constructor<?> ctorToUse = constructorToUse;
				final Object[] argumentsToUse = argsToUse;
				beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					public Object run() {
						return beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, beanFactory, ctorToUse, argumentsToUse);
					}
				}, beanFactory.getAccessControlContext());
			}
			else {
				beanInstance = this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}

			bw.setWrappedInstance(beanInstance);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes())) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		synchronized (mbd.constructorArgumentLock) {
			mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
		}
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(final Class<?> factoryClass, final RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged(new PrivilegedAction<Method[]>() {
				@Override
				public Method[] run() {
					return (mbd.isNonPublicAccessAllowed() ?
							ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
				}
			});
		}
		else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			final String beanName, final RootBeanDefinition mbd, final Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Object factoryBean;
		Class<?> factoryClass;
		boolean isStatic;

		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (factoryBean == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"factory-bean '" + factoryBeanName + "' (or a BeanPostProcessor involved) returned null");
			}
			factoryClass = factoryBean.getClass();
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}

		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			factoryClass = ClassUtils.getUserClass(factoryClass);

			Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
			List<Method> candidateSet = new ArrayList<Method>();
			for (Method candidate : rawCandidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
					candidateSet.add(candidate);
				}
			}
			Method[] candidates = candidateSet.toArray(new Method[candidateSet.size()]);
			AutowireUtils.sortFactoryMethods(candidates);

			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			List<Exception> causes = null;

			for (int i = 0; i < candidates.length; i++) {
				Method candidate = candidates[i];
				Class<?>[] paramTypes = candidate.getParameterTypes();

				if (paramTypes.length >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					if (resolvedValues != null) {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							argsHolder = createArgumentArray(
									beanName, mbd, resolvedValues, bw, paramTypes, paramNames, candidate, autowiring);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (this.beanFactory.logger.isTraceEnabled()) {
								this.beanFactory.logger.trace("Ignoring factory method [" + candidate +
										"] of bean '" + beanName + "': " + ex);
							}
							if (i == candidates.length - 1 && argsHolderToUse == null) {
								if (causes != null) {
									for (Exception cause : causes) {
										this.beanFactory.onSuppressedException(cause);
									}
								}
								throw ex;
							}
							else {
								// Swallow and try next overloaded factory method.
								if (causes == null) {
									causes = new LinkedList<Exception>();
								}
								causes.add(ex);
								continue;
							}
						}
					}

					else {
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}

					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterTypes().length &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<Method>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null) {
				List<String> argTypes = new ArrayList<String>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else {
					Set<ValueHolder> valueHolders = new LinkedHashSet<ValueHolder>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class.equals(factoryMethodToUse.getReturnType())) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
						"': needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		try {
			Object beanInstance;

			if (System.getSecurityManager() != null) {
				final Object fb = factoryBean;
				final Method factoryMethod = factoryMethodToUse;
				final Object[] args = argsToUse;
				beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					public Object run() {
						return beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, beanFactory, fb, factoryMethod, args);
					}
				}, beanFactory.getAccessControlContext());
			}
			else {
				beanInstance = beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, beanFactory, factoryBean, factoryMethodToUse, argsToUse);
			}

			if (beanInstance == null) {
				return null;
			}
			bw.setWrappedInstance(beanInstance);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * This method is also used for handling invocations of static factory methods.
	 */
	private int resolveConstructorArguments(
			String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		int minNrOfArgs = cargs.getArgumentCount();

		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, String[] paramNames, Object methodOrCtor,
			boolean autowiring) throws UnsatisfiedDependencyException {

		String methodType = (methodOrCtor instanceof Constructor ? "constructor" : "factory method");
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length); // 参数值数组大小肯定是和参数类型的数量大小是一样的。
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders =
				new HashSet<ConstructorArgumentValues.ValueHolder>(paramTypes.length);
		Set<String> autowiredBeanNames = new LinkedHashSet<String>(4);

		/**
		 *
		 * 配置文件:
		 * 	     <bean id="exampleBean" class="examples.ExampleBean">
		 * 	        <constructor-arg value="7500000年"/>
		 * 	        <constructor-arg  value="42"/>
		 * 	    </bean>
		 * 遍历Candidate的参数类型数组，对每一个参数类型，找到配置值
		 *
		 */
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			Class<?> paramType = paramTypes[paramIndex];
			String paramName = (paramNames != null ? paramNames[paramIndex] : null);
			// Try to find matching constructor argument value, either indexed or generic.
			/**
			 *
			 *  先根据参数顺序,看配置中是否有相同index的配置项，如有，看是否还有name,type的attribute，如果有，要比较配置是否和Candidate中的name,type一致
			 *
			 *  如配置中没有参数顺序对应的index配置项，则遍历配置中的一般配置项
			 *  如果一般配置项已经给其它参数值采用，则忽略该配置项
			 *  如果一般配置项有配置name,则参数name不能和配置项的name不一致
			 *  如果一般配置项有配置type,则参数type不能和配置项的type不一致
			 *  如果一般配置项 name,type都没配置，说明只配置了value。如果解析后的value类型为参数类型的子类，或者与参数类型是包装类与基础类的关系，则也会被选中。
			 *
			 */
			ConstructorArgumentValues.ValueHolder valueHolder =
					resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
			// If we couldn't find a direct match and are not supposed to autowire,
			// let's try the next generic, untyped argument value as fallback:
			// it could match after type conversion (for example, String -> int).
			if (valueHolder == null && !autowiring) {
				/**
				 *  可见，如果有多个只配置了value的配置项，那么采用值的时候是按照配置项的顺序来采用的。
				 *  	   <bean id="exampleBean" class="examples.ExampleBean">
				 * 		       <constructor-arg value="7500000年"/>
				 * 		       <constructor-arg  value="42"/>
				 * 		   </bean>
				 *  如上，如果构造器为 public examples.ExampleBean(int,java.lang.String)}， 那么就是按照配置项顺序来填充参数数组的。
				 *  不过填充的参数值在下面还会调用convertIfNecessary方法来转换，如上面7500000年无法转换为int,则会抛UnsatisfiedDependencyException异常
				 *  对于异常的处理
				 *  @see #autowireConstructor(String, RootBeanDefinition, Constructor[], Object[])
				 */
				valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
			}
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				if (valueHolder.isConverted()) {
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					/**
					 *  sourceValue 为beanDefinition中constructorArgumentValues里面的原始ValueHolder的value,即RuntimeBeanReference,TypedStringValue etc
					 *  originalValue, resolvedValue 为根据beanDefinition中配置的type，value 转化后的对象
					 *  convertedValue 为originalValue 根据paramType转换后的对象
					 *
					 *  因此
					 *
					 *  args.arguments 为可用于方法，构造函数调用的参数值
					 *  args.rawArguments 根据beanDefintion中的设置转化后的原始值
					 *  args.preparedArguments 为beanDefintion加载后的RuntimeBeanReference,TypedStringValue etc值
					 *
 					 */

					ConstructorArgumentValues.ValueHolder sourceHolder =
							(ConstructorArgumentValues.ValueHolder) valueHolder.getSource();
					Object sourceValue = sourceHolder.getValue();
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType,
								MethodParameter.forMethodOrConstructor(methodOrCtor, paramIndex));
						// TODO re-enable once race condition has been found (SPR-7423)
						/*
						if (originalValue == sourceValue || sourceValue instanceof TypedStringValue) {
							// Either a converted value or still the original one: store converted value.
							sourceHolder.setConvertedValue(convertedValue);
							args.preparedArguments[paramIndex] = convertedValue;
						}
						else {
						*/
							args.resolveNecessary = true;
							args.preparedArguments[paramIndex] = sourceValue;
						// }
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, paramIndex, paramType,
								"Could not convert " + methodType + " argument value of type [" +
								ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
								"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			/**
			 *
			 * 此处判断 valueHolder == null，如果不是autoWire即抛异常，非autowire则需要每个参数类型都要有对应的配置项
			 * 如未配置抛异常
			 *
			 * 如果是autowire,则按照参数类型去找对应的Bean,找不到抛 NoSuchBeanDefinitionException 异常
			 *
			 */
			else {
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, paramIndex, paramType,
							"Ambiguous " + methodType + " argument types - " +
							"did you specify the correct bean references as " + methodType + " arguments?");
				}
				try {
					MethodParameter param = MethodParameter.forMethodOrConstructor(methodOrCtor, paramIndex);
					Object autowiredArgument = resolveAutowiredArgument(param, beanName, autowiredBeanNames, converter);
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					args.preparedArguments[paramIndex] = new AutowiredArgumentMarker();
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, paramIndex, paramType, ex);
				}
			}
		}

		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (this.beanFactory.logger.isDebugEnabled()) {
				this.beanFactory.logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + methodType + " to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(
			String beanName, RootBeanDefinition mbd, BeanWrapper bw, Member methodOrCtor, Object[] argsToResolve) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = (methodOrCtor instanceof Method ?
				((Method) methodOrCtor).getParameterTypes() : ((Constructor<?>) methodOrCtor).getParameterTypes());

		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			MethodParameter methodParam = MethodParameter.forMethodOrConstructor(methodOrCtor, argIndex);
			GenericTypeResolver.resolveParameterType(methodParam, methodOrCtor.getDeclaringClass());
			if (argValue instanceof AutowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter);
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				String methodType = (methodOrCtor instanceof Constructor ? "constructor" : "factory method");
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, argIndex, paramType,
						"Could not convert " + methodType + " argument value of type [" +
						ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 */
	protected Object resolveAutowiredArgument(
			MethodParameter param, String beanName, Set<String> autowiredBeanNames, TypeConverter typeConverter) {

		return this.beanFactory.resolveDependency(
				new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object rawArguments[];

		public final Object arguments[];

		public final Object preparedArguments[];

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			/**
			 * 比如配置文件中是
			 *  Candidate:
			 *  	public examples.ExampleBean(int,java.lang.String)}
			 *  	public examples.ExampleBean(java.lang.String,java.lang.String)}
			 *		public examples.ExampleBean(long,java.lang.String)}
			 *		public examples.ExampleBean(double,java.lang.String)}
			 *  配置文件:
			 *      <bean id="exampleBean" class="examples.ExampleBean">
			 *         <constructor-arg value="7500000x"/>
			 *         <constructor-arg  value="42"/>
			 *     </bean>
			 *
			 *     此处比较先跟转换后的比较，
			 *     对这四个candidate 对应的 typeDiffWeight都是 0
			 *     但是第二个candidate 的rawTypeDiffWeight是 -1024 所以第二个优先
			 *
			 *     如果没有第二个candidate,则是完全按照排序时结果来（按照是否公有，参数个数倒序，此处相同，排序后仍会有个顺序），类定义中排在最后一个会先使用
			 *
			 */
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return (rawTypeDiffWeight < typeDiffWeight ? rawTypeDiffWeight : typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			/**
			 * 非宽松模式的权重计算，
			 * 也就意味着这个函数在多个构造函数参数为父子类或实现类的关系时，会全部返回（Integer.MAX_VALUE - 1024），
			 * 这样返回统一的数字相等，spring就会认为存在有歧义的函数，不能确定使用哪一个。
			 */
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Object constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Marker for autowired arguments in a cached argument array.
 	 */
	private static class AutowiredArgumentMarker {
	}


	/**
	 * Inner class to avoid a Java 6 dependency.
	 */
	private static class ConstructorPropertiesChecker {

		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}
}
