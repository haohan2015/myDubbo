package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.demo.User;
import com.alibaba.dubbo.demo.UserService;
import com.alibaba.fastjson.JSON;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * @author peng.li
 * @Description: TODO
 * @date 2020/7/14 20:04
 */
@Path("/users")
public class UserServiceImpl implements UserService{

    @Override
    @POST
    @Path("/register")
    @Consumes({MediaType.APPLICATION_JSON})
    public void registerUser(User user) {
        System.out.println("user = [" + JSON.toJSONString(user) + "]");
    }

    @GET
    @Path("/{id : \\d+}")
    @Produces({MediaType.APPLICATION_JSON})
    @Override
    public User getByUserId(@PathParam("id") Integer id) {
        User user = new User();
        user.setId(id);
        user.setName(String.valueOf(id));
        return user;
    }
}
