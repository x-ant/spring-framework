package com.learn.shadow.di;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
/**
 * .
 * create by 2021-12-08
 *
 * @author XHQ
 */
public class DiTest {

	@Test
	public void testDi() {
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();

		ac.register(App.class);
		ac.refresh();
		ac.getBean(A.class).printXAndY();

	}
}
