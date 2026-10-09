package org.adancau.doneapi;

import static org.junit.jupiter.api.Assertions.*;
import org.adancau.doneapi.security.RequestLimits;
import org.adancau.doneapi.auth.AuthRateFilter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;

class RequestLimitsTests {
  @Test void concurrentRequestsNeverExceedWindowAndCapacityFailsClosed() throws Exception {
    var clock=new BackendE2ETests.MutableClock();var limits=new RequestLimits(clock);var successes=new AtomicInteger();
    try(var pool=Executors.newFixedThreadPool(8)) {
      var futures=new java.util.ArrayList<Future<?>>();
      for(int i=0;i<200;i++)futures.add(pool.submit(()->{if(limits.consume("ip",12,60,2))successes.incrementAndGet();}));
      for(var future:futures)future.get();
    }
    assertEquals(12,successes.get());assertTrue(limits.consume("second",2,60,2));assertFalse(limits.consume("third",2,60,2));
    clock.advance(60);assertTrue(limits.consume("third",2,60,2));assertTrue(limits.consume("ip",12,60,2));
  }
  @Test void concurrentAdmissionShedsLoadAndReleasesPermitsAfterFailure() throws Exception {
    var limits=new RequestLimits(new BackendE2ETests.MutableClock());
    var filter=new AuthRateFilter(limits,JsonMapper.builder().build(),false,100,1000,10,10,10,10,100,1,1);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var pool=Executors.newSingleThreadExecutor()) {
      var waiting=pool.submit(()-> {
        try{filter.doFilter(new MockHttpServletRequest("GET","/"),new MockHttpServletResponse(),(req,res)->{
          entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}
          throw new jakarta.servlet.ServletException("test failure");
        });}catch(Exception expected){}
      });
      assertTrue(entered.await(5,TimeUnit.SECONDS));var overloaded=new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest("GET","/"),overloaded,(req,res)->fail("Must be rejected before processing"));
      assertEquals(503,overloaded.getStatus());release.countDown();waiting.get(5,TimeUnit.SECONDS);
      var admitted=new AtomicInteger();filter.doFilter(new MockHttpServletRequest("GET","/"),new MockHttpServletResponse(),(req,res)->admitted.incrementAndGet());
      assertEquals(1,admitted.get());
    }
  }

  @Test void accountExportBudgetFollowsTheUserAcrossIpAddresses() throws Exception {
    var filter=new org.adancau.doneapi.security.AccountRateFilter(new RequestLimits(new BackendE2ETests.MutableClock()),true);
    var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg","HS256").subject("owner").build();
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
    try {
      for(int i=0;i<3;i++) {
        var request=new MockHttpServletRequest("GET","/api/v1/me/export");request.setServletPath("/api/v1/me/export");request.setRemoteAddr("192.0.2."+i);
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{});
        assertEquals(i<2?200:429,response.getStatus());
      }
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
}
