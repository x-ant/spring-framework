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

/**
 * Provides mapping information for a single annotation (or meta-annotation) in
 * the context of a root annotation type.
 *
 * 代表注解上被标注的注解这种层级关系中的一个注解，用于说明自己的方法中，自己覆盖自己 和 下级覆盖自己的情况
 *
 * 完成了一个注解的属性映射，处理了AliasFor和上下级注解间同名属性的处理，确定了一个注解间和上下级注解间的取值的问题。
 * 没有对应的取值逻辑，但是提供了取值所需的所有信息。这个类并不是一个全局的类，所以内部没有对应的缓存。
 * 通过new来创建，而不是forxxx来创建。构造方法中就完成了所有功能。虽然解析了上下级关系，但是只处理自己。上下级需要自己new。
 * 只是属性，没有关心这个注解上还有没有注解的问题，只是关注被当前注解标注的注解中的属性方法的问题。不考虑@Inherited的问题。
 * 因为@Inherited要处理的是某一个类上面有没有继承的注解，而不是注解间的关系
 *
 *
 * 1、先通过反射获取当前注解的全部属性方法，然后封装为聚合属性 AttributeMethods 对象，该对象获取并通过下标来访问属性方法；
 *
 * 2、然后， AnnotationTypeMapping 将会遍历 AttributeMethods 中的方法，若属性方法上存在 @AliasFor 注解，则会解析注解，
 * 获取注解指定的类上的别名属性对应的方法，并与当前注解中的对应属性方法一并添加到名为 aliasBy 的 Map 集合中
 * 建立别名属性和当前注解属性的映射关系；
 *
 * 3、遍历当前注解中已经注册到 aliasBy 中的别名属性，然后拿着这个属性继续向子注解递归，
 * 一直到将子类中直接或间接作为该属性别名的属性全部收集完毕；
 *
 * 4、拿着收集到的别名属性，继续从当前元注解项子注解递归，然后在处理每一层的注解时：
 *   4.1、同一注解中互为别名的属性建立 MirrorSet ，然后从中选择出最后实际用于取值的最终属性，
 *   MirrorSet 关联的一组互为别名的属性取值时都从该最终属性获取值；
 *   4.2、遍历全部属性，分别在 annotationValueSource 和 annotationValueMappings中与该属性在 AttributeMethods中下标对应的位置，
 *   记录要调用哪个注解实例和该要在注解实例中最终调用的属性；
 *
 * 5、处理完 @AlisaFor 声明的显示别名后，将会为子注解与元注解中的同名属性设置隐式别名：
 *   5.1、遍历属性，若元注解中存在与根注解同名的属性，则将根注解中同名属性的对应下标设置到 conventionMappings 中；
 *   5.2、遍历属性，将元注解中的 annotationValueSource 和 annotationValueMappings ，分别替换为存在同名属性，
 *   且距离根注解最近的非根子注解与该子注解同名属性的下标，；
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
	 * Component注解Configuration。当前是Component，source就是Configuration。source被注解的注解
	 *
	 * 覆写 ：当子注解和元注解中的两个属性互为别名时，对子注解中的属性赋值，将覆盖元注解中的属性；
	 */
	@Nullable
	private final AnnotationTypeMapping source;

	/**
	 * 不是解析别的注解的来的，而是直接从类中生成的当前AnnotationTypeMapping，则自己就是根
	 * 即root代表 直接标注在类上的 注解
	 *
	 * 类似于parent，但是与parent相比。如果自己就是最顶级的，root就是自己。但是层级来看，root最下层被注解的注解
	 *
	 * Component注解Configuration，则root是Configuration。root是被注解的注解里最开始解析的地方。表明最初解析的是谁
	 */
	private final AnnotationTypeMapping root;

	/**
	 * 与root的距离，标记在root上是1，与root越近，其方法属性值更会被使用
	 */
	private final int distance;

	/**
	 * 当前注解的Class对象，标识自己的类型
	 */
	private final Class<? extends Annotation> annotationType;

	private final List<Class<? extends Annotation>> metaTypes;

	/**
	 * 看实际调用发现 source和annotation 代表解析Configuration后，接着解析其上的Component注解，这俩参数才会有值。
	 * 是当前注解的实例，且当前注解 已经标注到 其它注解上被解析
	 *
	 * 这个是对象实例和annotationType做区分
	 */
	@Nullable
	private final Annotation annotation;

	/**
	 * 当前对象代表的注解和这个注解声明的属性方法的封装
	 * 只是这个注解的属性方法，不包括父级继承的
	 */
	private final AttributeMethods attributes;

	/**
	 * 当前注解中属性互为别名的情况，不涉及其它注解
	 *
	 * 主要说明，哪几个方法是互为别名，但是A指向B，B谁也不指，这种会说明A、B同名，即不能表示到底是谁被覆盖
	 *
	 * 镜像 ：当同一注解类中的两个属性互为别名时，则对两者任一属性赋值，等同于对另一属性赋值；
	 */
	private final MirrorSets mirrorSets;

	/**
	 * 记录当前注解中被root注解覆盖的情况，其余为-1。aliasMappings[0]为1，说明当前的第0个方法被root注解的第1个方法覆盖
	 */
	private final int[] aliasMappings;

	/**
	 * 与root注解方法属性同名的情况
	 * aliasMappings[0]为1 说明当前的第0个方法被root注解的第1个方法同名
	 *
	 * 注意这个属性是隐式同名的处理与@AliasFor注解无关
	 */
	private final int[] conventionMappings;

	/**
	 * annotationValueMappings[0]为1，说明当前第0个方法，被第1个方法覆盖
	 * 与annotationValueSource配合就是 被哪一个注解的哪一个方法覆盖
	 *
	 * 这俩属性都被隐式别名影响，但是不包括value属性，注解使用的时候直接传参就是value，可能value属性比较方便，人家就没想重写
	 *
	 * 使用 @AlisaFor 注解在 spring 中称为显式别名，对应的还有一个隐式别名，也就是 只要子注解和元注解的属性名称相同，
	 * 则就会使用子注解的属性值覆盖元注解的属性值 ，即子注解的属性会强制作为元注解属性的别名。
	 * 这个隐式映射的优先级高于显式映射，换而言之，如果你在子注解为一个元注解通过 @AlisaFor 指定了显式别名，
	 * 但是偏偏子注解中海油一个属性与这个元注解中的属性同名，则最终取值时，优先取子注解中的同名字段，
	 * 而不是通过 @AlisaFor 指定的别名字段。
	 */
	private final int[] annotationValueMappings;

	/**
	 * annotationValueSource[0]不为空，说明当前第0个方法，指向了一个覆盖自己的注解
	 *
	 * 这俩属性都被隐式别名影响，但是不包括value属性，注解使用的时候直接传参就是value，可能value属性比较方便，人家就没想重写
	 */
	private final AnnotationTypeMapping[] annotationValueSource;

	/**
	 * 获取AliasFor注解的配置，保存为aliasedBy
	 *
	 * 其中key为被覆盖的属性方法，value是用于覆盖的属性方法 的map。说明我覆盖了谁，value覆盖了key
	 * 要注意的是，key可以是自己的属性方法，也可以是别的注解的属性方法，但是value一定是自己的属性方法
	 */
	private final Map<Method, List<Method>> aliasedBy;

	/**
	 * 就是判断当前注解是否可以被用于合成 MergedAnnotation
	 *
	 * 表明是否有重写的情况
	 */
	private final boolean synthesizable;

	private final Set<Method> claimedAliases = new HashSet<>();

	/**
	 * 构造函数。用Configuration 和 Component 表示
	 * source和annotation 代表解析Configuration后，接着解析其上的Component注解，这俩参数才会有值。
	 * 有层级结构才会有值，解析到注解上的注解才会有值
	 *
	 * @param source         被当前注解标注的注解
	 * @param annotationType 当前注解
	 * @param annotation     当前注解实例
	 */
	AnnotationTypeMapping(@Nullable AnnotationTypeMapping source,
			Class<? extends Annotation> annotationType, @Nullable Annotation annotation) {

		this.source = source;
		this.root = (source != null ? source.getRoot() : this);
		this.distance = (source == null ? 0 : source.getDistance() + 1);
		this.annotationType = annotationType;
		// source的metaTypes和当前元素的注解类型都收集在一起，一路迭代的注解都有那些，就是一路的annotationType
		this.metaTypes = merge(
				source != null ? source.getMetaTypes() : null,
				annotationType);
		this.annotation = annotation;
		// 注解、注解声明的属性方法的包装类
		this.attributes = AttributeMethods.forAnnotationType(annotationType);
		this.mirrorSets = new MirrorSets();
		// 初始化这个int[]数组，如果aliasMappings[0]不是-1而是2，说明当前的第0个属性方法被别的第2个属性方法覆盖了
		this.aliasMappings = filledIntArray(this.attributes.size());
		this.conventionMappings = filledIntArray(this.attributes.size());
		this.annotationValueMappings = filledIntArray(this.attributes.size());
		this.annotationValueSource = new AnnotationTypeMapping[this.attributes.size()];
		// key是被覆盖的属性方法，value是当前注解中用于覆盖的属性方法。说明我覆盖了谁
		// 同名的话，相当于有一个在key，另一个在value。不是两个都在value
		this.aliasedBy = resolveAliasedForTargets();
		// 初始化别名属性，为所有存在别名的属性建立MirrorSet
		processAliases();

		// 为元注解与根注解同名的属性强制设置别名
		addConventionMappings();
		// 为元注解与非根注解的子注解的同名的属性设置别名
		addConventionAnnotationValues();
		// 设置当前注解的可合成标记，是否有重写的情况
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
				// 形成，被覆盖的方法，用于覆盖的方法Map
				aliasedBy.computeIfAbsent(target, key -> new ArrayList<>()).add(attribute);
			}
		}
		return Collections.unmodifiableMap(aliasedBy);
	}

	/**
	 * 找到当前属性方法 用于 覆盖哪一个注解的属性方法。并返回这个被覆盖的属性方法。
	 *
	 * @param attribute 被注解的属性方法
	 * @param aliasFor 属性方法上的AliasFor注解
	 * @return 被覆盖的属性方法
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor) {
		return resolveAliasTarget(attribute, aliasFor, true);
	}

	/**
	 * 找到当前属性方法 用于 覆盖哪一个注解的属性方法。并返回这个被覆盖的属性方法。
	 *
	 * 1、需要覆盖哪一个注解，如果没写就是自己覆盖自己
	 * 2、被覆盖的方法要存在，用于覆盖的方法的返回值 与 覆盖方法返回值相同，或者是数组元素的类型
	 * 3、如果是自己覆盖自己，被覆盖的属性方法上可以有AliasFor，如果有两者一定相互指向
	 *
	 * 验证完成后返回，当前属性方法要覆盖的哪个方法作为返回值
	 *
	 * @param attribute 被注解的属性方法
	 * @param aliasFor 属性方法上的AliasFor注解
	 * @param checkAliasPair 是否校验，是不是自己的属性相互覆盖
	 * @return 被覆盖的属性方法
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor, boolean checkAliasPair) {
		// AliasFor 注解中的value 和 attribute只能写一个
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
		// 如果没有传入两个属性，这个属性方法名，就是要覆盖父级的属性方法名
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
		// 判断目标类是不是就是当前类判断是否是自己覆盖自己，并且判断目标方法上是否也被AliasFor注解标注
		// 如果自己覆盖自己并且目标方法上也有AliasFor，就要求被覆盖的方法也指向自己
		if (isAliasPair(target) && checkAliasPair) {
			// 被覆盖的属性方法是否也被AliasFor注解标注
			AliasFor targetAliasFor = target.getAnnotation(AliasFor.class);
			if (targetAliasFor != null) {
				// 找到被覆盖的属性方法，覆盖的是哪一个属性方法。这种就不用在检测是否自己覆盖自己了会死循环，所以false
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

	/**
	 * 相同或者当前注解的返回值是 被覆盖注解返回值的组成部分（被覆盖注解的返回值是一个数组）也可以。
	 *
	 * 当前的这个和父级相同，或者是父级的一份子。当前是小范围就行
	 *
	 * @param attributeType 要复写父级属性方法的返回值Class
	 * @param targetType 目标属性方法的返回值Class
	 * @return
	 */
	private boolean isCompatibleReturnType(Class<?> attributeType, Class<?> targetType) {
		return (attributeType == targetType || attributeType == targetType.getComponentType());
	}

	/**
	 * 循环遍历每一个属性，处理MirrorSet，并添加跨注解时优先取更接近root注解的处理
	 */
	private void processAliases() {
		// 这个属性的 所有 别名(包括自己)，这个列表里的每一个都代表同一个属性，这个值是跨注解的
		List<Method> aliases = new ArrayList<>();
		// attributes 这个包装类的size返回的是这个注解声明的属性方法的个数
		for (int i = 0; i < this.attributes.size(); i++) {
			aliases.clear();
			// 放入最初的属性方法，也就是被覆盖的属性方法，自己头上可能没有AliasFor
			aliases.add(this.attributes.get(i));
			// 收集 aliases.add(this.attributes.get(i)); 和覆盖这个属性方法的属性方法。收集跨注解的同名
			collectAliases(aliases);
			// 大于1说明，处理自己之外，确实有别的属性方法在覆盖自己
			if (aliases.size() > 1) {
				// 针对当前被覆盖的属性i，解析@AliasFor确定当前属性要到哪一个属性中取值
				processAliases(i, aliases);
			}
		}
	}

	/**
	 * 获取构建 当前属性方法，和覆盖当前属性方法的属性方法的 列表。收集跨注解的同名覆盖
	 *
	 * aliases最初有一个值，然后通过注解向被注解的注解方向寻找，看看有没有别的属性方法覆盖这个最初的值，有的话就放到这个列表里
	 * aliases始终表示某一个属性方法的所有别名情况
	 *
	 * @param aliases 当前aliases中的属性方法，如果在从自己开始的向下寻找中发现被别的注解的属性方法覆盖，则也放到这个列表中
	 */
	private void collectAliases(List<Method> aliases) {
		AnnotationTypeMapping mapping = this;
		// mapping是一直迭代的
		while (mapping != null) {
			int size = aliases.size();
			for (int j = 0; j < size; j++) {
				// 获取当前mapping注解中，用于覆盖aliases.get(j)的注解的方法，
				// aliases 最初放的是这个注解的方法。所以这里得到的一定是覆盖当前注解方法的方法，一定是同级或者下级
				List<Method> additional = mapping.aliasedBy.get(aliases.get(j));
				if (additional != null) {
					// 覆盖aliases.get(j)，说明是别名，也加入作为下一次查找 被覆盖 的条件
					aliases.addAll(additional);
				}
			}
			// 如果mapping是Component，source就是Configuration，往下走
			mapping = mapping.source;
		}
	}

	/**
	 * 针对当前被覆盖属性i，解析@AliasFor确定当前属性要到哪一个属性中取值
	 * 结果记录到  annotationValueMappings 和 annotationValueSource 中 自己的某一个属性实际要从那个注解中取值
	 *
	 * @param attributeIndex 这时当前注解的哪一个被覆盖属性方法的索引
	 * @param aliases 这个属性方法包括被覆盖的自己的所有别名，这个值是跨注解的，
	 *                   当前注解自己和自己被覆盖的情况，不包括自己覆盖别人
	 */
	private void processAliases(int attributeIndex, List<Method> aliases) {
		// 最初解析的那个注解的索引rootAttributeIndex对应的属性方法，覆盖了当前注解中的属性方法。
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		// 从当前元注解向子注解递归
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			// 确实发生了覆盖，mapping != this.root 说明mapping是被覆盖的，随着mapping的迭代，最终会等于root，遍历到最下面了
			if (rootAttributeIndex != -1 && mapping != this.root) {
				for (int i = 0; i < mapping.attributes.size(); i++) {
					// 解析时从上到下的过程，Component到Configuration，所以这里代表当前注解Component中的属性被覆盖了。
					if (aliases.contains(mapping.attributes.get(i))) {
						// 记录当前注解中被root注解覆盖的情况，位图法都改为指向 同名中最小方法属性。其余为-1
						// 最终每一层的mapping都会判断
						mapping.aliasMappings[i] = rootAttributeIndex;
					}
				}
			}
			// 记录当前注解的当前被覆盖的属性互为别名(mirrorSet)的情况
			mapping.mirrorSets.updateFrom(aliases);
			// 所有有别名的属性和其别名的集合，每一层都拿到所有的同名情况
			mapping.claimedAliases.addAll(aliases);

			// 当前注解实例不为空
			if (mapping.annotation != null) {
				// resolvedMirrors[0]的值为1，说明第0个方法取实际值要用第1个方法获取。获得多个同名用哪一个作为取值的标识
				int[] resolvedMirrors = mapping.mirrorSets.resolve(null,
						mapping.annotation, ReflectionUtils::invokeMethod);
				// 遍历当前不断迭代注解中的每一个属性
				for (int i = 0; i < mapping.attributes.size(); i++) {
					// mapping在不断跨注解，但是这里赋值始终时this。最终越靠近root的会作为最终的赋值
					if (aliases.contains(mapping.attributes.get(i))) {
						// 始终设置当前索引为attributeIndex的方法属性。
						// 每一层的注解都判断，如果发生覆盖，则用最新的方法取值和mapping
						this.annotationValueMappings[attributeIndex] = resolvedMirrors[i];
						this.annotationValueSource[attributeIndex] = mapping;
					}
				}
			}
			// 遍历各级注解
			mapping = mapping.source;
		}
	}

	/**
	 * 获取索引最小的 当前同名属性方法
	 *
	 * @param aliases 同名属性方法
	 * @return 索引
	 */
	private int getFirstRootAttributeIndex(Collection<Method> aliases) {
		// 最初解析的是谁
		AttributeMethods rootAttributes = this.root.getAttributes();
		for (int i = 0; i < rootAttributes.size(); i++) {
			// 根注解在层层关系中，@AliasFor覆盖了当前属性
			if (aliases.contains(rootAttributes.get(i))) {
				// 返回根注解第一个属性方法的索引
				return i;
			}
		}
		return -1;
	}

	/**
	 * 记录root注解中不是value并且和当前注解同名的情况，记录。
	 * 为元注解与根注解同名的属性强制设置别名，与AliasFor无关
	 */
	private void addConventionMappings() {
		if (this.distance == 0) {
			return;
		}
		AttributeMethods rootAttributes = this.root.getAttributes();
		int[] mappings = this.conventionMappings;
		for (int i = 0; i < mappings.length; i++) {
			// 遍历当前注解的属性，判断是否在根注解存在
			String name = this.attributes.get(i).getName();
			MirrorSet mirrors = getMirrorSets().getAssigned(i);
			int mapped = rootAttributes.indexOf(name);
			// 当前名不是value并且根注解中也存在，直接传参就是value，可能value属性比较方便，人家没想重写
			if (!MergedAnnotation.VALUE.equals(name) && mapped != -1) {
				// mappings[0]为1，说明当前第0的方法属性，被root注解的第1的方法属性覆盖
				mappings[i] = mapped;
				if (mirrors != null) {
					for (int j = 0; j < mirrors.size(); j++) {
						// 当前注解中互为别名的属性也处理一下
						mappings[mirrors.getAttributeIndex(j)] = mapped;
					}
				}
			}
		}
	}

	/**
	 * 为元注解与非根注解的子注解的同名的属性设置别名
	 */
	private void addConventionAnnotationValues() {
		for (int i = 0; i < this.attributes.size(); i++) {
			// 每一个属性都去不断迭代注解
			Method attribute = this.attributes.get(i);
			boolean isValueAttribute = MergedAnnotation.VALUE.equals(attribute.getName());
			// 注解不断迭代
			AnnotationTypeMapping mapping = this;
			while (mapping != null && mapping.distance > 0) {
				// 下级注解有这个属性
				int mapped = mapping.getAttributes().indexOf(attribute.getName());
				if (mapped != -1 && isBetterConventionAnnotationValue(i, isValueAttribute, mapping)) {
					this.annotationValueMappings[i] = mapped;
					this.annotationValueSource[i] = mapping;
				}
				mapping = mapping.source;
			}
		}
	}

	/**
	 * 是否将当前注解的属性方法转换为，传入的mapping中同名属性方法取值
	 *
	 * @param index
	 * @param isValueAttribute
	 * @param mapping
	 * @return
	 */
	private boolean isBetterConventionAnnotationValue(int index, boolean isValueAttribute,
			AnnotationTypeMapping mapping) {
		// 这个方法就没被AliasFor重写，直接用当前传入的注解取值
		if (this.annotationValueMappings[index] == -1) {
			return true;
		}
		// 被AliasFor重写了，不是value属性并且目前AliasFor具体root更远，则也用同名重写
		int existingDistance = this.annotationValueSource[index].distance;
		return !isValueAttribute && existingDistance > mapping.distance;
	}

	/**
	 * 设置当前注解的可合成标记
	 *
	 * 就是判断当前注解是否可以被用于合成 MergedAnnotation
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private boolean computeSynthesizableFlag() {
		// Uses @AliasFor for local aliases?
		// 被root注解用@AliasFor重写
		for (int index : this.aliasMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Uses @AliasFor for attribute overrides in meta-annotations?
		// 被别的注解用@AliasFor重写
		if (!this.aliasedBy.isEmpty()) {
			return true;
		}

		// Uses convention-based attribute overrides in meta-annotations?
		// 除value外的隐式重写
		for (int index : this.conventionMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Has nested annotations or arrays of annotations that are synthesizable?
		// 当前注解的属性方法中，是否有注解类型的，如果有则判断这个注解类型的当前方法的返回值是否可用于 MergedAnnotation
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
		// 两个都是注解，则比较内部的属性值
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


	// 代表当前注解所有互为别名的所有属性，同一个注解内可以出现A是B的别名，B不是A的别名的情况
	// 或者更奇怪的，A指向C，B指向C，但C谁也不指，同时可以出现D指向E，E指向D
	// 这种情况这个类，只能在解析C的时候表明A、B、C同名，但是不能说明C是哪一个属性方法
	// 镜像 ：当同一注解类中的两个属性互为别名时，则对两者任一属性赋值，等同于对另一属性赋值；
	/**
	 * A collection of {@link MirrorSet} instances that provides details of all
	 * defined mirrors.
	 */
	class MirrorSets {

		/**
		 * MirrorSet[0]和MirrorSet[1]有值，并且他俩想等，说明当前注解的第0和第1个属性方法互为别名
		 *
		 * 和assigned相比去重了，这个set中保存的一定是互为别名，由于互为别名只都是同一个对象所以只剩一个。
		 * 也就是，每一个都表示某一个属性的一个互为别名的情况
		 * 如果A、B、C同名，D、E同名，这里就俩对象
		 *
		 * assigned这个list中有脚标，脚标说明这是第几个属性方法，两个脚标中的指针相同说明互为别名
		 */
		private MirrorSet[] mirrorSets;

		/**
		 * 位图表示，当前注解的哪些属性 是共享别名的，共享别名的列表会被标记为同个MirrorSet的实例。
		 * 脚标代表是当前注解的哪一个方法属性
		 * 在解析当前MirrorSets所代表的属性时，assigned表示同名属性集合包括自己
		 *
		 * 如果A、B、C同名，D、E同名，这里就是脚标12345有值，123值相同，45值相同
		 */
		private final MirrorSet[] assigned;

		/**
		 * MirrorSet 共享别名的属性
		 */
		MirrorSets() {
			// 每一个 MirrorSet 代表当前注解 当前属性 存在了 属性互为别名 的 所有属性
			this.assigned = new MirrorSet[attributes.size()];
			this.mirrorSets = EMPTY_MIRROR_SETS;
		}

		/**
		 * 检查当前 被覆盖的**一个属性** 的所有别名，当前注解是不是有大于两个的属性在其中。
		 * 表明当前注解的属性中存在互为别名的情况
		 *
		 * @param aliases 一个被覆盖属性的所有别名，可能是来自自身也可能是被注解的注解覆盖导致
		 */
		void updateFrom(Collection<Method> aliases) {
			MirrorSet mirrorSet = null;
			int size = 0;
			int last = -1;
			for (int i = 0; i < attributes.size(); i++) {
				// 遍历当前注解的属性方法，定位到被覆盖的属性方法，和用于覆盖它的属性方法
				Method attribute = attributes.get(i);
				// 包含，说明要么被覆盖，要么是用于覆盖的
				if (aliases.contains(attribute)) {
					size++;
					// size > 1 当前注解中的属性方法在同名列表中出现两次，说明有两个是互为别名的
					if (size > 1) {
						// 为空才创建，只创建一次。所有其它别名使用同一个MirrorSet对象，
						if (mirrorSet == null) {
							mirrorSet = new MirrorSet();
							// 把上一个也标注了，assigned就是互为别名的属性集合的索引
							this.assigned[last] = mirrorSet;
						}
						// 创建 mirrorSet 表示，从属性方法的出第一个外的索引开始放mirrorSet。这里像是位图
						this.assigned[i] = mirrorSet;
					}
					// i 代表了几个属性方法，属性方法位置的索引
					last = i;
				}
			}
			// 上面的for循环检查了当前属性，在当前注解中是否有互为别名的情况，自己和其它都指向同一个mirrorSet
			if (mirrorSet != null) {
				mirrorSet.update();
				// 去重完就还剩一个
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

		/**
		 * 得到，当前注解中的所有属性方法的位图表示，脚标代表方法索引，经过@AliasFor解析后内部存实际需要获取值的方法
		 *
		 * @param source         被注解标注的注解，和source属性是同一个意思
		 * @param annotation     当前注解，这个有值，说明当前注解是解析别的注解得到的
		 * @param valueExtractor 获取注解的方法，就是反射的拉姆达表达式
		 * @return 脚标代表方法，经过@AliasFor解析后内部存实际需要获取值的方法
		 */
		int[] resolve(@Nullable Object source, @Nullable Object annotation, ValueExtractor valueExtractor) {
			int[] result = new int[attributes.size()];
			for (int i = 0; i < result.length; i++) {
				// 默认每个方法一开始都是把自己作为目标方法
				result[i] = i;
			}
			// 这里的size()表示当前注解有几对同名属性，从set集合中取，有一个说明一个覆盖情况
			for (int i = 0; i < size(); i++) {
				MirrorSet mirrorSet = get(i);
				// 得到 当前同名属性信息
				int resolved = mirrorSet.resolve(source, annotation, valueExtractor);
				for (int j = 0; j < mirrorSet.size; j++) {
					// 位图法，同名属性的每个方法，脚标代表方法索引，内部值代表要实际取值解析的目标方法
					// result[0]的值为1，说明第0个方法取实际值要用第1个方法获取
					result[mirrorSet.indexes[j]] = resolved;
				}
			}
			return result;
		}


		// 对于一个属性方法来说，自身互为别名的情况，有几个互为别名，分别是那几个
		/**
		 * A single set of mirror attributes.
		 */
		class MirrorSet {

			/**
			 * 当前注解中，同名属性有几个
			 */
			private int size;

			/**
			 * 属性方法索引的列表，从0开始一个一个的放，内部值为 同名属性方法 的索引
			 * 如果第4和第5的方法同名，这里就是 indexes[0]为4，indexes[1]为5
			 */
			private final int[] indexes = new int[attributes.size()];

			/**
			 * 给上面俩属性赋值，更新数据用的
			 */
			void update() {
				this.size = 0;
				Arrays.fill(this.indexes, -1);
				// 遍历，存在assigned被当前变量赋值，说明是同一个属性的别名。i代表属性方法
				for (int i = 0; i < MirrorSets.this.assigned.length; i++) {
					if (MirrorSets.this.assigned[i] == this) {
						// 从1开始一个一个的放，内部存属性方法 的索引
						this.indexes[this.size] = i;
						this.size++;
					}
				}
			}

			/**
			 * 解析并判断当前注解中 互为别名的属性。找出最后一个有值的方法属性。
			 * 如果存在多个有值且不同，则报错。如果都是默认值或者为空 也是返回最后一个方法属性的索引。
			 *
			 * 确定同注解互为别名，用哪一个属性方法作为取值方法
			 *
			 * @param source         被注解标注的注解，和source属性是同一个意思，只是用做提示，没啥用
			 * @param annotation     当前注解实例
			 * @param valueExtractor 回调对象，用于获取属性方法的值
			 * @param <A>            注解的类型
			 * @return 解析注解得到 当前注解内部 最终用于获取 互为别名的 属性值的属性方法的索引
			 */
			<A> int resolve(@Nullable Object source, @Nullable A annotation, ValueExtractor valueExtractor) {
				// 如果存在一个非空值，则会变成非-1
				int result = -1;
				// 上一个不是默认值的值
				Object lastValue = null;
				// 遍历等同的方法属性，一定会遍历完
				for (int i = 0; i < this.size; i++) {
					Method attribute = attributes.get(this.indexes[i]);
					// 拿到当前属性方法的值
					Object value = valueExtractor.extract(attribute, annotation);
					// 校验反射拿到的值和默认值是一样的不
					boolean isDefaultValue = (value == null ||
							isEquivalentToDefaultValue(attribute, value, valueExtractor));
					// 为空，或者等于默认值，说明当前没有赋值，或者和其它的值相等，可以不用考虑。
					if (isDefaultValue || ObjectUtils.nullSafeEquals(lastValue, value)) {
						if (result == -1) {
							// 为空，或者等于默认值，这个方法可以暂时作为一个选项，或者赋值的结果和上一个一样
							result = this.indexes[i];
						}
						continue;
					}
					// 同一个注解的多个同名属性，有值且不同则报错
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
