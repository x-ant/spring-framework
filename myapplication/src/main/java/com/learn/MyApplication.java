package com.learn;

import com.learn.config.MybatisConfig;
import com.learn.service.PaymentService;
import com.learn.start.AService;
import com.learn.start.UserConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * .
 * create by 2021-11-02
 *
 * @author XHQ
 */
@Slf4j
public class MyApplication {

	/**
	 * 简单生命周期
	 * 1、实例化spring容器
	 * 2、扫描符合spring bean规则的class -- 集合
	 * 3、遍历这个集合当中的类--封装成为一个bd对象
	 * 4、遍历beanDefinitionMap -- bd对象
	 * 5、解析-- validate
	 * 6、通过 -- bd -- class
	 * 7、得到所有构造方法--通过算法推断出一个合理的构造函数
	 * 8、通过这个合理的构造方法反射实例化一个对象
	 * 9、合并bd
	 * 10、提前暴露工厂--为了循环依赖
	 * 11、注入属性--判断是否需要完成属性填充：自动注入
	 * 12、执行部分aware接口
	 * 13、执行部分aware接口 Lifecycle callback anno
	 * 14、lifecycle callback interface
	 * 15、aop 事件
	 * 16、put singletonObjects
	 */
	public static void main(String[] args) {
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(MybatisConfig.class);
		PaymentService bean = ac.getBean(PaymentService.class);
		log.info("{}", bean.list());
	}
}
