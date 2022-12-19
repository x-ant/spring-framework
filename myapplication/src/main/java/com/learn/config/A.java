package com.learn.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * .
 * create by 2022-12-19
 *
 * @author XHQ
 */
@Component
public class A {

	@Autowired
	B b;
}
