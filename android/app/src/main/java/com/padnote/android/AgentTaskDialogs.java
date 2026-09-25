package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent Agent task list/detail with bounded foreground polling. */
final class AgentTaskDialogs {
    interface BundleFactory { byte[] make() throws Exception; }
    interface ArtifactSaveLauncher {
        void choose(AgentTaskStore.Task task, AgentTaskStore.Artifact artifact);
    }
    private final Activity activity;
    private final AgentConnectionStore connections;
    private final AgentTaskStore tasks;
    private final ExecutorService worker;
    private final ArtifactSaveLauncher artifactSaveLauncher;
    private final Handler main = new Handler(Looper.getMainLooper());

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker) {
        this(activity, connections, worker, null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher) {
        this.activity=activity;this.connections=connections;this.worker=worker;
        this.artifactSaveLauncher=artifactSaveLauncher;
        this.tasks=new AgentTaskStore(activity);
    }

    void showList() {
        List<AgentTaskStore.Task> values=tasks.list(); List<String> labels=new ArrayList<>();
        if(tasks.corruptCount()>0)labels.add("⚠ 有 "+tasks.corruptCount()+" 条任务记录损坏，未覆盖原文件");
        int warningRows=labels.size();
        for(AgentTaskStore.Task task:values)labels.add(task.title+"\n"+status(task.status)+
                (task.error.isEmpty()?"":" · "+task.error));
        if(labels.isEmpty())labels.add("暂无电脑任务");
        new AlertDialog.Builder(activity).setTitle("电脑任务")
                .setItems(labels.toArray(new String[0]),(d,w)->{if(!values.isEmpty()&&w>=warningRows)
                    showDetail(values.get(w-warningRows).clientTaskId);})
                .setNegativeButton("关闭",null).show();
    }

    void showNewText(AgentConnectionStore.Config profile) {
        EditText input=new EditText(activity);input.setHint("说明希望电脑 Agent 完成什么");
        input.setMinLines(6);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(activity).setTitle("发送文本任务到 "+profile.name).setView(input)
                .setNegativeButton("取消",null).setPositiveButton("发送",(d,w)->{
                    String text=input.getText().toString().trim();
                    if(text.isEmpty()){toast("任务说明不能为空");return;}
                    createAndSubmit(profile,"PadNote 文本任务",text,"",1,null);
                }).show();
    }

    void chooseAndSubmitBundle(String title,String input,String noteId,long noteRevision,
                               BundleFactory factory) {
        List<AgentConnectionStore.Config> profiles=new ArrayList<>();
        for(AgentConnectionStore.Config c:connections.list())if(c.verified()&&
                c.transport==AgentConnectionStore.Transport.BRIDGE&&c.capabilities.contains("task_bundle"))profiles.add(c);
        if(profiles.isEmpty()){toast("没有已验证且支持任务包的 Bridge Agent");return;}
        String[] labels=new String[profiles.size()];for(int i=0;i<labels.length;i++)labels[i]=profiles.get(i).name;
        new AlertDialog.Builder(activity).setTitle("发送到哪一个 Agent？").setItems(labels,(d,w)->
                worker.execute(()->{try{byte[] bundle=factory.make();main.post(()->
                        createAndSubmit(profiles.get(w),title,input,noteId,noteRevision,bundle));}
                    catch(Exception e){main.post(()->toast("生成任务包失败："+message(e)));}}))
                .setNegativeButton("取消",null).show();
    }

    private void createAndSubmit(AgentConnectionStore.Config profile,String title,String input,
                                 String noteId,long noteRevision,byte[] bundle) {
        final AgentTaskStore.Task task;
        try{task=tasks.create(profile,title,input,noteId,noteRevision,bundle);}
        catch(Exception e){toast("无法创建任务："+message(e));return;}
        submit(task);
    }

    private void submit(AgentTaskStore.Task task) {
        toast("任务已保存，正在提交…");
        worker.execute(()->{
            AgentConnectionStore.Config connection=connections.get(task.connectionId);
            if(!task.matches(connection)){try{tasks.markLocal(task.clientTaskId,
                    AgentTaskStore.Status.INTERRUPTED,"连接身份已变化，请新建任务");}catch(Exception ignored){}
                main.post(()->showDetail(task.clientTaskId));return;}
            try{
                AgentTaskClient.Submission result=new AgentTaskClient().submit(task,connection);
                AgentConnectionStore.Config latest=connections.get(task.connectionId);
                if(!task.matches(latest))throw new IllegalStateException(
                        "连接在提交期间发生变化，响应未写入任务");
                tasks.applySubmission(task.clientTaskId,task.connectionId,task.connectionRevision,
                        result.remoteTaskId,result.status);
            }catch(Exception e){
                try{if(e instanceof AgentTaskClient.HttpStatusException)
                    tasks.markFailed(task.clientTaskId,task.updatedAt,message(e));
                else tasks.markError(task.clientTaskId,task.updatedAt,
                        "提交结果未知，可用同一任务重试："+message(e));}catch(Exception ignored){}
            }
            main.post(()->showDetail(task.clientTaskId));
        });
    }

    private void showDetail(String clientId) {
        AgentTaskStore.Task initial=tasks.get(clientId);if(initial==null){toast("任务记录不存在");return;}
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView content=new TextView(activity);body.addView(content,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh=button("刷新"),retry=button("重试提交"),stop=button("停止"),once=button("允许一次"),deny=button("拒绝");
        actions.addView(refresh);actions.addView(retry);actions.addView(stop);actions.addView(once);actions.addView(deny);body.addView(actions);
        LinearLayout artifacts=new LinearLayout(activity);artifacts.setOrientation(LinearLayout.VERTICAL);
        body.addView(artifacts);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(initial.title).setView(scroll(body))
                .setNegativeButton("关闭",null).create();
        AtomicBoolean polling=new AtomicBoolean();
        long[] retryDelay={2500L};
        Runnable[] update=new Runnable[1];
        update[0]=()->{
            AgentTaskStore.Task task=tasks.get(clientId);if(task==null||!dialog.isShowing())return;
            content.setText(detail(task));retry.setVisibility(task.status==AgentTaskStore.Status.SUBMITTING?View.VISIBLE:View.GONE);
            stop.setVisibility(!task.status.terminal()&&!task.remoteTaskId.isEmpty()?View.VISIBLE:View.GONE);
            boolean approval=task.status==AgentTaskStore.Status.WAITING_FOR_APPROVAL&&!task.approvalId.isEmpty();
            once.setVisibility(approval?View.VISIBLE:View.GONE);deny.setVisibility(approval?View.VISIBLE:View.GONE);
            artifacts.removeAllViews();
            if(artifactSaveLauncher!=null)for(AgentTaskStore.Artifact artifact:task.artifacts){
                Button save=button("保存产物："+artifact.name+"（"+size(artifact.sizeBytes)+"）");
                save.setOnClickListener(v->artifactSaveLauncher.choose(task,artifact));artifacts.addView(save);
            }
            if(activity.getWindow().getDecorView().getWindowVisibility()!=View.VISIBLE){
                main.postDelayed(update[0],2500L);return;
            }
            if(!task.status.terminal()&&!task.remoteTaskId.isEmpty()&&polling.compareAndSet(false,true))
                worker.execute(()->{boolean success=refresh(task);polling.set(false);
                    retryDelay[0]=success?2500L:Math.min(30000L,retryDelay[0]*2L);
                    main.postDelayed(update[0],retryDelay[0]);});
        };
        refresh.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)worker.execute(()->{if(refresh(t))retryDelay[0]=2500L;main.post(update[0]);});});
        retry.setOnClickListener(v->{dialog.dismiss();AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)submit(t);});
        stop.setOnClickListener(v->control(clientId,"stop",dialog));once.setOnClickListener(v->control(clientId,"once",dialog));deny.setOnClickListener(v->control(clientId,"deny",dialog));
        dialog.setOnDismissListener(d->main.removeCallbacks(update[0]));dialog.show();update[0].run();
    }

    private boolean refresh(AgentTaskStore.Task task) {
        try{AgentConnectionStore.Config c=connections.get(task.connectionId);if(!task.matches(c)){
            tasks.markLocal(task.clientTaskId,AgentTaskStore.Status.INTERRUPTED,"连接身份已变化，已停止自动请求");return true;}
            AgentTaskClient.RemoteStatus remote=new AgentTaskClient().status(task,c);
            AgentConnectionStore.Config latest=connections.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException(
                    "连接在刷新期间发生变化，响应未写入任务");
            tasks.applyStatus(task.clientTaskId,task.connectionId,task.connectionRevision,
                    task.remoteTaskId,remote);
            return true;
        }catch(Exception e){try{tasks.markError(task.clientTaskId,task.updatedAt,
                "刷新失败："+message(e));}catch(Exception ignored){}return false;}
    }

    private void control(String id,String action,AlertDialog dialog){
        AgentTaskStore.Task task=tasks.get(id);if(task==null)return;worker.execute(()->{try{
            AgentConnectionStore.Config c=connections.get(task.connectionId);if(!task.matches(c))throw new IllegalStateException("连接身份已变化");
            AgentTaskClient client=new AgentTaskClient();if("stop".equals(action))client.stop(task,c);
            else client.approve(task,c,action);
            AgentConnectionStore.Config latest=connections.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException("连接在操作期间发生变化，响应已忽略");
            if("stop".equals(action))tasks.markLocal(id,AgentTaskStore.Status.STOPPING,"");
        }catch(Exception e){try{tasks.markError(id,task.updatedAt,
                "操作失败："+message(e));}catch(Exception ignored){}}
            main.post(()->{dialog.dismiss();showDetail(id);});});
    }

    private String detail(AgentTaskStore.Task t){StringBuilder s=new StringBuilder();s.append("状态：").append(status(t.status)).append("\n连接：").append(t.connectionName).append(" · ").append(t.kind==AgentConnectionStore.Kind.HERMES?"Hermes":"OpenClaw").append("\n地址：").append(t.origin).append("\n连接ID：").append(t.connectionId).append(" · 修订 ").append(t.connectionRevision);if(!t.remoteTaskId.isEmpty())s.append("\n任务ID：").append(t.remoteTaskId);if(!t.approvalTitle.isEmpty())s.append("\n\n待审批：").append(t.approvalTitle).append("\n").append(t.approvalDescription);if(!t.output.isEmpty())s.append("\n\n结果：\n").append(t.output);if(!t.error.isEmpty())s.append("\n\n提示：").append(t.error);if(!t.artifacts.isEmpty())s.append("\n\n产物：").append(t.artifacts.size()).append(" 个");return s.toString();}
    private static String size(long bytes){if(bytes>=1024L*1024L)return String.format(java.util.Locale.CHINA,"%.1f MiB",bytes/(1024f*1024f));if(bytes>=1024L)return String.format(java.util.Locale.CHINA,"%.1f KiB",bytes/1024f);return bytes+" B";}
    private static String status(AgentTaskStore.Status s){switch(s){case SUBMITTING:return"提交中/结果待确认";case RUNNING:return"运行中";case WAITING_FOR_APPROVAL:return"等待批准";case STOPPING:return"正在停止";case COMPLETED:return"已完成";case FAILED:return"失败";case CANCELLED:return"已取消";default:return"已中断";}}
    private Button button(String text){Button b=new Button(activity);b.setText(text);return b;}
    private ScrollView scroll(View v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private void toast(String m){Toast.makeText(activity,m,Toast.LENGTH_LONG).show();}
    private static String message(Throwable e){return e.getMessage()==null?"未知错误":e.getMessage();}
}
