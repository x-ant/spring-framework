package com.learn.dao;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * .
 * create by 2022-07-26
 *
 * @author XHQ
 */
public interface PaymentDao {

	@Select("select * from payment")
	List<Map<String, Object>> list();
}
