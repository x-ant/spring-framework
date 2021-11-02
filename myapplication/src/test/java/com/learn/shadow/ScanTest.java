package com.learn.shadow;

import com.learn.shadow.config.ShadowConfig;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * .
 * create by 2021-11-02
 *
 * @author XHQ
 */
public class ScanTest {

	@Test
	public void scanTest(){
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();
		ac.register(ShadowConfig.class);
		ac.refresh();
	}
}
