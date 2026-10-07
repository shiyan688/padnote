package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
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
    interface SourceNoteOpener { boolean open(String noteId); }
    interface TaskConnectionSource {
        List<AgentConnectionStore.Config> list();
        AgentConnectionStore.Config get(String id);
    }
    interface BundleFactory { byte[] make() throws Exception; }
    interface ArtifactSaveLauncher {
        void choose(AgentTaskStore.Task task, AgentTaskStore.Artifact artifact);
    }
    private final Activity activity;
    private final AgentConnectionStore connections;
    private final AgentTaskStore tasks;
    private final ExecutorService worker;
    private final ArtifactSaveLauncher artifactSaveLauncher;
    private final VideoTaskDialogs.Factory videoDialogFactory;
    private final SourceNoteOpener sourceNoteOpener;
    private final AgentTaskClient.Factory taskClientFactory;
    private final TaskConnectionSource taskConnectionSource;
    private final Handler main = new Handler(Looper.getMainLooper());

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker) {
        this(activity, connections, worker, null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher) {
        this(activity,connections,worker,artifactSaveLauncher,new AgentTaskStore(activity));
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,VideoTaskDialogs::new,null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,videoDialogFactory,null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory,SourceNoteOpener sourceNoteOpener) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,videoDialogFactory,
                sourceNoteOpener,AgentTaskClient::new);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory,SourceNoteOpener sourceNoteOpener,
                     AgentTaskClient.Factory taskClientFactory) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,videoDialogFactory,
                sourceNoteOpener,taskClientFactory,null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory,SourceNoteOpener sourceNoteOpener,
                     AgentTaskClient.Factory taskClientFactory,TaskConnectionSource taskConnectionSource) {
        this.activity=activity;this.connections=connections;this.worker=worker;
        this.artifactSaveLauncher=artifactSaveLauncher;
        this.tasks=tasks;
        this.videoDialogFactory=videoDialogFactory;
        this.sourceNoteOpener=sourceNoteOpener;
        this.taskClientFactory=taskClientFactory==null?AgentTaskClient::new:taskClientFactory;
        this.taskConnectionSource=taskConnectionSource==null?new TaskConnectionSource(){
            @Override public List<AgentConnectionStore.Config> list(){return AgentTaskDialogs.this.connections.list();}
            @Override public AgentConnectionStore.Config get(String id){return AgentTaskDialogs.this.connections.get(id);}
        }:taskConnectionSource;
    }

    void showForNote(String noteId) {
        List<AgentTaskStore.Task> values=new ArrayList<>();
        for(AgentTaskStore.Task task:tasks.list())if(noteId.equals(task.noteId))values.add(task);
        if(values.isEmpty()){new AlertDialog.Builder(activity).setTitle("本笔记任务历史")
                .setMessage("还没有关联的电脑任务。生成视频任务后，任务和产物会显示在这里。")
                .setNegativeButton("关闭",null).show();return;}
        List<String> labels=new ArrayList<>();for(AgentTaskStore.Task t:values){
            labels.add(historyRow(t)+"\n来源修订 "+t.noteRevision);}
        new AlertDialog.Builder(activity).setTitle("本笔记任务历史")
                .setAdapter(multilineRows(labels),(d,w)->showDetail(values.get(w).clientTaskId))
                .setNegativeButton("关闭",null).show();
    }

    void showList() {
        List<AgentTaskStore.Task> values=tasks.list();
        int corruptCount=tasks.corruptCount();
        if(values.isEmpty()){
            String message="还没有电脑任务。你可以选择现有连接，或添加/配对连接。任务只会在当前能力检查通过并由你确认后发送。";
            if(corruptCount>0)message+="\n\n另有 "+corruptCount+
                    " 条任务记录损坏，原文件未覆盖。";
            new AlertDialog.Builder(activity).setTitle("电脑任务").setMessage(message)
                    .setNegativeButton("关闭",null)
                    .setPositiveButton("选择或添加连接",(dialog,which)->
                            new AgentConnectionDialogs(activity,connections,worker,()->{},null,
                                    artifactSaveLauncher).show())
                    .show();
            return;
        }
        List<String> labels=new ArrayList<>();
        if(corruptCount>0)labels.add("⚠ 有 "+corruptCount+" 条任务记录损坏，未覆盖原文件");
        int warningRows=labels.size();
        for(AgentTaskStore.Task task:values)labels.add(historyRow(task)+
                (task.error.isEmpty()?"":"\n"+task.error));
        new AlertDialog.Builder(activity).setTitle("电脑任务")
                .setAdapter(multilineRows(labels),(d,w)->{if(w>=warningRows)
                    showDetail(values.get(w-warningRows).clientTaskId);})
                .setNegativeButton("关闭",null).show();
    }

    private static String historyRow(AgentTaskStore.Task task) {
        return task.title+"\n"+status(task.status)+"\n目标："+
                AgentTaskDestinationLabel.forTask(task);
    }

    private ArrayAdapter<String> multilineRows(List<String> labels) {
        return new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,labels) {
            @Override public View getView(int position,View convertView,ViewGroup parent) {
                TextView row=(TextView)super.getView(position,convertView,parent);
                row.setSingleLine(false);
                row.setMaxLines(Integer.MAX_VALUE);
                row.setEllipsize(null);
                return row;
            }
        };
    }

    void showNewText(AgentConnectionStore.Config profile) {
        EditText input=new EditText(activity);input.setHint("说明希望电脑 Agent 完成什么");
        input.setMinLines(6);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(activity).setTitle("发送文本任务到 "+destinationLabel(profile)).setView(input)
                .setNegativeButton("取消",null).setPositiveButton("发送",(d,w)->{
                    String text=input.getText().toString().trim();
                    if(text.isEmpty()){toast("任务说明不能为空");return;}
                    createAndSubmit(profile,"PadNote 文本任务",text,"",1,null);
                }).show();
    }

    void chooseAndSubmitBundle(String title,String input,String noteId,long noteRevision,
                               BundleFactory factory) {
        List<AgentConnectionStore.Config> profiles=new ArrayList<>();
        for(AgentConnectionStore.Config c:taskConnectionSource.list())if(c.verified()&&
                c.transport==AgentConnectionStore.Transport.BRIDGE&&c.capabilities.contains("task_bundle")&&
                (c.kind!=AgentConnectionStore.Kind.BUILTIN_VIDEO||c.capabilities.contains("video_task_submission")))profiles.add(c);
        if(profiles.isEmpty()){toast("没有已验证且支持任务包的 Bridge Agent");return;}
        String[] labels=new String[profiles.size()];for(int i=0;i<labels.length;i++)labels[i]=destinationLabel(profiles.get(i));
        new AlertDialog.Builder(activity).setTitle("发送到哪一个 Agent？").setItems(labels,(d,w)->{
                    AgentConnectionStore.Config selected=profiles.get(w);
                    new AlertDialog.Builder(activity).setTitle("确认发送任务包")
                            .setMessage("目标："+destinationLabel(selected)+"\n材料："+title+
                                    "\n确认后会将所选材料发送给此电脑 Agent。")
                            .setNegativeButton("取消",null).setPositiveButton("发送到此 Agent",(confirm,which)->
                                    worker.execute(()->{try{byte[] bundle=factory.make();main.post(()->
                                            createAndSubmit(selected,title,input,noteId,noteRevision,bundle));}
                                        catch(Exception e){main.post(()->toast("生成任务包失败："+message(e)));}})).show();
                })
                .setNegativeButton("取消",null).show();
    }

    static String destinationLabel(AgentConnectionStore.Config profile) {
        return AgentTaskDestinationLabel.forProfile(profile);
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
            AgentConnectionStore.Config connection=taskConnectionSource.get(task.connectionId);
            if(!task.matches(connection)){try{tasks.markLocal(task.clientTaskId,
                    AgentTaskStore.Status.INTERRUPTED,"连接身份已变化，请新建任务");}catch(Exception ignored){}
                main.post(()->showDetail(task.clientTaskId));return;}
            try{
                AgentTaskClient.Submission result=taskClientFactory.create().submit(task,connection);
                AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
                if(!task.matches(latest))throw new IllegalStateException(
                        "连接在提交期间发生变化，响应未写入任务");
                boolean applied=tasks.applySubmission(task.clientTaskId,task.connectionId,task.connectionRevision,
                        result.remoteTaskId,result.status,result.conversationId,result.parentTaskId);
                if(applied&&result.status==AgentTaskStore.Status.COMPLETED){
                    AgentTaskStore.Task submitted=tasks.get(task.clientTaskId);
                    if(submitted!=null)refresh(submitted);
                }
            }catch(Exception e){
                try{if(e instanceof AgentTaskClient.HttpStatusException)
                    tasks.markFailed(task.clientTaskId,task.updatedAt,message(e));
                else tasks.markError(task.clientTaskId,task.updatedAt,
                        "提交结果未知，可用同一任务重试："+message(e));}catch(Exception ignored){}
            }
            main.post(()->showDetail(task.clientTaskId));
        });
    }

    private void askFollowup(String parentId,AlertDialog parentDialog) {
        AgentTaskStore.Task parent=tasks.get(parentId);if(parent==null)return;
        EditText input=new EditText(activity);input.setHint("补充说明，沿用本轮已确认的电脑会话");
        input.setMinLines(4);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(activity).setTitle("继续沟通").setView(input)
                .setNegativeButton("取消",null).setPositiveButton("发送",(d,w)->{
                    String text=input.getText().toString().trim();if(text.isEmpty()){toast("追问不能为空");return;}
                    AgentConnectionStore.Config connection=taskConnectionSource.get(parent.connectionId);
                    if(!parent.matches(connection)){toast("连接身份已变化，不能继续沟通");return;}
                    try {
                        AgentTaskStore.Task child=tasks.createFollowup(parent,connection,text);
                        parentDialog.dismiss();
                        if(child.status==AgentTaskStore.Status.SUBMITTING)submit(child);
                        else showDetail(child.clientTaskId);
                    } catch(Exception e){
                        List<AgentTaskStore.Task> existing=tasks.children(parentId);
                        if(!existing.isEmpty()&&message(e).contains("新文字未发送")){
                            toast("旧追问已保存，新文字未发送，已打开后续轮次");
                            parentDialog.dismiss();showDetail(existing.get(0).clientTaskId);
                        }else toast("无法继续沟通："+message(e));
                    }
                }).show();
    }

    void showDetail(String clientId) {
        AgentTaskStore.Task initial=tasks.get(clientId);if(initial==null){toast("任务记录不存在");return;}
        final boolean videoBundleValid=VideoTaskClient.isVideoBundle(initial);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView content=new TextView(activity);body.addView(content,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);
        Button openSource=button("打开来源手写笔记");
        if(initial.noteId.isEmpty()||sourceNoteOpener==null)openSource.setVisibility(View.GONE);
        addAction(actions,openSource);
        Button refresh=button("刷新"),retry=button("重试提交"),stop=button("停止"),once=button("允许一次"),deny=button("拒绝");
        addAction(actions,refresh);addAction(actions,retry);addAction(actions,stop);addAction(actions,once);addAction(actions,deny);body.addView(actions);
        Button continueTask=button("继续沟通"),previous=button("上一轮"),video=button("视频分镜");
        addAction(actions,continueTask);addAction(actions,previous);
        addAction(actions,video);
        LinearLayout history=new LinearLayout(activity);history.setOrientation(LinearLayout.VERTICAL);
        body.addView(history);
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
            video.setVisibility(videoBundleValid&&!task.remoteTaskId.isEmpty()?View.VISIBLE:View.GONE);
            AgentConnectionStore.Config bound=taskConnectionSource.get(task.connectionId);
            boolean canContinue=task.transport==AgentConnectionStore.Transport.BRIDGE&&
                    task.status==AgentTaskStore.Status.COMPLETED&&task.followupAvailable&&
                    task.childClientTaskId.isEmpty()&&task.matches(bound);
            continueTask.setVisibility(canContinue?View.VISIBLE:View.GONE);
            previous.setVisibility(task.parentClientTaskId.isEmpty()?View.GONE:View.VISIBLE);
            history.removeAllViews();
            for(AgentTaskStore.Task child:tasks.children(clientId)){
                String input=inputText(child);String summary=input.length()>48?input.substring(0,48)+"…":input;
                Button open=button("打开后续轮 · "+status(child.status)+" · "+summary);
                open.setOnClickListener(v->{dialog.dismiss();showDetail(child.clientTaskId);});history.addView(open);
            }
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
        openSource.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null&&sourceNoteOpener.open(t.noteId))dialog.dismiss();else toast("找不到原笔记；任务与视频快照仍保留");});
        retry.setOnClickListener(v->{dialog.dismiss();AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)submit(t);});
        continueTask.setOnClickListener(v->askFollowup(clientId,dialog));
        previous.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null&&!t.parentClientTaskId.isEmpty()){dialog.dismiss();showDetail(t.parentClientTaskId);}});
        video.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)videoDialogFactory.create(activity,connections,tasks,worker).show(t);});
        stop.setOnClickListener(v->control(clientId,"stop",dialog));once.setOnClickListener(v->control(clientId,"once",dialog));deny.setOnClickListener(v->control(clientId,"deny",dialog));
        dialog.setOnDismissListener(d->main.removeCallbacks(update[0]));dialog.show();update[0].run();
    }

    private boolean refresh(AgentTaskStore.Task task) {
        try{AgentConnectionStore.Config c=taskConnectionSource.get(task.connectionId);if(!task.matches(c)){
            tasks.markLocal(task.clientTaskId,AgentTaskStore.Status.INTERRUPTED,"连接身份已变化，已停止自动请求");return true;}
            AgentTaskClient.RemoteStatus remote=taskClientFactory.create().status(task,c);
            AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
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
            AgentConnectionStore.Config c=taskConnectionSource.get(task.connectionId);if(!task.matches(c))throw new IllegalStateException("连接身份已变化");
            AgentTaskClient client=taskClientFactory.create();if("stop".equals(action))client.stop(task,c);
            else client.approve(task,c,action);
            AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException("连接在操作期间发生变化，响应已忽略");
            if("stop".equals(action))tasks.markLocal(id,AgentTaskStore.Status.STOPPING,"");
        }catch(Exception e){try{tasks.markError(id,task.updatedAt,
                "操作失败："+message(e));}catch(Exception ignored){}}
            main.post(()->{dialog.dismiss();showDetail(id);});});
    }

    private String detail(AgentTaskStore.Task t){StringBuilder s=new StringBuilder();s.append("状态：").append(status(t.status)).append("\n目标：").append(AgentTaskDestinationLabel.forTask(t)).append("\n连接修订：").append(t.connectionRevision);if(!t.remoteTaskId.isEmpty())s.append("\n任务ID：").append(t.remoteTaskId);if(!t.noteId.isEmpty()){s.append("\n来源笔记：").append(t.noteId).append(" · 修订 ").append(t.noteRevision);String bundleSha=bundleSha(t);if(!bundleSha.isEmpty())s.append(" · 输入包 SHA-256 ").append(bundleSha);}if(!t.parentClientTaskId.isEmpty())s.append("\n追问自：第 ").append(round(t)-1).append(" 轮");if(t.transport==AgentConnectionStore.Transport.BRIDGE)s.append("\n继续沟通：").append(t.followupAvailable?"电脑已确认可继续":(t.followupReason.isEmpty()?"暂不可用":t.followupReason));s.append("\n\n本轮说明：\n").append(inputText(t));if(!t.approvalTitle.isEmpty())s.append("\n\n待审批：").append(t.approvalTitle).append("\n").append(t.approvalDescription);if(!t.output.isEmpty())s.append("\n\n结果：\n").append(t.output);if(!t.error.isEmpty())s.append("\n\n提示：").append(t.error);if(!t.artifacts.isEmpty())s.append("\n\n产物：").append(t.artifacts.size()).append(" 个");return s.toString();}
    private String bundleSha(AgentTaskStore.Task task){try{return new org.json.JSONObject(task.submissionJson).optString("bundle_sha256","");}catch(Exception ignored){return "";}}
    private String inputText(AgentTaskStore.Task t){try{return new org.json.JSONObject(t.submissionJson).optString("input","");}catch(Exception ignored){return "";}}
    private int round(AgentTaskStore.Task t){int result=1;while(!t.parentClientTaskId.isEmpty()){result++;AgentTaskStore.Task p=tasks.get(t.parentClientTaskId);if(p==null)break;t=p;}return result;}
    private static String size(long bytes){if(bytes>=1024L*1024L)return String.format(java.util.Locale.CHINA,"%.1f MiB",bytes/(1024f*1024f));if(bytes>=1024L)return String.format(java.util.Locale.CHINA,"%.1f KiB",bytes/1024f);return bytes+" B";}
    private static String status(AgentTaskStore.Status s){switch(s){case SUBMITTING:return"提交中/结果待确认";case RUNNING:return"运行中";case WAITING_FOR_APPROVAL:return"等待批准";case STOPPING:return"正在停止";case COMPLETED:return"已完成";case FAILED:return"失败";case CANCELLED:return"已取消";default:return"已中断";}}
    private Button button(String text){Button b=new Button(activity);b.setText(text);return b;}
    private void addAction(LinearLayout parent,Button button){parent.addView(button,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));}
    private ScrollView scroll(View v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private void toast(String m){Toast.makeText(activity,m,Toast.LENGTH_LONG).show();}
    private static String message(Throwable e){return e.getMessage()==null?"未知错误":e.getMessage();}
}
