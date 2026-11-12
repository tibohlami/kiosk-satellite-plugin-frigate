// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.frigate.api.WsTlsRelay;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Manages the embedded WebView used both for real-time go2rtc MSE live streams
 * and hls.js VOD playback when ExoPlayer falls back to WebView HLS.
 */
public class FrigateWebStreamController {

    public interface Listener {
        boolean isClosed();
        boolean isLiveStreamMode();
        boolean isHlsPlayback();
        int getStreamSessionId();
        void onLiveMseStarting();
        void onMsePlaying(int sessionId, boolean hasAudio);
        void onMseFps(int sessionId, int fps);
        void onWebAutoMuted(int sessionId);
        void onMseFallback(int sessionId, String reason);
        void onHlsPlaying(int sessionId, int durationMs, boolean hasAudio);
        void onHlsProgress(int sessionId, int currentMs, int durationMs);
        void onHlsFps(int sessionId, int fps);
        void onHlsVideoSize(int sessionId, int width, int height);
        void onHlsEnded(int sessionId);
        void onHlsFallback(int sessionId, String reason);
    }

    private final PluginHost host;
    private final FrigateConfig config;
    private final Handler mainHandler;
    private final Listener listener;

    private WebView liveWebView;
    private volatile WsTlsRelay wsTlsRelay;

    public FrigateWebStreamController(PluginHost host, FrigateConfig config,
                                      Handler mainHandler, Listener listener) {
        this.host = host;
        this.config = config;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WebView createWebView(Context context) {
        liveWebView = new WebView(context);
        liveWebView.setBackgroundColor(Color.BLACK);
        WebSettings ws = liveWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        liveWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri reqUri = request != null ? request.getUrl() : null;
                    String path = reqUri != null ? reqUri.getPath() : null;
                    if (path != null && path.endsWith("/__plugin_assets__/hls.min.js") && host != null) {
                        return new WebResourceResponse("application/javascript", "UTF-8", host.openAsset("hls.min.js"));
                    }
                } catch (Exception e) {
                    android.util.Log.e("FrigatePlugin", "Failed to load hls.min.js asset", e);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (config != null && config.isAllowInsecureSsl()) {
                    handler.proceed();
                } else {
                    handler.cancel();
                }
            }
        });
        liveWebView.addJavascriptInterface(new MseBridge(), "AndroidBridge");
        liveWebView.setVisibility(View.GONE);
        return liveWebView;
    }

    public boolean hasWebView() {
        return liveWebView != null;
    }

    public void startLiveMse(String camera, int sessionId, boolean muted) {
        if (liveWebView == null || listener.isClosed()) return;

        String baseUrl = config.getFrigateUrl();
        applyAuthCookies(baseUrl, config.getAuthToken());

        closeWsRelay();
        String encodedCam = camera;
        try {
            encodedCam = URLEncoder.encode(camera, "UTF-8");
        } catch (Exception ignored) {}
        String wsRelative = "/live/mse/api/ws?src=" + encodedCam;
        String wsUrl = null;
        if (config != null && config.isAllowInsecureSsl() && baseUrl.toLowerCase(Locale.US).startsWith("https://")) {
            try {
                wsTlsRelay = new WsTlsRelay(config, baseUrl);
                wsUrl = wsTlsRelay.getLocalWsUrl(wsRelative);
            } catch (Exception e) {
                closeWsRelay();
                android.util.Log.w("FrigatePlugin", "Failed to start local WS TLS relay, falling back to direct wss", e);
            }
        }
        if (wsUrl == null) {
            String wsBase = baseUrl.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
            wsUrl = wsBase + wsRelative;
        }

        listener.onLiveMseStarting();

        if (host != null) {
            host.log("Frigate: Starting live MSE (video + audio) via " + wsUrl);
        }

        String html = buildMseHtml(wsUrl, muted, sessionId);
        liveWebView.setVisibility(View.VISIBLE);
        liveWebView.loadDataWithBaseURL(baseUrl + "/", html, "text/html", "UTF-8", null);
    }

    public void startHlsVod(String hlsUrl, int sessionId, boolean muted, boolean loop) {
        if (liveWebView == null || listener.isClosed()) return;

        String baseUrl = config.getFrigateUrl();
        String token = config.getAuthToken();
        String authHeader = "";
        if (token != null && !token.trim().isEmpty()) {
            String t = token.trim();
            authHeader = (t.startsWith("Bearer ") || t.startsWith("Basic ")) ? t : ("Bearer " + t);
            applyAuthCookies(baseUrl, t);
        }

        String html = buildHlsVodHtml(hlsUrl, authHeader, muted, loop, sessionId);
        liveWebView.setVisibility(View.VISIBLE);
        liveWebView.loadDataWithBaseURL(baseUrl + "/", html, "text/html", "UTF-8", null);
    }

    public void pauseLiveStream() {
        evalJs("if(window.pauseStream)pauseStream();");
    }

    public void pauseVod() {
        evalJs("if(window.pauseVod)pauseVod();");
    }

    public void playVod() {
        evalJs("if(window.playVod)playVod();");
    }

    public void seekVodMs(int targetMs, boolean resume) {
        evalJs("if(window.seekVodMs)seekVodMs(" + targetMs + "," + resume + ");");
    }

    public void setMuted(boolean muted) {
        evalJs("if (window.setMuted) setMuted(" + muted + ");");
    }

    public void setVodLoop(boolean loop) {
        evalJs("if (window.setVodLoop) setVodLoop(" + loop + ");");
    }

    public void stop() {
        closeWsRelay();
        if (liveWebView != null) {
            try {
                liveWebView.evaluateJavascript("if(window.stopStream)stopStream();", null);
                liveWebView.loadUrl("about:blank");
            } catch (Exception ignored) {}
            liveWebView.setVisibility(View.GONE);
        }
    }

    public void destroy() {
        closeWsRelay();
        if (liveWebView != null) {
            try {
                liveWebView.destroy();
            } catch (Exception ignored) {}
            liveWebView = null;
        }
    }

    private void evalJs(String script) {
        if (liveWebView != null) {
            liveWebView.evaluateJavascript(script, null);
        }
    }

    private void applyAuthCookies(String baseUrl, String token) {
        if (liveWebView == null || token == null) return;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(liveWebView, true);
            cm.setCookie(baseUrl, "frigate_token=" + trimmed + "; Path=/");
        } catch (Exception ignored) {}
    }

    private void closeWsRelay() {
        WsTlsRelay relay = wsTlsRelay;
        wsTlsRelay = null;
        if (relay != null) {
            try {
                relay.close();
            } catch (Exception ignored) {}
        }
    }

    private String buildMseHtml(String wsUrl, boolean muted, int sessionId) {
        String safeWs = wsUrl.replace("\\", "\\\\").replace("'", "\\'");
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>" +
                "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;}" +
                "video{width:100%;height:100%;object-fit:contain;background:#000;opacity:0;}</style></head><body>" +
                "<video id='v' autoplay playsinline poster='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7' " + (muted ? "muted" : "") + "></video>" +
                "<script>" +
                "const WS_URL='" + safeWs + "';const SID=" + sessionId + ";" +
                "const v=document.getElementById('v');let ws=null,ms=null,sb=null,q=[],started=false,stopped=false,hasAudio=false,lastBin=0,waitAt=0,lastFrames=-1,lastFpsTs=0;" +
                "function readFrames(){if(v.getVideoPlaybackQuality){const q=v.getVideoPlaybackQuality();if(q&&typeof q.totalVideoFrames==='number')return q.totalVideoFrames;}return typeof v.webkitDecodedFrameCount==='number'?v.webkitDecodedFrameCount:-1;}" +
                "let fpsTimer=setInterval(()=>{if(stopped||!started||v.paused)return;const f=readFrames();if(f<0)return;const now=Date.now();if(lastFrames>=0&&lastFpsTs>0){const dt=now-lastFpsTs;if(dt>=500){const fps=Math.max(0,Math.round(((f-lastFrames)*1000)/dt));if(window.AndroidBridge&&window.AndroidBridge.onMseFps)window.AndroidBridge.onMseFps(SID,fps);}}lastFrames=f;lastFpsTs=now;},1000);" +
                "function slog(m){if(!stopped&&window.AndroidBridge&&window.AndroidBridge.onStreamLog)window.AndroidBridge.onStreamLog(SID,m);}" +
                "let watchdog=setTimeout(()=>{if(!started&&!stopped&&window.AndroidBridge){window.AndroidBridge.onMseFallback(SID,'timeout');}},4500);" +
                "function start(){" +
                "  if(stopped)return;" +
                "  try{ws=new WebSocket(WS_URL);ws.binaryType='arraybuffer';}catch(e){fail('ws_init');return;}" +
                "  ws.onopen=()=>{" +
                "    const codecs=['avc1.640029','avc1.64002A','avc1.640033','avc1.42E01E','hvc1.1.6.L153.B0','mp4a.40.2','mp4a.40.5','opus','flac'];" +
                "    const ok=codecs.filter(c=>{try{return MediaSource.isTypeSupported('video/mp4; codecs=\"'+c+'\"');}catch(e){return false;}});" +
                "    ws.send(JSON.stringify({type:'mse',value:ok.join(',')}));" +
                "  };" +
                "  ws.onmessage=(ev)=>{" +
                "    if(stopped)return;" +
                "    if(typeof ev.data==='string'){" +
                "      try{const m=JSON.parse(ev.data);" +
                "        if(m.type==='mse'&&m.value){initMs(m.value);}" +
                "        else if(m.type==='error'){fail(m.value||'mse_err');}" +
                "      }catch(e){}" +
                "      return;" +
                "    }" +
                "    const now=Date.now();if(lastBin&&now-lastBin>800)slog('[MSE] ws chunk gap '+(now-lastBin)+'ms q='+q.length);lastBin=now;" +
                "    if(sb&&!sb.updating&&q.length===0){try{sb.appendBuffer(ev.data);}catch(e){q.push(ev.data);}}" +
                "    else{q.push(ev.data);if(q.length>60)q.shift();}" +
                "  };" +
                "  ws.onerror=()=>{if(!started)fail('ws_err');};" +
                "  ws.onclose=()=>{if(!stopped){if(!started)fail('ws_close');else{slog('[MSE] ws closed, reconnecting');setTimeout(()=>{if(!stopped)reconnect();},1500);}}};" +
                "}" +
                "function initMs(mime){" +
                "  if(!('MediaSource'in window)){fail('no_ms');return;}" +
                "  hasAudio=/mp4a|opus|flac/i.test(mime);" +
                "  ms=new MediaSource();v.src=URL.createObjectURL(ms);" +
                "  ms.addEventListener('sourceopen',()=>{" +
                "    try{sb=ms.addSourceBuffer(mime);sb.mode='segments';" +
                "      sb.addEventListener('updateend',()=>{" +
                "        if(stopped)return;" +
                "        if(q.length>0&&!sb.updating){try{sb.appendBuffer(q.shift());}catch(e){}}" +
                "        if(v.buffered.length>0){" +
                "          const end=v.buffered.end(v.buffered.length-1);" +
                "          if(end-v.currentTime>1.2){slog('[MSE] catchup lag='+(end-v.currentTime).toFixed(2)+'s');v.currentTime=end-0.25;}" +
                "          const st=v.buffered.start(0);" +
                "          if(!sb.updating&&v.currentTime-st>15){try{sb.remove(st,v.currentTime-10);}catch(e){}}" +
                "        }" +
                "      });" +
                "      if(q.length>0&&!sb.updating){try{sb.appendBuffer(q.shift());}catch(e){}}" +
                "      const p=v.play();if(p&&p.catch){p.catch(()=>{v.muted=true;v.play();if(window.AndroidBridge)window.AndroidBridge.onAutoMuted(SID);});}" +
                "    }catch(e){fail('sb_err');}" +
                "  });" +
                "}" +
                "v.onwaiting=()=>{if(!stopped&&!waitAt){waitAt=Date.now();slog('[MSE] stall/waiting at '+v.currentTime.toFixed(2)+'s');}};" +
                "v.onplaying=()=>{v.style.opacity='1';if(waitAt){slog('[MSE] resumed after '+(Date.now()-waitAt)+'ms');waitAt=0;}started=true;clearTimeout(watchdog);if(window.AndroidBridge)window.AndroidBridge.onMsePlaying(SID,hasAudio);};" +
                "function fail(r){clearTimeout(watchdog);if(!stopped&&window.AndroidBridge)window.AndroidBridge.onMseFallback(SID,r);}" +
                "function reconnect(){q=[];started=false;lastFrames=-1;lastFpsTs=0;v.style.opacity='0';try{if(ws)ws.close();}catch(e){}start();}" +
                "window.setMuted=(m)=>{v.muted=!!m;};" +
                "window.pauseStream=()=>{stopped=true;clearInterval(fpsTimer);try{v.pause();}catch(e){}try{if(ws)ws.close();}catch(e){}};" +
                "window.stopStream=()=>{stopped=true;clearTimeout(watchdog);clearInterval(fpsTimer);try{v.pause();v.src='';}catch(e){}try{if(ws)ws.close();}catch(e){}};" +
                "start();" +
                "</script></body></html>";
    }

    private String buildHlsVodHtml(String hlsUrl, String authHeader, boolean muted, boolean loop, int sessionId) {
        String safeUrl = hlsUrl.replace("\\", "\\\\").replace("'", "\\'");
        String safeAuth = authHeader != null ? authHeader.replace("\\", "\\\\").replace("'", "\\'") : "";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>" +
                "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;}" +
                "video{width:100%;height:100%;object-fit:contain;background:#000;opacity:0;}</style></head><body>" +
                "<video id='v' autoplay playsinline poster='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7' " + (muted ? "muted" : "") + "></video>" +
                "<script src='/__plugin_assets__/hls.min.js'></script>" +
                "<script>" +
                "const SRC='" + safeUrl + "';const AUTH='" + safeAuth + "';const SID=" + sessionId + ";" +
                "const v=document.getElementById('v');let hls=null,started=false,stopped=false,hasAudio=false,mediaRec=false;" +
                "let _loop=" + (loop ? "true" : "false") + ";let lastReport=0,waitAt=0,fragT={},lastSn=-1,allBuf=false,loopTimer=null,progTimer=null,waitNudge=null,lastFrames=-1,lastFpsTs=0,fpsTimer=null;" +
                "function slog(m){if(!stopped&&window.AndroidBridge&&window.AndroidBridge.onStreamLog)window.AndroidBridge.onStreamLog(SID,m);}" +
                "function readFrames(){if(v.getVideoPlaybackQuality){const q=v.getVideoPlaybackQuality();if(q&&typeof q.totalVideoFrames==='number')return q.totalVideoFrames;}return typeof v.webkitDecodedFrameCount==='number'?v.webkitDecodedFrameCount:-1;}" +
                "fpsTimer=setInterval(()=>{if(stopped||!started||v.paused)return;const f=readFrames();if(f<0)return;const now=Date.now();if(lastFrames>=0&&lastFpsTs>0){const dt=now-lastFpsTs;if(dt>=500){const fps=Math.max(0,Math.round(((f-lastFrames)*1000)/dt));if(window.AndroidBridge&&window.AndroidBridge.onHlsFps)window.AndroidBridge.onHlsFps(SID,fps);}}lastFrames=f;lastFpsTs=now;},1000);" +
                "let watchdog=setTimeout(()=>{if(!started&&!stopped&&window.AndroidBridge){window.AndroidBridge.onHlsFallback(SID,'timeout');}},6000);" +
                "function clrNudge(){if(waitNudge){clearTimeout(waitNudge);waitNudge=null;}}" +
                "function fail(r){clearTimeout(watchdog);clrNudge();if(!stopped){stopped=true;if(loopTimer)clearInterval(loopTimer);if(progTimer)clearInterval(progTimer);if(fpsTimer)clearInterval(fpsTimer);if(window.AndroidBridge)window.AndroidBridge.onHlsFallback(SID,r);}}" +
                "function durMs(){return (isFinite(v.duration)&&v.duration>0)?Math.round(v.duration*1000):0;}" +
                "function bufStart(){return v.buffered.length>0?v.buffered.start(0):0;}" +
                "function bufStr(){let s=[];for(let i=0;i<v.buffered.length;i++)s.push(v.buffered.start(i).toFixed(2)+'..'+v.buffered.end(i).toFixed(2));return '['+s.join(', ')+']';}" +
                "function loopTarget(){if(!v.buffered.length)return 0.04;const bs=v.buffered.start(0),be=v.buffered.end(v.buffered.length-1);return be-bs>0.12?bs+0.04:bs;}" +
                "function clampToBuf(sec){const bs=bufStart();return (v.buffered.length>0&&sec<=bs+0.02)?loopTarget():Math.max(0,sec);}" +
                "function checkAudio(d){" +
                "  if(d&&d.levels&&d.levels.some(l=>!!l.audioCodec))return true;" +
                "  if(hls&&hls.audioTracks&&hls.audioTracks.length>0)return true;" +
                "  if(v.audioTracks&&v.audioTracks.length>0)return true;" +
                "  return !!(v.webkitAudioDecodedByteCount>0);" +
                "}" +
                "function reportPlaying(){" +
                "  if(stopped)return;started=true;clearTimeout(watchdog);" +
                "  if(!hasAudio)hasAudio=checkAudio(null);" +
                "  if(window.AndroidBridge)window.AndroidBridge.onHlsPlaying(SID,durMs(),hasAudio);" +
                "}" +
                "function start(){" +
                "  if(typeof Hls==='undefined'||!Hls.isSupported()){fail('hls_unsupported');return;}" +
                "  hls=new Hls({" +
                "    enableWorker:true,lowLatencyMode:false,backBufferLength:30,maxBufferLength:20," +
                "    maxBufferHole:0.8,nudgeOffset:0.2,nudgeMaxRetry:5,highBufferWatchdogPeriod:0.25," +
                "    xhrSetup:function(xhr,url){if(AUTH)xhr.setRequestHeader('Authorization',AUTH);xhr.withCredentials=true;}" +
                "  });" +
                "  hls.on(Hls.Events.LEVEL_LOADED,(ev,data)=>{" +
                "    if(stopped||!data||!data.details||data.details.live)return;" +
                "    const fr=data.details.fragments;if(fr&&fr.length>0)lastSn=fr[fr.length-1].sn;" +
                "  });" +
                "  hls.on(Hls.Events.MANIFEST_PARSED,(ev,data)=>{" +
                "    if(stopped)return;hasAudio=checkAudio(data);" +
                "    const p=v.play();if(p&&p.catch){p.catch(()=>{v.muted=true;v.play();if(window.AndroidBridge)window.AndroidBridge.onAutoMuted(SID);});}" +
                "  });" +
                "  hls.on(Hls.Events.FRAG_BUFFERED,(ev,data)=>{" +
                "    if(stopped)return;" +
                "    if(data&&data.frag&&lastSn>=0&&data.frag.sn>=lastSn)allBuf=true;" +
                "    if(!started&&v.buffered.length>0){" +
                "      if(v.currentTime<v.buffered.start(0)+0.01)v.currentTime=loopTarget();" +
                "      setTimeout(()=>{if(!stopped&&!started&&v.buffered.length>0&&v.currentTime<v.buffered.start(0)+0.05){v.currentTime=loopTarget();v.play().catch(()=>{});}},70);" +
                "    }" +
                "  });" +
                "  hls.on(Hls.Events.FRAG_LOADING,(ev,data)=>{" +
                "    if(stopped||!data||!data.frag)return;const f=data.frag;fragT[f.sn]=Date.now();" +
                "    slog('[HLS] chunk #'+f.sn+' ('+f.type+') loading '+f.start.toFixed(1)+'s+'+f.duration.toFixed(1)+'s');" +
                "  });" +
                "  hls.on(Hls.Events.FRAG_LOADED,(ev,data)=>{" +
                "    if(stopped||!data||!data.frag)return;const f=data.frag;" +
                "    const dt=fragT[f.sn]?(Date.now()-fragT[f.sn]):-1;delete fragT[f.sn];" +
                "    const kb=data.frag.stats&&data.frag.stats.loaded?Math.round(data.frag.stats.loaded/1024):0;" +
                "    slog('[HLS] chunk #'+f.sn+' ('+f.type+') loaded '+kb+'KB in '+dt+'ms');" +
                "  });" +
                "  hls.on(Hls.Events.AUDIO_TRACKS_UPDATED,(ev,data)=>{if(data&&data.audioTracks&&data.audioTracks.length>0){hasAudio=true;if(started&&window.AndroidBridge)window.AndroidBridge.onHlsPlaying(SID,durMs(),true);}});" +
                "  hls.on(Hls.Events.ERROR,(ev,data)=>{" +
                "    if(stopped||!data)return;" +
                "    slog('[HLS] '+(data.fatal?'FATAL ':'warn ')+(data.details||'')+(data.response?' http='+data.response.code:''));" +
                "    if(!data.fatal){" +
                "      if(data.details===Hls.ErrorDetails.BUFFER_STALLED_ERROR&&v.buffered.length>0){" +
                "        const bs=v.buffered.start(0),be=v.buffered.end(v.buffered.length-1);" +
                "        if(v.currentTime<bs+0.02){v.currentTime=loopTarget();}" +
                "        else if(_loop&&isFinite(v.duration)&&be>=v.duration-0.8&&v.currentTime>=be-0.25){v.currentTime=loopTarget();v.play();}" +
                "      }" +
                "      return;" +
                "    }" +
                "    if(data.type===Hls.ErrorTypes.MEDIA_ERROR&&!mediaRec){mediaRec=true;hls.recoverMediaError();return;}" +
                "    fail(data.type+'_'+(data.details||'fatal'));" +
                "  });" +
                "  hls.loadSource(SRC);hls.attachMedia(v);" +
                "  loopTimer=setInterval(()=>{if(stopped||!started||!_loop||!allBuf||v.paused||!v.buffered.length)return;" +
                "    const be=v.buffered.end(v.buffered.length-1);" +
                "    if(isFinite(v.duration)&&be>=v.duration-0.5&&v.currentTime>=be-0.14){v.currentTime=loopTarget();}" +
                "  },60);" +
                "  progTimer=setInterval(()=>{if(stopped||!started||v.paused)return;" +
                "    const now=Date.now();if(now-lastReport<24)return;lastReport=now;" +
                "    if(window.AndroidBridge)window.AndroidBridge.onHlsProgress(SID,Math.round((v.currentTime||0)*1000),durMs());" +
                "  },25);" +
                "}" +
                "v.onwaiting=()=>{if(stopped)return;" +
                "  if(v.buffered.length>0){" +
                "    const cur=v.currentTime,bs=v.buffered.start(0),be=v.buffered.end(v.buffered.length-1);" +
                "    if(cur<bs+0.02){v.currentTime=loopTarget();return;}" +
                "    for(let i=0;i<v.buffered.length-1;i++){" +
                "      if(cur>=v.buffered.end(i)-0.15&&cur<=v.buffered.start(i+1)+0.02){" +
                "        const nxt=v.buffered.start(i+1)+0.02;slog('[HLS] jump gap '+cur.toFixed(2)+'s -> '+nxt.toFixed(2)+'s');v.currentTime=nxt;return;" +
                "      }" +
                "    }" +
                "    if(_loop&&isFinite(v.duration)&&be>=v.duration-0.8&&cur>=be-0.20){v.currentTime=loopTarget();v.play();return;}" +
                "    if(!waitAt){waitAt=Date.now();slog('[HLS] stall/waiting at '+cur.toFixed(2)+'s buf='+bufStr()+' dur='+(isFinite(v.duration)?v.duration.toFixed(2):'?'));}" +
                "    if(started&&!v.paused&&be>cur+0.30&&!waitNudge){" +
                "      const stCur=cur;waitNudge=setTimeout(()=>{waitNudge=null;if(!stopped&&!v.paused&&Math.abs(v.currentTime-stCur)<0.04&&v.buffered.length>0){" +
                "        const eNow=v.buffered.end(v.buffered.length-1);if(eNow>v.currentTime+0.25){const to=Math.min(eNow-0.1,v.currentTime+0.20);slog('[HLS] nudge stall -> '+to.toFixed(2)+'s');v.currentTime=to;v.play().catch(()=>{});}" +
                "      }},140);" +
                "    }" +
                "  }else if(!waitAt){waitAt=Date.now();slog('[HLS] stall/waiting at '+v.currentTime.toFixed(2)+'s (empty buf)');}" +
                "};" +
                "v.onplaying=()=>{v.style.opacity='1';clrNudge();if(waitAt){slog('[HLS] resumed after '+(Date.now()-waitAt)+'ms');waitAt=0;}reportPlaying();if(v.videoWidth>0&&v.videoHeight>0&&window.AndroidBridge&&window.AndroidBridge.onHlsVideoSize)window.AndroidBridge.onHlsVideoSize(SID,v.videoWidth,v.videoHeight);setTimeout(()=>{if(!stopped&&!hasAudio&&v.webkitAudioDecodedByteCount>0){hasAudio=true;if(window.AndroidBridge)window.AndroidBridge.onHlsPlaying(SID,durMs(),true);}},450);};" +
                "v.onresize=()=>{if(!stopped&&v.videoWidth>0&&v.videoHeight>0&&window.AndroidBridge&&window.AndroidBridge.onHlsVideoSize)window.AndroidBridge.onHlsVideoSize(SID,v.videoWidth,v.videoHeight);};" +
                "v.onloadedmetadata=()=>{if(!stopped&&v.videoWidth>0&&v.videoHeight>0&&window.AndroidBridge&&window.AndroidBridge.onHlsVideoSize)window.AndroidBridge.onHlsVideoSize(SID,v.videoWidth,v.videoHeight);if(started&&window.AndroidBridge)window.AndroidBridge.onHlsProgress(SID,Math.round((v.currentTime||0)*1000),durMs());};" +
                "v.ontimeupdate=()=>{if(stopped||!started)return;const now=Date.now();if(now-lastReport<55)return;lastReport=now;if(window.AndroidBridge)window.AndroidBridge.onHlsProgress(SID,Math.round((v.currentTime||0)*1000),durMs());};" +
                "v.onseeked=()=>{clrNudge();if(stopped)return;lastReport=0;if(window.AndroidBridge)window.AndroidBridge.onHlsProgress(SID,Math.round((v.currentTime||0)*1000),durMs());};" +
                "v.onended=()=>{clrNudge();if(stopped)return;if(_loop){v.currentTime=loopTarget();v.play();if(window.AndroidBridge)window.AndroidBridge.onHlsProgress(SID,0,durMs());}else{if(window.AndroidBridge)window.AndroidBridge.onHlsEnded(SID);}};" +
                "window.setMuted=(m)=>{v.muted=!!m;};" +
                "window.setVodLoop=(l)=>{_loop=!!l;};" +
                "window.pauseVod=()=>{clrNudge();try{v.pause();}catch(e){}};" +
                "window.playVod=()=>{try{if(v.ended||(isFinite(v.duration)&&v.currentTime>=v.duration-0.25))v.currentTime=loopTarget();v.play();}catch(e){}};" +
                "window.seekVodMs=(ms,resume)=>{clrNudge();try{v.currentTime=clampToBuf(ms/1000.0);if(resume)v.play();}catch(e){}};" +
                "window.stopStream=()=>{stopped=true;clearTimeout(watchdog);clrNudge();if(loopTimer)clearInterval(loopTimer);if(progTimer)clearInterval(progTimer);if(fpsTimer)clearInterval(fpsTimer);try{v.pause();v.removeAttribute('src');}catch(e){}try{if(hls)hls.destroy();}catch(e){}};" +
                "start();" +
                "</script></body></html>";
    }

    private class MseBridge {
        @JavascriptInterface
        public void onStreamLog(int sessionId, String message) {
            if (listener.isClosed() || sessionId != listener.getStreamSessionId() || message == null) return;
            String line = "Frigate: " + message;
            android.util.Log.d("FrigatePlugin", line);
            if (host != null) {
                host.log(line);
            }
        }

        @JavascriptInterface
        public void onMsePlaying(int sessionId, boolean hasAudio) {
            mainHandler.post(() -> {
                if (listener.isClosed() || !listener.isLiveStreamMode() || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onMsePlaying(sessionId, hasAudio);
            });
        }

        @JavascriptInterface
        public void onMseFps(int sessionId, int fps) {
            mainHandler.post(() -> {
                if (listener.isClosed() || !listener.isLiveStreamMode() || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onMseFps(sessionId, fps);
            });
        }

        @JavascriptInterface
        public void onAutoMuted(int sessionId) {
            mainHandler.post(() -> {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId()) return;
                if (!listener.isLiveStreamMode() && !listener.isHlsPlayback()) return;
                listener.onWebAutoMuted(sessionId);
            });
        }

        @JavascriptInterface
        public void onMseFallback(int sessionId, String reason) {
            mainHandler.post(() -> {
                if (listener.isClosed() || !listener.isLiveStreamMode() || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onMseFallback(sessionId, reason);
            });
        }

        @JavascriptInterface
        public void onHlsPlaying(int sessionId, int durationMs, boolean hasAudio) {
            mainHandler.post(() -> {
                if (listener.isClosed() || listener.isLiveStreamMode() || !listener.isHlsPlayback()
                        || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onHlsPlaying(sessionId, durationMs, hasAudio);
            });
        }

        @JavascriptInterface
        public void onHlsProgress(int sessionId, int currentMs, int durationMs) {
            mainHandler.post(() -> {
                if (listener.isClosed() || listener.isLiveStreamMode() || !listener.isHlsPlayback()
                        || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onHlsProgress(sessionId, currentMs, durationMs);
            });
        }

        @JavascriptInterface
        public void onHlsFps(int sessionId, int fps) {
            mainHandler.post(() -> {
                if (listener.isClosed() || listener.isLiveStreamMode() || !listener.isHlsPlayback()
                        || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onHlsFps(sessionId, fps);
            });
        }

        @JavascriptInterface
        public void onHlsVideoSize(int sessionId, int width, int height) {
            mainHandler.post(() -> {
                if (listener.isClosed() || listener.isLiveStreamMode() || !listener.isHlsPlayback()
                        || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onHlsVideoSize(sessionId, width, height);
            });
        }

        @JavascriptInterface
        public void onHlsEnded(int sessionId) {
            mainHandler.post(() -> {
                if (listener.isClosed() || listener.isLiveStreamMode() || !listener.isHlsPlayback()
                        || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onHlsEnded(sessionId);
            });
        }

        @JavascriptInterface
        public void onHlsFallback(int sessionId, String reason) {
            mainHandler.post(() -> {
                if (listener.isClosed() || listener.isLiveStreamMode() || !listener.isHlsPlayback()
                        || sessionId != listener.getStreamSessionId()) {
                    return;
                }
                listener.onHlsFallback(sessionId, reason);
            });
        }
    }
}
