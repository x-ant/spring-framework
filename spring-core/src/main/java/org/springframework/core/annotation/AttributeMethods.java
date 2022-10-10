/*
 * Copyright 2002-2019 the original author or authors.
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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;

// 封装了注解的属性方法，代表这个是哪一个注解，并且有哪些属性方法。不考虑属性方法上有没有注解。不涉及其它注解。就单指自己。
/**
 * Provides a quick way to access the attribute methods of an {@link Annotation}
 * with consistent ordering as well as a few useful utility methods.
 *
 * @author Phillip Webb
 * @since 5.2
 */
final class AttributeMethods {

	static final AttributeMethods NONE = new AttributeMethods(null, new Method[0]);


	private static final Map<Class<? extends Annotation>, AttributeMethods> cache =
			new ConcurrentReferenceHashMap<>();

	private static final Comparator<Method> methodComparator = (m1, m2) -> {
		if (m1 != null && m2 != null) {
			return m1.getName().compareTo(m2.getName());
		}
		return m1 != null ? -1 : 1;
	};


	@Nullable
	private final Class<? extends Annotation> annotationType;

	private final Method[] attributeMethods;

	/**
	 * 如果属性方法的返回值是一个类Class，那么这个类型有可能不存在，需要验证
	 */
	private final boolean[] canThrowTypeNotPresentException;

	/**
	 * 存在方法有默认值
	 */
	private final boolean hasDefaultValueMethod;

	/**
	 * 存在方法返回值是一个注解，或者是组合注解
	 */
	private final boolean hasNestedAnnotation;

	/**
	 * 使用 注解和其属性方法 创建 注解和属性方法的包装类
	 *
	 * @param annotationType   当前注解
	 * @param attributeMethods 当前注解内的属性方法
	 */
	private AttributeMethods(@Nullable Class<? extends Annotation> annotationType, Method[] attributeMethods) {
		this.annotationType = annotationType;
		this.attributeMethods = attributeMethods;
		this.canThrowTypeNotPresentException = new boolean[attributeMethods.length];
		boolean foundDefaultValueMethod = false;
		boolean foundNestedAnnotation = false;
		for (int i = 0; i < attributeMethods.length; i++) {
			Method method = this.attributeMethods[i];
			Class<?> type = method.getReturnType();
			if (method.getDefaultValue() != null) {
				foundDefaultValueMethod = true;
			}
			// getComponentType() 返回表示数组组件类型的Class 。如果此类不表示数组类，则此方法返回 null。
			if (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation())) {
				foundNestedAnnotation = true;
			}
			ReflectionUtils.makeAccessible(method);
			this.canThrowTypeNotPresentException[i] = (type == Class.class || type == Class[].class || type.isEnum());
		}
		this.hasDefaultValueMethod = foundDefaultValueMethod;
		this.hasNestedAnnotation = foundNestedAnnotation;
	}


	// 判断是否只有一个value属性
	/**
	 * Determine if this instance only contains a single attribute named
	 * {@code value}.
	 * @return {@code true} if there is only a value attribute
	 */
	boolean hasOnlyValueAttribute() {
		return (this.attributeMethods.length == 1 &&
				MergedAnnotation.VALUE.equals(this.attributeMethods[0].getName()));
	}


	// 验证所有返回类型是Class的属性方法，通过调用得到返回值来验证对应的Class是否存在。不报异常所有的都存在返回true
	/**
	 * Determine if values from the given annotation can be safely accessed without
	 * causing any {@link TypeNotPresentException TypeNotPresentExceptions}.
	 * @param annotation the annotation to check
	 * @return {@code true} if all values are present
	 * @see #validate(Annotation)
	 */
	boolean isValid(Annotation annotation) {
		assertAnnotation(annotation);
		for (int i = 0; i < size(); i++) {
			if (canThrowTypeNotPresentException(i)) {
				try {
					get(i).invoke(annotation);
				}
				catch (Throwable ex) {
					return false;
				}
			}
		}
		return true;
	}

	// 验证所有返回类型是Class的属性方法，通过调用得到返回值来验证对应的Class是否存在。有不存在的就抛异常
	/**
	 * Check if values from the given annotation can be safely accessed without causing
	 * any {@link TypeNotPresentException TypeNotPresentExceptions}. In particular,
	 * this method is designed to cover Google App Engine's late arrival of such
	 * exceptions for {@code Class} values (instead of the more typical early
	 * {@code Class.getAnnotations() failure}.
	 * @param annotation the annotation to validate
	 * @throws IllegalStateException if a declared {@code Class} attribute could not be read
	 * @see #isValid(Annotation)
	 */
	void validate(Annotation annotation) {
		assertAnnotation(annotation);
		for (int i = 0; i < size(); i++) {
			if (canThrowTypeNotPresentException(i)) {
				try {
					get(i).invoke(annotation);
				}
				catch (Throwable ex) {
					throw new IllegalStateException("Could not obtain annotation attribute value for " +
							get(i).getName() + " declared on " + annotation.annotationType(), ex);
				}
			}
		}
	}

	private void assertAnnotation(Annotation annotation) {
		Assert.notNull(annotation, "Annotation must not be null");
		if (this.annotationType != null) {
			Assert.isInstanceOf(this.annotationType, annotation);
		}
	}

	/**
	 * Get the attribute with the specified name or {@code null} if no
	 * matching attribute exists.
	 * @param name the attribute name to find
	 * @return the attribute method or {@code null}
	 */
	@Nullable
	Method get(String name) {
		int index = indexOf(name);
		return index != -1 ? this.attributeMethods[index] : null;
	}

	/**
	 * Get the attribute at the specified index.
	 * @param index the index of the attribute to return
	 * @return the attribute method
	 * @throws IndexOutOfBoundsException if the index is out of range
	 * (<tt>index &lt; 0 || index &gt;= size()</tt>)
	 */
	Method get(int index) {
		return this.attributeMethods[index];
	}

	/**
	 * Determine if the attribute at the specified index could throw a
	 * {@link TypeNotPresentException} when accessed.
	 * @param index the index of the attribute to check
	 * @return {@code true} if the attribute can throw a
	 * {@link TypeNotPresentException}
	 */
	boolean canThrowTypeNotPresentException(int index) {
		return this.canThrowTypeNotPresentException[index];
	}

	/**
	 * Get the index of the attribute with the specified name, or {@code -1}
	 * if there is no attribute with the name.
	 * @param name the name to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(String name) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].getName().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the index of the specified attribute, or {@code -1} if the
	 * attribute is not in this collection.
	 * @param attribute the attribute to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(Method attribute) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].equals(attribute)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the number of attributes in this collection.
	 * @return the number of attributes
	 */
	int size() {
		return this.attributeMethods.length;
	}

	/**
	 * Determine if at least one of the attribute methods has a default value.
	 * @return {@code true} if there is at least one attribute method with a default value
	 */
	boolean hasDefaultValueMethod() {
		return this.hasDefaultValueMethod;
	}

	/**
	 * Determine if at least one of the attribute methods is a nested annotation.
	 * @return {@code true} if there is at least one attribute method with a nested
	 * annotation type
	 */
	boolean hasNestedAnnotation() {
		return this.hasNestedAnnotation;
	}


	// 获取当前注解声明的属性方法，并放入缓存。不包括继承的。创建注解、注解声明的属性方法的包装类
	/**
	 * Get the attribute methods for the given annotation type.
	 * @param annotationType the annotation type
	 * @return the attribute methods for the annotation type
	 */
	static AttributeMethods forAnnotationType(@Nullable Class<? extends Annotation> annotationType) {
		if (annotationType == null) {
			return NONE;
		}
		return cache.computeIfAbsent(annotationType, AttributeMethods::compute);
	}

	/**
	 * 得到当前注解的所有属性方法，然后缓存。不包括继承
	 *
	 * @param annotationType 注解
	 * @return 封装好的这个注解和它对应的属性方法
	 */
	private static AttributeMethods compute(Class<? extends Annotation> annotationType) {
		// 返回一个包含Method对象的数组，该对象反映了此Class对象表示的类或接口的所有声明方法，
		// 包括公共、受保护、默认（包）访问和私有方法，但不包括继承方法。
		Method[] methods = annotationType.getDeclaredMethods();
		int size = methods.length;
		for (int i = 0; i < methods.length; i++) {
			if (!isAttributeMethod(methods[i])) {
				methods[i] = null;
				size--;
			}
		}
		if (size == 0) {
			return NONE;
		}
		Arrays.sort(methods, methodComparator);
		Method[] attributeMethods = Arrays.copyOf(methods, size);
		return new AttributeMethods(annotationType, attributeMethods);
	}

	/**
	 * 判断是否是注解的属性方法，没有参数，返回值不为void
	 * 可能有其他情况么？
	 *
	 * @param method 当前属性方法
	 * @return 是否是 属性方法
	 */
	private static boolean isAttributeMethod(Method method) {
		return (method.getParameterCount() == 0 && method.getReturnType() != void.class);
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 * @param attribute the attribute to describe
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Method attribute) {
		if (attribute == null) {
			return "(none)";
		}
		return describe(attribute.getDeclaringClass(), attribute.getName());
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 * @param annotationType the annotation type
	 * @param attributeName the attribute name
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Class<?> annotationType, @Nullable String attributeName) {
		if (attributeName == null) {
			return "(none)";
		}
		String in = (annotationType != null ? " in annotation [" + annotationType.getName() + "]" : "");
		return "attribute '" + attributeName + "'" + in;
	}

}
