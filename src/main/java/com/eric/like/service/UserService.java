package com.eric.like.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eric.like.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService extends IService<User> {

    User getLoginUser(HttpServletRequest request);
}
