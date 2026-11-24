package com.zhixu.kb.auth;

import com.zhixu.kb.common.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires a valid session for every /api route except the public auth endpoints,
 * requires the system admin role under /api/admin, and injects {@link User} into handlers.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor, HandlerMethodArgumentResolver {
    private static final String ATTR = AuthInterceptor.class.getName() + ".user";
    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    public static String token(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies())
            if (AuthService.COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) return true;
        User user = auth.resolve(token(request));
        if (user == null) throw ApiException.unauthorized("请先登录");
        if (request.getRequestURI().startsWith("/api/admin/") && !user.isAdmin())
            throw ApiException.forbidden("需要系统管理员权限");
        request.setAttribute(ATTR, user);
        return true;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == User.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binder) {
        Object user = request.getAttribute(ATTR, NativeWebRequest.SCOPE_REQUEST);
        if (user == null) throw ApiException.unauthorized("请先登录");
        return user;
    }
}
