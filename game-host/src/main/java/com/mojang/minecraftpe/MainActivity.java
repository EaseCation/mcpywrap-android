package com.mojang.minecraftpe;
import android.os.Bundle;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import dev.mcpy.game.NativeBridge;

public class MainActivity extends com.google.androidgamesdk.GameActivity {
 private static MainActivity instance;
 public static MainActivity mInstance;
 public static java.lang.ref.WeakReference<MainActivity> currentMainActivity;
 public static String m_storage_path="";
 public static final long startMiliStamp=System.currentTimeMillis();
 public String preloadingHTML="";
 public int virtualKeyboardHeight=0, navBarHeight=0, mLastPermissionRequestReason=0;
 private File runtime, data, session, control;
 private volatile String pendingServer;
 private JSONObject launch;
 private volatile String phase="loading", error="";
 private volatile boolean hudReady=false;
 private volatile String lastScreen="";
 private boolean autoPip=true, pipShown=false, pipRequested=false, directPip=false;
 private dev.mcpy.game.FloatingPipButton editButton;
 private android.widget.LinearLayout loadingPanel;
 private android.widget.TextView loadingText;
 private android.widget.ProgressBar loadingProgress;
 private android.view.SurfaceView gameSurface;
 private int fullRenderWidth,fullRenderHeight;
 private android.content.res.Configuration renderConfiguration;
 private long started=System.currentTimeMillis();
 private final android.os.Handler statusHandler=new android.os.Handler(android.os.Looper.getMainLooper());
 private final Runnable heartbeat=new Runnable(){public void run(){maybeEnterSmallWindow();pollCapture();writeStatus();updateLoadingPanel();statusHandler.postDelayed(this,2000);}};
 private String loadingStage(){
  if(phase.equals("saving"))return "正在保存世界，请稍候";
  if(phase.equals("failed"))return error.isEmpty()?"游戏启动失败，请返回编辑器查看日志":error;
  if(!phase.equals("loading"))return "";
  if(!ready||step==0)return "正在初始化游戏内核";
  if(step==1)return "正在准备模组源码";
  if(step==2)return "正在准备游戏界面";
  if(step==3)return "正在打开测试世界";
  if(step<requests.size())return "正在加载世界和模组";
  return "等待游戏画面就绪";
 }
 private void updateLoadingPanel(){
  if(loadingPanel==null)return;
  String label=loadingStage();loadingPanel.setVisibility(label.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
  boolean compact=isInPictureInPictureMode()||(directPip&&!pipShown);
  loadingText.setTextSize(compact?11:16);
  loadingPanel.setGravity(compact?android.view.Gravity.CENTER:android.view.Gravity.START);
  android.widget.FrameLayout.LayoutParams layout=(android.widget.FrameLayout.LayoutParams)loadingPanel.getLayoutParams();
  layout.width=android.view.ViewGroup.LayoutParams.MATCH_PARENT;
  layout.height=compact?android.view.ViewGroup.LayoutParams.MATCH_PARENT:android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
  loadingPanel.setLayoutParams(layout);
  if(!label.equals(loadingText.getText().toString()))loadingText.setText(label);
  loadingProgress.setVisibility(phase.equals("failed")?android.view.View.GONE:android.view.View.VISIBLE);
 }
 private String timelineState="";
 private synchronized void writeStatus(){
  if(session==null)return;
  if(initialized&&gameSurface==null)gameSurface=findGameSurface(getWindow().getDecorView());
  try {JSONObject value=new JSONObject().put("phase",phase).put("loadingStage",loadingStage()).put("error",error).put("heartbeat",System.currentTimeMillis()).put("pid",android.os.Process.myPid()).put("pip",isInPictureInPictureMode()).put("pipRequested",autoPip).put("launchMode",directPip?"direct_pip":"activity").put("screen",lastScreen).put("serverReady",new File(session,"server-source.json").isFile()).put("loadingElapsedMs",System.currentTimeMillis()-started).put("code",phase.equals("failed")&&error.startsWith("启动超时")?(ready?"world_load_timeout":"engine_load_timeout"):"").put("renderWidth",fullRenderWidth).put("renderHeight",fullRenderHeight).put("bufferWidth",gameSurface==null?0:gameSurface.getHolder().getSurfaceFrame().width()).put("bufferHeight",gameSurface==null?0:gameSurface.getHolder().getSurfaceFrame().height()).put("viewWidth",gameSurface==null?0:gameSurface.getWidth()).put("viewHeight",gameSurface==null?0:gameSurface.getHeight());
   value.put("clientReady",step>=4);
   String transition=phase+":"+step+":"+value.optBoolean("pip")+":"+value.optBoolean("serverReady");
   try{if(!transition.equals(timelineState)){
    JSONObject event=new JSONObject().put("stage",phase).put("step",step).put("screen",lastScreen).put("pip",value.optBoolean("pip"))
     .put("clientReady",step>=4).put("serverReady",value.optBoolean("serverReady")).put("loadingStage",loadingStage()).put("observed_at",System.currentTimeMillis());
    File timeline=new File(session,"game-timeline.jsonl");
    List<String> rows=timeline.isFile()?Files.readAllLines(timeline.toPath(),java.nio.charset.StandardCharsets.UTF_8):new ArrayList<>();
    rows=new ArrayList<>(rows.subList(Math.max(0,rows.size()-63),rows.size()));rows.add(event.toString());
    Files.write(timeline.toPath(),rows,java.nio.charset.StandardCharsets.UTF_8);timelineState=transition;
   }
   }catch(Exception timelineError){android.util.Log.w("McpyStatus","Cannot write diagnostic timeline",timelineError);}
   File temp=new File(session,"status.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
   java.nio.file.Files.move(temp.toPath(),new File(session,"status.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }catch(Exception e){android.util.Log.e("McpyStatus","Cannot write status",e);}
 }
 private void log(String text){if(session==null)return;try(FileOutputStream out=new FileOutputStream(new File(session,"runtime.log"),true)){out.write((System.currentTimeMillis()+" "+text+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}
 private void markWorldVisible(){
  if(hudReady&&step>=requests.size()&&(phase.equals("loading")||(phase.equals("failed")&&error.startsWith("启动超时")))){
   phase="running";error="";writeStatus();
   runOnUiThread(this::updateLoadingPanel);
   if(autoPip&&!pipShown)runOnUiThread(this::maybeEnterSmallWindow);
  }
 }
 // Return to the caller while the world loads, before Android can suspend its tool process.
 // Wait for a real full-size surface, so the engine keeps the complete framebuffer in PiP.
 private void maybeEnterSmallWindow(){
  if(directPip){if(isInPictureInPictureMode())pipShown=true;return;}
  if(!autoPip||pipShown||!initialized||!ready||step<2||stopping||phase.equals("failed"))return;
  android.view.SurfaceView surface=findGameSurface(getWindow().getDecorView());
  if(surface==null||surface.getWidth()<=0||surface.getHeight()<=0||!surface.getHolder().getSurface().isValid())return;
  showSmallWindow();pipShown=pipRequested||isInPictureInPictureMode();
 }
 private android.view.SurfaceView findGameSurface(android.view.View view){
  if(view instanceof android.view.SurfaceView)return (android.view.SurfaceView)view;
  if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.SurfaceView found=findGameSurface(group.getChildAt(i));if(found!=null)return found;}}
  return null;
 }
 private android.util.Rational pipAspectRatio(){
  if(fullRenderWidth>0&&fullRenderHeight>0){double ratio=(double)fullRenderWidth/fullRenderHeight;if(ratio>=0.42&&ratio<=2.39)return new android.util.Rational(fullRenderWidth,fullRenderHeight);}
  return new android.util.Rational(16,9);
 }
 private void showSmallWindow(){
  if(isInPictureInPictureMode())return;
  if(!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))return;
  try{
   gameSurface=findGameSurface(getWindow().getDecorView());
   keepRenderBuffer();
   pipRequested=true;if(!enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio()).build()))pipRequested=false;}catch(Exception e){pipRequested=false;log("PiP: "+e.getMessage());}
 }
 @Override public void onPictureInPictureModeChanged(boolean inPip,android.content.res.Configuration config){super.onPictureInPictureModeChanged(inPip,config);pipRequested=inPip;if(inPip){pipShown=true;android.view.WindowManager.LayoutParams window=getWindow().getAttributes();window.alpha=1f;getWindow().setAttributes(window);}else if(pipShown)setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);updateLoadingPanel();if(editButton!=null){editButton.setVisibility(inPip?android.view.View.GONE:android.view.View.VISIBLE);if(!inPip)editButton.post(editButton::restorePosition);}if(!inPip&&gameSurface!=null)gameSurface.post(this::keepRenderBuffer);writeStatus();}
 private void keepRenderBuffer(){
  if(gameSurface==null||fullRenderWidth<=0||fullRenderHeight<=0)return;
  android.graphics.Rect frame=gameSurface.getHolder().getSurfaceFrame();
  if(frame.width()!=fullRenderWidth||frame.height()!=fullRenderHeight)
   gameSurface.getHolder().setFixedSize(fullRenderWidth,fullRenderHeight);
 }
 @Override protected void onContentRectChangedNative(long handle,int x,int y,int width,int height){
  // Window bounds can be portrait or PiP-sized during transitions. Never resize
  // the engine to these transient bounds while its native Surface stays landscape.
  super.onContentRectChangedNative(handle,0,0,fullRenderWidth,fullRenderHeight);
 }
 @Override protected void onConfigurationChangedNative(long handle,android.content.res.Configuration configuration){
  super.onConfigurationChangedNative(handle,renderConfiguration!=null?renderConfiguration:configuration);
 }
 // The pinned GameActivity dispatches onPauseNative virtually. Android still receives onPause;
 // only the engine pause is deferred while the game remains visible in PiP.
 @Override protected void onPauseNative(long handle){if(!pipRequested&&!isInPictureInPictureMode())super.onPauseNative(handle);}
 @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused||pipRequested||isInPictureInPictureMode());}
 // Some vendor systems ignore launch-into-PiP options. Enter on first resume,
 // before revealing the transparent window, without waiting for engine readiness.
 @Override protected void onResume(){
  super.onResume();
  if(directPip&&!pipShown){
   try{
    if(isInPictureInPictureMode()){
     pipShown=true;android.view.WindowManager.LayoutParams window=getWindow().getAttributes();window.alpha=1f;getWindow().setAttributes(window);
    }else if(!enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio()).build())){
     phase="failed";error="系统未允许进入游戏画中画，请在 App 检查权限";writeStatus();finish();
    }
   }catch(Exception e){phase="failed";error="画中画启动失败："+e.getMessage();writeStatus();finish();}
  }
 }
 @Override protected void onUserLeaveHint(){super.onUserLeaveHint();if(!stopping&&autoPip)showSmallWindow();}

 private volatile boolean ready=false, active=false, initialized=false;
 private volatile int step=0; private long next=0;
 private boolean stopping=false, quitSent=false;
 private static final Set<String> missing=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private final List<String> requests=new ArrayList<>();
 public static MainActivity getInstance(){return instance;}
 private static void unsupported(String name){if(missing.add(name))android.util.Log.w("McpyUnsupported",name);}
 private String root(){return data.getAbsolutePath();}
 @Override public ClassLoader getClassLoader(){return MainActivity.class.getClassLoader();}
 @Override public File getFilesDir(){return data==null?super.getFilesDir():data;}
 @Override public File getExternalFilesDir(String type){return data==null?super.getExternalFilesDir(type):data;}
 @Override protected void attachBaseContext(Context original){
  try {
   runtime=new File(new String(Files.readAllBytes(new File(original.getNoBackupFilesDir(),"active-runtime.txt").toPath()), java.nio.charset.StandardCharsets.UTF_8).trim());
   Context context=original.createConfigurationContext(original.getResources().getConfiguration());
   ResourcesLoader loader=new ResourcesLoader();
   try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(runtime,"engine.apk"),ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(ResourcesProvider.loadFromApk(fd));}
   context.getResources().addLoaders(loader);
   super.attachBaseContext(context);
  }catch(Exception e){throw new IllegalStateException("Cannot prepare APK resources",e);}
 }
 @Override public void onCreate(Bundle state){
  instance=this;mInstance=this;currentMainActivity=new java.lang.ref.WeakReference<>(this);
  try {
   if(!android.os.Environment.isExternalStorageManager())throw new IOException("需要所有文件访问权限");
   data=new File(getIntent().getStringExtra("data")).getCanonicalFile();
   session=new File(getIntent().getStringExtra("session")).getCanonicalFile();
   File publicRoot=new File("/sdcard/mcpy").getCanonicalFile();
   if(!data.getPath().startsWith(new File(publicRoot,"worlds").getPath()+"/")||!session.getPath().startsWith(new File(publicRoot,"logs").getPath()+"/"))throw new IOException("Invalid public workspace");
   control=new File(getNoBackupFilesDir(),"game-control/"+session.getName());control.mkdirs();
   launch=new JSONObject(new String(Files.readAllBytes(new File(session,"launch.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
   autoPip=getIntent().getBooleanExtra("smallWindow",true);
   directPip=getIntent().getBooleanExtra("directPip",false);
   if(!directPip)setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
   else {android.view.WindowManager.LayoutParams window=getWindow().getAttributes();window.alpha=0f;getWindow().setAttributes(window);}
   // One landscape render space for this session, shared by fullscreen and PiP.
   // Reading the physical display avoids sampling the caller's portrait/PiP bounds.
   android.util.DisplayMetrics display=new android.util.DisplayMetrics();
   getWindowManager().getDefaultDisplay().getRealMetrics(display);
   fullRenderWidth=Math.max(display.widthPixels,display.heightPixels);
   fullRenderHeight=Math.min(display.widthPixels,display.heightPixels);
   renderConfiguration=new android.content.res.Configuration(getResources().getConfiguration());
   renderConfiguration.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;
   renderConfiguration.screenWidthDp=Math.round(fullRenderWidth/display.density);
   renderConfiguration.screenHeightDp=Math.round(fullRenderHeight/display.density);
   if(directPip){pipRequested=true;pipShown=false;}
   dev.mcpy.game.OfflineNetworkPolicy.enable();
   statusHandler.post(heartbeat);
   File installed=new File(data,"games/com.netease"); installed.mkdirs();
   for(String resource:new String[]{"vanilla.mcp","netease_resource_packs.json","client_cfg.json","rnconfig.json"}) { File file=new File(installed,resource);if(!file.exists())copyAssetTree("assets/"+resource,file); }
   copyAssetTree("developer_source_loader.py",new File(session,"developer_source_loader.py"));
   copyAssetTree("start_world.py",new File(session,"start_world.py"));
   copyAssetTree("host_debug.py",new File(session,"host_debug.py"));
   requests.add(rpc("__builtin__","eval",new JSONArray().put("True")));
   requests.add(rpc("__builtin__","execfile",new JSONArray().put(new File(session,"developer_source_loader.py").getPath()).put(new JSONObject().put("SOURCE_ROOTS",launch.getJSONArray("sourceRoots")).put("SOURCE_STATUS",new File(session,"server-source.json").getPath()))));
   requests.add(rpc("__builtin__","eval",new JSONArray().put("(lambda scene: (scene.setVisible(False), True)[1] if scene is not None and scene.getName() == 'launcher_scene' else False)(getattr(__import__('sys').modules.get('launcher.launcher'), 'base_scene', None))")));
   requests.add(rpc("__builtin__","execfile",new JSONArray().put(new File(session,"host_debug.py").getPath()).put(new JSONObject().put("SESSION_LOG",new File(session,"game.jsonl").getPath()))));
   requests.add(rpc("__builtin__","execfile",new JSONArray().put(new File(session,"start_world.py").getPath()).put(new JSONObject().put("DATA_ROOT",root()).put("CONFIG",launch))));
   requests.add(rpc("__builtin__","eval",new JSONArray().put("{'level_id': __import__('clientlevel').get_level_id(), 'source_modules': dict((k, getattr(v, '__file__', '')) for k,v in __import__('sys').modules.items() if isinstance(getattr(v, '__file__', ''), basestring) and (getattr(v, '__file__', '').startswith('/storage/emulated/0/mcpy/worlds/') or getattr(v, '__file__', '').startswith('/sdcard/mcpy/worlds/')))} if __import__('clientlevel').get_level_id() not in (None, -1, '-1') else False")));
   System.loadLibrary("c++_shared");System.loadLibrary("HttpClient.Android");System.loadLibrary("PlayFabMultiplayer");System.loadLibrary("fmod");System.loadLibrary("minecraftpe");
   NativeBridge.initialize(new File(runtime,"lib/libminecraftpe.so").getPath());
   NativeBridge.setWorkingDirectory(root());
   orgFmodInit();
   setTheme(0x7f0f00b2);
   super.onCreate(state);
   gameSurface=findGameSurface(getWindow().getDecorView());
   if(gameSurface!=null){
    gameSurface.getHolder().setFixedSize(fullRenderWidth,fullRenderHeight);
    gameSurface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob)->keepRenderBuffer());
   }
   if(getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))setPictureInPictureParams(new android.app.PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio()).build());
   getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
   android.view.WindowInsetsController insets=getWindow().getInsetsController();
   if(insets!=null){insets.hide(android.view.WindowInsets.Type.systemBars());insets.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}

   for(String helper:new String[]{"CocosUIKeyboard","CocosWebView","CocosUIMTextFont","CCLive"}) {
    Class<?> type=Class.forName("com.mojang.minecraftpe."+helper);
    Object object=type.getConstructor(android.app.Activity.class).newInstance(this);
    type.getMethod("initialize").invoke(object);
    if(helper.equals("CocosWebView"))type.getMethod("initPath",String.class).invoke(null,root());
   }
   nativeWaitCrashManagementSetupComplete();
   nativeCopyInnerAsset();
   initialized=true;log("GameActivity initialized");android.util.Log.i("McpyGame","GameActivity initialized");
   editButton=new dev.mcpy.game.FloatingPipButton(this,this::showSmallWindow);
   int buttonSize=Math.round(48*getResources().getDisplayMetrics().density);
   android.widget.FrameLayout.LayoutParams lp=new android.widget.FrameLayout.LayoutParams(buttonSize,buttonSize,android.view.Gravity.TOP|android.view.Gravity.LEFT);
   addContentView(editButton,lp);
   float density=getResources().getDisplayMetrics().density;
   loadingPanel=new android.widget.LinearLayout(this);loadingPanel.setOrientation(android.widget.LinearLayout.VERTICAL);
   int pad=(int)(12*density);loadingPanel.setPadding(pad,pad,pad,pad);
   android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(0xDD202020);background.setCornerRadius(pad);loadingPanel.setBackground(background);
   loadingText=new android.widget.TextView(this);loadingText.setTextColor(0xFFFFFFFF);loadingText.setTextSize(16);loadingText.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
   loadingPanel.addView(loadingText);
   loadingProgress=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);loadingProgress.setIndeterminate(true);loadingPanel.addView(loadingProgress);
   android.widget.FrameLayout.LayoutParams loadingLayout=new android.widget.FrameLayout.LayoutParams(Math.min((int)(400*density),getResources().getDisplayMetrics().widthPixels-(int)(180*density)),-2,android.view.Gravity.TOP|android.view.Gravity.START);
   loadingLayout.setMargins(pad,pad,pad,pad);addContentView(loadingPanel,loadingLayout);updateLoadingPanel();editButton.setVisibility(isInPictureInPictureMode()||directPip?android.view.View.GONE:android.view.View.VISIBLE);editButton.bringToFront();

  } catch(Throwable e){phase="failed";error=e.toString();log(android.util.Log.getStackTraceString(e));writeStatus();throw new IllegalStateException("Game startup failed",e);}
 }
 private void controlResult(String id,JSONObject result){controlResult(id,result,true);}
 private void controlResult(String id,JSONObject result,boolean release){
  try{File temporary=new File(control,id+".tmp");Files.write(temporary.toPath(),result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.move(temporary.toPath(),new File(control,id+".json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
  catch(Exception e){log("Control result: "+e);}
  new File(control,id+".running").delete();if(release)active=false;
 }
 private void pollCapture(){
  if(control==null||session==null)return;
  File request=new File(control,"screenshot-request.json");if(!request.isFile())return;
  String id="invalid";
  try{
   JSONObject command=new JSONObject(new String(Files.readAllBytes(request.toPath()),java.nio.charset.StandardCharsets.UTF_8));
   id=command.getString("id");if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid capture id");
   Files.move(request.toPath(),new File(control,id+".running").toPath());
   final String identity=id;

    runOnUiThread(()->{
     try{
      android.view.SurfaceView surface=findGameSurface(getWindow().getDecorView());
      if(surface==null)throw new IOException("Game surface unavailable");
      android.graphics.Rect frame=surface.getHolder().getSurfaceFrame();
      android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(frame.width(),frame.height(),android.graphics.Bitmap.Config.ARGB_8888);
      android.view.PixelCopy.request(surface.getHolder().getSurface(),bitmap,code->{
       try{
        if(code!=android.view.PixelCopy.SUCCESS)throw new IOException("PixelCopy: "+code);
        File capture=new File(session,"capture-"+identity+".png");try(FileOutputStream out=new FileOutputStream(capture)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        controlResult(identity,new JSONObject().put("ok",true).put("path",capture.getPath()).put("width",bitmap.getWidth()).put("height",bitmap.getHeight()),false);
       }catch(Exception e){controlResult(identity,failure(e),false);}finally{bitmap.recycle();}
      },statusHandler);
     }catch(Exception e){controlResult(identity,failure(e),false);}
    });

  }catch(Exception e){request.delete();controlResult(id,failure(e),false);}
 }
 private void pollControl(){
  File request=new File(control,"request.json");if(!request.isFile())return;
  String id="invalid";
  try{
   JSONObject command=new JSONObject(new String(Files.readAllBytes(request.toPath()),java.nio.charset.StandardCharsets.UTF_8));
   id=command.getString("id");if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid request id");
   Files.move(request.toPath(),new File(control,id+".running").toPath());active=true;
   final String identity=id;
   String code=command.getString("code");
   String encoded=android.util.Base64.encodeToString(code.getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);
   String expression=command.optBoolean("raw")?code:"__import__('_mcpy_host').execute(__import__('base64').b64decode('"+encoded+"'))";
   if(command.optString("side","client").equals("server")){
    String output=android.util.Base64.encodeToString(new File(control,id+".server.json").getPath().getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);
    expression="__import__('_mcpy_host').server_execute('"+encoded+"','"+output+"')";
    pendingServer=id;
   }
   final boolean serverSide=pendingServer!=null;
   NativeBridge.dispatch(this,rpc("__builtin__","eval",new JSONArray().put(expression)),new com.facebook.react.bridge.Callback((status,value)->{
    try{
     if(serverSide&&status==0)return;
     pendingServer=null;
     controlResult(identity,new JSONObject().put("ok",status==0).put("status",status).put("reply",new JSONObject(value)));
    }catch(Exception e){pendingServer=null;controlResult(identity,failure(e));}
   }));
  }catch(Exception e){request.delete();controlResult(id,failure(e));}
 }
 private static JSONObject failure(Exception error){try{return new JSONObject().put("ok",false).put("error",error.toString());}catch(Exception ignored){return new JSONObject();}}
 private void orgFmodInit()throws Exception{Class.forName("org.fmod.FMOD").getMethod("init",Context.class).invoke(null,this);}
 private void copyAssetTree(String path,File target)throws IOException{
  String[] list=getAssets().list(path);
  if(list!=null&&list.length>0){target.mkdirs();for(String child:list)copyAssetTree(path+"/"+child,new File(target,child));}
  else{target.getParentFile().mkdirs();if(target.isFile()&&path.startsWith("demo-addon/"))return;try(InputStream in=getAssets().open(path);OutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}}
 }
 private static String rpc(String module,String function,JSONArray args)throws JSONException{return new JSONObject().put("module_name",module).put("func_name",function).put("args",args).put("use_instance",false).toString();}
 public void setRuntimeMsg(String message){
  if(message.contains("engineIsReady")){if(!ready)log("engine_ready");ready=true;}
  try{JSONObject status=new JSONObject(message);
   if(status.has("top_screen")){String screen=status.getString("top_screen");if(!screen.equals(lastScreen))log("screen: "+screen);lastScreen=screen;}
   if("hud_screen".equals(status.optString("top_screen"))){hudReady=true;markWorldVisible();}status.remove("rn_call_python");android.util.Log.i("McpyRuntime",status.toString());}catch(JSONException invalid){android.util.Log.w("McpyRuntime","Non-JSON runtime status");}
 }
 public void tick(){
  if(!initialized||phase.equals("saved"))return;
  if(phase.equals("loading")&&System.currentTimeMillis()-started>300000){phase="failed";error="启动超时，请查看运行日志";writeStatus();}
  if(pendingServer!=null){
   String identity=pendingServer;File reply=new File(control,identity+".server.json");
   if(reply.isFile())try{
    JSONObject value=new JSONObject(new String(Files.readAllBytes(reply.toPath()),java.nio.charset.StandardCharsets.UTF_8));
    pendingServer=null;controlResult(identity,new JSONObject().put("ok",true).put("reply",new JSONObject().put("ret",value)));reply.delete();
   }catch(Exception e){log("Pending server result: "+e);}
  }
  if(!ready||active||System.currentTimeMillis()<next)return;
  if(new File(session,"save-exit.request").isFile()){stopping=true;new File(session,"save-exit.request").delete();}
  if(stopping){
   phase="saving";active=true;
   try {
    String expression=quitSent?"__import__('clientlevel').get_level_id() in (None, -1, '-1')":"(__import__('minecraft').instance.quit_local_game(), False)[1]";
    NativeBridge.dispatch(this,rpc("__builtin__","eval",new JSONArray().put(expression)),new com.facebook.react.bridge.Callback((code,result)->{
     android.util.Log.i("McpyLifecycle","save-exit "+code+" "+result);
     try { if(code==0&&Boolean.TRUE.equals(new JSONObject(result).opt("ret"))){
      try(FileOutputStream out=new FileOutputStream(new File(session,"save-exit.complete"))){out.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
      phase="saved";writeStatus();runOnUiThread(()->{finish();});
     }}catch(Exception e){android.util.Log.e("McpyLifecycle","Save exit",e);}
     quitSent=true;next=System.currentTimeMillis()+250;active=false;
    }));
   }catch(JSONException e){active=false;throw new IllegalStateException(e);}
   return;
  }
  if(step>=requests.size()){pollControl();return;}
  active=true; final int current=step;
  NativeBridge.dispatch(this,requests.get(step),new com.facebook.react.bridge.Callback((code,result)->{
   log("bootstrap step="+current+" code="+code+(code==0?"":" result="+result.substring(0,Math.min(result.length(),1024))));
   android.util.Log.i("McpyPython","step="+current+" code="+code+" "+result);
   log("step="+current+" code="+code+" "+result);
   try{JSONObject value=new JSONObject(result);
    if(code==0&&value.has("ret")&&!Boolean.FALSE.equals(value.opt("ret"))){
     step++;
     if(current==requests.size()-1){
      markWorldVisible();
      try(FileOutputStream out=new FileOutputStream(new File(session,"engine-proof.json"))){out.write(value.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
     }
    }else {next=System.currentTimeMillis()+1000;if(code!=0&&!"rejected".equals(value.optString("error"))){error=result;}}
   }catch(Exception e){error=e.toString();next=System.currentTimeMillis()+1000;}
   active=false;
  }));
 }
 @Override public void onBackPressed(){stopping=true;}
 @Override protected void onDestroy(){statusHandler.removeCallbacks(heartbeat);if(!phase.equals("saved")){phase="interrupted";writeStatus();}super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}
 public boolean checkKeepStartSize(){return false;}
 public boolean isNetworkEnabled(boolean wifi){return false;}
 public String getExternalStoragePath(){return root();}
 public String getInternalStoragePath(){return root();}
 public String getFilesStoragePath(){return root();}
 public String getFileSharingStoragePath(){return root();}
 public String getLegacyExternalStoragePath(String path){return "";}
 public int getLogoStep(){return 3;}
 public int getAndroidVersion(){return android.os.Build.VERSION.SDK_INT;}
 public int getScreenWidth(){return fullRenderWidth>0?fullRenderWidth:getResources().getDisplayMetrics().widthPixels;}
 public int getScreenHeight(){return fullRenderHeight>0?fullRenderHeight:getResources().getDisplayMetrics().heightPixels;}
 public int getDisplayWidth(){return getScreenWidth();}
 public int getDisplayHeight(){return getScreenHeight();}
 public int getPlatformDpi(){return getResources().getDisplayMetrics().densityDpi;}
 public String getLocale(){return Locale.getDefault().toString();}
 public String getSysLanguage(){return Locale.getDefault().getLanguage();}
 public String getOSName(){return "Android";}
 public String getOSVersion(){return android.os.Build.VERSION.RELEASE;}
 public String getDeviceModel(){return android.os.Build.MODEL;}
 public String createUUID(){return UUID.randomUUID().toString();}
 public boolean getIsEmulator(){return android.os.Build.FINGERPRINT.contains("generic");}
 public boolean hasWriteExternalStoragePermission(){return true;}
 public long getTotalMemory(){android.app.ActivityManager.MemoryInfo i=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(i);return i.totalMem;}
 public long getFreeMemory(){android.app.ActivityManager.MemoryInfo i=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(i);return i.availMem;}
 public long getMemoryLimit(){return getTotalMemory();}
 public long getUsedMemory(){return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
 public long getUsableSpace(String p){return data.getUsableSpace();}
 public long calculateAvailableDiskFreeSpace(String p){return data.getUsableSpace();}
 public boolean copyInnerAssetFile(String from,String to){try{copyAssetTree(from,new File(to));return true;}catch(Exception e){android.util.Log.e("McpyAssets",from,e);return false;}}
 public boolean copyInnerAssetDir(String from,String to){return copyInnerAssetFile(from,to);}
 public boolean copyInnerAsset(String from,String to){return copyInnerAssetFile(from,to);}
 public void postScriptError(String title,String detail){String message=title+": "+detail;log("SCRIPT "+message);if(message.contains("/mcpy/")){error=message;writeStatus();}android.util.Log.e("McpyScript",message);}
 public String getPropStr(String key){return "";}
 public int getPropInt(String key,int value){return value;}
 public String getHardwareUUID(){return "mcpy-android-demo";}
 public String getSecureStorageKey(String key){return getPreferences(0).getString(key,"");}
 public void setSecureStorageKey(String key,String value){getPreferences(0).edit().putString(key,value).apply();}
 public void runNativeCallbackOnUiThread(long callback){runOnUiThread(()->nativeRunNativeCallbackOnUiThread(callback));}
 public static java.lang.String encryptionMD5(byte[] a0){unsupported("encryptionMD5");return "";}
 public static boolean isPowerVR(){unsupported("isPowerVR");return false;}
 public static native void nativeWaitCrashManagementSetupComplete();
 public static void saveScreenshot(java.lang.String a0,int a1,int a2,int[] a3){unsupported("saveScreenshot");}
 public void CallBaseOnCreate(android.os.Bundle a0){unsupported("CallBaseOnCreate");}
 public java.lang.String GetAuthTypeName(){unsupported("GetAuthTypeName");return "";}
 public android.graphics.Bitmap GetBitmapFromPath(java.lang.String a0){unsupported("GetBitmapFromPath");return null;}
 public java.lang.String GetDnsTxt(java.lang.String a0){unsupported("GetDnsTxt");return "";}
 public java.lang.String GetMyPackageName(){unsupported("GetMyPackageName");return "";}
 public boolean IsClassOK(java.lang.String a0){unsupported("IsClassOK");return false;}
 public void acquireMulticast(){unsupported("acquireMulticast");}
 public void addListener(com.mojang.minecraftpe.ActivityListener a0){unsupported("addListener");}
 public void bindDumpCondition(java.lang.String a0,java.lang.String a1){unsupported("bindDumpCondition");}
 public android.graphics.Bitmap blurBitmap(android.graphics.Bitmap a0,float a1){unsupported("blurBitmap");return null;}
 public void buyGame(){unsupported("buyGame");}
 public boolean canOpenPackage(java.lang.String a0){unsupported("canOpenPackage");return false;}
 public boolean canOpenUrl(java.lang.String a0){unsupported("canOpenUrl");return false;}
 public void changeOrientation(int a0){unsupported("changeOrientation");}
 public void changeOrientationOnUiThread(int a0){unsupported("changeOrientationOnUiThread");}
 public void checkCreateRole(){unsupported("checkCreateRole");}
 public void checkFullScreen(){unsupported("checkFullScreen");}
 public int checkLicense(){unsupported("checkLicense");return 0;}
 public void clearDuowanIntentData(){unsupported("clearDuowanIntentData");}
 public void clearGameInfo(){unsupported("clearGameInfo");}
 public void clearLoginInformation(){unsupported("clearLoginInformation");}
 public boolean commonAddStaticviewImage(java.lang.String a0,java.lang.String a1,boolean a2){unsupported("commonAddStaticviewImage");return false;}
 public boolean commonSetStaticviewImage(java.lang.String a0,boolean a1){unsupported("commonSetStaticviewImage");return false;}
 public boolean copyAssets(android.content.Context a0,java.lang.String a1,java.lang.String a2){unsupported("copyAssets");return false;}
 public void copyFile(android.content.Context a0,java.lang.String a1,java.lang.String a2){unsupported("copyFile");}
 public void copyToClipboard(byte[] a0){unsupported("copyToClipboard");}
 public void copyToPickedFile(java.lang.String a0){unsupported("copyToPickedFile");}
 public android.content.Intent createAndroidLaunchIntent(){unsupported("createAndroidLaunchIntent");return null;}
 public void createQRCode(java.lang.String a0,int a1,int a2,java.lang.String a3,java.lang.String a4){unsupported("createQRCode");}
 public com.mojang.minecraftpe.TextInputProxyEditTextbox createTextWidget(){unsupported("createTextWidget");return null;}
 public void deviceIdCorrelationStart(){unsupported("deviceIdCorrelationStart");}
 public void displayDialog(int a0){unsupported("displayDialog");}
 public void doShare(java.lang.String a0,java.lang.String a1,java.lang.String a2,java.lang.String a3,java.lang.String a4,java.lang.String a5,java.lang.String a6){unsupported("doShare");}
 public void enableEnvSDKLog(boolean a0){unsupported("enableEnvSDKLog");}
 public void enterBackground(){unsupported("enterBackground");}
 public void exitApp(){unsupported("exitApp");}
 public void exitAppCallback(){unsupported("exitAppCallback");}
 public void extendFunc(java.lang.String a0){unsupported("extendFunc");}
 public boolean extraSetStaticViewImage(java.lang.String a0,java.lang.String a1,boolean a2){unsupported("extraSetStaticViewImage");return false;}
 public void gameLoginSuccess(){unsupported("gameLoginSuccess");}
 public int getAPIVersion(java.lang.String a0){unsupported("getAPIVersion");return 0;}
 public java.lang.String getAccessToken(){unsupported("getAccessToken");return "";}
 public java.lang.String getAppChannel(){unsupported("getAppChannel");return "";}
 public java.lang.String getBackGroundPng(java.lang.String a0,java.lang.String a1){unsupported("getBackGroundPng");return "";}
 public com.mojang.minecraftpe.BatteryMonitor getBatteryMonitor(){unsupported("getBatteryMonitor");return null;}
 public java.lang.String[] getBroadcastAddresses(){unsupported("getBroadcastAddresses");return new String[0];}
 public int getCaretPosition(){unsupported("getCaretPosition");return 0;}
 public java.lang.String getChannel(){unsupported("getChannel");return "";}
 public java.lang.String getClientId(){unsupported("getClientId");return "";}
 public java.lang.String getCpuFreq(){unsupported("getCpuFreq");return "";}
 public java.lang.String getCpuName(){unsupported("getCpuName");return "";}
 public java.lang.String getCurDensityImgDirPath(boolean a0){unsupported("getCurDensityImgDirPath");return "";}
 public java.lang.String getCurEnvStartLogoUrl(){unsupported("getCurEnvStartLogoUrl");return "";}
 public long getDebugMemoryInfo(java.lang.String a0){unsupported("getDebugMemoryInfo");return 0;}
 public int getDeviceOrientation(){unsupported("getDeviceOrientation");return 0;}
 public java.lang.String getDuowanIntentData(){unsupported("getDuowanIntentData");return "";}
 public java.lang.String getFileTimestamp(int a0){unsupported("getFileTimestamp");return "";}
 public java.lang.String getFirstDeviceId(){unsupported("getFirstDeviceId");return "";}
 public java.lang.String getFormattedDateString(int a0){unsupported("getFormattedDateString");return "";}
 public java.lang.String getGalleryPath(){unsupported("getGalleryPath");return "";}
 public com.mojang.minecraftpe.HardwareInformation getHardwareInfo(){return new HardwareInformation(this);}
 public java.lang.String getIMEI(){unsupported("getIMEI");return "";}
 public java.lang.String[] getIPAddresses(){unsupported("getIPAddresses");return new String[0];}
 public int[] getImageData(java.lang.String path){
  android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(path);
  if(bitmap==null){try(InputStream stream=getAssets().open(path)){bitmap=android.graphics.BitmapFactory.decodeStream(stream);}catch(IOException error){android.util.Log.w("McpyAssets","Image unavailable: "+path);return null;}}
  if(bitmap==null)return null;
  int w=bitmap.getWidth(),h=bitmap.getHeight();
  if(w<=0||h<=0||(long)w*h>16777216){bitmap.recycle();return null;}
  int[] pixels=new int[2+w*h];pixels[0]=w;pixels[1]=h;bitmap.getPixels(pixels,2,w,0,0,w,h);bitmap.recycle();return pixels;
 }
 public java.lang.String getImageName(){unsupported("getImageName");return "";}
 public java.lang.String getInputDevices(){unsupported("getInputDevices");return "";}
 public boolean getIsRoot(){unsupported("getIsRoot");return false;}
 public boolean getIsRunningInBrowserStack(){unsupported("getIsRunningInBrowserStack");return false;}
 public int getKeyFromKeyCode(int a0,int a1,int a2){unsupported("getKeyFromKeyCode");return 0;}
 public float getKeyboardHeight(){unsupported("getKeyboardHeight");return 0;}
 public java.lang.String getLegacyDeviceID(){unsupported("getLegacyDeviceID");return "";}
 public long getLowMemoryThreshold(){android.app.ActivityManager.MemoryInfo info=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);return info.threshold;}
 public java.lang.String getMacAddress(){unsupported("getMacAddress");return "";}
 public java.lang.String getMachineCode(){unsupported("getMachineCode");return "";}
 public int getNavigationBarHeight(){unsupported("getNavigationBarHeight");return 0;}
 public java.lang.String getNetworkISP(){unsupported("getNetworkISP");return "";}
 public int getNotchHeight(){unsupported("getNotchHeight");return 0;}
 public long getNtesAppInitStartTime(){unsupported("getNtesAppInitStartTime");return 0;}
 public int getOrientation(){unsupported("getOrientation");return 0;}
 public long getPSSMemory(){unsupported("getPSSMemory");return 0;}
 public java.lang.String getPackageSignMd5(){unsupported("getPackageSignMd5");return "";}
 public java.lang.String getPayChannel(){unsupported("getPayChannel");return "";}
 public java.lang.String getPlatformStringVar(int a0){unsupported("getPlatformStringVar");return "";}
 public java.lang.String getProfileId(){unsupported("getProfileId");return "";}
 public java.lang.String getProfileName(){unsupported("getProfileName");return "";}
 public java.lang.String getRomSize(){unsupported("getRomSize");return "";}
 public java.lang.String getSauthJson(){unsupported("getSauthJson");return "";}
 public java.lang.String getSdkUid(){unsupported("getSdkUid");return "";}
 public java.lang.String getSystemProcessInfo(){unsupported("getSystemProcessInfo");return "";}
 public java.lang.String getTextBoxBackend(){unsupported("getTextBoxBackend");return "";}
 public com.mojang.minecraftpe.ThermalMonitor getThermalMonitor(){unsupported("getThermalMonitor");return null;}
 public long getTimeFromProcessStart(){return android.os.SystemClock.elapsedRealtime();}
 public java.lang.String getTimeZone(){unsupported("getTimeZone");return "";}
 public java.lang.String getUnisdkVer(){unsupported("getUnisdkVer");return "";}
 public int getUserInputStatus(){unsupported("getUserInputStatus");return 0;}
 public java.lang.String[] getUserInputString(){unsupported("getUserInputString");return new String[0];}
 public boolean hasAudioPermission(){unsupported("hasAudioPermission");return false;}
 public boolean hasBuyButtonWhenInvalidLicense(){unsupported("hasBuyButtonWhenInvalidLicense");return false;}
 public boolean hasCameraPermission(){unsupported("hasCameraPermission");return false;}
 public boolean hasFeature(java.lang.String a0){unsupported("hasFeature");return false;}
 public boolean hasHardwareKeyboard(){unsupported("hasHardwareKeyboard");return false;}
 public boolean hasNotchInScreen(){unsupported("hasNotchInScreen");return false;}
 public boolean hasPermission(java.lang.String a0){unsupported("hasPermission");return false;}
 public boolean hasReadContactsPermission(){unsupported("hasReadContactsPermission");return false;}
 public boolean hasReadMediaImagesPermission(){unsupported("hasReadMediaImagesPermission");return false;}
 public void hideKeyboard(){unsupported("hideKeyboard");}
 public void hideLogo(boolean a0){unsupported("hideLogo");}
 public void initEnvSDK(java.lang.String a0,java.lang.String a1,java.lang.String a2){unsupported("initEnvSDK");}
 public native void initLibHTTPClient(android.content.Context a0);
 public void initNotifyBack(java.lang.String a0,java.lang.String a1,int a2){unsupported("initNotifyBack");}
 public void initializeCrashManager(){unsupported("initializeCrashManager");}
 public void initializeMulticast(){unsupported("initializeMulticast");}
 public void initiateUserInput(int a0){unsupported("initiateUserInput");}
 public native boolean isAndroidChromebook();
 public native boolean isAndroidTrial();
 public boolean isApkDebuggable(android.content.Context a0){unsupported("isApkDebuggable");return false;}
 public native boolean isBrazeEnabled();
 public void isDarenUpdated(){unsupported("isDarenUpdated");}
 public boolean isDeviceRooted(){unsupported("isDeviceRooted");return false;}
 public native boolean isEduMode();
 public boolean isFirstSnooperStart(){unsupported("isFirstSnooperStart");return false;}
 public boolean isLaunchedFromDW(){unsupported("isLaunchedFromDW");return false;}
 public boolean isMulticastHeld(){unsupported("isMulticastHeld");return false;}
 public boolean isOnWifi(){unsupported("isOnWifi");return false;}
 public native boolean isPreview();
 public native boolean isPublishBuild();
 public boolean isSharePlatformInstalled(java.lang.String a0){unsupported("isSharePlatformInstalled");return false;}
 public boolean isSoftKeyboardVisible(){unsupported("isSoftKeyboardVisible");return false;}
 public boolean isSpecialChannel(){unsupported("isSpecialChannel");return false;}
 public boolean isTTSEnabled(){unsupported("isTTSEnabled");return false;}
 public boolean isTTSInstalled(){unsupported("isTTSInstalled");return false;}
 public native boolean isTestInfrastructureDisabled();
 public boolean isTextToSpeechInProgress(){unsupported("isTextToSpeechInProgress");return false;}
 public void killProcess(android.content.Context a0){unsupported("killProcess");}
 public void launchGameCallback(int a0){unsupported("launchGameCallback");}
 public void launchUri(java.lang.String a0){unsupported("launchUri");}
 public void lockCursor(){unsupported("lockCursor");}
 public void logout(){unsupported("logout");}
 public void makeJavaDump(){unsupported("makeJavaDump");}
 public void makeJniDump(){unsupported("makeJniDump");}
 public void musicPause(){unsupported("musicPause");}
 public void musicPlay(java.lang.String a0){unsupported("musicPlay");}
 public void musicResume(){unsupported("musicResume");}
 public void musicStop(){unsupported("musicStop");}
 public native void nativeBackPressed();
 public native void nativeBackSpacePressed();
 public native void nativeBeginSpeakMessage(java.lang.String a0);
 public native void nativeCaretPosition(int a0);
 public native void nativeChangeOrientation(int a0);
 public native void nativeCheckActivatedLog();
 public native boolean nativeCheckAssetInstall();
 public native void nativeClearAButtonState();
 public native void nativeContactsPermissionResult(boolean a0);
 public native void nativeCopyInnerAsset();
 public native void nativeCreateLoginScene(int a0,java.lang.String a1);
 public native void nativeCustomCommand(java.lang.String a0);
 public native void nativeDeviceCorrelation(long a0,java.lang.String a1,long a2,java.lang.String a3);
 public native java.lang.String nativeEncryptToken(java.lang.String a0,java.lang.String a1);
 public native void nativeEndSpeakMessage();
 public native java.lang.String nativeGetDataUrl();
 public native java.lang.String nativeGetDeviceId();
 public native java.lang.String nativeGetEngineVersion();
 public native java.lang.String nativeGetGameSemVerString();
 public native java.lang.String nativeGetLoginStage();
 public native java.lang.String nativeGetLoginToken();
 public native boolean nativeGetOfflineStartState();
 public native java.lang.String nativeGetPatvhVersion();
 public native java.lang.String nativeGetPythonMainThreadStack();
 public native java.lang.String nativeGetScriptRuntimeInfo();
 public native java.lang.String nativeGetUserDataPath();
 public native void nativeHandleNotification(java.lang.String a0);
 public native boolean nativeIsAtlasDevBuild();
 public native boolean nativeIsEngineReady();
 public native boolean nativeIsInGame();
 public native boolean nativeKeyHandler(int a0,int a1,int a2);
 public native void nativeLaunchCommand(java.lang.String a0);
 public native void nativeOnDestroy();
 public native void nativeOnEnterBackground();
 public native void nativeOnPickImageCanceled(long a0);
 public native void nativeOnPickImageSuccess(long a0,java.lang.String a1);
 public native void nativeOnResumeFromBackground();
 public native void nativePostGuestBindMsg(java.lang.String a0);
 public native void nativePostJsErrorDump(java.lang.String a0);
 public native void nativeProcessIntentUriQuery(java.lang.String a0,java.lang.String a1);
 public native void nativeProcessLaunchType(java.lang.String a0);
 public native java.lang.String nativeReadAssetFile(java.lang.String a0);
 public native void nativeRecordFinish(java.lang.String a0,boolean a1);
 public native void nativeRequestPermission(boolean a0);
 public native void nativeResize(int a0,int a1);
 public native void nativeReturnKeyPressed();
 public native void nativeRunNativeCallbackOnUiThread(long a0);
 public void nativeSendMessageToJs(java.lang.String a0){unsupported("nativeSendMessageToJs");}
 public native void nativeSendPressed();
 public native void nativeSetHeadphonesConnected(boolean a0);
 public native void nativeSetLaunchNotificationArgs(java.lang.String a0);
 public native void nativeSetNotificationDeviceToken(java.lang.String a0);
 public native void nativeSetPatchVersion(java.lang.String a0);
 public native void nativeSetSurface(android.view.Surface a0);
 public native void nativeSetTextboxText(java.lang.String a0);
 public native void nativeShiftKeyPressed(int a0);
 public native void nativeShutdown();
 public native void nativeStartRender();
 public native void nativeStopThis();
 public native void nativeStoragePermissionRequestResult(boolean a0,int a1);
 public native void nativeSuspend();
 public native void nativeSuspendGameplayUpdates(boolean a0);
 public native void nativeSyncDumpPath(java.lang.String a0);
 public native void nativeTranslateFinish(java.lang.String a0);
 public native int nativeUnzipFile(java.lang.String a0,java.lang.String a1);
 public native void nativeUploadFinish(java.lang.String a0);
 public native void nativeWebviewBackPressed();
 public void nextLogoStep(){unsupported("nextLogoStep");}
 public void ngpushInit(){unsupported("ngpushInit");}
 public void ntGuestBind(){unsupported("ntGuestBind");}
 public void onCreateRole(java.lang.String a0){unsupported("onCreateRole");}
 public boolean onKey(android.view.View a0,int a1,android.view.KeyEvent a2){unsupported("onKey");return false;}
 public native void onRNGLSurfaceChanged(int a0,int a1,int a2);
 public native boolean onRNGLSurfaceCreated(android.view.Surface a0,int a1,int a2,int a3);
 public native void onRNGLSurfaceDestroyed(int a0);
 public native void onRNGLSurfaceEnable(int a0,boolean a1);
 public native int onRNGLSurfaceInit();
 public native void onRNGLSurfaceRelease(int a0);
 public void openAndroidAppSettings(){unsupported("openAndroidAppSettings");}
 public void openAnnouncementInfo(){unsupported("openAnnouncementInfo");}
 public void openInvestigate(java.lang.String a0){unsupported("openInvestigate");}
 public void openMiniProgram(java.lang.String a0,java.lang.String a1,int a2){unsupported("openMiniProgram");}
 public boolean openPackageUrl(java.lang.String a0,java.lang.String a1){unsupported("openPackageUrl");return false;}
 public void openUnisdkUser(){unsupported("openUnisdkUser");}
 public void postAsync(java.lang.String a0,java.lang.String a1){unsupported("postAsync");}
 public void postJsErrorDump(java.lang.String a0,java.lang.String a1){unsupported("postJsErrorDump");}
 public void postPyErrorDump(java.lang.String a0,java.lang.String a1){android.util.Log.e("McpyScript",a0+": "+a1);}
 public void postRuntimeErrorDump(java.lang.String a0,java.lang.String a1){unsupported("postRuntimeErrorDump");}
 public void postScreenshotToFacebook(java.lang.String a0,int a1,int a2,int[] a3){unsupported("postScreenshotToFacebook");}
 public boolean preCheckIsFile(java.lang.String a0){unsupported("preCheckIsFile");return false;}
 public java.lang.String processUrlStr(){unsupported("processUrlStr");return "";}
 public void quit(){unsupported("quit");}
 public java.lang.String readFromClipboard(){unsupported("readFromClipboard");return "";}
 public void realnameVerify(){unsupported("realnameVerify");}
 public void releaseImageViewResouce(android.widget.ImageView a0,java.lang.Boolean a1){unsupported("releaseImageViewResouce");}
 public void releaseMulticast(){unsupported("releaseMulticast");}
 public void removeListener(com.mojang.minecraftpe.ActivityListener a0){unsupported("removeListener");}
 public void repairDisplayMetrics(){unsupported("repairDisplayMetrics");}
 public void requestAllPermission(){unsupported("requestAllPermission");}
 public void requestAudioPermission(){unsupported("requestAudioPermission");}
 public void requestCameraPermission(){unsupported("requestCameraPermission");}
 public void requestContactsPermission(){unsupported("requestContactsPermission");}
 public void requestMediaImagesPermission(int a0){unsupported("requestMediaImagesPermission");}
 public void requestStoragePermission(int a0){unsupported("requestStoragePermission");}
 public boolean resStrValidCheckAndExtract(org.json.JSONObject a0,java.util.ArrayList a1){unsupported("resStrValidCheckAndExtract");return false;}
 public void restart(){unsupported("restart");}
 public void resumeGameplayUpdates(){unsupported("resumeGameplayUpdates");}
 public java.lang.String reviewNickname(byte[] a0){unsupported("reviewNickname");return "";}
 public java.lang.String reviewWords(java.lang.String a0,java.lang.String a1,byte[] a2){unsupported("reviewWords");return "";}
 public boolean saveImageFromURL(java.lang.String a0,java.lang.String a1){unsupported("saveImageFromURL");return false;}
 public void saveImageToGallery(java.lang.String a0){unsupported("saveImageToGallery");}
 public void scannerQRCode(java.lang.String a0){unsupported("scannerQRCode");}
 public java.lang.String searchRightPatchDir(){unsupported("searchRightPatchDir");return "";}
 public void setCachedDeviceId(java.lang.String a0){unsupported("setCachedDeviceId");}
 public void setCaretPosition(int a0){unsupported("setCaretPosition");}
 public void setClipboard(java.lang.String a0){unsupported("setClipboard");}
 public void setDRPFString(java.lang.String a0){unsupported("setDRPFString");}
 public void setDctoolSceneInfo(java.lang.String a0){unsupported("setDctoolSceneInfo");}
 public void setDefaultUserinfo(){unsupported("setDefaultUserinfo");}
 public void setDumpUserInfo(java.lang.String a0,java.lang.String a1,java.lang.String a2){unsupported("setDumpUserInfo");}
 public void setFloatBtnVisible(boolean a0){unsupported("setFloatBtnVisible");}
 public void setIsPowerVR(boolean a0){unsupported("setIsPowerVR");}
 public void setKeepScreenOnFlag(boolean a0){unsupported("setKeepScreenOnFlag");}
 public void setLeaveServer(){unsupported("setLeaveServer");}
 public void setLoginInformation(java.lang.String a0,java.lang.String a1,java.lang.String a2,java.lang.String a3){unsupported("setLoginInformation");}
 public void setLoginUid(java.lang.String a0){unsupported("setLoginUid");}
 public void setMulticastReferenceCounting(boolean a0){unsupported("setMulticastReferenceCounting");}
 public void setPropInt(java.lang.String a0,int a1){unsupported("setPropInt");}
 public void setPropStr(java.lang.String a0,java.lang.String a1){unsupported("setPropStr");}
 public void setRefreshToken(java.lang.String a0){unsupported("setRefreshToken");}
 public void setSdkUid(java.lang.String a0){unsupported("setSdkUid");}
 public void setSession(java.lang.String a0){unsupported("setSession");}
 public void setStaticViewImage(){unsupported("setStaticViewImage");}
 public void setTTSLanguage(java.lang.String a0){unsupported("setTTSLanguage");}
 public void setTextBoxBackend(java.lang.String a0){unsupported("setTextBoxBackend");}
 public void setUnisdkGmUid(java.lang.String a0){unsupported("setUnisdkGmUid");}
 public void setUnisdkProp(java.lang.String a0,java.lang.String a1,java.lang.String a2,int a3){unsupported("setUnisdkProp");}
 public void setUnisdkUserInfo(java.lang.String a0){unsupported("setUnisdkUserInfo");}
 public void setVolume(float a0){unsupported("setVolume");}
 public void setupCrashHunter(){unsupported("setupCrashHunter");}
 public void setupKeyboardViews(java.lang.String a0,int a1,boolean a2,boolean a3,boolean a4,int a5){unsupported("setupKeyboardViews");}
 public void share(java.lang.String a0,java.lang.String a1,java.lang.String a2){unsupported("share");}
 public void shareFile(java.lang.String a0,java.lang.String a1,java.lang.String a2){unsupported("shareFile");}
 public boolean shouldShowLogout(){unsupported("shouldShowLogout");return false;}
 public boolean shouldShowUnisdkUser(){unsupported("shouldShowUnisdkUser");return false;}
 public void showKeyboard(java.lang.String a0,int a1,boolean a2,boolean a3,boolean a4,int a5){unsupported("showKeyboard");}
 public void showLoadingAni(){unsupported("showLoadingAni");}
 public void showUserLicense(){unsupported("showUserLicense");}
 public void showlogoview(){unsupported("showlogoview");}
 public void startPickerActivity(android.content.Intent a0,int a1){unsupported("startPickerActivity");}
 public void startTextToSpeech(java.lang.String a0){unsupported("startTextToSpeech");}
 public void statsTrackEvent(java.lang.String a0,java.lang.String a1){unsupported("statsTrackEvent");}
 public void statsUpdateUserData(java.lang.String a0,java.lang.String a1){unsupported("statsUpdateUserData");}
 public void stopTextToSpeech(){unsupported("stopTextToSpeech");}
 public boolean supportsSizeQuery(java.lang.String a0){unsupported("supportsSizeQuery");return false;}
 public boolean supportsTTSLanguage(java.lang.String a0){unsupported("supportsTTSLanguage");return false;}
 public void suspendGameplayUpdates(){unsupported("suspendGameplayUpdates");}
 public void unbindDumpCondition(java.lang.String a0,java.lang.String a1){unsupported("unbindDumpCondition");}
 public boolean unisdkInit(){unsupported("unisdkInit");return false;}
 public void unisdkLogin(){unsupported("unisdkLogin");}
 public void unisdkPay(java.lang.String a0){unsupported("unisdkPay");}
 public void unlockCursor(){unsupported("unlockCursor");}
 public void updateLocalization(java.lang.String a0,java.lang.String a1){unsupported("updateLocalization");}
 public void uploadUserinfo(){unsupported("uploadUserinfo");}
 public void vibrate(int a0){unsupported("vibrate");}

 public void SetImageBitMap(android.widget.ImageView a0,int a1,java.lang.String a2){unsupported("SetImageBitMap");}
 public void assertIsMainThread(){unsupported("assertIsMainThread");}
 public void cancelRecordVoice(){unsupported("cancelRecordVoice");}
 public void checkNotifyPopup(){unsupported("checkNotifyPopup");}
 public void clearVoice(){unsupported("clearVoice");}
 public void configureBrazeAtRuntime(){unsupported("configureBrazeAtRuntime");}
 public java.lang.String constructNotifyInfo(android.content.Intent a0){unsupported("constructNotifyInfo");return "";}
 public void createAlertDialog(boolean a0,boolean a1,boolean a2){unsupported("createAlertDialog");}
 public void dismissTextWidget(){unsupported("dismissTextWidget");}
 public void downloadVoiceFile(java.lang.String a0,java.lang.String a1){unsupported("downloadVoiceFile");}
 public android.view.inputmethod.InputMethodManager getInputMethodManager(){unsupported("getInputMethodManager");return null;}
 public static long getStat(java.lang.String a0){unsupported("getStat");return 0;}
 public void getTranslation(){unsupported("getTranslation");}
 public void initCCVoice(){try{Class.forName("com.netease.cc.voice.CCVoiceEngine").getMethod("Load",Context.class).invoke(null,getApplicationContext());}catch(Exception error){throw new IllegalStateException("CCVoice Java initialization failed",error);}}
 public void initNgDetect(){unsupported("initNgDetect");}
 public void initNgScreenShotCatch(){unsupported("initNgScreenShotCatch");}
 public void initNgVoice(){unsupported("initNgVoice");}
 public boolean isAppStateActive(){unsupported("isAppStateActive");return false;}
 public boolean isCanExecute(java.lang.String a0){unsupported("isCanExecute");return false;}
 public boolean isNotificationEnabled(android.content.Context a0){unsupported("isNotificationEnabled");return false;}
 public boolean isTextWidgetActive(){unsupported("isTextWidgetActive");return false;}
 public boolean ngVoiceHasPermission(){unsupported("ngVoiceHasPermission");return false;}
 public void ngVoiceRequestPermission(){unsupported("ngVoiceRequestPermission");}
 public void onDialogCanceled(){unsupported("onDialogCanceled");}
 public void onDialogCompleted(){unsupported("onDialogCompleted");}
 public void onPushNotification(android.content.Intent a0){unsupported("onPushNotification");}
 public void processBaiduIntent(android.content.Intent a0){unsupported("processBaiduIntent");}
 public void processChannelIntent(android.content.Intent a0){unsupported("processChannelIntent");}
 public void processIntent(android.content.Intent a0){unsupported("processIntent");}
 public void registerSystemScreenCaptured(){unsupported("registerSystemScreenCaptured");}
 public void registerSystemScreenshot(){unsupported("registerSystemScreenshot");}
 public void setNgVoicePropStr(java.lang.String a0,java.lang.String a1,int a2){unsupported("setNgVoicePropStr");}
 public void startPlayback(java.lang.String a0){unsupported("startPlayback");}
 public void startRecordVoice(){unsupported("startRecordVoice");}
 public void stopPlayback(){unsupported("stopPlayback");}
 public void stopRecordVoice(){unsupported("stopRecordVoice");}
 public void unisdkEnvInit(){unsupported("unisdkEnvInit");}
 public void uploadVoiceFile(){unsupported("uploadVoiceFile");}
 public boolean checkIsCocosEnabled(){unsupported("checkIsCocosEnabled");return false;}
 public java.lang.String chromebookCompatibilityIP(){unsupported("chromebookCompatibilityIP");return "";}
 public int getDensityDpi(){unsupported("getDensityDpi");return 0;}
 public android.app.ActivityManager.MemoryInfo getMemoryInfo(){unsupported("getMemoryInfo");return null;}
 public java.lang.String getProp(java.lang.String a0){unsupported("getProp");return "";}
 public boolean hasHardwareChanged(){unsupported("hasHardwareChanged");return false;}
 public void initStorageStatus(){unsupported("initStorageStatus");}
 public boolean isChromebook(){unsupported("isChromebook");return false;}
 public boolean isDemo(){unsupported("isDemo");return false;}
 public boolean isHardwareKeyboardHidden(){unsupported("isHardwareKeyboardHidden");return false;}
 public boolean isTablet(){unsupported("isTablet");return false;}
 @Override public void onRestart(){super.onRestart();}
 public void openFile(){unsupported("openFile");}
 public void pickImage(long a0){unsupported("pickImage");}
 public void saveFile(java.lang.String a0){unsupported("saveFile");}
 public void setFileDialogCallback(long a0){unsupported("setFileDialogCallback");}
}
