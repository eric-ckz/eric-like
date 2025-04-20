package com.eric.like.controller;


import com.eric.like.common.BaseResponse;
import com.eric.like.common.ResultUtils;
import com.eric.like.constant.UserConstant;
import com.eric.like.model.entity.User;
import com.eric.like.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;//

    /**
     * 登录
     */
    @GetMapping("/login")
    public BaseResponse<User> login(Long userId, HttpServletRequest request) {
        User user = userService.getById(userId);
        request.getSession().setAttribute(UserConstant.LOGIN_USER, user);
        return ResultUtils.success(user);
    }

    /**
     * 获取当前登录用户
     */
    @GetMapping("/get/login")
    public BaseResponse<User> getLoginUser(HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(UserConstant.LOGIN_USER);
        return ResultUtils.success(loginUser);
    }

    @GetMapping("/logout")
    public BaseResponse<Integer> logout(HttpServletRequest request) {
        request.getSession().removeAttribute(UserConstant.LOGIN_USER);
        return ResultUtils.success(1);
    }

}
