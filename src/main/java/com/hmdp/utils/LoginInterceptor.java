package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {
    /**
     *
     * @param request  current HTTP request
     * @param response current HTTP response
     * @param handler  chosen handler to execute, for type and/or instance evaluation
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 获取Session
        HttpSession session = request.getSession();
        // 获取Session中的User
        Object user = session.getAttribute("user");

        // 判断User是否存在
        if (user == null) {
            // 不存在则返回状态码: 401
            response.setStatus(401);
            // 拦截
            return false;
        }

        // 将用户信息保存至ThreadLocal
        UserHolder.saveUser((UserDTO) user);

        // 放行
        return true;
    }


    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) {
        // 登录验证完毕后, 释放ThreadLocal<UserDTO>
        UserHolder.removeUser();
    }

}
