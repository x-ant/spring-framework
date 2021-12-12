package com.learn.shadow.di;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * .
 * create by 2021-12-08
 *
 * @author XHQ
 */
public class A {


	X x;
	Y y;

	public A(X x) {
		this.x = x;
	}

	public void printXAndY(){
		System.out.println(this.x);
		System.out.println(this.y);
	}
}
