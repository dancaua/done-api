package org.adancau.doneapi.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared across IPs within an instance; persistent account login/reset limits live in RateLimits. */
@Component
public class AccountRateFilter extends OncePerRequestFilter {
  private final RequestLimits limits;
  private final boolean enabled;
  private final java.util.concurrent.Semaphore exports=new java.util.concurrent.Semaphore(1);
  public AccountRateFilter(RequestLimits limits,@Value("${app.rate.enabled:true}") boolean enabled) {
    this.limits=limits;this.enabled=enabled;
  }
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
    var auth=SecurityContextHolder.getContext().getAuthentication();
    if (enabled && auth instanceof JwtAuthenticationToken jwt &&
        !limits.consume("user:"+jwt.getToken().getSubject(),180,60,20000)) {
      res.setStatus(429);res.setHeader("Retry-After","60");res.setContentType("application/problem+json");
      res.getWriter().write("{\"status\":429,\"code\":\"rate_limited\",\"detail\":\"Too many requests. Please try again later.\"}");return;
    }
    boolean export=req.getServletPath().equals("/api/v1/me/export") && auth instanceof JwtAuthenticationToken;
    if(export && enabled && !limits.consume("export:"+auth.getName(),2,300,20000)) {
      res.setStatus(429);res.setHeader("Retry-After","300");res.setContentType("application/problem+json");
      res.getWriter().write("{\"status\":429,\"code\":\"rate_limited\",\"detail\":\"Please wait before exporting again.\"}");return;
    }
    if(export && !exports.tryAcquire()) {
      res.setStatus(503);res.setHeader("Retry-After","5");res.setContentType("application/problem+json");
      res.getWriter().write("{\"status\":503,\"code\":\"server_busy\",\"detail\":\"Please retry the export shortly.\"}");return;
    }
    try { chain.doFilter(req,res); } finally { if(export)exports.release(); }
  }
}
