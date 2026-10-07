package com.opencode.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;

/** OpenCode: pantalla única. WebView a pantalla completa contra el servidor local oculto. */
public class MainActivity extends Activity {

    private static String encodeDirSlug(String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            return android.util.Base64.encodeToString(path.getBytes("UTF-8"),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP).trim();
        } catch (Exception e) {
            return "";
        }
    }


    static final String SERVER_URL = "http://127.0.0.1:4096/";

    private WebView web;
    private View splash;
    private volatile boolean loaded;
    private ValueCallback<Uri[]> fileCallback;
    private final AtomicBoolean waitingForServer = new AtomicBoolean(false);
    private volatile boolean inLandscapeForDialog = false;
    private volatile String lastChatUrl = null;

    public class AndroidBridge {
        @JavascriptInterface
        public String listProjects() {
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "OpenCode");
                if (!dir.exists()) dir.mkdirs();
                File[] files = dir.listFiles();
                JSONArray arr = new JSONArray();
                if (files != null) {
                    Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File f : files) {
                        if (f.isDirectory() && !f.getName().startsWith(".")) {
                            JSONObject o = new JSONObject();
                            o.put("name", f.getName());
                            o.put("path", f.getAbsolutePath());
                            arr.put(o);
                        }
                    }
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String createProject(String name) {
            try {
                if (name == null || name.trim().isEmpty()) {
                    name = "proyecto-" + (System.currentTimeMillis() % 10000);
                }
                String clean = name.trim().replaceAll("[^a-zA-Z0-9._-]", "-");
                File base = new File(Environment.getExternalStorageDirectory(), "OpenCode");
                File pDir = new File(base, clean);
                if (!pDir.exists()) {
                    pDir.mkdirs();
                    File readme = new File(pDir, "README.md");
                    FileOutputStream fos = new FileOutputStream(readme);
                    fos.write(("# " + clean + "\n\nCreado con OpenCode en Android.\n").getBytes("UTF-8"));
                    fos.close();
                }
                String sessRes = http("http://127.0.0.1:4096/session?directory="
                    + URLEncoder.encode(pDir.getAbsolutePath(), "UTF-8"),
                    "{\"title\":\"Chat\",\"directory\":\"" + pDir.getAbsolutePath() + "\"}");
                JSONObject obj = new JSONObject(sessRes);
                String id = obj.getString("id");
                String dirSlug = encodeDirSlug(pDir.getAbsolutePath());
                final String targetUrl = "http://127.0.0.1:4096/" + dirSlug + "/session/" + id;
                lastChatUrl = targetUrl;
                runOnUiThread(() -> {
                    setLandscapeMode(false);
                    if (web != null) {
                        web.evaluateJavascript(
                            "window.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true}));",
                            null);
                        web.loadUrl(targetUrl);
                    }
                });
                JSONObject out = new JSONObject();
                out.put("success", true);
                out.put("name", clean);
                out.put("url", targetUrl);
                return out.toString();
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public String openProject(String name) {
            try {
                File pDir = new File(Environment.getExternalStorageDirectory(), "OpenCode/" + name);
                String sessRes = http("http://127.0.0.1:4096/session?directory="
                    + URLEncoder.encode(pDir.getAbsolutePath(), "UTF-8"),
                    "{\"title\":\"Chat\",\"directory\":\"" + pDir.getAbsolutePath() + "\"}");
                JSONObject obj = new JSONObject(sessRes);
                String id = obj.getString("id");
                String dirSlug = encodeDirSlug(pDir.getAbsolutePath());
                final String targetUrl = "http://127.0.0.1:4096/" + dirSlug + "/session/" + id;
                lastChatUrl = targetUrl;
                runOnUiThread(() -> {
                    setLandscapeMode(false);
                    if (web != null) {
                        web.evaluateJavascript(
                            "window.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true}));",
                            null);
                        web.loadUrl(targetUrl);
                    }
                });
                JSONObject out = new JSONObject();
                out.put("success", true);
                out.put("url", targetUrl);
                return out.toString();
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public void setLandscapeMode(boolean enable) {
            MainActivity.this.setLandscapeMode(enable);
        }
    }

    public void setLandscapeMode(boolean enable) {
        inLandscapeForDialog = enable;
        runOnUiThread(() -> {
            if (enable) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        });
    }

        private static final String MOBILE_SCRIPT = "(function(){\n  if(!window._ocFetchPatched){\n    window._ocFetchPatched=true;\n    var origFetch=window.fetch;\n    window.fetch=function(){\n      var args=arguments;\n      var url=args[0];\n      var strUrl=typeof url==='string'?url:(url&&url.url?url.url:'');\n      return origFetch.apply(this,args).then(function(res){\n        if(strUrl.indexOf('/path')!==-1){\n          return res.clone().json().then(function(data){\n            var p='/storage/emulated/0/OpenCode';\n            data.home=p;\n            if(!data.directory||data.directory.indexOf('server-home')!==-1){\n              data.directory=p;\n            }\n            if(!data.worktree||data.worktree.indexOf('server-home')!==-1){\n              data.worktree=p;\n            }\n            return new Response(JSON.stringify(data),{\n              status:res.status,\n              statusText:res.statusText,\n              headers:res.headers\n            });\n          }).catch(function(){return res;});\n        }\n        if(strUrl.indexOf('/file')!==-1 && strUrl.indexOf('/file/content')===-1 && strUrl.indexOf('/file/status')===-1){\n          return res.clone().json().then(function(data){\n            if(Array.isArray(data)){\n              var clean = data.filter(function(item){\n                var n = item.name || '';\n                var p = item.path || '';\n                if(n.indexOf('.')===0) return false;\n                if(n==='android-sdk'||n==='toolchain') return false;\n                if(p.indexOf('/.')!==-1||p.indexOf('server-home')!==-1) return false;\n                return true;\n              });\n              return new Response(JSON.stringify(clean),{\n                status:res.status,\n                statusText:res.statusText,\n                headers:res.headers\n              });\n            }\n            return res;\n          }).catch(function(){return res;});\n        }\n        return res;\n      });\n    };\n  }\n  if(!document.getElementById('oc-mobile-enhancer-css')){\n    var st=document.createElement('style');\n    st.id='oc-mobile-enhancer-css';\n    st.textContent='.oc-anchored-footer{position:sticky!important;bottom:0!important;background:#000000!important;z-index:50!important;}'\n      + '[data-directory-path*=\"/.\"],[data-directory-path*=\"~/.\"],[data-directory-path*=\"server-home\"],[data-directory-path*=\"android-sdk\"],[data-directory-path*=\"toolchain\"]{display:none!important;}'\n      + '#oc-create-project-row:hover{background:rgba(255,255,255,0.08)!important;}'\n      + '#oc-create-project-row:active{background:rgba(255,255,255,0.15)!important;}'\n      + 'form[data-component=\"prompt-input-v2\"]>div.flex.h-11,form[data-component=\"prompt-input\"]>div.flex.h-11{height:auto!important;min-height:44px;padding-top:6px;padding-bottom:6px!important;}'\n      + 'form[data-component=\"prompt-input-v2\"] div.min-w-0.flex-1,form[data-component=\"prompt-input\"] div.min-w-0.flex-1{flex-wrap:wrap!important;row-gap:6px!important;}'\n      + 'form[data-component=\"prompt-input-v2\"] [data-action=\"prompt-submit\"],form[data-component=\"prompt-input-v2\"] button[type=\"submit\"],form[data-component=\"prompt-input\"] [data-action=\"prompt-submit\"],form[data-component=\"prompt-input\"] button[type=\"submit\"]{flex-shrink:0!important;}';\n    document.head.appendChild(st);\n  }\n  var oldM=document.getElementById('oc-proj-modal');if(oldM)oldM.remove();\n  var oldB=document.getElementById('oc-drawer-bar');if(oldB)oldB.remove();\n  var oldBtn=document.getElementById('oc-home-create-btn');if(oldBtn)oldBtn.remove();\n  var oldCard=document.getElementById('oc-create-project-card');if(oldCard)oldCard.remove();\n\n  function anchorSettingsAndHelp(){\n    var btns=document.querySelectorAll('button');\n    for(var i=0;i<btns.length;i++){\n      var b=btns[i];\n      var isSettings=b.querySelector('[name=\"settings-gear\"],[data-icon=\"settings-gear\"]')||(b.getAttribute('aria-label')||'').toLowerCase().indexOf('ajuste')!==-1;\n      if(isSettings){\n        var p=b.parentElement;\n        if(p&&!p.classList.contains('oc-anchored-footer')){\n          p.classList.add('oc-anchored-footer');\n        }\n      }\n    }\n  }\n\n  function renderSidebarProjects(){\n    if(!window.AndroidBridge||!window.AndroidBridge.listProjects)return;\n    var raw=window.AndroidBridge.listProjects();\n    if(!raw)return;\n    var list=[];\n    try{list=JSON.parse(raw);}catch(e){return;}\n    \n    var drawer=document.querySelector('div[class*=\"fixed top-10 bottom-0 start-0\"]')||document.querySelector('[data-slot=\"sidebar\"]')||document.querySelector('nav');\n    if(!drawer)return;\n\n    var allTexts=drawer.querySelectorAll('div,p,span');\n    for(var i=0;i<allTexts.length;i++){\n      var el=allTexts[i];\n      var t=(el.textContent||'').trim().toLowerCase();\n      if(t==='a\u00fan no hay nada'||t==='crea una sesi\u00f3n para empezar'||t.indexOf('a\u00fan no hay nada')!==-1||t.indexOf('no hay nada que mostrar')!==-1){\n        var emptyBox=el.closest('div.flex.flex-col')||el.parentElement;\n        if(emptyBox&&list.length>0){\n          emptyBox.style.display='none';\n        }\n      }\n    }\n\n    var targetContainer=document.getElementById('oc-sidebar-projects-container');\n    if(!targetContainer){\n      targetContainer=document.createElement('div');\n      targetContainer.id='oc-sidebar-projects-container';\n      targetContainer.style.cssText='display:flex;flex-direction:column;gap:4px;padding:4px 0;width:100%;';\n      \n      var addBtn=null;\n      var btns=drawer.querySelectorAll('button,a,div[role=\"button\"]');\n      for(var b=0;b<btns.length;b++){\n        if((btns[b].textContent||'').toLowerCase().indexOf('a\u00f1adir proyecto')!==-1){\n          addBtn=btns[b];\n          break;\n        }\n      }\n      \n      if(addBtn&&addBtn.parentElement){\n        addBtn.parentElement.insertAdjacentElement('afterend',targetContainer);\n      }else{\n        var searchInput=drawer.querySelector('input');\n        if(searchInput&&searchInput.parentElement){\n          var formBox=searchInput.closest('form')||searchInput.parentElement;\n          if(formBox&&formBox.parentElement){\n            formBox.parentElement.insertAdjacentElement('afterend',targetContainer);\n          }\n        }\n      }\n    }\n\n    var currentSig=list.map(function(p){return p.name;}).join('|');\n    if(targetContainer.dataset.sig!==currentSig){\n      targetContainer.dataset.sig=currentSig;\n      targetContainer.innerHTML='';\n      \n      var header=document.createElement('div');\n      header.style.cssText='font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.05em;color:rgba(255,255,255,0.45);padding:6px 4px 2px 4px;';\n      header.textContent='Proyectos ('+list.length+')';\n      targetContainer.appendChild(header);\n\n      for(var j=0;j<list.length;j++){\n        (function(proj){\n          var item=document.createElement('div');\n          item.className='w-full flex items-center justify-between rounded-lg px-2.5 py-2 cursor-pointer transition-colors';\n          item.style.cssText='background:rgba(255,255,255,0.04);margin-bottom:4px;border:1px solid rgba(255,255,255,0.06);';\n          item.innerHTML='<div class=\"flex items-center gap-x-2.5 grow min-w-0\">'\n            +'<div class=\"shrink-0 size-5 flex items-center justify-center text-text-weak\">'\n            +'<svg class=\"size-4\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">'\n            +'<path d=\"M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z\"/>'\n            +'</svg>'\n            +'</div>'\n            +'<span class=\"text-13-medium text-text-strong font-medium truncate\">'+proj.name+'</span>'\n            +'</div>'\n            +'<span class=\"text-xs text-text-weak opacity-40\">&rarr;</span>';\n          \n          item.onmouseover=function(){item.style.background='rgba(255,255,255,0.09)';};\n          item.onmouseout=function(){item.style.background='rgba(255,255,255,0.04)';};\n          item.onclick=function(e){\n            e.preventDefault();\n            e.stopPropagation();\n            if(window.AndroidBridge&&window.AndroidBridge.openProject){\n              window.AndroidBridge.openProject(proj.name);\n            }\n          };\n          targetContainer.appendChild(item);\n        })(list[j]);\n      }\n    }\n  }\n\n  function isActualProjectDialog(dialog){\n    if(!dialog)return false;\n    var titleEl=dialog.querySelector('[data-slot=\"dialog-title\"],h1,h2,header');\n    var title=(titleEl?titleEl.textContent:'').toLowerCase();\n    var badWords=['ajuste','setting','configura','preferenc','atajo','shortcut','permiso','permission','modelo','model','proveedor','provider','servidor','server','cuenta','account','notifica','sonido','sound'];\n    for(var i=0;i<badWords.length;i++){\n      if(title.indexOf(badWords[i])!==-1)return false;\n    }\n    if(dialog.querySelector('[role=\"tablist\"],[data-slot=\"tabs\"]'))return false;\n    var searchInput=dialog.querySelector('input');\n    var placeholder=(searchInput?(searchInput.placeholder||''):'').toLowerCase();\n    var hasProjectKeywords=title.indexOf('proyecto')!==-1||title.indexOf('project')!==-1||placeholder.indexOf('carpeta')!==-1||placeholder.indexOf('folder')!==-1;\n    var hasPaths=!!dialog.querySelector('[data-directory-path]');\n    return (hasProjectKeywords||hasPaths);\n  }\n\n  function enhanceOpenProjectDialog(dialog){\n    var searchInput=dialog.querySelector('input');\n    if(!searchInput)return;\n    var pathElements=dialog.querySelectorAll('[data-directory-path]');\n    for(var i=0;i<pathElements.length;i++){\n      var val=pathElements[i].getAttribute('data-directory-path')||'';\n      if(val.indexOf('/.')!==-1||val.indexOf('~/.')!==-1||val.indexOf('server-home')!==-1||val.indexOf('toolchain')!==-1||val.indexOf('android-sdk')!==-1){\n        var row=pathElements[i].closest('.rounded-md')||pathElements[i].parentElement;\n        if(row)row.style.display='none';\n      }\n    }\n    var titleEl=dialog.querySelector('[data-slot=\"dialog-title\"],h2');\n    if(titleEl&&!titleEl.dataset.ocTitleFixed){\n      titleEl.textContent='Crear o abrir proyecto';\n      titleEl.dataset.ocTitleFixed='1';\n    }\n    if(searchInput.placeholder.indexOf('Nombre')===-1){\n      searchInput.placeholder='Nombre del nuevo proyecto o buscar...';\n    }\n    var listHeader=dialog.querySelector('[data-slot=\"list-header\"]');\n    if(listHeader&&listHeader.textContent&&listHeader.textContent.indexOf('existente')===-1&&listHeader.textContent.indexOf('Crear')===-1){\n      listHeader.textContent='O abrir proyecto existente:';\n    }\n    if(!document.getElementById('oc-create-project-row')){\n      var createRow=document.createElement('div');\n      createRow.id='oc-create-project-row';\n      createRow.className='w-full flex items-center justify-between rounded-md px-2 py-2 cursor-pointer transition-colors';\n      createRow.style.cssText='border-bottom:1px solid rgba(255,255,255,0.08);margin-bottom:6px;';\n      createRow.innerHTML='<div class=\"flex items-center gap-x-3 grow min-w-0\">'\n        +'<div class=\"shrink-0 size-5 flex items-center justify-center text-text-weak\">'\n        +'<svg class=\"size-4\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">'\n        +'<path d=\"M12 5v14M5 12h14\"/>'\n        +'</svg>'\n        +'</div>'\n        +'<div class=\"flex items-center text-14-regular min-w-0\">'\n        +'<span class=\"text-text-strong whitespace-nowrap font-medium\">Crear proyecto:</span>'\n        +'<span id=\"oc-create-name-preview\" class=\"whitespace-nowrap font-medium ml-1.5 overflow-hidden text-ellipsis truncate text-text-strong\">nuevo-proyecto</span>'\n        +'</div>'\n        +'</div>'\n        +'<span class=\"text-12-regular text-text-weak opacity-60 px-1.5 py-0.5 rounded border border-white/10\">Enter \\u21b5</span>';\n      function doCreate(e){\n        if(e){e.preventDefault();e.stopPropagation();}\n        var val=searchInput.value.trim();\n        var pName=val||('proyecto-'+Math.floor(Math.random()*1000));\n        pName=pName.replace(/[^a-zA-Z0-9._-]/g,'-');\n        if(window.AndroidBridge&&window.AndroidBridge.createProject){\n          window.AndroidBridge.createProject(pName);\n        }\n      }\n      createRow.onclick=doCreate;\n      searchInput.addEventListener('keydown',function(e){\n        if(e.key==='Enter'){\n          e.preventDefault();\n          e.stopPropagation();\n          doCreate(e);\n        }\n      });\n      searchInput.addEventListener('input',function(){\n        var prev=document.getElementById('oc-create-name-preview');\n        if(prev){\n          var val=searchInput.value.trim();\n          prev.textContent=val||'nuevo-proyecto';\n        }\n      });\n      var listContainer=dialog.querySelector('[data-slot=\"list-items\"],[role=\"listbox\"],[data-slot=\"list-group\"],ul')||dialog.querySelector('.px-3');\n      if(listContainer){\n        listContainer.insertAdjacentElement('afterbegin',createRow);\n      }else{\n        var parentBox=searchInput.closest('form')||searchInput.parentElement;\n        if(parentBox&&parentBox.parentElement){\n          parentBox.parentElement.appendChild(createRow);\n        }\n      }\n    }\n  }\n\n  function enhanceSettingsDialog(dialog){\n    var bad=dialog.querySelector('#oc-create-project-row');\n    if(bad)bad.remove();\n    var isEnabled=localStorage.getItem('opencode_auto_accept_permissions')==='true';\n    var allControls=dialog.querySelectorAll('[role=\"switch\"],input[type=\"checkbox\"],button[data-state]');\n    for(var i=0;i<allControls.length;i++){\n      var ctrl=allControls[i];\n      var row=ctrl.closest('div,label,li')||ctrl.parentElement;\n      var text=(row?row.textContent:'').toLowerCase();\n      var isPermRow=(text.indexOf('permis')!==-1||text.indexOf('aprobaci')!==-1||text.indexOf('solicitud')!==-1);\n      if(isPermRow||ctrl.hasAttribute('disabled')||ctrl.getAttribute('aria-disabled')==='true'){\n        ctrl.removeAttribute('disabled');\n        ctrl.removeAttribute('aria-disabled');\n        ctrl.removeAttribute('data-disabled');\n        if('disabled' in ctrl)ctrl.disabled=false;\n        ctrl.style.opacity='1';\n        ctrl.style.pointerEvents='auto';\n        ctrl.style.cursor='pointer';\n        ctrl.classList.remove('opacity-50','pointer-events-none','cursor-not-allowed');\n        if(row){\n          row.style.opacity='1';\n          row.style.pointerEvents='auto';\n          row.classList.remove('opacity-50','pointer-events-none','cursor-not-allowed');\n        }\n        if(isPermRow){\n          if(isEnabled){\n            ctrl.setAttribute('data-state','checked');\n            ctrl.setAttribute('aria-checked','true');\n          }\n          if(!ctrl._ocSwitchBound){\n            ctrl._ocSwitchBound=true;\n            ctrl.addEventListener('click',function(e){\n              var cur=this.getAttribute('data-state')||(this.getAttribute('aria-checked')==='true'?'checked':'unchecked');\n              var next=(cur==='checked'?'unchecked':'checked');\n              this.setAttribute('data-state',next);\n              this.setAttribute('aria-checked',next==='checked'?'true':'false');\n              localStorage.setItem('opencode_auto_accept_permissions',next==='checked'?'true':'false');\n              window._ocAutoAcceptPermissions=(next==='checked');\n            },true);\n          }\n        }\n      }\n    }\n  }\n\n  function autoApprovePendingPermissions(){\n    var isEnabled=localStorage.getItem('opencode_auto_accept_permissions')==='true'||window._ocAutoAcceptPermissions===true;\n    if(!isEnabled)return;\n    var btns=document.querySelectorAll('button');\n    for(var i=0;i<btns.length;i++){\n      var b=btns[i];\n      var t=(b.textContent||'').trim().toLowerCase();\n      if(t==='permitir'||t==='allow'||t==='aprobar'||t==='permitir siempre'||t==='always allow'||t==='once'){\n        var card=b.closest('[data-component=\"notification\"],[data-kind=\"permission\"],div[class*=\"permission\"],div[class*=\"toast\"]');\n        if(card||b.getAttribute('data-action')==='allow'||b.getAttribute('data-action')==='once'){\n          b.click();\n        }\n      }\n    }\n  }\n\n  function handleDialogs(){\n    var existingRow=document.getElementById('oc-create-project-row');\n    if(existingRow){\n      var parentDialog=existingRow.closest('div[role=\"dialog\"],[data-slot=\"dialog-content\"],[data-dialog-layer]');\n      if(!parentDialog||!isActualProjectDialog(parentDialog)){\n        existingRow.remove();\n      }\n    }\n    var dialogs=document.querySelectorAll('div[role=\"dialog\"],[data-slot=\"dialog-content\"],[data-dialog-layer]');\n    for(var i=0;i<dialogs.length;i++){\n      var d=dialogs[i];\n      if(isActualProjectDialog(d)){\n        enhanceOpenProjectDialog(d);\n      }else{\n        enhanceSettingsDialog(d);\n      }\n    }\n  }\n\n  function checkOrientation(){\n    var openMenus=document.querySelectorAll('[role=\"menu\"],[role=\"listbox\"],[data-slot=\"popover-content\"],[data-component=\"combobox\"],[data-slot=\"combobox-content\"]');\n    for(var m=0;m<openMenus.length;m++){\n      var mText=(openMenus[m].textContent||'').toLowerCase();\n      if(mText.indexOf('modelo')!==-1||mText.indexOf('provider')!==-1||mText.indexOf('claude')!==-1||mText.indexOf('gpt')!==-1||mText.indexOf('gemini')!==-1||mText.indexOf('deepseek')!==-1){\n        if(window._ocCurrentLandscape!==false){\n          window._ocCurrentLandscape=false;\n          if(window.AndroidBridge&&window.AndroidBridge.setLandscapeMode){\n            window.AndroidBridge.setLandscapeMode(false);\n          }\n        }\n        return;\n      }\n    }\n\n    var isDrawer=false;\n    var drawer=document.querySelector('div[class*=\"fixed top-10 bottom-0 start-0\"]');\n    if(drawer&&drawer.classList.contains('translate-x-0')){\n      isDrawer=true;\n    }\n    var toggleBtn=document.querySelector('button[aria-label*=\"sidebar\" i],button[aria-label*=\"menu\" i]');\n    if(toggleBtn&&toggleBtn.getAttribute('aria-expanded')==='true'){\n      isDrawer=true;\n    }\n\n    var isSettings=false;\n    var isProject=false;\n    var allDialogs=document.querySelectorAll('div[role=\"dialog\"],[data-slot=\"dialog-content\"],[data-dialog-layer]');\n    for(var d=0;d<allDialogs.length;d++){\n      var dlg=allDialogs[d];\n      if(isActualProjectDialog(dlg)){\n        isProject=true;\n      }else{\n        var dTitle=((dlg.querySelector('[data-slot=\"dialog-title\"],h1,h2')||{}).textContent||'').toLowerCase();\n        if(dTitle.indexOf('ajuste')!==-1||dTitle.indexOf('setting')!==-1||dTitle.indexOf('configura')!==-1||dTitle.indexOf('permiso')!==-1||dlg.classList.contains('settings-dialog')||dlg.classList.contains('settings-v2-dialog')){\n          isSettings=true;\n        }\n      }\n    }\n\n    var isHome=(window.location.pathname==='/'||window.location.pathname==='');\n    var shouldLandscape=isDrawer||isSettings||isProject||isHome;\n\n    if(window._ocCurrentLandscape!==shouldLandscape){\n      window._ocCurrentLandscape=shouldLandscape;\n      if(window.AndroidBridge&&window.AndroidBridge.setLandscapeMode){\n        window.AndroidBridge.setLandscapeMode(shouldLandscape);\n      }\n    }\n  }\n\n  if(!window._ocClickHooked){\n    window._ocClickHooked=true;\n    document.addEventListener('click',function(e){\n      var modelBtn=e.target.closest('[data-slot=\"model-selector\"],[data-action=\"model\"],button[aria-haspopup=\"listbox\"],button[aria-haspopup=\"menu\"]');\n      if(modelBtn){\n        var btnText=(modelBtn.textContent||'').toLowerCase();\n        if(btnText.indexOf('claude')!==-1||btnText.indexOf('gpt')!==-1||btnText.indexOf('gemini')!==-1||btnText.indexOf('deepseek')!==-1||btnText.indexOf('modelo')!==-1){\n          if(window.AndroidBridge)window.AndroidBridge.setLandscapeMode(false);\n          return;\n        }\n      }\n      var menu=e.target.closest('button[aria-label*=\"sidebar\" i],button[aria-label*=\"menu\" i]');\n      if(menu){\n        var isExpanded=menu.getAttribute('aria-expanded')==='true';\n        if(!isExpanded){\n          if(window.AndroidBridge)window.AndroidBridge.setLandscapeMode(true);\n        }\n      }\n      var gear=e.target.closest('button[aria-label*=\"setting\" i],button[aria-label*=\"ajuste\" i],[data-component=\"sidebar-rail\"]>div:last-child button:first-child');\n      if(gear){\n        if(window.AndroidBridge)window.AndroidBridge.setLandscapeMode(true);\n      }\n    },true);\n  }\n\n  function tick(){\n    checkOrientation();\n    anchorSettingsAndHelp();\n    renderSidebarProjects();\n    handleDialogs();\n    autoApprovePendingPermissions();\n  }\n  tick();\n\n  if(!window._ocObserver){\n    window._ocObserver=new MutationObserver(function(){\n      tick();\n    });\n    window._ocObserver.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['class','aria-expanded']});\n  }\n  if(!window._ocTicker){\n    window._ocTicker=setInterval(function(){\n      tick();\n    },400);\n  }\n})();";

    private void injectMobileEnhancements() {
        if (web == null) return;
        web.evaluateJavascript(MOBILE_SCRIPT, null);
    }

        private WebResourceResponse handleIntercept(Uri uri) {
        if (uri == null || uri.getPath() == null) return null;
        String path = uri.getPath();
        File workDir = new File(Environment.getExternalStorageDirectory(), "OpenCode");
        if (!workDir.exists()) workDir.mkdirs();
        String workPath = workDir.getAbsolutePath();

        if (path.equals("/path")) {
            try {
                String fullUrl = "http://127.0.0.1:4096/path" + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
                HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();
                conn.disconnect();
                JSONObject json = new JSONObject(bos.toString("UTF-8"));
                json.put("home", workPath);
                String curDir = json.optString("directory", "");
                if (curDir.isEmpty() || curDir.contains("server-home")) {
                    json.put("directory", workPath);
                }
                String curWt = json.optString("worktree", "");
                if (curWt.isEmpty() || curWt.contains("server-home")) {
                    json.put("worktree", workPath);
                }
                byte[] data = json.toString().getBytes("UTF-8");
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(data));
            } catch (Exception ignored) {}
        }

        if (path.equals("/file")) {
            String dirParam = uri.getQueryParameter("directory");
            if (dirParam == null || dirParam.isEmpty() || dirParam.equals(workPath)
                || dirParam.endsWith("/OpenCode") || dirParam.contains("server-home") || dirParam.equals("~")) {
                try {
                    File[] subFiles = workDir.listFiles();
                    org.json.JSONArray arr = new org.json.JSONArray();
                    if (subFiles != null) {
                        for (File f : subFiles) {
                            if (f.isDirectory() && !f.getName().startsWith(".")
                                && !f.getName().equals("android-sdk") && !f.getName().equals("toolchain")) {
                                org.json.JSONObject itm = new org.json.JSONObject();
                                itm.put("name", f.getName());
                                itm.put("path", f.getAbsolutePath());
                                itm.put("type", "directory");
                                arr.put(itm);
                            }
                        }
                    }
                    byte[] data = arr.toString().getBytes("UTF-8");
                    return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(data));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 OpenCode-Android");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        web.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    WebResourceResponse res = handleIntercept(request.getUrl());
                    if (res != null) return res;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null) {
                    WebResourceResponse res = handleIntercept(Uri.parse(url));
                    if (res != null) return res;
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("/session/")) {
                    lastChatUrl = url;
                }
                injectMobileEnhancements();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (url != null && url.contains("/session/")) {
                    lastChatUrl = url;
                }
                injectMobileEnhancements();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(i, null), 1001);
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        splash = new View(this);
        splash.setBackgroundColor(Color.BLACK);
        ImageView icon = new ImageView(this);
        icon.setImageResource(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()));
        FrameLayout splashWrap = new FrameLayout(this);
        splashWrap.setBackgroundColor(Color.BLACK);
        int pad = (int) (96 * getResources().getDisplayMetrics().density);
        splashWrap.setPadding(pad, pad, pad, pad);
        splashWrap.addView(icon, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(splashWrap, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        splash = splashWrap;

        setContentView(root);

        if (SetupActivity.needed(this)) {
            startActivity(new Intent(this, SetupActivity.class));
        }
        ensureStorage();
        startService(new Intent(this, OpenCodeService.class));
        waitForServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loaded) {
            waitForServer();
        }
    }

    private void ensureStorage() {
        // No auto-abrir ajustes: el asistente (SetupActivity) lo guía.
    }

    private void waitForServer() {
        if (loaded) return;
        if (!waitingForServer.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                while (!loaded && !isFinishing()) {
                    if (serverUp()) {
                        loaded = true;
                        final String url = resolveChatUrl();
                        lastChatUrl = url;
                        runOnUiThread(() -> {
                            web.loadUrl(url);
                            web.postDelayed(() -> {
                                if (splash != null) splash.setVisibility(View.GONE);
                            }, 800);
                        });
                        return;
                    }
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
                }
            } finally {
                waitingForServer.set(false);
            }
        }).start();
    }

    /** Abre directo en un chat: reutiliza la última sesión o crea una. */
    private static String resolveChatUrl() {
        try {
            String list = http("http://127.0.0.1:4096/session", null);
            JSONArray arr = new JSONArray(list);
            String bestId = null;
            String bestDir = null;
            long bestTime = -1;
            File workDir = new File(Environment.getExternalStorageDirectory(), "OpenCode");
            String workPath = workDir.getAbsolutePath();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                long t = s.optJSONObject("time") != null
                    ? s.optJSONObject("time").optLong("updated", 0) : 0;
                if (t >= bestTime) {
                    bestTime = t;
                    bestId = s.optString("id", null);
                    bestDir = s.optString("directory", null);
                }
            }
            if (bestId == null) {
                String created = http("http://127.0.0.1:4096/session", "{\"title\":\"Chat\"}");
                JSONObject s = new JSONObject(created);
                bestId = s.getString("id");
                bestDir = s.optString("directory", null);
            }
            if (bestDir != null && !bestDir.isEmpty() && !bestDir.equals(workPath) && !bestDir.contains("server-home")) {
                String slug = encodeDirSlug(bestDir);
                if (!slug.isEmpty()) {
                    return "http://127.0.0.1:4096/" + slug + "/session/" + bestId;
                }
            }
            return "http://127.0.0.1:4096/global/session/" + bestId;
        } catch (Exception ignored) {
            return SERVER_URL;
        }
    }

    private static String http(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes("UTF-8"));
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(o.toByteArray(), "UTF-8");
    }

    private static boolean serverUp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(SERVER_URL).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && fileCallback != null) {
            Uri[] uris = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    uris = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        uris[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    uris = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(function(){\n  var hasDialog = !!document.querySelector('[data-component=\"dialog-overlay\"], [data-dialog-layer], [data-slot=\"dialog-content\"], .settings-dialog, .settings-v2-dialog, div[role=\"dialog\"]');\n  if (hasDialog) {\n    window.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true, cancelable:true}));\n    document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true, cancelable:true}));\n    var closeBtn = document.querySelector('button[aria-label*=\"close\" i], [data-slot=\"dialog-close-button\"], [data-dialog-layer] button');\n    if (closeBtn) closeBtn.click();\n    var overlay = document.querySelector('[data-component=\"dialog-overlay\"]');\n    if (overlay) overlay.click();\n    if (window.location.pathname !== '/' && window.location.pathname !== '') {\n      setTimeout(function(){\n        var drawer = document.querySelector('div[class*=\"fixed top-10 bottom-0 start-0\"]');\n        if (!drawer || !drawer.classList.contains('translate-x-0')) {\n          var toggleBtn = document.querySelector('button[aria-label*=\"sidebar\" i], button[aria-label*=\"menu\" i]');\n          if (toggleBtn) toggleBtn.click();\n        }\n      }, 60);\n    }\n    return 'closed_dialog';\n  }\n  var drawer = document.querySelector('div[class*=\"fixed top-10 bottom-0 start-0\"]');\n  if (drawer && drawer.classList.contains('translate-x-0')) {\n    var backdrop = document.querySelector('div[class*=\"fixed inset-x-0 top-10 bottom-0\"]');\n    if (backdrop) backdrop.click();\n    else {\n      var toggleBtn = document.querySelector('button[aria-label*=\"sidebar\" i], button[aria-label*=\"menu\" i]');\n      if (toggleBtn) toggleBtn.click();\n    }\n    return 'closed_drawer';\n  }\n  if (window.location.pathname === '/' || window.location.pathname === '') {\n    return 'on_home';\n  }\n  return 'none';\n})()", value -> {
            String res = value != null ? value.replace("\"", "").trim() : "";
            if ("closed_dialog".equals(res)) {
                setLandscapeMode(false);
                return;
            }
            if ("closed_drawer".equals(res)) {
                setLandscapeMode(false);
                return;
            }
            if ("on_home".equals(res)) {
                setLandscapeMode(false);
                if (web.canGoBack()) {
                    web.goBack();
                } else if (lastChatUrl != null && !lastChatUrl.isEmpty()) {
                    web.evaluateJavascript(
                        "(function(){ if (window.location.href !== '" + lastChatUrl + "') window.location.href = '" + lastChatUrl + "'; })()",
                        null);
                }
                return;
            }
            if (web.canGoBack()) {
                web.goBack();
            } else {
                super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        android.content.SharedPreferences prefs =
            getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        boolean is24_7 = prefs.getBoolean("pref_24_7", false);
        if (isFinishing() && !is24_7) {
            Intent exitIntent = new Intent(this, OpenCodeService.class)
                .setAction(OpenCodeService.ACTION_STOP_SERVICE);
            startService(exitIntent);
        }
        super.onDestroy();
    }
}
