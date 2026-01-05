package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器
 * 用于拦截未登录用户的请求，确保只有已登录用户才能访问受保护的资源
 */
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 请求预处理方法
     * 在请求处理之前进行拦截检查，验证用户是否已登录
     *
     * @param request  HTTP请求对象，用于获取请求相关信息
     * @param response HTTP响应对象，用于设置响应状态码
     * @param handler  被执行的处理器对象
     * @return true表示放行请求，false表示拦截请求
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;
    }
}
