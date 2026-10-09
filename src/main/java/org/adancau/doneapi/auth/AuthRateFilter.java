package org.adancau.doneapi.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.adancau.doneapi.common.ApiErrors;
import org.adancau.doneapi.security.RequestLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Runs before bearer decoding, database access and BCrypt, in BOTH security chains. */
@Component
public class AuthRateFilter extends OncePerRequestFilter {
  private final RequestLimits rates;
  private final JsonMapper json;
  private final boolean enabled;
  private final int perIp, global, authLimit, registrationLimit, recoveryLimit, writeLimit, capacity;
  private final Semaphore concurrent;
  private final Semaphore expensive;

  public AuthRateFilter(RequestLimits rates, JsonMapper json,
      @Value("${app.rate.enabled:true}") boolean enabled,
      @Value("${app.rate.ip-per-minute:240}") int perIp,
      @Value("${app.rate.global-per-minute:3000}") int global,
      @Value("${app.rate.auth-per-five-minutes:40}") int authLimit,
      @Value("${app.rate.registration-per-five-minutes:5}") int registrationLimit,
      @Value("${app.rate.recovery-per-five-minutes:10}") int recoveryLimit,
      @Value("${app.rate.writes-per-minute:60}") int writeLimit,
      @Value("${app.rate.capacity:20000}") int capacity,
      @Value("${app.rate.concurrent-requests:48}") int concurrent,
      @Value("${app.rate.concurrent-auth:4}") int expensive) {
    this.rates=rates;this.json=json;this.enabled=enabled;
    this.perIp=perIp;this.global=global;this.authLimit=authLimit;
    this.registrationLimit=registrationLimit;this.recoveryLimit=recoveryLimit;
    this.writeLimit=writeLimit;this.capacity=capacity;
    if (java.util.stream.IntStream.of(perIp,global,authLimit,registrationLimit,recoveryLimit,writeLimit,capacity,concurrent,expensive).anyMatch(v -> v<1))
      throw new IllegalStateException("Request limits must be positive.");
    this.concurrent=new Semaphore(concurrent);this.expensive=new Semaphore(expensive);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    res.setHeader("Cache-Control", "no-store");
    String path=req.getServletPath(), ip=req.getRemoteAddr();
    boolean auth=path.startsWith("/api/v1/auth/") || path.equals("/api/v1/me/password") || (path.equals("/api/v1/me") && req.getMethod().equals("DELETE"));
    boolean write=!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD");
    if (req.getContentLengthLong()>65536) { reject(res,413,"request_too_large",0);return; }
    String authorization=req.getHeader("Authorization");
    if (authorization!=null && authorization.length()>8192) { reject(res,400,"invalid_request",0);return; }
    if (enabled) {
      if (!rates.consume("global",global,60,capacity) || !rates.consume("ip:"+ip,perIp,60,capacity)) {
        reject(res,429,"rate_limited",60);return;
      }
      String category=path.equals("/api/v1/auth/register") ? "register" :
          path.equals("/api/v1/auth/forgot-password") || path.equals("/api/v1/auth/reset-password") ? "recovery" : "auth";
      int limit=category.equals("register") ? registrationLimit : category.equals("recovery") ? recoveryLimit : authLimit;
      if (auth && !rates.consume(category+":"+ip,limit,300,capacity)) { reject(res,429,"rate_limited",300);return; }
      if (write && !rates.consume("write:"+ip,writeLimit,60,capacity)) { reject(res,429,"rate_limited",60);return; }
    }
    if (!concurrent.tryAcquire()) { reject(res,503,"server_busy",1);return; }
    boolean acquired=false;
    try {
      if (auth) {
        acquired=expensive.tryAcquire();
        if (!acquired) { reject(res,503,"server_busy",1);return; }
      }
      byte[] body=req.getInputStream().readNBytes(65537);
      if (body.length>65536) { reject(res,413,"request_too_large",0);return; }
      chain.doFilter(new BodyRequest(req,body),res);
    } finally {
      if (acquired) expensive.release();
      concurrent.release();
    }
  }

  private void reject(HttpServletResponse res, int status, String code, int retry) throws IOException {
    res.setStatus(status);res.setCharacterEncoding("UTF-8");res.setContentType("application/problem+json");
    if (retry>0) res.setHeader("Retry-After",Integer.toString(retry));
    res.getWriter().write(json.writeValueAsString(ApiErrors.problem(HttpStatus.valueOf(status),code,
        status==429 ? "Too many requests. Please try again later." : status==503 ? "Service busy. Please retry." : "Invalid or oversized request.")));
  }

  private static class BodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var stream = new java.io.ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return stream.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return stream.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return stream.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous request body");
        }
      };
    }

    @Override
    public java.io.BufferedReader getReader() {
      return new java.io.BufferedReader(
          new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
