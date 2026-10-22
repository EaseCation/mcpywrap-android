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
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader, ApplicationInfo appInfo) { info=appInfo; return loader; }
    @Override public Activity instantiateActivity(ClassLoader cl,String name,Intent intent) throws InstantiationException,IllegalAccessException,ClassNotFoundException {
        if (!name.equals("com.mojang.minecraftpe.MainActivity")) return super.instantiateActivity(cl,name,intent);
        try {
            File active=new File(info.dataDir,"no_backup/active-runtime.txt");
            String root=new String(Files.readAllBytes(active.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            // Runtime GameActivity and adapter are intentionally in the same loader.
            File adapter=new File(info.dataDir,"code_cache/game-adapter.apk");
            adapter.getParentFile().mkdirs();
            if(adapter.exists()&&!adapter.delete())throw new java.io.IOException("Cannot replace own adapter cache");
            try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(info.sourceDir);java.io.InputStream input=zip.getInputStream(zip.getEntry("assets/game-adapter.apk"));java.io.OutputStream output=new java.io.FileOutputStream(adapter)){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}
            adapter.setReadOnly();
            ClassLoader game=new DexClassLoader(adapter.getPath()+File.pathSeparator+root+"/engine.apk",info.dataDir+"/code_cache",root+"/lib"+File.pathSeparator+info.nativeLibraryDir,cl.getParent());
            return (Activity)game.loadClass(name).getDeclaredConstructor().newInstance();
        } catch (Exception e) { throw new ClassNotFoundException("Cannot create game runtime",e); }
    }
}
