/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.aop.aspectj.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.DeclareParents;
import org.aspectj.lang.annotation.Pointcut;

import org.springframework.aop.Advisor;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;
import org.springframework.aop.aspectj.DeclareParentsAdvisor;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConvertingComparator;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.comparator.CompoundComparator;
import org.springframework.util.comparator.InstanceComparator;

/**
 * Factory that can create Spring AOP Advisors given AspectJ classes from
 * classes honoring the AspectJ 5 annotation syntax, using reflection to
 * invoke the corresponding advice methods.
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Phillip Webb
 * @since 2.0
 */
public class ReflectiveAspectJAdvisorFactory extends AbstractAspectJAdvisorFactory {

	private static final Comparator<Method> METHOD_COMPARATOR;

	static {
		CompoundComparator<Method> comparator = new CompoundComparator<Method>();
		comparator.addComparator(new ConvertingComparator<Method, Annotation>(
				new InstanceComparator<Annotation>(
						Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class),
				new Converter<Method, Annotation>() {
					public Annotation convert(Method method) {
						AspectJAnnotation<?> annotation = AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(method);
						return annotation == null ? null : annotation.getAnnotation();
					}
				}));
		comparator.addComparator(new ConvertingComparator<Method, String>(
				new Converter<Method, String>() {
					public String convert(Method method) {
						return method.getName();
					}
				}));
		METHOD_COMPARATOR = comparator;
	}


	public List<Advisor> getAdvisors(MetadataAwareAspectInstanceFactory maaif) {
		final Class<?> aspectClass = maaif.getAspectMetadata().getAspectClass();
		final String aspectName = maaif.getAspectMetadata().getAspectName();
		validate(aspectClass);

		// We need to wrap the MetadataAwareAspectInstanceFactory with a decorator
		// so that it will only instantiate once.
		// 主要是实现了 getAspectInstance方法，作用一是保存实例化后的对象在 materialized 中，
		// 二是在getAspectInstance时加锁，如果父类没有提供锁，那么用自身this为锁，如果父类有提供锁，用父类锁
		// todo 没看明白，父类加锁的场景 BeanFactoryAspectInstanceFactory#getAspectCreationMutex
		final MetadataAwareAspectInstanceFactory lazySingletonAspectInstanceFactory =
				new LazySingletonAspectInstanceFactoryDecorator(maaif);

		final List<Advisor> advisors = new LinkedList<Advisor>();
		for (Method method : getAdvisorMethods(aspectClass)) {
			/** getAdvisorMethods 返回类的所有方法公私有等等（并用递归返回父类，父接口的所有方法)，但这些方法会剔除@PointCut标注的方法
			 *  因为PointCut标注的方法只是为了填写切入点表达式，不会用于创建Advisor
			 *  获得方法后，参看 InstanceComparator 的排序规则， 按照@Before @.. 排序，其它不在列表中的，排在最后
			 *  Advisor
			 *  "org.springframework.aop.aspectj.annotation.InstantiationModelAwarePointcutAdvisorImpl@726d8fa0{
			 *  InstantiationModelAwarePointcutAdvisor:
			 *  expression [test()];  ----此处仅仅是标注在＠Before,@After等中的表达式而已，不是@PointCut中的表达式
			 *  advice method [public void com.codeanalysis.aop.AspectJTest.beforeTest()];
			 *  perClauseKind=SINGLETON}"
			 */
			Advisor advisor = getAdvisor(method, lazySingletonAspectInstanceFactory, advisors.size(), aspectName);
			if (advisor != null) {
				advisors.add(advisor);
			}
		}

		// If it's a per target aspect, emit the dummy instantiating aspect.
		// todo isLazilyInstantiated 情况下发生什么要看下， 判断是是对 per target的，在此处提前创建一个Aspect对象
		if (!advisors.isEmpty() && lazySingletonAspectInstanceFactory.getAspectMetadata().isLazilyInstantiated()) {
			Advisor instantiationAdvisor = new SyntheticInstantiationAdvisor(lazySingletonAspectInstanceFactory);
			advisors.add(0, instantiationAdvisor);
		}

		// Find introduction fields. todo 引入增强要看下
		for (Field field : aspectClass.getDeclaredFields()) {
			Advisor advisor = getDeclareParentsAdvisor(field);
			if (advisor != null) {
				advisors.add(advisor);
			}
		}

		return advisors;
	}

	private List<Method> getAdvisorMethods(Class<?> aspectClass) {
		final List<Method> methods = new LinkedList<Method>();
		ReflectionUtils.doWithMethods(aspectClass, new ReflectionUtils.MethodCallback() {
			public void doWith(Method method) throws IllegalArgumentException {
				// Exclude pointcuts 有标注为@pointcut @before @Around 等， @pointcut只是切入点（连接点的过滤器），不是advisor
				if (AnnotationUtils.getAnnotation(method, Pointcut.class) == null) {
					methods.add(method);
				}
			}
		});
		Collections.sort(methods, METHOD_COMPARATOR); // 使用 CompoundComparator， 先按 Annotation比较，再按方法名比较，这是一个多个比较器使用的好方法（层层包装很精彩）
		return methods;
	}

	/**
	 * Build a {@link org.springframework.aop.aspectj.DeclareParentsAdvisor}
	 * for the given introduction field.
	 * <p>Resulting Advisors will need to be evaluated for targets.
	 * @param introductionField the field to introspect
	 * @return {@code null} if not an Advisor
	 */
	private Advisor getDeclareParentsAdvisor(Field introductionField) {
		DeclareParents declareParents = introductionField.getAnnotation(DeclareParents.class);
		if (declareParents == null) {
			// Not an introduction field
			return null;
		}

		if (DeclareParents.class.equals(declareParents.defaultImpl())) {
			// This is what comes back if it wasn't set. This seems bizarre...
			// TODO this restriction possibly should be relaxed
			throw new IllegalStateException("defaultImpl must be set on DeclareParents");
		}

		return new DeclareParentsAdvisor(
				introductionField.getType(), declareParents.value(), declareParents.defaultImpl());
	}


	public Advisor getAdvisor(Method candidateAdviceMethod, MetadataAwareAspectInstanceFactory aif,
			int declarationOrderInAspect, String aspectName) {

		validate(aif.getAspectMetadata().getAspectClass());

		/**
		 * 主要保存了 AspectClass (pointcutDeclarationScope) 以及 expression， (这里没有保存 adviceMethod),
		 * @see AspectJExpressionPointcut#matches(Class)
		 * @see AspectJExpressionPointcut#matches(Method, Class, boolean)
		 * 上述两方法分别调用下面的aspectj原生方法来检测
		 * 调用aspectj原生方法 this.pointcutExpression.couldMatchJoinPointsInType去检测连接点类型是否合适
		 * 调用aspectj原生方法 shadowMatch = this.pointcutExpression.matchesMethodExecution(targetMethod)去检测连接点方法是否合适
		 * "org.springframework.aop.aspectj.AspectJExpressionPointcut@691eb389{AspectJExpressionPointcut: () test()}"
		 * 此处是@Before,@After等标注中的表达式，不是@PointCut中的表达式
		 */
		AspectJExpressionPointcut ajexp =
				getPointcut(candidateAdviceMethod, aif.getAspectMetadata().getAspectClass());
		if (ajexp == null) {
			return null;
		}// 没有把annotation传入，annotation一律是通过candidateAdviceMethod来找到annotation的。
		return new InstantiationModelAwarePointcutAdvisorImpl(
				this, ajexp, aif, candidateAdviceMethod, declarationOrderInAspect, aspectName);
	}

	private AspectJExpressionPointcut getPointcut(Method candidateAdviceMethod, Class<?> candidateAspectClass) {
		/** 此处方法获取方法上的注解实例，并包装成AspectJAnnotation实例
		 * 主要有
		 *  annotation  原始注解实例
		 *  annotationType AspectJAnnotationType类型的枚举
		 *  pointcutExpression 注解实例的value或者pointcut的值
		 *  argumentNames 注解实例的argumentNames的值 (argumentNames 用途见 aspectJ Before上的说明，即@Before标注的Advice方法参数名称在运行时可能获取不到，是为这准备的)
		 */
		/**
		 * When compiling without debug info, or when interpreting pointcuts at runtime,
		 * the names of any arguments used in the advice declaration are not available.
		 * Under these circumstances only, it is necessary to provide the arg names in
		 * the annotation - these MUST duplicate the names used in the annotated method.
		 * Format is a simple comma-separated list.
		 */
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		if (aspectJAnnotation == null) {
			return null;
		}
		/** AspectJExpressionPointcut：
		 * pointcutDeclarationScope  即@Aspect标注的类
		 * expression 即@Before("test()")中的value/pointcut字符值
		 */
		AspectJExpressionPointcut ajexp =
				new AspectJExpressionPointcut(candidateAspectClass, new String[0], new Class[0]);
		ajexp.setExpression(aspectJAnnotation.getPointcutExpression());
		return ajexp;
	}


	public Advice getAdvice(Method candidateAdviceMethod, AspectJExpressionPointcut ajexp,
			MetadataAwareAspectInstanceFactory aif, int declarationOrderInAspect, String aspectName) {

		Class<?> candidateAspectClass = aif.getAspectMetadata().getAspectClass();
		validate(candidateAspectClass);

		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		if (aspectJAnnotation == null) {
			return null;
		}

		// If we get here, we know we have an AspectJ method.
		// Check that it's an AspectJ-annotated class
		if (!isAspect(candidateAspectClass)) {
			throw new AopConfigException("Advice must be declared inside an aspect type: " +
					"Offending method '" + candidateAdviceMethod + "' in class [" +
					candidateAspectClass.getName() + "]");
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Found AspectJ method: " + candidateAdviceMethod);
		}

		AbstractAspectJAdvice springAdvice;

		switch (aspectJAnnotation.getAnnotationType()) {
			case AtBefore:
				// candidateAdviceMethod 增强器方法，ajexp 增强器表达式（切入点表达式）,aif 获取@spect类实例的工厂 LazySingletonAspectInstanceFactoryDecorator
				// 具体切入点类型用类来表达，所以在ajexp仅仅储存了@Aspect类以及表达式字符串
				springAdvice = new AspectJMethodBeforeAdvice(candidateAdviceMethod, ajexp, aif);
				break;
			case AtAfter:
				springAdvice = new AspectJAfterAdvice(candidateAdviceMethod, ajexp, aif);
				break;
			case AtAfterReturning:
				springAdvice = new AspectJAfterReturningAdvice(candidateAdviceMethod, ajexp, aif);
				AfterReturning afterReturningAnnotation = (AfterReturning) aspectJAnnotation.getAnnotation();
				if (StringUtils.hasText(afterReturningAnnotation.returning())) {
					springAdvice.setReturningName(afterReturningAnnotation.returning());
				}
				break;
			case AtAfterThrowing:
				springAdvice = new AspectJAfterThrowingAdvice(candidateAdviceMethod, ajexp, aif);
				AfterThrowing afterThrowingAnnotation = (AfterThrowing) aspectJAnnotation.getAnnotation();
				if (StringUtils.hasText(afterThrowingAnnotation.throwing())) {
					springAdvice.setThrowingName(afterThrowingAnnotation.throwing());
				}
				break;
			case AtAround:
				springAdvice = new AspectJAroundAdvice(candidateAdviceMethod, ajexp, aif);
				break;
			case AtPointcut:
				if (logger.isDebugEnabled()) {
					logger.debug("Processing pointcut '" + candidateAdviceMethod.getName() + "'");
				}
				return null;
			default:
				throw new UnsupportedOperationException(
						"Unsupported advice type on method " + candidateAdviceMethod);
		}

		// Now to configure the advice...
		springAdvice.setAspectName(aspectName);
		springAdvice.setDeclarationOrder(declarationOrderInAspect);
		String[] argNames = this.parameterNameDiscoverer.getParameterNames(candidateAdviceMethod);
		if (argNames != null) {
			springAdvice.setArgumentNamesFromStringArray(argNames);
		}
		springAdvice.calculateArgumentBindings();
		return springAdvice;
	}

	/**
	 * Synthetic advisor that instantiates the aspect.
	 * Triggered by per-clause pointcut on non-singleton aspect.
	 * The advice has no effect.
	 */
	@SuppressWarnings("serial")
	protected static class SyntheticInstantiationAdvisor extends DefaultPointcutAdvisor {

		public SyntheticInstantiationAdvisor(final MetadataAwareAspectInstanceFactory aif) {
			super(aif.getAspectMetadata().getPerClausePointcut(), new MethodBeforeAdvice() {
				public void before(Method method, Object[] args, Object target) {
					// Simply instantiate the aspect
					aif.getAspectInstance();
				}
			});
		}
	}

}
