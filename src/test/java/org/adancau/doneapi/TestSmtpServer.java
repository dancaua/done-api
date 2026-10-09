package org.adancau.doneapi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/** Actual loopback SMTP transport; no external email and no mock of JavaMail. */
final class TestSmtpServer implements AutoCloseable {
  private final ServerSocket server;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"test-smtp");t.setDaemon(true);return t;});
  final BlockingQueue<MimeMessage> messages=new LinkedBlockingQueue<>();
  volatile boolean rejectNext;
  TestSmtpServer() {
    try { server=new ServerSocket(0,10,InetAddress.getLoopbackAddress()); }
    catch(IOException e) { throw new UncheckedIOException(e); }
    worker.submit(() -> {
      while(!server.isClosed()) {
        try(var socket=server.accept()) {
          socket.setSoTimeout(5000);
          var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
          var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
          out.print("220 localhost test SMTP\r\n");out.flush();
          String line;
          while((line=in.readLine())!=null) {
            if(line.startsWith("QUIT")){out.print("221 bye\r\n");out.flush();break;}
            if(line.equals("DATA")) {
              if(rejectNext){rejectNext=false;out.print("451 temporary failure\r\n");out.flush();continue;}
              out.print("354 send data\r\n");out.flush();var body=new StringBuilder();
              while((line=in.readLine())!=null&&!line.equals("."))body.append(line.startsWith("..")?line.substring(1):line).append("\r\n");
              messages.add(new MimeMessage(Session.getInstance(new java.util.Properties()),new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8))));
            }
            out.print("250 OK\r\n");out.flush();
          }
        }catch(Exception e){if(!server.isClosed())throw new IllegalStateException(e);}
      }
    });
  }
  int port(){return server.getLocalPort();}
  String takeBody() throws Exception {var mail=messages.poll(5,TimeUnit.SECONDS);org.junit.jupiter.api.Assertions.assertNotNull(mail,"SMTP delivery missing");return mail.getContent().toString();}
  public void close() throws IOException {server.close();worker.shutdownNow();}
}
