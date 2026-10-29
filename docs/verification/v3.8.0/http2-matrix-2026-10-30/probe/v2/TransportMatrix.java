package arris.probe;

import okhttp3.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.net.ssl.*;

/** Standalone Android shell probe. Serial read-only URL requests; never logs cookie or media URL. */
public class TransportMatrix {
  static final String HOST="https://interface3.music.163.com", PATH="/eapi/song/enhance/player/url/v1";
  static final IdentityHashMap<Connection,Integer> ids=new IdentityHashMap<>();
  static final IdentityHashMap<Connection,Long> births=new IdentityHashMap<>();
  static final IdentityHashMap<Connection,Long> releases=new IdentityHashMap<>();
  static long now(){return android.os.SystemClock.elapsedRealtime();}
  static class Trace extends okhttp3.EventListener {
    final JSONObject row; final long start=now(); final JSONArray attempts=new JSONArray();
    Trace(JSONObject row){this.row=row;}
    void put(String k,Object v){try{row.put(k,v);}catch(Exception ignored){}}
    void mark(String k){put(k,now()-start);}
    @Override public void dnsStart(Call c,String domain){mark("dns_start_ms");}
    @Override public void dnsEnd(Call c,String domain,List<InetAddress> a){mark("dns_end_ms");put("dns_addresses",a.size()); try { JSONArray addresses=new JSONArray(); for(InetAddress x:a)addresses.put(new JSONObject().put("address",x.getHostAddress()).put("family",x instanceof Inet4Address?"IPv4":"IPv6")); put("dns_results",addresses); } catch(Exception ignored) {}}
    @Override public void connectStart(Call c,InetSocketAddress a,Proxy p){mark("connect_start_ms");put("proxy_type",p.type().name()); try{attempts.put(new JSONObject().put("ms",now()-start).put("address",a.toString()).put("proxy",p.type().name()));put("connect_attempts",attempts);}catch(Exception ignored){}}
    @Override public void secureConnectStart(Call c){mark("tls_start_ms");}
    @Override public void secureConnectEnd(Call c,Handshake h){mark("tls_end_ms");put("tls_version",h.tlsVersion().javaName());}
    @Override public void connectEnd(Call c,InetSocketAddress a,Proxy p,Protocol proto){mark("connect_end_ms");put("negotiated",String.valueOf(proto));}
    @Override public void connectFailed(Call c,InetSocketAddress a,Proxy p,Protocol proto,IOException e){mark("connect_failed_ms");put("connect_error",error(e));}
    @Override public void connectionAcquired(Call c,Connection conn){
      long n=now(); boolean seen=ids.containsKey(conn);
      if(!seen){ids.put(conn,ids.size()+1);births.put(conn,n);}
      put("connection_id",ids.get(conn));put("reused",seen);put("connection_age_ms",n-births.get(conn));
      put("idle_since_last_release_ms",releases.containsKey(conn)?n-releases.get(conn):0);
      put("protocol",conn.protocol().toString());mark("acquired_ms");
    }
    @Override public void connectionReleased(Call c,Connection conn){releases.put(conn,now());mark("released_ms");}
    @Override public void requestHeadersStart(Call c){mark("request_headers_start_ms");}
    @Override public void requestHeadersEnd(Call c,Request r){mark("request_headers_end_ms");}
    @Override public void requestBodyEnd(Call c,long n){mark("request_body_end_ms");put("request_bytes",n);}
    @Override public void responseHeadersStart(Call c){mark("response_headers_start_event_ms");}
    @Override public void responseHeadersEnd(Call c,Response r){mark("response_headers_end_ms");put("http_status",r.code());}
    @Override public void responseBodyEnd(Call c,long n){mark("response_body_end_ms");put("response_bytes",n);}
    @Override public void canceled(Call c){mark("cancel_event_ms");}
    @Override public void callFailed(Call c,IOException e){mark("failed_ms");put("error",error(e));}
  }
  static String error(Throwable e){
    String s=e.getClass().getSimpleName()+": "+e.getMessage();
    if(e.getCause()!=null)s+="; cause="+e.getCause().getClass().getSimpleName()+": "+e.getCause().getMessage();
    return s.replaceAll("https?://\\S+","[URL]");
  }
  static void emit(JSONObject o){System.out.println(o.toString());System.out.flush();}
  static String encrypt(JSONObject data)throws Exception{
    String body=data.toString(), path=PATH.replace("/eapi/","/api/");
    byte[] hash=MessageDigest.getInstance("MD5").digest(("nobody"+path+"use"+body+"md5forencrypt").getBytes("UTF-8"));
    StringBuilder md5=new StringBuilder();for(byte b:hash)md5.append(String.format("%02x",b&255));
    Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec("e82ckenh8dichen8".getBytes("UTF-8"),"AES"));
    byte[] enc=cipher.doFinal((path+"-36cd479b6b5-"+body+"-36cd479b6b5-"+md5).getBytes("UTF-8"));
    StringBuilder hex=new StringBuilder();for(byte b:enc)hex.append(String.format("%02x",b&255));return hex.toString();
  }
  static void measure(OkHttpClient client,Request request,String route,String arm,int round)throws Exception{
    JSONObject row=new JSONObject().put("route",route).put("arm",arm).put("round",round).put("wall_utc_ms",System.currentTimeMillis());
    Trace trace=new Trace(row);
    Call call=client.newBuilder().eventListener(trace).build().newCall(request);
    try(Response resp=call.execute()){
      String text=resp.body().string();
      try{
        JSONObject root=new JSONObject(text);row.put("business_code",root.optInt("code",-1));
        JSONObject item=root.optJSONArray("data")==null?null:root.optJSONArray("data").optJSONObject(0);
        row.put("playable",item!=null&&!item.isNull("url")&&!item.optString("url").isEmpty());
        if(item!=null)row.put("item_code",item.optInt("code",-1));
      }catch(Exception ignored){row.put("json_response",false);}
    }catch(Exception e){row.put("error",error(e));}
    row.put("total_ms",now()-trace.start).put("call_cancelled",call.isCanceled()).put("cancel_origin",call.isCanceled()?"call_deadline_no_user_cancel":"none");
    emit(row);
  }
  static void close(OkHttpClient c){c.connectionPool().evictAll();}
  public static void main(String[] args)throws Exception{
    String cookie=Files.readString(Paths.get(args[0])).trim();
    if(cookie.isEmpty())throw new IllegalArgumentException("missing session");
    // Keep ordinary certificate verification; explicitly trust the currently configured capture CA too.
    TrustManagerFactory defaults=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());defaults.init((KeyStore)null);
    KeyStore keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null,null);int i=0;
    for(TrustManager m:defaults.getTrustManagers())if(m instanceof X509TrustManager)
      for(X509Certificate cert:((X509TrustManager)m).getAcceptedIssuers())keys.setCertificateEntry("system"+(i++),cert);
    try(InputStream in=new FileInputStream(args[1])){keys.setCertificateEntry("capture",CertificateFactory.getInstance("X.509").generateCertificate(in));}
    TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tmf.init(keys);
    X509TrustManager tm=(X509TrustManager)tmf.getTrustManagers()[0];SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,new TrustManager[]{tm},null);
    LinkedHashMap<String,String> ck=new LinkedHashMap<>();
    for(String bit:cookie.split(";")){int eq=bit.indexOf('=');if(eq>0)ck.put(bit.substring(0,eq).trim(),bit.substring(eq+1).trim());}
    ck.put("os","pc");ck.put("appver","3.0.6");ck.put("osver","10.0.19045");
    String device=ck.getOrDefault("deviceId","arris-protocol-matrix");ck.putIfAbsent("deviceId",device);
    StringBuilder normalized=new StringBuilder();for(Map.Entry<String,String> en:ck.entrySet()){if(normalized.length()>0)normalized.append("; ");normalized.append(en.getKey()).append('=').append(en.getValue());}
    JSONObject header=new JSONObject().put("os","pc").put("appver","3.0.6").put("osver","10.0.19045").put("deviceId",device).put("requestId","25000123");
    JSONObject payload=new JSONObject().put("ids","[25841325]").put("level","jymaster").put("encodeType","flac").put("header",header.toString());
    Request req=new Request.Builder().url(HOST+PATH).header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
      .header("Referer","https://music.163.com/").header("Cookie",normalized.toString()).post(new FormBody.Builder().add("params",encrypt(payload)).build()).build();
    emit(new JSONObject().put("kind","setup").put("uid",android.os.Process.myUid()).put("sdk",android.os.Build.VERSION.SDK_INT).put("song_id",25841325).put("request_snapshot","fixed_all_arms").put("ping_seconds",15).put("idle_pool_seconds",30).put("deadline_seconds",8).put("concurrency",1));
    boolean ipv4Only=args.length>2&&args[2].equals("ipv4_only");
    OkHttpClient base=new OkHttpClient.Builder().dns(host -> { List<InetAddress> found=Dns.SYSTEM.lookup(host); if(!ipv4Only)return found; List<InetAddress> out=new ArrayList<>(); for(InetAddress x:found)if(x instanceof Inet4Address)out.add(x); if(out.isEmpty())throw new UnknownHostException("no IPv4 addresses"); return out; }).sslSocketFactory(ssl.getSocketFactory(),tm).pingInterval(15,TimeUnit.SECONDS).connectTimeout(30,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build();
    for(String route:new String[]{"direct","capture_proxy"}){
      Proxy proxy=route.equals("direct")?Proxy.NO_PROXY:new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8888));
      OkHttpClient reusable=base.newBuilder().proxy(proxy).connectionPool(new ConnectionPool(5,30,TimeUnit.SECONDS)).build();
      measure(reusable,req,route,"reuse_h2_seed",0);Thread.sleep(2000);
      String[][] orders={{"fresh_h2","reuse_h2","fresh_h1"},{"reuse_h2","fresh_h1","fresh_h2"},{"fresh_h1","fresh_h2","reuse_h2"}};
      for(int round=0;round<3;round++)for(String arm:orders[round]){
        OkHttpClient c=arm.equals("reuse_h2")?reusable:base.newBuilder().proxy(proxy).connectionPool(new ConnectionPool(5,30,TimeUnit.SECONDS))
          .protocols(arm.equals("fresh_h1")?Arrays.asList(Protocol.HTTP_1_1):Arrays.asList(Protocol.HTTP_2,Protocol.HTTP_1_1)).build();
        measure(c,req,route,arm,round+1);if(c!=reusable)close(c);Thread.sleep(2000);
      }
      Thread.sleep(20000);measure(reusable,req,route,"reuse_h2_after20s",4);
      Thread.sleep(35000);measure(reusable,req,route,"reuse_h2_after35s",5);close(reusable);
    }
    base.dispatcher().executorService().shutdown();base.connectionPool().evictAll();
  }
}
