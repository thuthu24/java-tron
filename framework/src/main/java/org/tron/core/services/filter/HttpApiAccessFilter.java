package org.tron.core.services.filter;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Set;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;

@Component
@Slf4j(topic = "httpApiAccessFilter")
public class HttpApiAccessFilter implements Filter {

  private final Set<HttpMethod> access = Sets.immutableEnumSet(HttpMethod.GET, HttpMethod.POST);

  @Override
  public void init(FilterConfig filterConfig) {
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    try {
      if (request instanceof HttpServletRequest) {
        String endpoint = ((HttpServletRequest) request).getRequestURI();
        HttpServletResponse resp = (HttpServletResponse) response;

        if (isDisabled(endpoint)) {
          resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
          resp.setContentType("application/json; charset=utf-8");
          JSONObject jsonObject = new JSONObject();
          jsonObject.put("Error", "this API is unavailable due to config");
          resp.getWriter().println(jsonObject.toJSONString());
          return;
        }

        if (isForbidden((HttpServletRequest) request)) {
          resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
          resp.setContentType("application/json; charset=utf-8");
          JSONObject jsonObject = new JSONObject();
          jsonObject.put("Error", "only " + access + " allowed");
          resp.getWriter().println(jsonObject.toJSONString());
          return;
        }

        CharResponseWrapper responseWrapper = new CharResponseWrapper(resp);
        chain.doFilter(request, responseWrapper);

      } else {
        chain.doFilter(request, response);
      }

    } catch (Exception e) {
      logger.error("http api access filter exception: {}", e.getMessage());
    }
  }

  @Override
  public void destroy() {

  }

  private boolean isDisabled(String endpoint) {
    boolean disabled = false;

    try {
      List<String> disabledApiList = CommonParameter.getInstance().getDisabledApiList();
      if (!disabledApiList.isEmpty()) {
        disabled = disabledApiList.contains(endpoint.split("/")[2].toLowerCase());
      }
    } catch (Exception e) {
      logger.error("check isDisabled except, endpoint={}, error is {}", endpoint, e.getMessage());
    }

    return disabled;
  }

  private boolean isForbidden(HttpServletRequest request) {
    HttpMethod method =  HttpMethod.fromString(request.getMethod());

    if (method == null) {
      return true;
    }
    return !access.contains(method);
  }

}



