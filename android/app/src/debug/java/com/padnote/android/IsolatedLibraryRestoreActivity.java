package com.padnote.android;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;

/** Test-only Activity whose files, cache, private dirs and preferences are UUID-owned. */
public final class IsolatedLibraryRestoreActivity extends MainActivity {
    private File root;
    private String scope;
    private File root(){
        if(root==null){String id=getIntent().getStringExtra("fixture_id");if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IllegalStateException("missing fixture UUID");root=new File(super.getFilesDir(),"ui-fixtures/"+id);if(!root.mkdirs()&&!root.isDirectory())throw new IllegalStateException("fixture root");scope=id;}
        return root;
    }
    @Override public File getFilesDir(){File f=new File(root(),"files");if(!f.mkdirs()&&!f.isDirectory())throw new IllegalStateException("fixture files");return f;}
    @Override public File getCacheDir(){File f=new File(root(),"cache");if(!f.mkdirs()&&!f.isDirectory())throw new IllegalStateException("fixture cache");return f;}
    @Override public File getDir(String name,int mode){File f=new File(root(),"dirs/"+name);if(!f.mkdirs()&&!f.isDirectory())throw new IllegalStateException("fixture dir");return f;}
    @Override public Context getApplicationContext(){return this;}
    @Override public SharedPreferences getSharedPreferences(String name,int mode){root();return super.getSharedPreferences("ui-fixture-"+scope+"-"+name,mode);}
    public File fixtureRoot(){return root();}
    public File writeEvidenceScreenshot(String name,byte[] png)throws Exception{
        if(name==null||!name.matches("[a-z0-9-]{1,64}\\.png")||png==null||png.length<8||png.length>16*1024*1024)
            throw new IllegalArgumentException("invalid isolated screenshot evidence");
        File evidence=new File(root(),"evidence");
        if(!evidence.mkdirs()&&!evidence.isDirectory())throw new IllegalStateException("screenshot evidence directory");
        File destination=new File(evidence,name);
        if(destination.exists())throw new IllegalStateException("refusing screenshot overwrite");
        try(FileOutputStream output=new FileOutputStream(destination,false)){output.write(png);output.flush();output.getFD().sync();}
        return destination;
    }
}
