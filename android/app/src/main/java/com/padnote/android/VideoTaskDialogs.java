package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native review and explicit approval UI for a Bridge video bundle task. */
final class VideoTaskDialogs {
    interface Factory { VideoTaskDialogs create(Activity activity,AgentConnectionStore connections,
            AgentTaskStore tasks,ExecutorService worker); }
    private final Activity activity;
    private final AgentConnectionStore connections;
    private final AgentTaskStore tasks;
    private final ExecutorService worker;
    private final VideoOperationStore store;
    private final VideoTaskClient client;
    private final Handler main=new Handler(Looper.getMainLooper());

    VideoTaskDialogs(Activity activity,AgentConnectionStore connections,AgentTaskStore tasks,
                     ExecutorService worker){this(activity,connections,tasks,worker,new VideoOperationStore(activity),new VideoTaskClient());}
    VideoTaskDialogs(Activity activity,AgentConnectionStore connections,AgentTaskStore tasks,
                     ExecutorService worker,VideoOperationStore store,VideoTaskClient client){
        this.activity=activity;this.connections=connections;this.tasks=tasks;this.worker=worker;this.store=store;this.client=client;
    }

    void show(AgentTaskStore.Task task){
        if(!VideoTaskClient.isVideoBundle(task)){toast("该任务不是可审阅的Bridge视频任务包");return;}
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView message=new TextView(activity);root.addView(message,new LinearLayout.LayoutParams(-1,-2));
        TextView diagnosticDetails=new TextView(activity);diagnosticDetails.setVisibility(View.GONE);
        LinearLayout scenes=new LinearLayout(activity);scenes.setOrientation(LinearLayout.VERTICAL);root.addView(scenes);
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);root.addView(actions);
        Button diagnostics=button("检查电脑视频依赖"),refresh=button("刷新状态与审阅"),initialize=button("初始化视频任务"),
                storyboard=button("生成下一版分镜"),approve=button("批准当前分镜"),cancel=button("取消排队中的操作"),stop=button("请求停止生成分镜"),stopStatus=button("查看停止状态"),retry=button("用同一幂等键重试提交"),
                reconcile=button("核对电脑端结果"),
                diagnosticDetailsToggle=button("显示技术诊断详情");
        diagnosticDetailsToggle.setVisibility(View.GONE);actions.addView(diagnostics,new LinearLayout.LayoutParams(-1,-2));
        actions.addView(diagnosticDetailsToggle,new LinearLayout.LayoutParams(-1,-2));
        for(Button b:new Button[]{refresh,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile})actions.addView(b,new LinearLayout.LayoutParams(-1,-2));
        root.addView(diagnosticDetails,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("视频分镜").setView(scroll(root)).setNegativeButton("关闭",null).create();
        AtomicBoolean busy=new AtomicBoolean(),dialogActive=new AtomicBoolean(true);JSONObject[] displayed={null};boolean[] imagesReady={false};
        AtomicBoolean storeHealthy=new AtomicBoolean(true);
        Runnable[] reload=new Runnable[1];
        reload[0]=()->{
            if(!dialog.isShowing()||!busy.compareAndSet(false,true))return;
            imagesReady[0]=false;approve.setEnabled(false);message.setText("正在读取电脑视频任务…");
            initialize.setEnabled(false);storyboard.setEnabled(false);cancel.setEnabled(false);stop.setEnabled(false);stopStatus.setEnabled(false);retry.setEnabled(false);reconcile.setEnabled(false);
            worker.execute(()->{
                String status="",error="";JSONObject review=null,diagnostic=null;VideoOperationStore.Operation op=null;JSONObject inspection=null;boolean initSucceeded=false,freshReview=false,freshOperation=false,verifiedStop=false;
                try{
                    AgentConnectionStore.Config config=current(task);java.util.List<VideoOperationStore.Operation> history;
                    try{store.bind(task);history=store.operations(task);}catch(Exception corrupt){storeHealthy.set(false);throw corrupt;}
                    if(!history.isEmpty())op=history.get(history.size()-1);
                    // Load the prior successful snapshot first; a newer remote result for the
                    // active operation below must remain authoritative for review binding.
                    for(VideoOperationStore.Operation old:history){if("initialize".equals(old.action)&&"succeeded".equals(old.status))initSucceeded=true;if("succeeded".equals(old.status)&&old.result!=null)inspection=old.result;
                        if("storyboard".equals(old.action)&&"cancelled".equals(old.status)&&old.cancelControl!=null&&"verified_cancelled".equals(old.cancelControl.optString("status")))verifiedStop=true;}
                    if(op!=null&&"prepared".equals(op.status)){op=store.update(task,op.clientOperationId,"","submission_uncertain","",null,true);}
                    if(op!=null&& !op.operationId.isEmpty()&&("queued".equals(op.status)||"running".equals(op.status)||"unknown".equals(op.status))){
                        JSONObject remote=client.operation(task,config,op);
                        current(task);
                        op=applyOperationResponse(task,op,remote);
                        freshOperation=op!=null;
                        if(op==null)op=store.latest(task);
                        if("succeeded".equals(op.status)&&op.result!=null){inspection=op.result;if("initialize".equals(op.action))initSucceeded=true;}
                    }
                    if(op!=null&&op.cancelControl!=null&&
                            ("running".equals(op.status)||"unknown".equals(op.status))){
                        try{JSONObject control=client.cancelRequest(task,current(task),op);current(task);op=store.applyCancelControl(task,op,control);if(op==null)op=store.latest(task);}
                        catch(Exception ignored){/* A failed read never changes the saved stop intent or operation. */}
                    }
                    if(op!=null&&"initialize".equals(op.action)&&"succeeded".equals(op.status))initSucceeded=true;
                    try{diagnostic=client.diagnostics(task,current(task));current(task);}catch(Exception e){error="电脑依赖诊断不可用。";}
                    try{review=client.review(task,current(task));current(task);validateReviewMatchesInspection(review,inspection);store.saveReview(task,review);freshReview=true;}catch(Exception e){try{review=store.review(task);}catch(Exception ignored){review=null;}if(error.isEmpty())error=friendlyFailure(e,task);}
                    if(review!=null&&freshReview){
                        JSONArray list=review.getJSONArray("scenes");java.util.ArrayList<android.graphics.Bitmap> bitmaps=new java.util.ArrayList<>();long pixels=0;
                        for(int i=0;i<list.length();i++){
                            JSONObject scene=list.getJSONObject(i),meta=scene.getJSONObject("preview");pixels+=(long)meta.getInt("width")*meta.getInt("height");if(pixels>16_777_216L)throw new IllegalStateException("缩略图总内存预算超限，未启用批准");byte[] bytes=client.preview(task,current(task),review,meta);current(task);
                            android.graphics.Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                            if(bitmap==null)throw new IllegalStateException("预览PNG无法解码");bitmaps.add(bitmap);
                        }
                        if(task.matches(connections.get(task.connectionId))){displayed[0]=review;imagesReady[0]=true;}
                        final JSONObject shown=review;final java.util.List<android.graphics.Bitmap> previews=bitmaps;
                        final String finalError=error;final VideoOperationStore.Operation finalOp=op;final JSONObject finalDiagnostic=diagnostic,finalInspection=inspection;
                        main.post(()->{if(!dialog.isShowing())return;scenes.removeAllViews();try{
                            TextView heading=new TextView(activity);heading.setText("第 "+shown.getInt("revision")+" 版 · "+shown.getJSONObject("episode").getString("title")+"\n批准只记录审核结论，不会启动配音、付费请求或渲染。\n");scenes.addView(heading);
                            JSONArray items=shown.getJSONArray("scenes");for(int i=0;i<items.length();i++){JSONObject s=items.getJSONObject(i);TextView text=new TextView(activity);text.setText("场景 "+(i+1)+" · "+s.getString("visual_kind")+"\n"+s.getString("learning_objective")+"\n解说："+s.getString("narration")+"\n屏幕文字："+join(s.getJSONArray("screen_text"))+"\n");scenes.addView(text);ImageView image=new ImageView(activity);image.setAdjustViewBounds(true);image.setMaxHeight(dp(240));image.setImageBitmap(previews.get(i));scenes.addView(image);}
                        }catch(Exception ignored){}message.setText(summary(task,finalOp,finalDiagnostic,finalInspection,finalError));}
                    );
                    } else {if(review!=null)error+=(error.isEmpty()?"":"\n")+"当前显示的是本地缓存审阅，未取得电脑端最新版本，不能批准。连接可用后刷新再审阅。";status=summary(task,op,diagnostic,inspection,error);}
                }catch(Exception e){
                    status=friendlyFailure(e,task);
                    try{review=store.review(task);}catch(Exception ignored){}
                    if(review!=null)status+="\n本机保留了这版分镜文字；图片未缓存，连接恢复且身份仍匹配后才能重新加载。缓存内容不能用于批准。";
                }
                final String output=status;final VideoOperationStore.Operation active=op;final JSONObject diag=diagnostic,inspect=inspection;
                final boolean initialized=initSucceeded,operationFresh=freshOperation,stopped=verifiedStop;final JSONObject visible=review;
                main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(visible!=null&&scenes.getChildCount()==0)renderReviewText(scenes,visible);
                    if(!output.isEmpty())message.setText(output);
                    configure(task,active,diag,inspect,initialized,visible,imagesReady[0],storeHealthy.get(),operationFresh,stopped,message,diagnostics,refresh,diagnosticDetails,diagnosticDetailsToggle,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);
                    if(active!=null&&("queued".equals(active.status)||"running".equals(active.status)))main.postDelayed(()->{if(isDialogActive(dialog,dialogActive))pollOperation(task,dialog,busy,reload[0],dialogActive);},3000L);
                });
            });
        };
        diagnostics.setOnClickListener(v->run(task,dialog,busy,()->{
            AgentConnectionStore.Config c=current(task);AgentConnectionClient.ProbeResult probe=AgentConnectionClient.probeResult(c);
            if(!connections.applyProbeSuccess(c.id,c.revision,probe))throw new IllegalStateException("连接身份变化，请重新打开任务");
            c=current(task);JSONObject d=client.diagnostics(task,c);c=current(task);StringBuilder technical=new StringBuilder("诊断详情（电脑返回）：\n");JSONObject checks=d.getJSONObject("checks");for(String key:new String[]{"worker_modules","storyboard_browser","render_browser","ffmpeg","ffprobe","tts"}){JSONObject item=checks.getJSONObject(key);technical.append(diagnosticName(key)).append("：").append(diagnosticStatus(item.getString("status"))).append(" · ").append(item.getString("reason")).append('\n');}
            technical.append("视频操作能力：").append(c.capabilities.contains("video_operations")?"可用":"不可用").append("\nruntime_verified=false；video_ready=false；完整运行环境尚未核验；配音/渲染以依赖详情为准，批准不启动生产。\n");
            VideoOperationStore.Operation latest=store.latest(task);JSONObject inspection=latestInspection(task);boolean initialized=false;for(VideoOperationStore.Operation item:store.operations(task))if("initialize".equals(item.action)&&"succeeded".equals(item.status))initialized=true;
            final boolean initDone=initialized,stopped=store.hasVerifiedStoryboardStop(task);final String tech=technical.toString();main.post(()->{message.setText("依赖检查已完成。这不代表完整运行环境已核验；配音/渲染以依赖详情为准，批准不启动生产。");diagnosticDetails.setText(tech);diagnosticDetails.setVisibility(View.GONE);diagnosticDetailsToggle.setText("显示技术诊断详情");diagnosticDetailsToggle.setVisibility(View.VISIBLE);configure(task,latest,d,inspection,initDone,displayed[0],imagesReady[0],storeHealthy.get(),false,stopped,message,diagnostics,refresh,diagnosticDetails,diagnosticDetailsToggle,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);});},reload[0]));
        diagnosticDetailsToggle.setOnClickListener(v->{boolean show=diagnosticDetails.getVisibility()!=View.VISIBLE;diagnosticDetails.setVisibility(show?View.VISIBLE:View.GONE);diagnosticDetailsToggle.setText(show?"隐藏技术诊断详情":"显示技术诊断详情");});
        refresh.setOnClickListener(v->reload[0].run());
        initialize.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"initialize",displayed[0],reload[0]);});
        storyboard.setOnClickListener(v->{
            JSONObject state;
            try{state=latestInspection(task);}catch(Exception e){message.setText("无法读取本地审核状态，请刷新后重试。");return;}
            if(state!=null&&"approved".equals(state.optString("status"))){
                new AlertDialog.Builder(activity).setTitle("生成视频并调用云配音？")
                        .setMessage("本次会把已批准稿发送到你配置的配音服务，再在电脑渲染。配音可能产生供应商费用；结果未知时不会自动重试。")
                        .setNegativeButton("暂不生成",null).setPositiveButton("同意配音并生成",(d,w)->{
                            disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"produce",displayed[0],reload[0]);
                        }).show();
            }else{
                new AlertDialog.Builder(activity).setTitle("发送笔记材料并生成分镜？")
                        .setMessage("任务包中的笔记完整 Markdown 正文以及本任务填写的受众和学习目标会发送到“"+task.connectionName+"”电脑，再调用你配置的模型供应商账户生成分镜；供应商可能收费。只确认本次分镜请求。")
                        .setNegativeButton("返回",null).setPositiveButton("发送材料并生成分镜",(d,w)->{
                            disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"storyboard",displayed[0],reload[0]);
                        }).show();
            }
        });
        approve.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"approve",displayed[0],reload[0]);});
        retry.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);retrySubmission(task,dialog,busy,reload[0]);});
        cancel.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);cancel(task,dialog,busy,reload[0]);});
        stop.setOnClickListener(v->{
            VideoOperationStore.Operation active;
            try{active=store.latest(task);}catch(Exception e){message.setText("无法读取当前视频操作，请刷新后重试。");return;}
            if(active!=null&&"produce".equals(active.action)){
                new AlertDialog.Builder(activity).setTitle("请求停止配音与渲染？")
                    .setMessage("电脑端会核对并停止这次确切的配音与渲染操作。已发生的供应商费用和云端处理无法撤回；只有电脑确认停止后才会显示已停止。")
                    .setNegativeButton("继续运行",null).setPositiveButton("请求停止",(d,w)->{
                        disable(initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);
                        requestRunningStop(task,dialog,dialogActive,busy,reload[0]);
                    }).show();
            } else {
                disable(initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);
                requestRunningStop(task,dialog,dialogActive,busy,reload[0]);
            }
        });
        stopStatus.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);refreshRunningStop(task,dialog,dialogActive,busy,reload[0]);});
        reconcile.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry,reconcile);reconcileOperation(task,dialog,dialogActive,busy,reload[0]);});
        dialog.setOnDismissListener(d->{dialogActive.set(false);main.removeCallbacks(reload[0]);});dialog.show();reload[0].run();
    }

    private void configure(AgentTaskStore.Task task,VideoOperationStore.Operation op,JSONObject diagnostic,JSONObject inspection,
                           boolean initializedSuccessfully,JSONObject visibleReview,boolean imagesReady,boolean storeHealthy,boolean operationStatusFresh,boolean verifiedStop,
                           TextView message,Button diagnostics,Button refresh,TextView diagnosticDetails,Button diagnosticDetailsToggle,
                           Button initialize,Button storyboard,Button approve,Button cancel,Button stop,Button stopStatus,Button retry,Button reconcile){
        AgentConnectionStore.Config c=connections.get(task.connectionId);boolean bound=task.matches(c);
        if(!bound){message.setText("此视频任务仍绑定原连接；连接已撤销或身份已变化。网络暂时不可用时，恢复网络后可刷新重查。若原连接配置已删除或身份信息变化，请按应用的连接流程新建并验证连接；这不会恢复旧任务绑定。旧任务仅保留历史，如需继续请新建任务。始终不会自动批准、配音或渲染。");diagnosticDetails.setVisibility(View.GONE);diagnosticDetailsToggle.setVisibility(View.GONE);disable(diagnostics,refresh,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);return;}
        if(!storeHealthy){message.setText("本地视频操作记录无法安全读取，已停用操作以防重复提交。请保留原记录并检查设备存储。");diagnosticDetails.setVisibility(View.GONE);diagnosticDetailsToggle.setVisibility(View.GONE);disable(diagnostics,refresh,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);return;}
        if(verifiedStop)message.append("\n当前任务的分镜已由电脑端核实停止，原材料与历史仍保留。请返回笔记新建视频任务继续。");
        boolean cap=c.capabilities.contains("video_operations");
        boolean runningStoryboard=op!=null&&"storyboard".equals(op.action)&&"running".equals(op.status);
        if(!cap&&!runningStoryboard)message.setText("这台电脑尚未确认支持视频操作。请重新验证连接后查看详情；旧任务不会自动绑定到变化后的连接。");
        boolean active=op!=null&&!VideoOperationStore.terminal(op.status);
        initialize.setVisibility(active||initializedSuccessfully?View.GONE:View.VISIBLE);
        storyboard.setVisibility(active||verifiedStop||visibleReview!=null?View.GONE:View.VISIBLE);
        boolean initialized=inspection!=null&&"initialized".equals(inspection.optString("status"))&&"idle".equals(inspection.optString("phase"));
        boolean reviewReady=imagesReady&&visibleReview!=null;boolean canApprove=!verifiedStop&&reviewReady&&"awaiting_storyboard_review".equals(visibleReview.optString("status"));
        boolean approved=inspection!=null&&"approved".equals(inspection.optString("status"))&&"approval_pending".equals(inspection.optString("phase"));
        boolean canProduce=approved&&c.capabilities.contains("video_production")&&op!=null&&"approve".equals(op.action)&&"succeeded".equals(op.status);
        storyboard.setText(canProduce?"生成视频（配音与渲染）…":"生成分镜");
        storyboard.setVisibility(active||verifiedStop||(!initialized&&!canProduce)?View.GONE:View.VISIBLE);
        storyboard.setEnabled(cap&&!active&&!verifiedStop&&(initialized||canProduce));
        approve.setText(canApprove?"批准第"+visibleReview.optInt("revision")+"版分镜":"批准当前分镜");
        approve.setVisibility(canApprove?View.VISIBLE:View.GONE);approve.setEnabled(cap&&imagesReady&&canApprove&&!active);
        boolean queued=op!=null&&"queued".equals(op.status)&&!op.operationId.isEmpty();cancel.setVisibility(queued?View.VISIBLE:View.GONE);cancel.setEnabled(cap);
        boolean stopSupported=op!=null&&("storyboard".equals(op.action)||"produce".equals(op.action))&&
                ("running".equals(op.status)||"produce".equals(op.action)&&"unknown".equals(op.status));
        boolean canStop=operationStatusFresh&&stopSupported&&op.cancelControl==null;
        boolean repeatedStop=operationStatusFresh&&stopSupported&&op.cancelControl!=null&&
                ("pending".equals(op.cancelControl.optString("status"))||"unconfirmed".equals(op.cancelControl.optString("status")));
        canStop=canStop||repeatedStop;
        stop.setText(repeatedStop?"再次请求停止":op!=null&&"produce".equals(op.action)?"请求停止配音与渲染":"请求停止生成分镜");stop.setVisibility(canStop?View.VISIBLE:View.GONE);stop.setEnabled(canStop);
        boolean canViewStop=op!=null&&op.cancelControl!=null&&("running".equals(op.status)||"unknown".equals(op.status));
        stopStatus.setVisibility(canViewStop?View.VISIBLE:View.GONE);stopStatus.setEnabled(canViewStop);
        if(op!=null&&op.cancelControl!=null){String control=op.cancelControl.optString("status");if("pending".equals(control))message.append("\n停止请求尚未确认是否送达；请查看电脑端停止状态，不会重跑原操作。");else if("requested".equals(control))message.append("\n电脑端已记录停止请求，尚未核实操作已结束；这不会撤回已发生的费用或写入。");else if("unconfirmed".equals(control))message.append("\n电脑端暂时不能确认分镜已停止；原操作不会重跑。费用与已写入内容不会撤回。");}
        boolean canRetry=op!=null&&"submission_uncertain".equals(op.status)&&op.operationId.isEmpty();retry.setVisibility(canRetry?View.VISIBLE:View.GONE);retry.setEnabled(cap);
        boolean canReconcile=op!=null&&"unknown".equals(op.status)&&!op.operationId.isEmpty();
        reconcile.setText(op!=null&&op.cancelControl!=null?"核对电脑端停止或操作结果":"核对电脑端结果");reconcile.setVisibility(canReconcile?View.VISIBLE:View.GONE);reconcile.setEnabled(canReconcile);
        initialize.setEnabled(cap&&!active&&!initializedSuccessfully);diagnostics.setEnabled(true);refresh.setEnabled(true);diagnosticDetailsToggle.setEnabled(true);
    }

    private void pollOperation(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,Runnable reload,AtomicBoolean dialogActive){
        if(!isDialogActive(dialog,dialogActive)||!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{boolean keepPolling=false,refreshAll=false;String warning="";
            try{AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);AgentConnectionStore.Config c=current(currentTask);
                VideoOperationStore.Operation op=store.latest(currentTask);
                if(op!=null&&("queued".equals(op.status)||"running".equals(op.status))&&!op.operationId.isEmpty()){
                    JSONObject remote=client.operation(currentTask,c,op);
                    current(currentTask);
                    VideoOperationStore.Operation applied=applyOperationResponse(currentTask,op,remote);
                    VideoOperationStore.Operation currentOp=applied==null?store.latest(currentTask):applied;
                    keepPolling=currentOp!=null&&("queued".equals(currentOp.status)||"running".equals(currentOp.status));
                    refreshAll=!keepPolling&&applied!=null;
                }
            }catch(Exception e){warning=friendlyFailure(e,task);}
            final boolean repeat=keepPolling,full=refreshAll;final String error=warning;
            main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(!error.isEmpty())toast(error);
                if(full)reload.run();else if(repeat)main.postDelayed(()->{if(isDialogActive(dialog,dialogActive))pollOperation(task,dialog,busy,reload,dialogActive);},3000L);});
        });}catch(java.util.concurrent.RejectedExecutionException closed){busy.set(false);}
    }
    private boolean isDialogActive(AlertDialog dialog,AtomicBoolean dialogActive){return dialogActive.get()&&dialog.isShowing()&&!activity.isFinishing()&&!activity.isDestroyed();}

    private VideoOperationStore.Operation applyOperationResponse(AgentTaskStore.Task task,
                                                                  VideoOperationStore.Operation expected,JSONObject remote)throws Exception{
        if("cancelled".equals(remote.getString("status"))){
            if("queued".equals(expected.status))return store.updateIfCurrent(task,expected,
                    remote.getString("operation_id"),"cancelled",
                    remote.isNull("error")?"":remote.getString("error"),remote.isNull("result")?null:remote.getJSONObject("result"),false);
            if(expected.cancelControl==null)return null;
            JSONObject control=client.cancelRequest(task,current(task),expected);current(task);
            if(!"verified_cancelled".equals(control.getString("status")))return null;
            return store.applyCancelControl(task,expected,control);
        }
        return store.updateIfCurrent(task,expected,remote.getString("operation_id"),remote.getString("status"),
                remote.isNull("error")?"":remote.getString("error"),remote.isNull("result")?null:remote.getJSONObject("result"),false);
    }

    private void start(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,String action,JSONObject displayedReview,Runnable reload){
        if(!busy.compareAndSet(false,true))return;
        worker.execute(()->{VideoOperationStore.Operation[] intent={null};try{
            AgentConnectionStore.Config config=current(task);store.bind(task);
            if(("storyboard".equals(action)||"approve".equals(action))&&store.hasVerifiedStoryboardStop(task))
                throw new IllegalStateException("此任务的分镜已由电脑端核实停止；请返回笔记新建视频任务");
            JSONObject parameters=new JSONObject();
            if(!config.capabilities.contains("video_operations"))throw new IllegalStateException("请先刷新连接能力");
            if("storyboard".equals(action)){
                JSONObject review=displayedReview;if(review==null)review=store.review(task);
                if(review!=null){VideoTaskClient.validateReview(review,task);long revision=review.getLong("revision"),cursor=review.getLong("event_cursor");if(revision>=9007199254740991L||cursor>9007199254740989L)throw new IllegalStateException("分镜版本或事件游标已超出安全范围");parameters.put("revision",revision+1).put("event_cursor",cursor);}
                else {JSONObject inspection=latestInspection(task);if(inspection==null||!"initialized".equals(inspection.optString("status"))||!"idle".equals(inspection.optString("phase")))throw new IllegalStateException("请先初始化并刷新任务状态");long cursor=inspection.getLong("event_cursor");if(cursor>9007199254740989L)throw new IllegalStateException("事件游标已超出安全范围");parameters.put("revision",1).put("event_cursor",cursor);}
            } else if("approve".equals(action)){
                if(displayedReview==null)throw new IllegalStateException("请先刷新并完整查看当前分镜与图片");VideoTaskClient.validateReview(displayedReview,task);
                JSONObject saved=store.review(task);if(saved==null||!sameReview(saved,displayedReview))throw new IllegalStateException("审阅版本已变化，请刷新后重新查看");
                long cursor=displayedReview.getLong("event_cursor");if(cursor>=9007199254740991L)throw new IllegalStateException("事件游标已超出安全范围");
                parameters.put("revision",displayedReview.getLong("revision")).put("event_cursor",cursor)
                        .put("review_sha256",displayedReview.getString("review_sha256")).put("lesson_ir_sha256",displayedReview.getString("lesson_ir_sha256"));
            } else if("produce".equals(action)){
                JSONObject approved=latestInspection(task);JSONObject review=store.review(task);
                VideoTaskClient.validateReview(review,task);
                if(approved==null||!"approved".equals(approved.optString("status"))||!"approval_pending".equals(approved.optString("phase"))||
                        review==null||!"approved".equals(review.optString("status"))||
                        approved.optLong("revision",-1)!=review.optLong("revision",-2)||approved.optLong("event_cursor",-1)!=review.optLong("event_cursor",-2)||
                        !approved.optString("review_sha256").equals(review.optString("review_sha256"))||!approved.optString("lesson_ir_sha256").equals(review.optString("lesson_ir_sha256")))
                    throw new IllegalStateException("请刷新并核对已批准版本后再生成");
                parameters.put("revision",approved.getLong("revision")).put("event_cursor",approved.getLong("event_cursor"))
                        .put("review_sha256",approved.getString("review_sha256")).put("lesson_ir_sha256",approved.getString("lesson_ir_sha256"))
                        .put("allow_cloud_tts",true);
            }
            VideoOperationStore.Operation operation=store.reserve(task,action,parameters,
                    "approve".equals(action)?displayedReview:null);
            intent[0]=operation;
            if("submission_uncertain".equals(operation.status)&&operation.operationId.isEmpty()){
                // Explicit retry reuses the exact key and body.
            }else if(!"prepared".equals(operation.status))throw new IllegalStateException("已有同版本视频操作，请刷新其状态");
            JSONObject response=client.submit(task,current(task),operation);
            current(task);
            store.updateIfCurrent(task,operation,response.getString("operation_id"),response.getString("status"),
                    response.isNull("error")?"":response.getString("error"),response.isNull("result")?null:response.getJSONObject("result"),false);
        }catch(Exception e){try{if(intent[0]!=null&&intent[0].operationId.isEmpty())store.updateIfCurrent(task,intent[0],"","submission_uncertain","",null,true);}catch(Exception ignored){}
            main.post(()->toast("操作未能确认；保留原幂等键，可刷新或同键重试。"+friendlyFailure(e,task)));}
            main.post(()->{busy.set(false);if(dialog.isShowing())reload.run();});
        });
    }

    private void reconcileOperation(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean dialogActive,
                                    AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{
            String notice="";
            try{
                if(!isDialogActive(dialog,dialogActive)){busy.set(false);return;}
                AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);
                AgentConnectionStore.Config config=current(currentTask);
                VideoOperationStore.Operation expected=store.latest(currentTask);
                if(expected==null||!"unknown".equals(expected.status)||expected.operationId.isEmpty())
                    throw new IllegalStateException("当前没有可核对的未知操作");
                JSONObject response=client.reconcile(currentTask,config,expected);
                current(currentTask);
                VideoOperationStore.Operation applied=store.updateReconciledIfCurrent(currentTask,expected,
                        response.getString("operation_id"),response.getString("status"),
                        response.isNull("error")?"":response.getString("error"),
                        response.isNull("result")?null:response.getJSONObject("result"));
                if(applied==null)notice="本地操作状态刚刚发生变化；已忽略较早的核对响应。请刷新查看当前结果。";
                else if("unknown".equals(applied.status))notice="电脑端当前仍无法确认结果；原操作不会重跑。稍后可以再次核对。";
                else if("cancelled".equals(applied.status))notice="电脑端已核实分镜停止。已发生的费用或写入不会撤回。";
            }catch(Exception error){
                if(!task.matches(connections.get(task.connectionId)))
                    notice="连接身份已变化，已忽略核对响应。旧任务仍绑定原连接。";
                else if(error instanceof VideoTaskClient.HttpError
                        &&(((VideoTaskClient.HttpError)error).status==409||((VideoTaskClient.HttpError)error).status==503))
                    notice="电脑端当前无法核对结果。原操作不会重跑，请稍后再试。";
                else notice="核对未完成，原操作仍保留为结果未知，不会重新提交。";
            }
            final String message=notice;
            main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(!message.isEmpty())toast(message);reload.run();});
        });}catch(java.util.concurrent.RejectedExecutionException closed){busy.set(false);}
    }

    private void retrySubmission(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;worker.execute(()->{try{AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);AgentConnectionStore.Config c=current(currentTask);VideoOperationStore.Operation op=store.active(currentTask);if(op==null||!"submission_uncertain".equals(op.status)||!op.operationId.isEmpty())throw new IllegalStateException("没有可安全同键重试的提交");JSONObject response=client.submit(currentTask,c,op);current(currentTask);store.updateIfCurrent(currentTask,op,response.getString("operation_id"),response.getString("status"),response.isNull("error")?"":response.getString("error"),response.isNull("result")?null:response.getJSONObject("result"),false);}catch(Exception e){main.post(()->toast("同键重试仍未确认："+friendlyFailure(e,task)));}main.post(()->{busy.set(false);if(dialog.isShowing())reload.run();});});
    }
    private void cancel(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;worker.execute(()->{try{AgentTaskStore.Task t=tasks.get(task.clientTaskId);AgentConnectionStore.Config c=current(t);VideoOperationStore.Operation op=store.active(t);if(op==null||!"queued".equals(op.status))throw new IllegalStateException("只有排队中的操作可以取消");JSONObject response=client.cancel(t,c,op);current(t);store.updateIfCurrent(t,op,response.getString("operation_id"),response.getString("status"),response.isNull("error")?"":response.getString("error"),response.isNull("result")?null:response.getJSONObject("result"),false);}catch(Exception e){main.post(()->toast("取消未确认，请刷新电脑端状态："+friendlyFailure(e,task)));}main.post(()->{busy.set(false);if(dialog.isShowing())reload.run();});});
    }
    private void requestRunningStop(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean dialogActive,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{String notice="";try{
            if(!isDialogActive(dialog,dialogActive)){busy.set(false);return;}
            AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);AgentConnectionStore.Config config=current(currentTask);
            VideoOperationStore.Operation expected=store.latest(currentTask);
            if(expected==null||!("storyboard".equals(expected.action)||"produce".equals(expected.action))||
                    !("running".equals(expected.status)||"produce".equals(expected.action)&&"unknown".equals(expected.status)))throw new IllegalStateException("当前没有可停止的运行中操作");
            boolean createdIntent=expected.cancelControl==null;
            VideoOperationStore.Operation intent=store.prepareRunningCancel(currentTask,expected);
            if(intent==null)throw new IllegalStateException("操作状态已变化，请刷新后再试");
            if(!isDialogActive(dialog,dialogActive)){if(createdIntent)store.clearUnsentRunningCancel(currentTask,intent);busy.set(false);return;}
            JSONObject response=client.cancelRunning(currentTask,config,intent);
            current(currentTask);
            VideoOperationStore.Operation applied=store.applyCancelControl(currentTask,intent,response);
            if(applied==null)notice="任务状态刚刚变化；停止响应未应用，请刷新查看。";
            else if("verified_cancelled".equals(response.getString("status")))notice="电脑端已核实操作停止。已发生的供应商费用或写入不会撤回。";
            else if("too_late".equals(response.getString("status")))notice="电脑端操作已先结束；停止没有改变原结果。";
            else if("unconfirmed".equals(response.getString("status")))notice="电脑端暂时不能确认分镜已停止；原操作不会重跑。费用与已写入内容不会撤回。";
            else notice="电脑端已记录停止请求，但尚未核实操作已结束；费用与已写入内容不会撤回。";
            if(applied!=null&&"too_late".equals(response.getString("status"))){
                JSONObject operation=client.operation(currentTask,current(currentTask),applied);current(currentTask);
                store.updateIfCurrent(currentTask,applied,operation.getString("operation_id"),operation.getString("status"),
                        operation.isNull("error")?"":operation.getString("error"),operation.isNull("result")?null:operation.getJSONObject("result"),false);
            }
        }catch(Exception error){notice=error instanceof VideoTaskClient.HttpError&&((VideoTaskClient.HttpError)error).status==404?
                "此电脑助手尚未提供运行中停止功能；原分镜仍在本机保留。":"停止请求结果尚未确认；请刷新查看电脑端状态。未知操作不会重跑。";}
            final String message=notice;main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(!message.isEmpty())toast(message);reload.run();});
        });}catch(java.util.concurrent.RejectedExecutionException closed){busy.set(false);}
    }
    private void refreshRunningStop(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean dialogActive,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{String notice="";try{
            if(!isDialogActive(dialog,dialogActive)){busy.set(false);return;}
            AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);AgentConnectionStore.Config config=current(currentTask);
            VideoOperationStore.Operation expected=store.latest(currentTask);
            if(expected==null||expected.cancelControl==null||expected.operationId.isEmpty())throw new IllegalStateException("没有已保存的停止请求");
            JSONObject response=client.cancelRequest(currentTask,config,expected);current(currentTask);
            VideoOperationStore.Operation applied=store.applyCancelControl(currentTask,expected,response);
            if(applied==null)notice="任务状态已变化；旧的停止状态未应用。";
            else switch(response.getString("status")){
                case "verified_cancelled":notice="电脑端已核实操作停止；已发生的供应商费用或写入不会撤回。";break;
                case "too_late":notice="电脑端操作已先结束；请刷新查看实际结果。";break;
                case "unconfirmed":notice="电脑端暂时不能确认分镜已停止。原操作不会重跑；可核对电脑端操作结果。";break;
                default:notice="电脑端已记录停止请求，但尚未核实操作已结束。费用与已写入内容不会撤回。";
            }
            if(applied!=null&&"too_late".equals(response.getString("status"))&&"running".equals(applied.status)){
                JSONObject operation=client.operation(currentTask,current(currentTask),applied);current(currentTask);
                store.updateIfCurrent(currentTask,applied,operation.getString("operation_id"),operation.getString("status"),
                        operation.isNull("error")?"":operation.getString("error"),operation.isNull("result")?null:operation.getJSONObject("result"),false);
            }
        }catch(Exception error){
            if(error instanceof VideoTaskClient.HttpError&&((VideoTaskClient.HttpError)error).status==404)
                notice="这台电脑助手没有运行中停止接口；本机仍保留请求与分镜，更新电脑助手后可继续查看。";
            else if(error instanceof VideoTaskClient.HttpError&&((VideoTaskClient.HttpError)error).status==409)
                notice="电脑端还没有找到停止请求。刷新确认分镜仍在执行后，可明确选择“再次请求停止”。";
            else notice="暂时无法读取停止状态。原请求仍保留，恢复连接后可再次查看。";
        }
            final String message=notice;main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(!message.isEmpty())toast(message);reload.run();});
        });}catch(java.util.concurrent.RejectedExecutionException closed){busy.set(false);}
    }
    private void run(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,Throwing action,Runnable reload){if(!busy.compareAndSet(false,true))return;worker.execute(()->{Exception failure=null;try{current(task);action.run();}catch(Exception e){failure=e;}final Exception error=failure;main.post(()->{busy.set(false);if(error!=null){toast(friendlyFailure(error,task));if(dialog.isShowing())reload.run();}});});}
    private static void renderReviewText(LinearLayout scenes,JSONObject review){try{
        TextView heading=new TextView(scenes.getContext());heading.setText("第 "+review.getInt("revision")+" 版 · "+review.getJSONObject("episode").getString("title")+"\n缓存文字仅供查看；完整图片未缓存，不能据此批准。批准也不会启动配音或渲染。\n");scenes.addView(heading);
        JSONArray items=review.getJSONArray("scenes");for(int i=0;i<items.length();i++){JSONObject scene=items.getJSONObject(i);TextView text=new TextView(scenes.getContext());text.setText("场景 "+(i+1)+" · "+scene.getString("visual_kind")+"\n"+scene.getString("learning_objective")+"\n解说："+scene.getString("narration")+"\n屏幕文字："+join(scene.getJSONArray("screen_text"))+"\n");scenes.addView(text);}
    }catch(Exception ignored){scenes.removeAllViews();}}
    private String friendlyFailure(Exception e,AgentTaskStore.Task task){
        if(!task.matches(connections.get(task.connectionId)))return "此视频任务连接已撤销或身份已变化，旧任务不会自动重新绑定；请保留本地历史并新建任务。";
        if(e instanceof java.io.IOException)return "暂时无法连接电脑。请检查网络后刷新重查；未确认的操作不会自动重放。";
        if(e instanceof VideoTaskClient.HttpError)return "电脑端暂未返回可验证状态。刷新只读取状态；结果未知时不会自动重放。";
        return "视频操作未能完成或尚未确认。请刷新查看；不要据此重复发起未知操作。";
    }
    private JSONObject latestInspection(AgentTaskStore.Task task)throws Exception{JSONObject result=null;for(VideoOperationStore.Operation o:store.operations(task))if("succeeded".equals(o.status)&&o.result!=null)result=o.result;return result;}
    private static void validateReviewMatchesInspection(JSONObject review,JSONObject inspection)throws Exception{
        if(inspection==null||!review.getString("task_id").equals(inspection.getString("task_id"))||
                !review.getString("status").equals(inspection.getString("status"))||review.getLong("event_cursor")!=inspection.getLong("event_cursor"))
            throw new IllegalStateException("审阅与当前视频操作inspection不一致");
        if(inspection.has("revision")&&(review.getLong("revision")!=inspection.getLong("revision")||
                !review.getString("review_sha256").equals(inspection.getString("review_sha256"))||
                !review.getString("lesson_ir_sha256").equals(inspection.getString("lesson_ir_sha256"))))
            throw new IllegalStateException("审阅版本摘要与当前状态不一致");
    }
    private static boolean sameReview(JSONObject a,JSONObject b){return a.toString().equals(b.toString());}
    private AgentConnectionStore.Config current(AgentTaskStore.Task task){AgentConnectionStore.Config c=connections.get(task.connectionId);if(!task.matches(c))throw new IllegalStateException("任务连接已撤销或身份已变化");return c;}
    private String summary(AgentTaskStore.Task task,VideoOperationStore.Operation op,JSONObject diagnostics,JSONObject inspection,String error){StringBuilder s=new StringBuilder("视频任务 · ").append(task.title).append('\n');if(op!=null)s.append("当前操作：").append(actionLabel(op.action)).append(" · ").append(statusLabel(op.status)).append('\n');if(op!=null&&!op.error.isEmpty()&&!(("cancelled".equals(op.status))&&op.cancelControl!=null&&"verified_cancelled".equals(op.cancelControl.optString("status"))))s.append(errorLabel(op.error)).append('\n');if(op!=null&&"unknown".equals(op.status))s.append("执行结果尚未确认。可点“核对电脑端结果”读取电脑端保存记录；不会重跑。\n");if(op!=null&&op.cancelControl!=null&&"pending".equals(op.cancelControl.optString("status"))){if("succeeded".equals(op.status)||"failed".equals(op.status))s.append("电脑端操作已先结束；停止请求没有改变原结果。\n");else s.append("停止请求尚未确认是否送达；原操作不会重跑。\n");}if(op!=null&&op.cancelControl!=null&&"requested".equals(op.cancelControl.optString("status")))s.append("电脑端已记录停止请求，尚未核实操作已结束；不会重跑，也不会撤回已发生的费用或写入。\n");if(op!=null&&op.cancelControl!=null&&"unconfirmed".equals(op.cancelControl.optString("status")))s.append("电脑端暂时不能确认分镜已停止；原操作不会重跑，也不会撤回已发生的费用或写入。\n");if(op!=null&&"cancelled".equals(op.status)&&op.cancelControl!=null&&"verified_cancelled".equals(op.cancelControl.optString("status")))s.append("电脑端已核实分镜停止。已发生的费用或写入不会撤回。\n");if(op!=null&&"submission_uncertain".equals(op.status))s.append("尚未收到电脑端操作编号，可使用原提交安全重试；不会生成新幂等键。\n");if(op!=null&&"failed".equals(op.status))s.append("电脑端已确认本次失败。修复后可由你显式重新操作。\n");if(inspection!=null)s.append("电脑端进度：").append(workerStatusLabel(inspection.optString("status"))).append(" · ").append(workerPhaseLabel(inspection.optString("phase"))).append('\n');if(diagnostics!=null)s.append("完整运行环境尚未核验；配音/渲染以依赖详情为准，批准不启动生产。\n");if(!error.isEmpty())s.append(error).append('\n');return s.toString();}
    private static String actionLabel(String action){switch(action){case"initialize":return"初始化";case"storyboard":return"分镜生成";case"approve":return"批准分镜";default:return"视频操作";}}
    private static String statusLabel(String status){switch(status){case"prepared":return"准备提交";case"submission_uncertain":return"提交结果待确认";case"queued":return"排队中";case"running":return"执行中";case"succeeded":return"已完成";case"failed":return"已确认失败";case"unknown":return"结果未知";case"cancelled":return"已取消";default:return"状态待确认";}}
    private static String errorLabel(String code){switch(code){case"worker_failed":return"电脑端报告操作失败。";case"worker_unknown":return"电脑端暂时无法确认执行结果。";case"worker_interrupted":return"电脑端在操作过程中退出。";case"worker_timeout":return"电脑端操作超时，结果需核对。";case"worker_result_invalid":return"电脑端返回的结果未通过校验。";case"authorization_revoked":return"此设备的电脑端授权已撤销。";case"instance_retired":return"原电脑助手实例已停用。";case"run_unavailable":return"原任务不可用。";case"source_changed":return"原材料已变化，操作未继续。";case"binding_changed":return"任务或连接身份已变化。";case"cancelled":return"电脑端已取消排队操作。";default:return"电脑端返回了需要核对的状态。";}}
    private static String workerStatusLabel(String status){switch(status){case"initialized":return"已初始化";case"awaiting_storyboard_review":return"等待审阅";case"approved":return"分镜已批准";case"ready_to_render":return"等待后续制作";case"running":return"执行中";case"interrupted":return"已中断";case"failed":return"失败";case"cancelling":return"正在取消";case"cancelled":return"已取消";case"completed":return"已完成";default:return"状态待确认";}}
    private static String workerPhaseLabel(String phase){switch(phase){case"idle":return"等待操作";case"storyboard":return"生成分镜";case"awaiting_approval":return"等待批准";case"approval_pending":return"批准已记录";case"approval_consumed":return"批准已使用";case"tts_starting":return"准备配音";case"audio_ready":return"音频已准备";case"render_starting":return"准备渲染";case"completed":return"已完成";case"cancelling":return"正在取消";case"cancelled":return"已取消";default:return"处理中";}}
    private static String diagnosticName(String key){switch(key){case"worker_modules":return"任务模块";case"storyboard_browser":return"分镜浏览器";case"render_browser":return"渲染浏览器";case"ffmpeg":return"视频编码器";case"ffprobe":return"媒体检查器";case"tts":return"语音服务";default:return"电脑依赖";}}
    private static String diagnosticStatus(String status){switch(status){case"available":return"可用";case"missing":return"缺失";case"not_configured":return"未配置";default:return"尚未检查";}}
    private static String join(JSONArray array){StringBuilder s=new StringBuilder();for(int i=0;i<array.length();i++){if(i>0)s.append(" / ");s.append(array.optString(i));}return s.toString();}
    private Button button(String text){Button b=new Button(activity);b.setText(text);return b;}
    private static void disable(Button...buttons){for(Button b:buttons)b.setEnabled(false);}
    private ScrollView scroll(View v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private interface Throwing{void run()throws Exception;}
}
