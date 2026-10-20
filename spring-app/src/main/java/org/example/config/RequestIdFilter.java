package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 为请求处理线程绑定服务端生成的 Request ID，并在处理结束后恢复 MDC。 */
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-ID";
    public static final String MDC_KEY = "requestId";
    public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 仅首次进入时生成 ID；后续 ERROR/ASYNC 分派复用请求属性，不读取客户端 Header。
        String requestId = (String) request.getAttribute(REQUEST_ATTRIBUTE);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        }
        // 处理链执行前返回 ID；响应已提交时无法再补写或修改 Header。
        if (!response.isCommitted()) {
            response.setHeader(HEADER_NAME, requestId);
        }
        String previous = MDC.get(MDC_KEY);
        try {
            MDC.put(MDC_KEY, requestId);
            filterChain.doFilter(request, response);
        } finally {
            // 保留进入前的调用上下文，同时避免当前请求 ID 在线程复用时残留。
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // 为错误分派重新绑定同一 ID，覆盖容器在原请求链结束后的错误处理。
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 仅支持 Servlet 异步再分派，不会自动传播 MDC 到任意异步任务线程。
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 嵌套 ERROR 可能切换回原始响应；复用 ID，并重新设置尚未提交的响应头。
        doFilterInternal(request, response, filterChain);
    }
}
