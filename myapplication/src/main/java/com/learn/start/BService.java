package com.learn.start;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * .
 * create by 2022-05-04
 *
 * @author XHQ
 */
@Service
public class BService {

	@Autowired
	private AService aService;
}
