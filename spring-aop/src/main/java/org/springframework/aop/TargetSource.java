/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop;

import org.springframework.lang.Nullable;

/**
 * A {@code TargetSource} is used to obtain the current "target" of
 * an AOP invocation, which will be invoked via reflection if no around
 * advice chooses to end the interceptor chain itself.
 *
 * <p>If a {@code TargetSource} is "static", it will always return
 * the same target, allowing optimizations in the AOP framework. Dynamic
 * target sources can support pooling, hot swapping, etc.
 *
 * <p>Application developers don't usually need to work with
 * {@code TargetSources} directly: this is an AOP framework interface.
 *
 * TargetSource（目标源）是被代理的target（目标对象）实例的来源。
 * TargetSource被用于获取当前MethodInvocation（方法调用）所需要的target（目标对象），
 * 这个target通过反射的方式被调用（如：method.invode(target,args)）。
 * 换句话说，proxy（代理对象）代理的不是target，而是TargetSource，这点非常重要
 *
 * 通常情况下，一个proxy（代理对象）只能代理一个target，每次方法调用的目标也是唯一固定的target。
 * 但是，如果让proxy代理TargetSource，可以使得每次方法调用的target实例都不同（当然也可以相同，这取决于TargetSource实现）。
 * 这种机制使得方法调用变得灵活，可以扩展出很多高级功能，如：target pool（目标对象池）、hot swap（运行时目标对象热替换），等等。
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 */
public interface TargetSource extends TargetClassAware {

	/**
	 * Return the type of targets returned by this {@link TargetSource}.
	 * <p>Can return {@code null}, although certain usages of a {@code TargetSource}
	 * might just work with a predetermined target class.
	 * @return the type of targets returned by this {@link TargetSource}
	 *
	 * 返回当前目标源的目标类型
	 * 可以返回null值，如：EmptyTargetSource（未知类会使用这个目标源）
	 */
	@Override
	@Nullable
	Class<?> getTargetClass();

	/**
	 * Will all calls to {@link #getTarget()} return the same object?
	 * <p>In that case, there will be no need to invoke {@link #releaseTarget(Object)},
	 * and the AOP framework can cache the return value of {@link #getTarget()}.
	 * @return {@code true} if the target is immutable
	 * @see #getTarget
	 *
	 * 当前目标源是否是静态的。
	 * 如果为false，则每次方法调用结束后会调用releaseTarget()释放目标对象.
	 * 如果为true，则目标对象不可变，也就没必要释放了。
	 */
	boolean isStatic();

	/**
	 * Return a target instance. Invoked immediately before the
	 * AOP framework calls the "target" of an AOP method invocation.
	 * @return the target object which contains the joinpoint,
	 * or {@code null} if there is no actual target instance
	 * @throws Exception if the target object can't be resolved
	 *
	 * 获取一个目标对象。
	 * 在每次MethodInvocation方法调用执行之前获取。
	 */
	@Nullable
	Object getTarget() throws Exception;

	/**
	 * Release the given target object obtained from the
	 * {@link #getTarget()} method, if any.
	 * @param target object obtained from a call to {@link #getTarget()}
	 * @throws Exception if the object can't be released
	 *
	 * 释放指定的目标对象。
	 */
	void releaseTarget(Object target) throws Exception;

}
