package com.learn.start;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * .
 * create by 2021-11-02
 *
 * @author XHQ
 */
@ComponentScan
@Configuration
public class UserConfig {

	@Bean
	public User user() {
		System.out.println(this);
		System.out.println("init");
		return new User("testId", "testName");
	}

	/**
	 * 即便这里添加了new User()，实际user也只会实例化一次
	 * 因为这个类添加了@Configuration
	 * 该注解使得该类是一个全配置类。使得getBean(UserConfig.class)获取的是cglib代理后的对象
	 * 该代理子类中user()方法和admin()方法会被重写为，
	 *
	 * @return bean
	 */
	@Bean
	public Admin admin() {
		System.out.println(this);
		user();
		return new Admin();
	}

	/* 被重写成如下格式，在这个子类中33行的new User(),会调用下面这个方法。
	public User user() {
		User bean = beanFactory.getBean(User.class);
		if (bean == null) {
			super.user()

		}
		return bean;
	}

	public Admin admin() {
		// beanFactory是代理类中的属性
		Admin bean = beanFactory.getBean(Admin.class);
		if (bean == null) {
			// 要注意。因为是在子类，即代理类中，
			// admin()中user()的调用实际是子类中重写后的user()，所以多次调用也只会初始化一次user
			super.admin()

		}
		return bean;
	}
	*/
}
