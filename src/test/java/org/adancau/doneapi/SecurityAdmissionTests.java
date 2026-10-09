package org.adancau.doneapi;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "app.jobs.enabled=false","app.rate.enabled=true","app.rate.ip-per-minute=6",
  "app.rate.auth-per-five-minutes=2","app.rate.registration-per-five-minutes=2","app.rate.recovery-per-five-minutes=2",
  "app.auth.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWY=","logging.level.root=WARN"})
@Import(BackendE2ETests.TestTime.class)
class SecurityAdmissionTests {
  @DynamicPropertySource static void database(DynamicPropertyRegistry p){ BackendE2ETests.database(p); }
  @Autowired BackendE2ETests.MutableClock clock;
  @LocalServerPort int port;
  final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  @BeforeEach void nextWindow(){clock.advance(86400);}
  HttpResponse<String> request(String method,String path,String body,String spoof) throws Exception {
    var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
    if(spoof!=null)req.header("X-Forwarded-For",spoof).header("Forwarded","for="+spoof);
    if(body!=null)req.header("Content-Type","application/json");
    return client.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
  @Test void ipLimitCannotBeBypassedBySpoofedForwardedHeaders() throws Exception {
    for(int i=0;i<6;i++)assertEquals(200,request("GET","/actuator/health",null,"192.0.2."+i).statusCode());
    var denied=request("GET","/actuator/health",null,"198.51.100.1");assertEquals(429,denied.statusCode());
    assertEquals("60",denied.headers().firstValue("Retry-After").orElseThrow());
    assertTrue(denied.body().contains("rate_limited"));assertTrue(denied.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
    clock.advance(60);assertEquals(200,request("GET","/actuator/health",null,null).statusCode());
  }
  @Test void authRequestsAreLimitedBeforeDtoValidationOrPasswordHashing() throws Exception {
    for(int i=0;i<2;i++)assertEquals(400,request("POST","/api/v1/auth/login","{}",null).statusCode());
    var response=request("POST","/api/v1/auth/login","{}",null);assertEquals(429,response.statusCode());
    assertEquals("300",response.headers().firstValue("Retry-After").orElseThrow());
    assertEquals(200,request("GET","/actuator/health",null,null).statusCode());
  }
  @Test void recoveryLimitAndSharingChainAreAlsoProtected() throws Exception {
    for(int i=0;i<2;i++)assertEquals(400,request("POST","/api/v1/auth/forgot-password","{}",null).statusCode());
    assertEquals(429,request("POST","/api/v1/auth/reset-password","{}",null).statusCode());
    for(int i=0;i<3;i++)assertTrue(request("GET","/api/shares/invalid",null,null).statusCode()>=400);
    assertEquals(429,request("GET","/api/shares/invalid",null,null).statusCode());
  }
  @Test void firewallRejectsEncodedTraversalAndTrace() throws Exception {
    assertEquals(405,request("TRACE","/api/v1/me",null,null).statusCode());
    assertEquals(400,request("GET","/site/%2e%2e/application.properties",null,null).statusCode());
  }
}
