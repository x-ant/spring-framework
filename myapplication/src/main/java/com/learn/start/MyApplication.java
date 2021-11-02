package com.learn.start;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * .
 * create by 2021-11-02
 *
 * @author XHQ
 */
public class MyApplication {

	public static void main(String[] args) {
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(UserConfig.class);
		User bean = ac.getBean(User.class);
		System.out.println(bean);
	}
}
