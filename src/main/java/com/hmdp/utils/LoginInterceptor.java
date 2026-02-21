package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {
    /**
     * 校验登录状态
     *
     * @param request  current HTTP request
     * @param response current HTTP response
     * @param handler  chosen handler to execute, for type and/or instance evaluation
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 从ThreadLocal中获取用户
        UserDTO userDTO = UserHolder.getUser();

        // 判断User是否存在
        if (userDTO == null) {
            // 不存在则返回状态码: 401
            response.setStatus(401);
            // 拦截
            return false;
        }

        // 放行
        return true;
    }
}
