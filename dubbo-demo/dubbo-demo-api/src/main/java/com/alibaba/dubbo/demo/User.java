package com.alibaba.dubbo.demo;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * @author peng.li
 * @Description: TODO
 * @date 2020/7/14 20:03
 */
public class User implements Serializable{

    private static final long serialVersionUID = -3177815185954294708L;

    @NotNull
    private Integer id;

    @NotNull
    private String name;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
