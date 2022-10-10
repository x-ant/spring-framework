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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.annotation.AnnotationTypeMapping.MirrorSets.MirrorSet;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

// 这个类代表了一个注解的所有信息，并通过source来表示注解间的关系。不过滤属性方法。处理了属性方法上的@AliasFor注解
// 这个类代表的也可能是一个元注解，jdk自带注解等。
/**
 * Provides mapping information for a single annotation (or meta-annotation) in
 * the context of a root annotation type.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.2
 * @see AnnotationTypeMappings
 */
final class AnnotationTypeMapping {

	private static final MirrorSet[] EMPTY_MIRROR_SETS = new MirrorSet[0];


	/**
	 * source说明，当前这个AnnotationTypeMapping是从别的AnnotationTypeMapping中的注解上拿到的
	 * 即当前 AnnotationTypeMapping 代表的注解 是标注在 source 代表的注解上的
	 *
	 * Component注解Configuration。source就是Configuration
	 */
	@Nullable
	private final AnnotationTypeMapping source;

	/**
	 * 不是解析别的注解的来的，而是直接从类中生成的当前AnnotationTypeMapping，则自己就是根
	 * 即root代表 直接标注在类上的 注解
	 */
	private final AnnotationTypeMapping root;

	private final int distance;

	private final Class<? extends Annotation> annotationType;

	private final List<Class<? extends Annotation>> metaTypes;

	@Nullable
	private final Annotation annotation;

	/**
	 * 注解和其声明的属性方法的封装
	 */
	private final AttributeMethods attributes;

	/**
	 * 当前注解中属性互为别名的情况，不涉及其它注解
	 */
	private final MirrorSets mirrorSets;

	private final int[] aliasMappings;

	private final int[] conventionMappings;

	private final int[] annotationValueMappings;

	private final AnnotationTypeMapping[] annotationValueSource;

	/**
	 * AliasFor注解中表达的意思是，哪一个注解的哪一个方法。
	 * 如果当前属性方法中出现了AliasFor，并且这两者搭配出现了对于当前注解来说的新组合。
	 * 则存入当前属性中。即当前属性是所有AliasFor注解的映射
	 *
	 * key为被覆盖的属性方法，value是用于覆盖的属性方法 的map
	 *
	 */
	private final Map<Method, List<Method>> aliasedBy;

	private final boolean synthesizable;

	private final Set<Method> claimedAliases = new HashSet<>();


	AnnotationTypeMapping(@Nullable AnnotationTypeMapping source,
			Class<? extends Annotation> annotationType, @Nullable Annotation annotation) {

		this.source = source;
		this.root = (source != null ? source.getRoot() : this);
		this.distance = (source == null ? 0 : source.getDistance() + 1);
		this.annotationType = annotationType;
		this.metaTypes = merge(
				source != null ? source.getMetaTypes() : null,
				annotationType);
		this.annotation = annotation;
		// 注解、注解声明的属性方法的包装类
		this.attributes = AttributeMethods.forAnnotationType(annotationType);
		this.mirrorSets = new MirrorSets();
		this.aliasMappings = filledIntArray(this.attributes.size());
		this.conventionMappings = filledIntArray(this.attributes.size());
		this.annotationValueMappings = filledIntArray(this.attributes.size());
		this.annotationValueSource = new AnnotationTypeMapping[this.attributes.size()];
		this.aliasedBy = resolveAliasedForTargets();
		processAliases();
		addConventionMappings();
		addConventionAnnotationValues();
		this.synthesizable = computeSynthesizableFlag();
	}


	private static <T> List<T> merge(@Nullable List<T> existing, T element) {
		if (existing == null) {
			return Collections.singletonList(element);
		}
		List<T> merged = new ArrayList<>(existing.size() + 1);
		merged.addAll(existing);
		merged.add(element);
		return Collections.unmodifiableList(merged);
	}

	/**
	 * 使用当前注解中的属性方法集合 attributes 解析这些属性方法上的AliasFor注解
	 * 最终得到，key为被覆盖的属性方法，value是用于覆盖的属性方法 的map
	 *
	 * @return key为被覆盖的属性方法，value是用于覆盖的属性方法 的map
	 */
	private Map<Method, List<Method>> resolveAliasedForTargets() {
		// key是被覆盖的属性方法，value是用于覆盖的属性方法列表，一个key可以被多个value覆盖
		Map<Method, List<Method>> aliasedBy = new HashMap<>();
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			// 找到当前方法属性上的AliasFor注解实例
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null) {
				// 传入当前的属性方法实例，找到被覆盖的属性方法
				Method target = resolveAliasTarget(attribute, aliasFor);
				aliasedBy.computeIfAbsent(target, key -> new ArrayList<>()).add(attribute);
			}
		}
		return Collections.unmodifiableMap(aliasedBy);
	}

	/**
	 * 找到当前属性方法 用于 覆盖哪一个注解的属性方法。并返回这个被覆盖的属性方法。
	 *
	 * @param attribute 当前注解上的属性方法
	 * @param aliasFor 属性方法上的AliasFor注解
	 * @return 被覆盖的属性方法
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor) {
		return resolveAliasTarget(attribute, aliasFor, true);
	}

	/**
	 * 找到当前属性方法 用于 覆盖哪一个注解的属性方法。并返回这个被覆盖的属性方法。
	 *
	 * @param attribute 当前注解上的属性方法
	 * @param aliasFor 属性方法上的AliasFor注解
	 * @param checkAliasPair 是否校验，是不是自己的属性相互覆盖
	 * @return 被覆盖的属性方法
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor, boolean checkAliasPair) {
		if (StringUtils.hasText(aliasFor.value()) && StringUtils.hasText(aliasFor.attribute())) {
			throw new AnnotationConfigurationException(String.format(
					"In @AliasFor declared on %s, attribute 'attribute' and its alias 'value' " +
					"are present with values of '%s' and '%s', but only one is permitted.",
					AttributeMethods.describe(attribute), aliasFor.attribute(),
					aliasFor.value()));
		}
		// 拿到当前属性方法，要覆盖哪一个注解的属性方法。
		Class<? extends Annotation> targetAnnotation = aliasFor.annotation();
		if (targetAnnotation == Annotation.class) {
			// 如果是默认值，则要覆盖自己的属性，给自己的属性起别名
			targetAnnotation = this.annotationType;
		}
		// 获取这个属性方法上AliasFor注解的 attribute 的属性值 (作为需要处理的真正属性方法)
		String targetAttributeName = aliasFor.attribute();
		if (!StringUtils.hasLength(targetAttributeName)) {
			// 如果为空 则获取这个属性方法上AliasFor注解的 value 的属性值 (作为需要处理的真正属性方法)
			targetAttributeName = aliasFor.value();
		}
		if (!StringUtils.hasLength(targetAttributeName)) {
			// 还为空，则当前属性方法的名字就是 需要处理的属性方法
			targetAttributeName = attribute.getName();
		}
		// 获取需要处理的 注解的属性方法。使用
		Method target = AttributeMethods.forAnnotationType(targetAnnotation).get(targetAttributeName);
		if (target == null) {
			if (targetAnnotation == this.annotationType) {
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for '%s' which is not present.",
						AttributeMethods.describe(attribute), targetAttributeName));
			}
			throw new AnnotationConfigurationException(String.format(
					"%s is declared as an @AliasFor nonexistent %s.",
					StringUtils.capitalize(AttributeMethods.describe(attribute)),
					AttributeMethods.describe(targetAnnotation, targetAttributeName)));
		}
		// 如果目标属性方法，就是当前AliasFor注解的方法。相当于注解没用到，也不是别的注解也没有别的名字
		if (target.equals(attribute)) {
			throw new AnnotationConfigurationException(String.format(
					"@AliasFor declaration on %s points to itself. " +
					"Specify 'annotation' to point to a same-named attribute on a meta-annotation.",
					AttributeMethods.describe(attribute)));
		}
		// 判断，当前被注解的属性方法的返回值，和被覆盖的属性方法的返回值是否相同。
		// 如果相同或者当前注解的返回值是 被覆盖注解返回值的组成部分（被覆盖注解的返回值是一个数组）也可以。
		if (!isCompatibleReturnType(attribute.getReturnType(), target.getReturnType())) {
			throw new AnnotationConfigurationException(String.format(
					"Misconfigured aliases: %s and %s must declare the same return type.",
					AttributeMethods.describe(attribute),
					AttributeMethods.describe(target)));
		}
		// 判断当前属性方法被用于覆盖自身其它属性方法，并且判断目标方法上是否也被AliasFor注解标注
		if (isAliasPair(target) && checkAliasPair) {
			// 被覆盖的属性方法是否也被AliasFor注解标注
			AliasFor targetAliasFor = target.getAnnotation(AliasFor.class);
			if (targetAliasFor != null) {
				// 找到被覆盖的属性方法，覆盖的是哪一个属性方法。
				Method mirror = resolveAliasTarget(target, targetAliasFor, false);
				// 如果不是相互覆盖就报错。
				if (!mirror.equals(attribute)) {
					throw new AnnotationConfigurationException(String.format(
							"%s must be declared as an @AliasFor %s, not %s.",
							StringUtils.capitalize(AttributeMethods.describe(target)),
							AttributeMethods.describe(attribute), AttributeMethods.describe(mirror)));
				}
			}
		}
		return target;
	}

	/**
	 * 当前注解，就是声明 被覆盖属性方法的注解。
	 * 自己的属性起了一个自己另一个属性的名字作为别名的情况。
	 *
	 * @param target 找到的需要覆盖的方法属性
	 * @return 是否是自己 的方法属性 覆盖自己的 另一个方法属性
	 */
	private boolean isAliasPair(Method target) {
		return (this.annotationType == target.getDeclaringClass());
	}

	private boolean isCompatibleReturnType(Class<?> attributeType, Class<?> targetType) {
		return (attributeType == targetType || attributeType == targetType.getComponentType());
	}

	/**
	 *
	 */
	private void processAliases() {
		// 这个属性的 所有 别名(包括自己)，这个列表里的每一个都代表同一个属性
		List<Method> aliases = new ArrayList<>();
		// attributes 这个包装类的size返回的是这个注解声明的属性方法的个数
		for (int i = 0; i < this.attributes.size(); i++) {
			aliases.clear();
			aliases.add(this.attributes.get(i));
			// Component注解了Configuration。解析Component时合并，会合并Configuration中@AliasFor标注的属性方法。
			collectAliases(aliases);
			// 大于1说明，起码是一个自己和一个@AliasFor
			if (aliases.size() > 1) {
				processAliases(i, aliases);
			}
		}
	}

	/**
	 * 获取构建 当前属性方法，和覆盖当前属性方法的属性方法的 列表
	 *
	 * @param aliases 看调用实际里面只有一个值，当前注解上的属性方法
	 */
	private void collectAliases(List<Method> aliases) {
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			int size = aliases.size();
			for (int j = 0; j < size; j++) {
				// 用当前注解上的属性方法在被覆盖属性方法里找的到，说明自己覆盖自己
				// 当前属性方法 aliases 被自己或者被自己注解的注解中的属性方法 重写则可以取到值
				List<Method> additional = mapping.aliasedBy.get(aliases.get(j));
				if (additional != null) {
					// 别名里就加上 用于覆盖自己的属性，或者被下级覆盖的属性
					aliases.addAll(additional);
				}
			}
			// 合并被自己注解的注解信息。如果是看层级的话是一个向下(被注解)的方向
			mapping = mapping.source;
		}
	}

	/**
	 *
	 *
	 * @param attributeIndex 哪一个属性方法的索引
	 * @param aliases 这个属性方法包括自己的所有别名
	 */
	private void processAliases(int attributeIndex, List<Method> aliases) {
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			if (rootAttributeIndex != -1 && mapping != this.root) {
				for (int i = 0; i < mapping.attributes.size(); i++) {
					// 解析时从下到上的过程，Configuration到Component，所以这里代表当前注解Component中的属性被覆盖了。
					if (aliases.contains(mapping.attributes.get(i))) {
						// 记录当前注解中被覆盖的属性方法的索引
						mapping.aliasMappings[i] = rootAttributeIndex;
					}
				}
			}
			// 记录当前属性互为别名的情况
			mapping.mirrorSets.updateFrom(aliases);

			mapping.claimedAliases.addAll(aliases);
			if (mapping.annotation != null) {
				int[] resolvedMirrors = mapping.mirrorSets.resolve(null,
						mapping.annotation, ReflectionUtils::invokeMethod);
				for (int i = 0; i < mapping.attributes.size(); i++) {
					if (aliases.contains(mapping.attributes.get(i))) {
						this.annotationValueMappings[attributeIndex] = resolvedMirrors[i];
						this.annotationValueSource[attributeIndex] = mapping;
					}
				}
			}
			mapping = mapping.source;
		}
	}

	private int getFirstRootAttributeIndex(Collection<Method> aliases) {
		AttributeMethods rootAttributes = this.root.getAttributes();
		for (int i = 0; i < rootAttributes.size(); i++) {
			// 根注解用@AliasFor标注了当前属性
			if (aliases.contains(rootAttributes.get(i))) {
				// 返回根注解第一个属性方法的索引
				return i;
			}
		}
		return -1;
	}

	private void addConventionMappings() {
		if (this.distance == 0) {
			return;
		}
		AttributeMethods rootAttributes = this.root.getAttributes();
		int[] mappings = this.conventionMappings;
		for (int i = 0; i < mappings.length; i++) {
			String name = this.attributes.get(i).getName();
			MirrorSet mirrors = getMirrorSets().getAssigned(i);
			int mapped = rootAttributes.indexOf(name);
			if (!MergedAnnotation.VALUE.equals(name) && mapped != -1) {
				mappings[i] = mapped;
				if (mirrors != null) {
					for (int j = 0; j < mirrors.size(); j++) {
						mappings[mirrors.getAttributeIndex(j)] = mapped;
					}
				}
			}
		}
	}

	private void addConventionAnnotationValues() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			boolean isValueAttribute = MergedAnnotation.VALUE.equals(attribute.getName());
			AnnotationTypeMapping mapping = this;
			while (mapping != null && mapping.distance > 0) {
				int mapped = mapping.getAttributes().indexOf(attribute.getName());
				if (mapped != -1 && isBetterConventionAnnotationValue(i, isValueAttribute, mapping)) {
					this.annotationValueMappings[i] = mapped;
					this.annotationValueSource[i] = mapping;
				}
				mapping = mapping.source;
			}
		}
	}

	private boolean isBetterConventionAnnotationValue(int index, boolean isValueAttribute,
			AnnotationTypeMapping mapping) {

		if (this.annotationValueMappings[index] == -1) {
			return true;
		}
		int existingDistance = this.annotationValueSource[index].distance;
		return !isValueAttribute && existingDistance > mapping.distance;
	}

	@SuppressWarnings("unchecked")
	private boolean computeSynthesizableFlag() {
		// Uses @AliasFor for local aliases?
		for (int index : this.aliasMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Uses @AliasFor for attribute overrides in meta-annotations?
		if (!this.aliasedBy.isEmpty()) {
			return true;
		}

		// Uses convention-based attribute overrides in meta-annotations?
		for (int index : this.conventionMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Has nested annotations or arrays of annotations that are synthesizable?
		if (getAttributes().hasNestedAnnotation()) {
			AttributeMethods attributeMethods = getAttributes();
			for (int i = 0; i < attributeMethods.size(); i++) {
				Method method = attributeMethods.get(i);
				Class<?> type = method.getReturnType();
				if (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation())) {
					Class<? extends Annotation> annotationType =
							(Class<? extends Annotation>) (type.isAnnotation() ? type : type.getComponentType());
					AnnotationTypeMapping mapping = AnnotationTypeMappings.forAnnotationType(annotationType).get(0);
					if (mapping.isSynthesizable()) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/**
	 * Method called after all mappings have been set. At this point no further
	 * lookups from child mappings will occur.
	 */
	void afterAllMappingsSet() {
		validateAllAliasesClaimed();
		for (int i = 0; i < this.mirrorSets.size(); i++) {
			validateMirrorSet(this.mirrorSets.get(i));
		}
		this.claimedAliases.clear();
	}

	private void validateAllAliasesClaimed() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null && !this.claimedAliases.contains(attribute)) {
				Method target = resolveAliasTarget(attribute, aliasFor);
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for %s which is not meta-present.",
						AttributeMethods.describe(attribute), AttributeMethods.describe(target)));
			}
		}
	}

	private void validateMirrorSet(MirrorSet mirrorSet) {
		Method firstAttribute = mirrorSet.get(0);
		Object firstDefaultValue = firstAttribute.getDefaultValue();
		for (int i = 1; i <= mirrorSet.size() - 1; i++) {
			Method mirrorAttribute = mirrorSet.get(i);
			Object mirrorDefaultValue = mirrorAttribute.getDefaultValue();
			if (firstDefaultValue == null || mirrorDefaultValue == null) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare default values.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
			if (!ObjectUtils.nullSafeEquals(firstDefaultValue, mirrorDefaultValue)) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare the same default value.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
		}
	}

	/**
	 * Get the root mapping.
	 * @return the root mapping
	 */
	AnnotationTypeMapping getRoot() {
		return this.root;
	}

	/**
	 * Get the source of the mapping or {@code null}.
	 * @return the source of the mapping
	 */
	@Nullable
	AnnotationTypeMapping getSource() {
		return this.source;
	}

	/**
	 * Get the distance of this mapping.
	 * @return the distance of the mapping
	 */
	int getDistance() {
		return this.distance;
	}

	/**
	 * Get the type of the mapped annotation.
	 * @return the annotation type
	 */
	Class<? extends Annotation> getAnnotationType() {
		return this.annotationType;
	}

	List<Class<? extends Annotation>> getMetaTypes() {
		return this.metaTypes;
	}

	/**
	 * Get the source annotation for this mapping. This will be the
	 * meta-annotation, or {@code null} if this is the root mapping.
	 * @return the source annotation of the mapping
	 */
	@Nullable
	Annotation getAnnotation() {
		return this.annotation;
	}

	/**
	 * Get the annotation attributes for the mapping annotation type.
	 * @return the attribute methods
	 */
	AttributeMethods getAttributes() {
		return this.attributes;
	}

	/**
	 * Get the related index of an alias mapped attribute, or {@code -1} if
	 * there is no mapping. The resulting value is the index of the attribute on
	 * the root annotation that can be invoked in order to obtain the actual
	 * value.
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getAliasMapping(int attributeIndex) {
		return this.aliasMappings[attributeIndex];
	}

	/**
	 * Get the related index of a convention mapped attribute, or {@code -1}
	 * if there is no mapping. The resulting value is the index of the attribute
	 * on the root annotation that can be invoked in order to obtain the actual
	 * value.
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getConventionMapping(int attributeIndex) {
		return this.conventionMappings[attributeIndex];
	}

	/**
	 * Get a mapped attribute value from the most suitable
	 * {@link #getAnnotation() meta-annotation}.
	 * <p>The resulting value is obtained from the closest meta-annotation,
	 * taking into consideration both convention and alias based mapping rules.
	 * For root mappings, this method will always return {@code null}.
	 * @param attributeIndex the attribute index of the source attribute
	 * @param metaAnnotationsOnly if only meta annotations should be considered.
	 * If this parameter is {@code false} then aliases within the annotation will
	 * also be considered.
	 * @return the mapped annotation value, or {@code null}
	 */
	@Nullable
	Object getMappedAnnotationValue(int attributeIndex, boolean metaAnnotationsOnly) {
		int mappedIndex = this.annotationValueMappings[attributeIndex];
		if (mappedIndex == -1) {
			return null;
		}
		AnnotationTypeMapping source = this.annotationValueSource[attributeIndex];
		if (source == this && metaAnnotationsOnly) {
			return null;
		}
		return ReflectionUtils.invokeMethod(source.attributes.get(mappedIndex), source.annotation);
	}

	/**
	 * Determine if the specified value is equivalent to the default value of the
	 * attribute at the given index.
	 * @param attributeIndex the attribute index of the source attribute
	 * @param value the value to check
	 * @param valueExtractor the value extractor used to extract values from any
	 * nested annotations
	 * @return {@code true} if the value is equivalent to the default value
	 */
	boolean isEquivalentToDefaultValue(int attributeIndex, Object value, ValueExtractor valueExtractor) {

		Method attribute = this.attributes.get(attributeIndex);
		return isEquivalentToDefaultValue(attribute, value, valueExtractor);
	}

	/**
	 * Get the mirror sets for this type mapping.
	 * @return the attribute mirror sets
	 */
	MirrorSets getMirrorSets() {
		return this.mirrorSets;
	}

	/**
	 * Determine if the mapped annotation is <em>synthesizable</em>.
	 * <p>Consult the documentation for {@link MergedAnnotation#synthesize()}
	 * for an explanation of what is considered synthesizable.
	 * @return {@code true} if the mapped annotation is synthesizable
	 * @since 5.2.6
	 */
	boolean isSynthesizable() {
		return this.synthesizable;
	}


	private static int[] filledIntArray(int size) {
		int[] array = new int[size];
		Arrays.fill(array, -1);
		return array;
	}

	private static boolean isEquivalentToDefaultValue(Method attribute, Object value,
			ValueExtractor valueExtractor) {

		return areEquivalent(attribute.getDefaultValue(), value, valueExtractor);
	}

	private static boolean areEquivalent(@Nullable Object value, @Nullable Object extractedValue,
			ValueExtractor valueExtractor) {

		if (ObjectUtils.nullSafeEquals(value, extractedValue)) {
			return true;
		}
		if (value instanceof Class && extractedValue instanceof String) {
			return areEquivalent((Class<?>) value, (String) extractedValue);
		}
		if (value instanceof Class[] && extractedValue instanceof String[]) {
			return areEquivalent((Class[]) value, (String[]) extractedValue);
		}
		if (value instanceof Annotation) {
			return areEquivalent((Annotation) value, extractedValue, valueExtractor);
		}
		return false;
	}

	private static boolean areEquivalent(Class<?>[] value, String[] extractedValue) {
		if (value.length != extractedValue.length) {
			return false;
		}
		for (int i = 0; i < value.length; i++) {
			if (!areEquivalent(value[i], extractedValue[i])) {
				return false;
			}
		}
		return true;
	}

	private static boolean areEquivalent(Class<?> value, String extractedValue) {
		return value.getName().equals(extractedValue);
	}

	private static boolean areEquivalent(Annotation annotation, @Nullable Object extractedValue,
			ValueExtractor valueExtractor) {

		AttributeMethods attributes = AttributeMethods.forAnnotationType(annotation.annotationType());
		for (int i = 0; i < attributes.size(); i++) {
			Method attribute = attributes.get(i);
			Object value1 = ReflectionUtils.invokeMethod(attribute, annotation);
			Object value2;
			if (extractedValue instanceof TypeMappedAnnotation) {
				value2 = ((TypeMappedAnnotation<?>) extractedValue).getValue(attribute.getName()).orElse(null);
			}
			else {
				value2 = valueExtractor.extract(attribute, extractedValue);
			}
			if (!areEquivalent(value1, value2, valueExtractor)) {
				return false;
			}
		}
		return true;
	}


	/**
	 * A collection of {@link MirrorSet} instances that provides details of all
	 * defined mirrors.
	 */
	class MirrorSets {

		private MirrorSet[] mirrorSets;

		private final MirrorSet[] assigned;

		/**
		 * MirrorSets 代表一个属性，在当前注解中其它别名的集合
		 * MirrorSet 共享别名的属性
		 */
		MirrorSets() {
			// 每一个 MirrorSet 代表当前注解 存在了 属性互为别名 的 所有属性
			this.assigned = new MirrorSet[attributes.size()];
			this.mirrorSets = EMPTY_MIRROR_SETS;
		}

		/**
		 * 检查当前 **一个属性** 的所有别名，当前注解是不是有大于两个的属性在其中。
		 * 表明当前注解的属性中存在互为别名的情况
		 *
		 * @param aliases 一个属性的所有别名，注意这里实际时方法实例的列表。方法相同说明是一个类的一个方法。
		 */
		void updateFrom(Collection<Method> aliases) {
			MirrorSet mirrorSet = null;
			int size = 0;
			int last = -1;
			for (int i = 0; i < attributes.size(); i++) {
				Method attribute = attributes.get(i);
				if (aliases.contains(attribute)) {
					size++;
					// 当前注解的属性方法中，存在第二个被AliasFor注释的方法
					if (size > 1) {
						// 为空才创建，所有其它别名使用同一个MirrorSet对象
						if (mirrorSet == null) {
							mirrorSet = new MirrorSet();
							// 把上一个也标注了，assigned就是共性别名的属性集合的索引
							this.assigned[last] = mirrorSet;
						}
						// 创建 mirrorSet 表示，从属性方法的出第一个外的索引开始放mirrorSet。这里像是位图
						this.assigned[i] = mirrorSet;
					}
					last = i;
				}
			}
			// 上面的for循环检查了当前属性，在当前注解中是否有互为别名的情况，为出自己外的其它每一个创建一个mirrorSet
			if (mirrorSet != null) {
				mirrorSet.update();
				Set<MirrorSet> unique = new LinkedHashSet<>(Arrays.asList(this.assigned));
				unique.remove(null);
				// MirrorSets中的mirrorSets属性记录别名情况
				this.mirrorSets = unique.toArray(EMPTY_MIRROR_SETS);
			}
		}

		int size() {
			return this.mirrorSets.length;
		}

		MirrorSet get(int index) {
			return this.mirrorSets[index];
		}

		@Nullable
		MirrorSet getAssigned(int attributeIndex) {
			return this.assigned[attributeIndex];
		}

		int[] resolve(@Nullable Object source, @Nullable Object annotation, ValueExtractor valueExtractor) {
			int[] result = new int[attributes.size()];
			for (int i = 0; i < result.length; i++) {
				result[i] = i;
			}
			for (int i = 0; i < size(); i++) {
				MirrorSet mirrorSet = get(i);
				int resolved = mirrorSet.resolve(source, annotation, valueExtractor);
				for (int j = 0; j < mirrorSet.size; j++) {
					result[mirrorSet.indexes[j]] = resolved;
				}
			}
			return result;
		}


		// 共享别名情况
		/**
		 * A single set of mirror attributes.
		 */
		class MirrorSet {

			/**
			 * 当前注解中共享一个别名的有几个
			 */
			private int size;

			/**
			 * 那几个共享，属性方法的索引大于-1
			 */
			private final int[] indexes = new int[attributes.size()];

			/**
			 * 给上面俩属性赋值，更新数据用的
			 */
			void update() {
				this.size = 0;
				Arrays.fill(this.indexes, -1);
				for (int i = 0; i < MirrorSets.this.assigned.length; i++) {
					if (MirrorSets.this.assigned[i] == this) {
						this.indexes[this.size] = i;
						this.size++;
					}
				}
			}

			<A> int resolve(@Nullable Object source, @Nullable A annotation, ValueExtractor valueExtractor) {
				int result = -1;
				Object lastValue = null;
				for (int i = 0; i < this.size; i++) {
					Method attribute = attributes.get(this.indexes[i]);
					Object value = valueExtractor.extract(attribute, annotation);
					boolean isDefaultValue = (value == null ||
							isEquivalentToDefaultValue(attribute, value, valueExtractor));
					if (isDefaultValue || ObjectUtils.nullSafeEquals(lastValue, value)) {
						if (result == -1) {
							result = this.indexes[i];
						}
						continue;
					}
					if (lastValue != null && !ObjectUtils.nullSafeEquals(lastValue, value)) {
						String on = (source != null) ? " declared on " + source : "";
						throw new AnnotationConfigurationException(String.format(
								"Different @AliasFor mirror values for annotation [%s]%s; attribute '%s' " +
								"and its alias '%s' are declared with values of [%s] and [%s].",
								getAnnotationType().getName(), on,
								attributes.get(result).getName(),
								attribute.getName(),
								ObjectUtils.nullSafeToString(lastValue),
								ObjectUtils.nullSafeToString(value)));
					}
					result = this.indexes[i];
					lastValue = value;
				}
				return result;
			}

			int size() {
				return this.size;
			}

			Method get(int index) {
				int attributeIndex = this.indexes[index];
				return attributes.get(attributeIndex);
			}

			int getAttributeIndex(int index) {
				return this.indexes[index];
			}
		}
	}

}
