/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Scanner to search for relevant annotations in the annotation hierarchy of an
 * {@link AnnotatedElement}.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.2
 * @see AnnotationsProcessor
 */
abstract class AnnotationsScanner {

	private static final Annotation[] NO_ANNOTATIONS = {};

	private static final Method[] NO_METHODS = {};


	/**
	 * 可以获取到被注解的元素，和其对应的注解。
	 * getAnnotations() 是可以获取到父类的有 @Inherited 修饰的注解的，
	 * 而 getDeclaredAnnotations() 则只能获取当前类的注解。
	 */
	private static final Map<AnnotatedElement, Annotation[]> declaredAnnotationCache =
			new ConcurrentReferenceHashMap<>(256);

	private static final Map<Class<?>, Method[]> baseTypeMethodsCache =
			new ConcurrentReferenceHashMap<>(256);


	private AnnotationsScanner() {
	}


	/**
	 * Scan the hierarchy of the specified element for relevant annotations and
	 * call the processor as required.
	 *
	 * AnnotationScanner中就这一个允许外部调用的静态方法
	 *
	 * @param context an optional context object that will be passed back to the
	 * processor，一个可选上下文对象，在processor中使用，可以是一个注解字符串名，可以是一个Class可以是一个AnnotationTypeMappings等，
	 * 要看具体的逻辑咋处理。
	 * @param source the source element to scan，要被扫描的元素，要扫描谁
	 * @param searchStrategy the search strategy to use，扫描策略，怎样扫描
	 * @param processor the processor that receives the annotations，具体的扫描逻辑
	 * @return the result of {@link AnnotationsProcessor#finish(Object)}
	 */
	@Nullable
	static <C, R> R scan(C context, AnnotatedElement source, SearchStrategy searchStrategy,
			AnnotationsProcessor<C, R> processor) {

		R result = process(context, source, searchStrategy, processor);
		return processor.finish(result);
	}

	@Nullable
	private static <C, R> R process(C context, AnnotatedElement source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		if (source instanceof Class) {
			return processClass(context, (Class<?>) source, searchStrategy, processor);
		}
		if (source instanceof Method) {
			return processMethod(context, (Method) source, searchStrategy, processor);
		}
		return processElement(context, source, processor);
	}

	@Nullable
	private static <C, R> R processClass(C context, Class<?> source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		switch (searchStrategy) {
			case DIRECT:
				return processElement(context, source, processor);
			case INHERITED_ANNOTATIONS:
				return processClassInheritedAnnotations(context, source, searchStrategy, processor);
			case SUPERCLASS:
				return processClassHierarchy(context, source, processor, false, false);
			case TYPE_HIERARCHY:
				return processClassHierarchy(context, source, processor, true, false);
			case TYPE_HIERARCHY_AND_ENCLOSING_CLASSES:
				return processClassHierarchy(context, source, processor, true, true);
		}
		throw new IllegalStateException("Unsupported search strategy " + searchStrategy);
	}

	@Nullable
	private static <C, R> R processClassInheritedAnnotations(C context, Class<?> source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		try {
			// 没有层级结构，自己就是顶级结构
			if (isWithoutHierarchy(source, searchStrategy)) {
				return processElement(context, source, processor);
			}
			Annotation[] relevant = null;
			int remaining = Integer.MAX_VALUE;
			int aggregateIndex = 0;
			Class<?> root = source;
			while (source != null && source != Object.class && remaining > 0 &&
					!hasPlainJavaAnnotationsOnly(source)) {
				R result = processor.doWithAggregate(context, aggregateIndex);
				if (result != null) {
					return result;
				}
				Annotation[] declaredAnnotations = getDeclaredAnnotations(source, true);
				if (relevant == null && declaredAnnotations.length > 0) {
					relevant = root.getAnnotations();
					remaining = relevant.length;
				}
				for (int i = 0; i < declaredAnnotations.length; i++) {
					if (declaredAnnotations[i] != null) {
						boolean isRelevant = false;
						for (int relevantIndex = 0; relevantIndex < relevant.length; relevantIndex++) {
							if (relevant[relevantIndex] != null &&
									declaredAnnotations[i].annotationType() == relevant[relevantIndex].annotationType()) {
								isRelevant = true;
								relevant[relevantIndex] = null;
								remaining--;
								break;
							}
						}
						if (!isRelevant) {
							declaredAnnotations[i] = null;
						}
					}
				}
				result = processor.doWithAnnotations(context, aggregateIndex, source, declaredAnnotations);
				if (result != null) {
					return result;
				}
				source = source.getSuperclass();
				aggregateIndex++;
			}
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processClassHierarchy(C context, Class<?> source,
			AnnotationsProcessor<C, R> processor, boolean includeInterfaces, boolean includeEnclosing) {

		return processClassHierarchy(context, new int[] {0}, source, processor,
				includeInterfaces, includeEnclosing);
	}

	@Nullable
	private static <C, R> R processClassHierarchy(C context, int[] aggregateIndex, Class<?> source,
			AnnotationsProcessor<C, R> processor, boolean includeInterfaces, boolean includeEnclosing) {

		try {
			R result = processor.doWithAggregate(context, aggregateIndex[0]);
			if (result != null) {
				return result;
			}
			if (hasPlainJavaAnnotationsOnly(source)) {
				return null;
			}
			Annotation[] annotations = getDeclaredAnnotations(source, false);
			result = processor.doWithAnnotations(context, aggregateIndex[0], source, annotations);
			if (result != null) {
				return result;
			}
			aggregateIndex[0]++;
			if (includeInterfaces) {
				for (Class<?> interfaceType : source.getInterfaces()) {
					R interfacesResult = processClassHierarchy(context, aggregateIndex,
						interfaceType, processor, true, includeEnclosing);
					if (interfacesResult != null) {
						return interfacesResult;
					}
				}
			}
			Class<?> superclass = source.getSuperclass();
			if (superclass != Object.class && superclass != null) {
				R superclassResult = processClassHierarchy(context, aggregateIndex,
					superclass, processor, includeInterfaces, includeEnclosing);
				if (superclassResult != null) {
					return superclassResult;
				}
			}
			if (includeEnclosing) {
				// Since merely attempting to load the enclosing class may result in
				// automatic loading of sibling nested classes that in turn results
				// in an exception such as NoClassDefFoundError, we wrap the following
				// in its own dedicated try-catch block in order not to preemptively
				// halt the annotation scanning process.
				try {
					Class<?> enclosingClass = source.getEnclosingClass();
					if (enclosingClass != null) {
						R enclosingResult = processClassHierarchy(context, aggregateIndex,
							enclosingClass, processor, includeInterfaces, true);
						if (enclosingResult != null) {
							return enclosingResult;
						}
					}
				}
				catch (Throwable ex) {
					AnnotationUtils.handleIntrospectionFailure(source, ex);
				}
			}
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processMethod(C context, Method source,
			SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		switch (searchStrategy) {
			case DIRECT:
			case INHERITED_ANNOTATIONS:
				return processMethodInheritedAnnotations(context, source, processor);
			case SUPERCLASS:
				return processMethodHierarchy(context, new int[] {0}, source.getDeclaringClass(),
						processor, source, false);
			case TYPE_HIERARCHY:
			case TYPE_HIERARCHY_AND_ENCLOSING_CLASSES:
				return processMethodHierarchy(context, new int[] {0}, source.getDeclaringClass(),
						processor, source, true);
		}
		throw new IllegalStateException("Unsupported search strategy " + searchStrategy);
	}

	@Nullable
	private static <C, R> R processMethodInheritedAnnotations(C context, Method source,
			AnnotationsProcessor<C, R> processor) {

		try {
			R result = processor.doWithAggregate(context, 0);
			return (result != null ? result :
				processMethodAnnotations(context, 0, source, processor));
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processMethodHierarchy(C context, int[] aggregateIndex,
			Class<?> sourceClass, AnnotationsProcessor<C, R> processor, Method rootMethod,
			boolean includeInterfaces) {

		try {
			R result = processor.doWithAggregate(context, aggregateIndex[0]);
			if (result != null) {
				return result;
			}
			if (hasPlainJavaAnnotationsOnly(sourceClass)) {
				return null;
			}
			boolean calledProcessor = false;
			if (sourceClass == rootMethod.getDeclaringClass()) {
				result = processMethodAnnotations(context, aggregateIndex[0],
					rootMethod, processor);
				calledProcessor = true;
				if (result != null) {
					return result;
				}
			}
			else {
				for (Method candidateMethod : getBaseTypeMethods(context, sourceClass)) {
					if (candidateMethod != null && isOverride(rootMethod, candidateMethod)) {
						result = processMethodAnnotations(context, aggregateIndex[0],
							candidateMethod, processor);
						calledProcessor = true;
						if (result != null) {
							return result;
						}
					}
				}
			}
			if (Modifier.isPrivate(rootMethod.getModifiers())) {
				return null;
			}
			if (calledProcessor) {
				aggregateIndex[0]++;
			}
			if (includeInterfaces) {
				for (Class<?> interfaceType : sourceClass.getInterfaces()) {
					R interfacesResult = processMethodHierarchy(context, aggregateIndex,
						interfaceType, processor, rootMethod, true);
					if (interfacesResult != null) {
						return interfacesResult;
					}
				}
			}
			Class<?> superclass = sourceClass.getSuperclass();
			if (superclass != Object.class && superclass != null) {
				R superclassResult = processMethodHierarchy(context, aggregateIndex,
					superclass, processor, rootMethod, includeInterfaces);
				if (superclassResult != null) {
					return superclassResult;
				}
			}
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(rootMethod, ex);
		}
		return null;
	}

	private static <C> Method[] getBaseTypeMethods(C context, Class<?> baseType) {
		if (baseType == Object.class || hasPlainJavaAnnotationsOnly(baseType)) {
			return NO_METHODS;
		}

		Method[] methods = baseTypeMethodsCache.get(baseType);
		if (methods == null) {
			boolean isInterface = baseType.isInterface();
			methods = isInterface ? baseType.getMethods() : ReflectionUtils.getDeclaredMethods(baseType);
			int cleared = 0;
			for (int i = 0; i < methods.length; i++) {
				if ((!isInterface && Modifier.isPrivate(methods[i].getModifiers())) ||
						hasPlainJavaAnnotationsOnly(methods[i]) ||
						getDeclaredAnnotations(methods[i], false).length == 0) {
					methods[i] = null;
					cleared++;
				}
			}
			if (cleared == methods.length) {
				methods = NO_METHODS;
			}
			baseTypeMethodsCache.put(baseType, methods);
		}
		return methods;
	}

	private static boolean isOverride(Method rootMethod, Method candidateMethod) {
		return (!Modifier.isPrivate(candidateMethod.getModifiers()) &&
				candidateMethod.getName().equals(rootMethod.getName()) &&
				hasSameParameterTypes(rootMethod, candidateMethod));
	}

	private static boolean hasSameParameterTypes(Method rootMethod, Method candidateMethod) {
		if (candidateMethod.getParameterCount() != rootMethod.getParameterCount()) {
			return false;
		}
		Class<?>[] rootParameterTypes = rootMethod.getParameterTypes();
		Class<?>[] candidateParameterTypes = candidateMethod.getParameterTypes();
		if (Arrays.equals(candidateParameterTypes, rootParameterTypes)) {
			return true;
		}
		return hasSameGenericTypeParameters(rootMethod, candidateMethod,
				rootParameterTypes);
	}

	private static boolean hasSameGenericTypeParameters(
			Method rootMethod, Method candidateMethod, Class<?>[] rootParameterTypes) {

		Class<?> sourceDeclaringClass = rootMethod.getDeclaringClass();
		Class<?> candidateDeclaringClass = candidateMethod.getDeclaringClass();
		if (!candidateDeclaringClass.isAssignableFrom(sourceDeclaringClass)) {
			return false;
		}
		for (int i = 0; i < rootParameterTypes.length; i++) {
			Class<?> resolvedParameterType = ResolvableType.forMethodParameter(
					candidateMethod, i, sourceDeclaringClass).resolve();
			if (rootParameterTypes[i] != resolvedParameterType) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	private static <C, R> R processMethodAnnotations(C context, int aggregateIndex, Method source,
			AnnotationsProcessor<C, R> processor) {

		Annotation[] annotations = getDeclaredAnnotations(source, false);
		R result = processor.doWithAnnotations(context, aggregateIndex, source, annotations);
		if (result != null) {
			return result;
		}
		Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(source);
		if (bridgedMethod != source) {
			Annotation[] bridgedAnnotations = getDeclaredAnnotations(bridgedMethod, true);
			for (int i = 0; i < bridgedAnnotations.length; i++) {
				if (ObjectUtils.containsElement(annotations, bridgedAnnotations[i])) {
					bridgedAnnotations[i] = null;
				}
			}
			return processor.doWithAnnotations(context, aggregateIndex, source, bridgedAnnotations);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processElement(C context, AnnotatedElement source,
			AnnotationsProcessor<C, R> processor) {

		try {
			// 处理 聚合
			R result = processor.doWithAggregate(context, 0);
			return (result != null ? result : processor.doWithAnnotations(
				context, 0, source, getDeclaredAnnotations(source, false)));
		}
		catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	/**
	 * 找到当前能被注解标注的元素上有没有对应的注解类型.
	 * getAnnotations() 是可以获取到父类的有 @Inherited 修饰的注解的，
	 * 而 getDeclaredAnnotations() 则只能获取当前类的注解。
	 *
	 * @param source         能被注解标注的元素
	 * @param annotationType 需要找的注解类型
	 * @param <A>            目标注解类型
	 * @return 目标注解
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	static <A extends Annotation> A getDeclaredAnnotation(AnnotatedElement source, Class<A> annotationType) {
		Annotation[] annotations = getDeclaredAnnotations(source, false);
		for (Annotation annotation : annotations) {
			if (annotation != null && annotationType == annotation.annotationType()) {
				return (A) annotation;
			}
		}
		return null;
	}

	/**
	 * 在当前类AnnotationsScanner缓存了当前类上的注解，并在AttributeMethods中缓存了注解里的属性方法
	 *
	 * 获取当前类声明的所有注解，如果有一个有效就返回所有注解，然后缓存
	 *
	 * @param source    当前元素，当前类
	 * @param defensive 为true返回克隆对象，操作不影响缓存中的数据
	 * @return 类上的注解
	 */
	static Annotation[] getDeclaredAnnotations(AnnotatedElement source, boolean defensive) {
		boolean cached = false;
		Annotation[] annotations = declaredAnnotationCache.get(source);
		if (annotations != null) {
			cached = true;
		}
		else {
			// 返回此元素上直接存在的注释。此方法忽略继承的注释。如果此元素上没有直接存在注释，则返回值为长度为 0 的数组。
			// 此方法的调用者可以自由修改返回的数组；不会影响返回给其他调用者的数组。
			// getAnnotations() 是可以获取到父类的有 @Inherited 修饰的注解的，
			// 而 getDeclaredAnnotations() 则只能获取当前类的注解。
			annotations = source.getDeclaredAnnotations();
			if (annotations.length != 0) {
				// 都验证不通过，才会全部忽略，有一个能验证通过就都要
				boolean allIgnored = true;
				for (int i = 0; i < annotations.length; i++) {
					Annotation annotation = annotations[i];
					// getClass获取到的是一个代理类对象，通过该对象可以获取到注解中定义的属性方法和继承Object的方法
					// 代理类上没有注解所以拿了也是空。注解是接口，实际是代理后使用

					// annotationType获取到的是注解本身（接口的Class对象），通过该接口可以获取注解上的注解和注解中声明的方法
					// 这里做了解析也做了缓存
					if (isIgnorable(annotation.annotationType()) ||
							!AttributeMethods.forAnnotationType(annotation.annotationType()).isValid(annotation)) {
						annotations[i] = null;
					}
					else {
						allIgnored = false;
					}
				}
				annotations = (allIgnored ? NO_ANNOTATIONS : annotations);
				// 是一个类，或者是成员(字段或方法)或构造函数
				if (source instanceof Class || source instanceof Member) {
					// 缓存标识source上的所有注解，可以是类可以是方法
					declaredAnnotationCache.put(source, annotations);
					cached = true;
				}
			}
		}
		// 只要可能被缓存，就只能返回clone对象，所以这里排除可能缓存的所有情况
		if (!defensive || annotations.length == 0 || !cached) {
			return annotations;
		}
		return annotations.clone();
	}

	private static boolean isIgnorable(Class<?> annotationType) {
		return AnnotationFilter.PLAIN.matches(annotationType);
	}

	/**
	 * 简单判断一个元素上是否有注解，如果没有则返回true，如果可能有就返回false
	 *
	 * @param source 元素上是否有可能的注解相对getAnnotations方法来说
	 * @param searchStrategy 搜索注解的策略，主要是用于判断是否是DIRECT
	 * @return 如果没有则返回true，如果可能有就返回false
	 */
	static boolean isKnownEmpty(AnnotatedElement source, SearchStrategy searchStrategy) {
		if (hasPlainJavaAnnotationsOnly(source)) {
			return true;
		}
		// 如果是顶级，或者只查找自己，就可以直接返回getDeclaredAnnotations，不用管其它的注解了
		if (searchStrategy == SearchStrategy.DIRECT || isWithoutHierarchy(source, searchStrategy)) {
			if (source instanceof Method && ((Method) source).isBridge()) {
				return false;
			}
			return getDeclaredAnnotations(source, false).length == 0;
		}
		return false;
	}

	static boolean hasPlainJavaAnnotationsOnly(@Nullable Object annotatedElement) {
		if (annotatedElement instanceof Class) {
			return hasPlainJavaAnnotationsOnly((Class<?>) annotatedElement);
		}
		else if (annotatedElement instanceof Member) {
			// Member中得getDeclaringClass 获取当前成员(方法、变量)或构造方法，在哪个类中声明。
			// 使用自定义类User.getMethods()获取方法，会发现wait等方法对应得类是java.lang.Object
			return hasPlainJavaAnnotationsOnly(((Member) annotatedElement).getDeclaringClass());
		}
		else {
			return false;
		}
	}

	static boolean hasPlainJavaAnnotationsOnly(Class<?> type) {
		return (type.getName().startsWith("java.") || type == Ordered.class);
	}

	/**
	 * 判断当前能被注解标注的元素是否没有层级关系。当前类是不是层级的顶级
	 * 实际有层级关系的只有Class(存在继承关系)和Method(从父类继承得来)，不考虑属性
	 *
	 * 如果是Class则判断 没有父类并且没有实现接口，如果搜索策略考虑内部类则把外部类作为父级
	 * 如果是Method则判断 method 的声明类的层级关系。
	 *
	 * @param source         能被注解标注的元素
	 * @param searchStrategy 注解查找策略
	 * @return 是否没有层级关系，是不是顶级
	 */
	private static boolean isWithoutHierarchy(AnnotatedElement source, SearchStrategy searchStrategy) {
		if (source == Object.class) {
			return true;
		}
		if (source instanceof Class) {
			Class<?> sourceClass = (Class<?>) source;
			// 没有父类并且没有接口
			boolean noSuperTypes = (sourceClass.getSuperclass() == Object.class &&
					sourceClass.getInterfaces().length == 0);
			// getEnclosingClass能够获取匿名内部类对应的外部类Class对象，
			// 而getDeclaringClass不能够获取匿名内部类对应的声明类Class对象。
			return (searchStrategy == SearchStrategy.TYPE_HIERARCHY_AND_ENCLOSING_CLASSES ? noSuperTypes &&
					sourceClass.getEnclosingClass() == null : noSuperTypes);
		}
		if (source instanceof Method) {
			Method sourceMethod = (Method) source;
			return (Modifier.isPrivate(sourceMethod.getModifiers()) ||
					isWithoutHierarchy(sourceMethod.getDeclaringClass(), searchStrategy));
		}
		return true;
	}

	static void clearCache() {
		declaredAnnotationCache.clear();
		baseTypeMethodsCache.clear();
	}

}
