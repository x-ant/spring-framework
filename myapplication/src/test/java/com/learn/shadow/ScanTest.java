package com.learn.shadow;

import com.learn.shadow.bean.Z1;
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

	/**
	 * spring容器在初始化的过程中，会解析配置类然后实例化一个扫描器完成扫描
	 */
	@Test
	public void testScan1(){
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();
		ac.register(ShadowConfig.class);

		ac.refresh();

		for(String beanDefinitionName: ac.getBeanDefinitionNames()){
			System.out.println(beanDefinitionName);
		}
	}

	/**
	 * 和testScan1方法中的扫描器不是同一个
	 */
	@Test
	public void testScan2(){
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();
		ac.scan("com.learn.shadow");
		ac.refresh();

		for(String beanDefinitionName: ac.getBeanDefinitionNames()){
			System.out.println(beanDefinitionName);
		}
	}


	/**
	 * 直接注册而不是扫描
	 */
	@Test
	public void testScan3(){
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();
		ac.register(Z1.class);
		ac.refresh();

		for(String beanDefinitionName: ac.getBeanDefinitionNames()){
			System.out.println(beanDefinitionName);
		}
	}
}
