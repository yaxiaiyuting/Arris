package main

import (
 "io"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
)

type noonTransport func(*http.Request) (*http.Response,error)
func (f noonTransport) RoundTrip(r *http.Request)(*http.Response,error){return f(r)}
func noonReply(r *http.Request,status int,body string)*http.Response {
 return &http.Response{StatusCode:status,Header:make(http.Header),Body:io.NopCloser(strings.NewReader(body)),Request:r}
}
func noonServer()*server{return &server{ncmBase:"https://fixture.invalid",cache:&memCache{items:make(map[string]cacheEntry)}}}

func TestNoonAccountCacheReusesAnotherCookieWithTheSamePrefix(t *testing.T){
 previous:=httpClient;defer func(){httpClient=previous}()
 calls:=0
 httpClient=&http.Client{Transport:noonTransport(func(r *http.Request)(*http.Response,error){
  calls++
  if !strings.Contains(r.Header.Get("Cookie"),"MUSIC_U=fixture-A"){t.Fatal("unexpected upstream credential")}
  return noonReply(r,200,`{"code":200,"account":{"id":101}}`),nil
 })}
 s:=noonServer();routes:=s.routes()
 prefix:="os=pc; appver=3.1.1; channel=pc; deviceId=fixture-common; "
 invoke:=func(ticket string)string{
  req:=httptest.NewRequest("GET","http://local.invalid/api/user/account",nil)
  req.Header.Set("Cookie",prefix+"MUSIC_U="+ticket)
  recorder:=httptest.NewRecorder();routes.ServeHTTP(recorder,req);return recorder.Body.String()
 }
 a:=invoke("fixture-A");b:=invoke("fixture-B")
 if a!=b || !strings.Contains(b,`"id":101`) || calls!=1 {t.Fatalf("unexpected cache outcome calls=%d A=%s B=%s",calls,a,b)}
 t.Log("AUDIT optional Go backend: B receives A's account JSON from the shared 40-character cookie-prefix cache; no TCP or remote account used")
}

func TestNoonUpstreamHttpFailureIsReturnedAsHttpSuccess(t *testing.T){
 previous:=httpClient;defer func(){httpClient=previous}()
 httpClient=&http.Client{Transport:noonTransport(func(r *http.Request)(*http.Response,error){return noonReply(r,503,`{"code":503}`),nil})}
 req:=httptest.NewRequest("GET","http://local.invalid/api/search?keywords=fixture",nil)
 w:=httptest.NewRecorder();noonServer().routes().ServeHTTP(w,req)
 if w.Code!=200 || w.Body.String()!=`{"code":503}`{t.Fatalf("unexpected status=%d body=%s",w.Code,w.Body.String())}
 t.Log("AUDIT optional Go backend: real upstream HTTP 503 fixture is emitted as downstream HTTP 200")
}

func TestNoonBusinessErrorIsCachedAsALyricForTwentyFourHours(t *testing.T){
 previous:=httpClient;defer func(){httpClient=previous}()
 calls:=0
 httpClient=&http.Client{Transport:noonTransport(func(r *http.Request)(*http.Response,error){
  calls++;body:=`{"code":500,"message":"fixture temporary failure"}`
  if calls>1 {body=`{"code":200,"lrc":{"lyric":"fixture healthy"}}`}
  return noonReply(r,200,body),nil
 })}
 s:=noonServer();routes:=s.routes()
 invoke:=func()string{w:=httptest.NewRecorder();routes.ServeHTTP(w,httptest.NewRequest("GET","http://local.invalid/api/lyric?id=1",nil));return w.Body.String()}
 a:=invoke();b:=invoke()
 if calls!=1 || a!=b || !strings.Contains(b,`"code":500`){t.Fatalf("unexpected cached outcome calls=%d body=%s",calls,b)}
 t.Log("AUDIT optional Go backend: HTTP 200 with business code 500 is cached by the real lyric route; healthy transport fixture is never retried during its 24h TTL")
}
