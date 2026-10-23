package dev.mcpy.game;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.nio.file.Files;

public final class GameComponentFactory extends AppComponentFactory {
    private static ApplicationInfo info;
    public static final class CancelledLaunchActivity extends Activity {
        @Override public void onCreate(android.os.Bundle state){super.onCreate(state);finish();}
    }
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader, ApplicationInfo appInfo) { info=appInfo; return loader; }
    private static boolean validAttempt(Intent intent){
        if(!intent.hasExtra("launchAttempt"))return true;
        try{
            org.json.JSONObject pending=new org.json.JSONObject(new String(Files.readAllBytes(new File(info.dataDir,"no_backup/launch-handoff.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            String state=pending.getString("state");
            return pending.getString("session").equals(new File(intent.getStringExtra("session")).getName())&&pending.getString("attempt").equals(intent.getStringExtra("launchAttempt"))&&
                (state.equals("started")||state.equals("presentation_waiting")||(state.equals("dispatching")&&System.currentTimeMillis()<pending.getLong("expires_at")));
        }catch(Exception ignored){return false;}
    }
    private static void report(Intent intent,String stage,Throwable error){
        try{
            if(intent.hasExtra("launchAttempt")){
                org.json.JSONObject pending=new org.json.JSONObject(new String(Files.readAllBytes(new File(info.dataDir,"no_backup/launch-handoff.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
                if(!pending.getString("attempt").equals(intent.getStringExtra("launchAttempt")))return;
            }
            File session=new File(intent.getStringExtra("session")).getCanonicalFile();
            File logs=new File("/sdcard/mcpy/logs").getCanonicalFile();
            if(!session.getParentFile().equals(logs))return;
            org.json.JSONObject record=new org.json.JSONObject().put("pid",stage.equals("cancelled_before_native")?0:android.os.Process.myPid())
                .put("observed_at",System.currentTimeMillis()).put("stage",stage).put("phase",stage.equals("cancelled_before_native")?"cancelled":error==null?"loading":"failed");
            if(error!=null)record.put("error",error.toString());
            try{File timeline=new File(session,"bootstrap-timeline.jsonl");
            java.util.List<String> rows=timeline.isFile()?Files.readAllLines(timeline.toPath(),java.nio.charset.StandardCharsets.UTF_8):new java.util.ArrayList<>();
            rows=new java.util.ArrayList<>(rows.subList(Math.max(0,rows.size()-63),rows.size()));rows.add(record.toString());
            Files.write(timeline.toPath(),rows,java.nio.charset.StandardCharsets.UTF_8);
            }catch(Exception ignored){}
            File temp=new File(session,"bootstrap.tmp");Files.write(temp.toPath(),record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.move(temp.toPath(),new File(session,"bootstrap.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception ignored){}
    }
    @Override public Activity instantiateActivity(ClassLoader cl,String name,Intent intent) throws InstantiationException,IllegalAccessException,ClassNotFoundException {
        if (!name.equals("com.mojang.minecraftpe.MainActivity")) return super.instantiateActivity(cl,name,intent);
        if(!validAttempt(intent))return new CancelledLaunchActivity();
        report(intent,"process_started",null);
        try {
            File active=new File(info.dataDir,"no_backup/active-runtime.txt");
            String root=new String(Files.readAllBytes(active.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            // Runtime GameActivity and adapter are intentionally in the same loader.
            File adapter=new File(info.dataDir,"code_cache/game-adapter.apk");
            adapter.getParentFile().mkdirs();
            if(adapter.exists()&&!adapter.delete())throw new java.io.IOException("Cannot replace own adapter cache");
            try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(info.sourceDir);java.io.InputStream input=zip.getInputStream(zip.getEntry("assets/game-adapter.apk"));java.io.OutputStream output=new java.io.FileOutputStream(adapter)){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}
            adapter.setReadOnly();
            report(intent,"loading_apk_classes",null);
            ClassLoader game=new DexClassLoader(adapter.getPath()+File.pathSeparator+root+"/engine.apk",info.dataDir+"/code_cache",root+"/lib"+File.pathSeparator+info.nativeLibraryDir,cl.getParent());
            Activity activity=(Activity)game.loadClass(name).getDeclaredConstructor().newInstance();
            if(!validAttempt(intent)){report(intent,"cancelled_before_native",null);return new CancelledLaunchActivity();}
            report(intent,"activity_constructed",null);return activity;
        } catch (Throwable e) { report(intent,"bootstrap_failed",e);throw new ClassNotFoundException("Cannot create game runtime",e); }
    }
}
