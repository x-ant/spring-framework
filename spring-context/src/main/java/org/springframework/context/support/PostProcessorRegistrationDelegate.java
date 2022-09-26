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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}

	/**
	 * 执行BeanFactoryPostProcessors的所有实现类
	 * 所有实现类{
	 *     1、spring内置的---在这个方法之前它就被封装成了bd，并且put到了bdmap中
	 *     		这种内置的如果没有被封装成bd--没有put到bdmap，就不会变成bean，就没法执行
	 *     2、程序员提供
	 *     	    实现了Ordered接口的类(优先执行)
	 *     		通过扫描出来，比如写了@Component（bd bdmap bean 再执行一次，这个再次的意思是相对于内置的bean）
	 *     		通过api提供（直接调用这个方法然后传入list）或者ac.addBeanFactoryPostProcessor()
	 * }
	 * 所有实现类的层级关系{
	 *     1、直接实现了BeanFactoryPostProcessors
	 *     2、BeanFactoryPostProcessors的子类（比如BeanDefinitionRegistryPostProcessor子接口）
	 *     上面两种，从代码看，先执行了子类，即第二种BeanDefinitionRegistryPostProcessor
	 *     如果想要在扫描之前加一些扩展的话，可以从这下手
	 * }
	 *
	 * BeanFactoryPostProcessor这个集合一般情况下等于null
	 * 只有程序员通过spring容器对象 手动添加的BeanFactoryPostProcessor对象这个才不为空
	 * 这种需要自己调用一遍完整流程，步骤为：
	 * AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();
	 * ac.register(App.class);
	 * ac.addBeanFactoryPostProcessor(new XxxBeanFactoryPostProcessor());
	 * ac.refresh();
	 *
	 * 在这个方法之前，spring内置的会放到bdmap中，然后通过bdmap newInstance，然后执行，
	 * 也就是说内置的一定在这个方法之前就存在于bdmap
	 * 但是自定义的bean在这个方法前没有，最终也可以执行，因为内置的bean执行时会完成扫描，
	 * 扫描的动作就是这些内置的bean完成的，所以需要先把内置bean实例化，然后把自定义的bean，扫描、放入bdmap、执行PostProcessors等
	 *
	 * 整个流程中始终保证了BeanDefinitionRegistryPostProcessor的执行优先于BeanFactoryPostProcessor，
	 * 一般的集成框架实现BeanDefinitionRegistryPostProcessor都是为了完成自定义的扫描
	 *
	 * 要完成这些后置事件的调用，首先肯定要完成实例化
	 *
	 * @param beanFactory bean工厂
	 * @param beanFactoryPostProcessors 工厂生产完bean的后置处理
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		// 存储已经处理完成的BeanFactoryPostProcessor的名字，每一步都可以作为下一次的判断，
		// 内置、程序员提供、Ordered接口的，就是方法注释中的三种
		Set<String> processedBeans = new HashSet<>();

		// ConfigurableListableBeanFactory这个参数类型确实实现了BeanDefinitionRegistry
		// 正常情况下是一定成立的
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			// 存储已经处理过的直接BeanFactoryPostProcessor的实现的java对象
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			// 存储已经处理过BeanDefinitionRegistryPostProcessor的实现类的java对象
			// 和processedBeans差不多，一个存名字一个存对象
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			// BeanFactoryPostProcessor这个集合一般情况下等于null
			// 只有程序员通过spring容器对象 手动添加的BeanFactoryPostProcessor对象这个才不为空
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					// 如我们自己implements BeanDefinitionRegistryPostProcessor并且没有加@Component
					// 而是我们自己手动添加的ApplicationContext.addBeanFactoryPostProcessor(new TestBeanDefinitionRegistryPostProcessor());
					// 那么在下面这句话就会执行重写的方法;
					// 这里也保证了BeanDefinitionRegistryPostProcessor的执行优先于BeanFactoryPostProcessor
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					// 这里先缓存而不是先执行，就是为了保证BeanDefinitionRegistryPostProcessor的执行优先于BeanFactoryPostProcessor
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			// 存储的是当前需要执行的BeanDefinitionRegistryPostProcessor的实现类的对象
			// 每次执行完都会清除，防止重复执行
			// BeanDefinitionRegistryPostProcessor是BeanFactoryPostProcessor的子接口，只是多一个方法定义
			// 所以可以认为BeanDefinitionRegistryPostProcessor是BeanFactoryPostProcessor的一种
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			// spring内置的
			// 从bdmap中获取BeanDefinition的name
			// 从bdmap 获取BeanDefinition的beanClass类型为BeanDefinitionRegistryPostProcessor
			// 执行spring内置的BeanDefinitionRegistryPostProcessor
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// 参数中的getBean会放入单例池，工厂才有放入单例池这一说，所以实现类不会是xxxContext，这里是AbstractBeanFactory
					// 只有在bdmap中才会被实例化
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					// 标记这里已经处理过了，这里处理的是spring内置的，下面可以判断出余下的就是程序员提供的了
					processedBeans.add(ppName);
				}
			}

			// region ========== 排序，汇总、调用、清除 ==========
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			// 调用BeanDefinitionRegistryPostProcessors的方法，子接口自己的方法，
			// 这一步调用了内置类ConfigurationClassPostProcessor的postProcessBeanDefinitionRegistry 完
			// 成了bean的扫描
			// BeanDefinitionRegistryPostProcessor的执行优先于BeanFactoryPostProcessor
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			currentRegistryProcessors.clear();
			// endregion

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			// 程序员提供
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				// 第一步已经标记过了，所以processedBeans中是内置的，如果不是内置的说明是程序员提供的
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}

			// region ========== 排序，汇总、调用、清除 ==========
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			currentRegistryProcessors.clear();
			// endregion

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				// 其余的BeanDefinitionRegistryPostProcessor的类
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}

				// region ========== 排序，汇总、调用、清除 ==========
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				currentRegistryProcessors.clear();
				// endregion
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			// 上面代码先执行了BeanFactoryPostProcessor的子接口BeanDefinitionRegistryPostProcessor的所有实现类
			// 其中最先执行了通过api直接注册的BeanDefinitionRegistryPostProcessor
			// 然后是spring内置的 比如 ConfigurationClassPostProcessor
			// 接着执行ConfigurationClassPostProcessor扫描出来的BeanDefinitionRegistryPostProcessor实现类，
			// 这些实现类中最先执行Ordered接口的
			// 最后是没有实现Ordered接口的
			// BeanDefinitionRegistryPostProcessor全部调用完成才会执行下面的BeanFactoryPostProcessor的实现类
			// 注意BeanDefinitionRegistryPostProcessor是BeanFactoryPostProcessor的子接口
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			// regularPostProcessors是程序员通过api传递的，直接实现BeanFactoryPostProcessor的实现类
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		// 拿到所有的BeanFactoryPostProcessor的名字,包括内置的和程序员提供(xml，注解，api)的，包括BeanDefinitionRegistryPostProcessor
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 执行其余的BeanFactoryPostProcessor，即没有实现BeanDefinitionRegistryPostProcessor，
		// 也不是addBeanFactoryPostProcessor添加的，所以最终本方法的第二个参数传递的beanFactoryPostProcessors
		// 始终在同类型中优先执行
		// 存储实现了PriorityOrdered的BeanFactoryPostProcessor接口的java对象
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		// 存储实现了Ordered的BeanFactoryPostProcessor接口bean的名字
		List<String> orderedPostProcessorNames = new ArrayList<>();
		// 存储没有实现PriorityOrdered和Ordered的BeanFactoryPostProcessor接口bean的名字
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		// 除了第一个是存java对象剩下两个是名字，为了保证不同类型实例化的顺序，下面三个for循环每次实例化一种
		for (String ppName : postProcessorNames) {
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		// 这里调用清理缓存，里面的clearByTypeCache();方法对应的缓存应该还没有值，这里应该只是为了清理bd缓存。
		// 只有冻结才会缓存，冻结发生在之后的实例化单例bean的方法中
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		for (BeanPostProcessor postProcessor : postProcessors) {
			beanFactory.addBeanPostProcessor(postProcessor);
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
