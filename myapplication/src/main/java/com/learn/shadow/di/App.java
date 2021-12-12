package com.learn.shadow.di;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * .
 * create by 2021-12-08
 *
 * @author XHQ
 */
//@Configuration
//@ComponentScan("com.learn.shadow.di")
@ImportResource("classpath:com/learn/shadow/di/spring.xml")
public class App {
}
