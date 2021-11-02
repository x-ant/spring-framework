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
	public User user(){
		return new User("testId", "testName");
	}
}
