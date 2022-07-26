package com.learn.service;

import com.learn.dao.PaymentDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * .
 * create by 2022-07-26
 *
 * @author XHQ
 */
@Service
public class PaymentService {

	@Autowired
	private PaymentDao paymentDao;

	public List<Map<String, Object>> list() {
		return paymentDao.list();
	}
}
