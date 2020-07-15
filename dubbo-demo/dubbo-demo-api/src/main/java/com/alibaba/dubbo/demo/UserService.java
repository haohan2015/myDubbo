package com.alibaba.dubbo.demo;

/**
 * @author peng.li
 * @Description: TODO
 * @date 2020/7/14 20:02
 */
public interface UserService {

    void registerUser(User user);

    User getByUserId(Integer id);
}
