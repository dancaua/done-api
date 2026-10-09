package org.adancau.doneapi.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.adancau.doneapi.common.ApiErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AuthRateFilter extends OncePerRequestFilter {
  private final RateLimits rates;
  private final JsonMapper json;
  private final boolean enabled;

  public AuthRateFilter(
      RateLimits rates, JsonMapper json, @Value("${app.rate.enabled:true}") boolean enabled) {
    this.rates = rates;
    this.json = json;
    this.enabled = enabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (req.getContentLengthLong() > 65536) {
      res.setStatus(413);
      res.setCharacterEncoding("UTF-8");
      res.setContentType("application/problem+json");
      res.getWriter()
          .write(
              json.writeValueAsString(
                  ApiErrors.problem(
                      HttpStatus.CONTENT_TOO_LARGE,
                      "request_too_large",
                      "Cererea este prea mare.")));
      return;
    }
    String path = req.getRequestURI();
    if (enabled && ((path.startsWith("/api/v1/auth/") && !path.contains("logout")) || path.startsWith("/api/shares"))) {
      int limit = path.endsWith("register") ? 10 : req.getMethod().equals("GET") ? 600 : 120;
      if (!rates.consume("ip:" + req.getRemoteAddr() + ":" + (path.startsWith("/api/shares") ? "shares:"+req.getMethod() : path), limit)) {
        res.setStatus(429);
        res.setHeader("Retry-After", "300");
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/problem+json");
        res.getWriter()
            .write(
                json.writeValueAsString(
                    ApiErrors.problem(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate_limited",
                        "Prea multe încercări. Așteaptă cinci minute.")));
        return;
      }
    }
    byte[] body = req.getInputStream().readNBytes(65537);
    if (body.length > 65536) {
      res.setStatus(413);
      res.setCharacterEncoding("UTF-8");
      res.setContentType("application/problem+json");
      res.getWriter()
          .write(
              json.writeValueAsString(
                  ApiErrors.problem(
                      HttpStatus.CONTENT_TOO_LARGE,
                      "request_too_large",
                      "Cererea este prea mare.")));
      return;
    }
    chain.doFilter(new BodyRequest(req, body), res);
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
