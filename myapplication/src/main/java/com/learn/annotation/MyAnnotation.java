package com.learn.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@ContextConfiguration
@Retention(RetentionPolicy.RUNTIME)
public @interface MyAnnotation {

	@AliasFor(annotation = ContextConfiguration.class, attribute = "locations")
	String[] value() default {};

	@AliasFor(annotation = ContextConfiguration.class, attribute = "locations")
	String[] groovyScripts() default {};

	@AliasFor(annotation = ContextConfiguration.class, attribute = "locations")
	String[] xmlFiles() default {};
}