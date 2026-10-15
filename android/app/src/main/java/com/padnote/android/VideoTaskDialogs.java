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
        if(!VideoTaskClient.isVideoBundle(task)){toast(activity.getString(R.string.flow_video_bundle_invalid));return;}
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView message=new TextView(activity);root.addView(message,new LinearLayout.LayoutParams(-1,-2));
        TextView diagnosticDetails=new TextView(activity);diagnosticDetails.setVisibility(View.GONE);
        LinearLayout scenes=new LinearLayout(activity);scenes.setOrientation(LinearLayout.VERTICAL);root.addView(scenes);
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);root.addView(actions);
        Button diagnostics=button(activity.getString(R.string.flow_check_video_deps)),refresh=button(activity.getString(R.string.flow_refresh_review)),initialize=button(activity.getString(R.string.flow_init_video_task)),
                storyboard=button(activity.getString(R.string.flow_new_storyboard)),approve=button(activity.getString(R.string.flow_approve_storyboard)),cancel=button(activity.getString(R.string.flow_cancel_queued)),stop=button(activity.getString(R.string.flow_stop_storyboard)),stopStatus=button(activity.getString(R.string.flow_stop_status)),retry=button(activity.getString(R.string.flow_retry_same_key)),
                reconcile=button(activity.getString(R.string.flow_reconcile_result)),
                diagnosticDetailsToggle=button(activity.getString(R.string.flow_show_tech));
        diagnosticDetailsToggle.setVisibility(View.GONE);actions.addView(diagnostics,new LinearLayout.LayoutParams(-1,-2));
        actions.addView(diagnosticDetailsToggle,new LinearLayout.LayoutParams(-1,-2));
        for(Button b:new Button[]{refresh,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile})actions.addView(b,new LinearLayout.LayoutParams(-1,-2));
        root.addView(diagnosticDetails,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_video_review_title)).setView(scroll(root)).setNegativeButton(activity.getString(R.string.flow_close),null).create();
        AtomicBoolean busy=new AtomicBoolean(),dialogActive=new AtomicBoolean(true);JSONObject[] displayed={null};boolean[] imagesReady={false};
        AtomicBoolean storeHealthy=new AtomicBoolean(true);
        Runnable[] reload=new Runnable[1];
        reload[0]=()->{
            if(!dialog.isShowing()||!busy.compareAndSet(false,true))return;
            imagesReady[0]=false;approve.setEnabled(false);message.setText(activity.getString(R.string.flow_loading_video));
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
                    try{diagnostic=client.diagnostics(task,current(task));current(task);}catch(Exception e){error=activity.getString(R.string.flow_diagnostics_unavailable);}
                    try{review=client.review(task,current(task));current(task);validateReviewMatchesInspection(review,inspection);store.saveReview(task,review);freshReview=true;}catch(Exception e){try{review=store.review(task);}catch(Exception ignored){review=null;}if(error.isEmpty())error=friendlyFailure(e,task);}
                    if(review!=null&&freshReview){
                        JSONArray list=review.getJSONArray("scenes");java.util.ArrayList<android.graphics.Bitmap> bitmaps=new java.util.ArrayList<>();long pixels=0;
                        for(int i=0;i<list.length();i++){
                            JSONObject scene=list.getJSONObject(i),meta=scene.getJSONObject("preview");pixels+=(long)meta.getInt("width")*meta.getInt("height");if(pixels>16_777_216L)throw new IllegalStateException(activity.getString(R.string.flow_memory_budget_exceeded));byte[] bytes=client.preview(task,current(task),review,meta);current(task);
                            android.graphics.Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                            if(bitmap==null)throw new IllegalStateException(activity.getString(R.string.flow_preview_decode_failed));bitmaps.add(bitmap);
                        }
                        if(task.matches(connections.get(task.connectionId))){displayed[0]=review;imagesReady[0]=true;}
                        final JSONObject shown=review;final java.util.List<android.graphics.Bitmap> previews=bitmaps;
                        final String finalError=error;final VideoOperationStore.Operation finalOp=op;final JSONObject finalDiagnostic=diagnostic,finalInspection=inspection;
                        main.post(()->{if(!dialog.isShowing())return;scenes.removeAllViews();try{
                            TextView heading=new TextView(activity);heading.setText(activity.getString(R.string.flow_video_review_revision,shown.getInt("revision"),shown.getJSONObject("episode").getString("title"))+"\n"+activity.getString(R.string.flow_approval_only));scenes.addView(heading);
                            JSONArray items=shown.getJSONArray("scenes");for(int i=0;i<items.length();i++){JSONObject s=items.getJSONObject(i);TextView text=new TextView(activity);text.setText(activity.getString(R.string.flow_scene_label,i+1)+" · "+s.getString("visual_kind")+"\n"+s.getString("learning_objective")+"\n"+activity.getString(R.string.flow_narration_label)+" "+s.getString("narration")+"\n"+activity.getString(R.string.flow_screen_text_label)+" "+join(s.getJSONArray("screen_text"))+"\n");scenes.addView(text);ImageView image=new ImageView(activity);image.setAdjustViewBounds(true);image.setMaxHeight(dp(240));image.setImageBitmap(previews.get(i));scenes.addView(image);}
                        }catch(Exception ignored){}message.setText(summary(task,finalOp,finalDiagnostic,finalInspection,finalError));}
                    );
                    } else {if(review!=null)error+=(error.isEmpty()?"":"\n")+activity.getString(R.string.flow_cached_review_stale);status=summary(task,op,diagnostic,inspection,error);}
                }catch(Exception e){
                    status=friendlyFailure(e,task);
                    try{review=store.review(task);}catch(Exception ignored){}
                    if(review!=null)status+="\n"+activity.getString(R.string.flow_cached_images_missing);
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
            if(!connections.applyProbeSuccess(c.id,c.revision,probe))throw new IllegalStateException(activity.getString(R.string.flow_identity_changed_reopen));
            c=current(task);JSONObject d=client.diagnostics(task,c);c=current(task);StringBuilder technical=new StringBuilder(activity.getString(R.string.flow_diagnostics_label));JSONObject checks=d.getJSONObject("checks");for(String key:new String[]{"worker_modules","storyboard_browser","render_browser","ffmpeg","ffprobe","tts"}){JSONObject item=checks.getJSONObject(key);technical.append(diagnosticName(key)).append("：").append(diagnosticStatus(item.getString("status"))).append(" · ").append(item.getString("reason")).append('\n');}
            technical.append(activity.getString(R.string.flow_video_capability_label)).append(c.capabilities.contains("video_operations")?activity.getString(R.string.flow_available):activity.getString(R.string.flow_unavailable)).append(activity.getString(R.string.flow_runtime_legacy_diagnostic));
            VideoOperationStore.Operation latest=store.latest(task);JSONObject inspection=latestInspection(task);boolean initialized=false;for(VideoOperationStore.Operation item:store.operations(task))if("initialize".equals(item.action)&&"succeeded".equals(item.status))initialized=true;
            final boolean initDone=initialized,stopped=store.hasVerifiedStoryboardStop(task);final String tech=technical.toString();main.post(()->{message.setText(activity.getString(R.string.flow_deps_check_done));diagnosticDetails.setText(tech);diagnosticDetails.setVisibility(View.GONE);diagnosticDetailsToggle.setText(activity.getString(R.string.flow_show_tech));diagnosticDetailsToggle.setVisibility(View.VISIBLE);configure(task,latest,d,inspection,initDone,displayed[0],imagesReady[0],storeHealthy.get(),false,stopped,message,diagnostics,refresh,diagnosticDetails,diagnosticDetailsToggle,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);});},reload[0]));
        diagnosticDetailsToggle.setOnClickListener(v->{boolean show=diagnosticDetails.getVisibility()!=View.VISIBLE;diagnosticDetails.setVisibility(show?View.VISIBLE:View.GONE);diagnosticDetailsToggle.setText(activity.getString(show?R.string.flow_hide_tech:R.string.flow_show_tech));});
        refresh.setOnClickListener(v->reload[0].run());
        initialize.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"initialize",displayed[0],reload[0]);});
        storyboard.setOnClickListener(v->{
            JSONObject state;
            try{state=latestInspection(task);}catch(Exception e){message.setText(activity.getString(R.string.flow_local_review_unavailable));return;}
            if(state!=null&&"approved".equals(state.optString("status"))){
                new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_produce_title))
                        .setMessage(activity.getString(R.string.flow_produce_body))
                        .setNegativeButton(activity.getString(R.string.flow_not_now),null).setPositiveButton(activity.getString(R.string.flow_approve_produce),(d,w)->{
                            disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"produce",displayed[0],reload[0]);
                        }).show();
            }else{
                new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_send_material_title))
                        .setMessage(activity.getString(R.string.flow_send_material_body,task.connectionName))
                        .setNegativeButton(activity.getString(R.string.flow_back),null).setPositiveButton(activity.getString(R.string.flow_send_and_generate),(d,w)->{
                            disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"storyboard",displayed[0],reload[0]);
                        }).show();
            }
        });
        approve.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);start(task,dialog,busy,"approve",displayed[0],reload[0]);});
        retry.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);retrySubmission(task,dialog,busy,reload[0]);});
        cancel.setOnClickListener(v->{disable(initialize,storyboard,approve,cancel,retry);cancel(task,dialog,busy,reload[0]);});
        stop.setOnClickListener(v->{
            VideoOperationStore.Operation active;
            try{active=store.latest(task);}catch(Exception e){message.setText(activity.getString(R.string.flow_cannot_read_op));return;}
            if(active!=null&&"produce".equals(active.action)){
                new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_stop_produce_title))
                    .setMessage(activity.getString(R.string.flow_stop_produce_body))
                    .setNegativeButton(activity.getString(R.string.flow_keep_running),null).setPositiveButton(activity.getString(R.string.flow_stop_request),(d,w)->{
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
        if(!bound){message.setText(activity.getString(R.string.flow_bound_connection_changed));diagnosticDetails.setVisibility(View.GONE);diagnosticDetailsToggle.setVisibility(View.GONE);disable(diagnostics,refresh,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);return;}
        if(!storeHealthy){message.setText(activity.getString(R.string.flow_local_video_store_unreadable));diagnosticDetails.setVisibility(View.GONE);diagnosticDetailsToggle.setVisibility(View.GONE);disable(diagnostics,refresh,initialize,storyboard,approve,cancel,stop,stopStatus,retry,reconcile);return;}
        if(verifiedStop)message.append(activity.getString(R.string.flow_verified_stop_done));
        boolean cap=c.capabilities.contains("video_operations");
        boolean runningStoryboard=op!=null&&"storyboard".equals(op.action)&&"running".equals(op.status);
        if(!cap&&!runningStoryboard)message.setText(activity.getString(R.string.flow_video_capability_unverified));
        boolean active=op!=null&&!VideoOperationStore.terminal(op.status);
        initialize.setVisibility(active||initializedSuccessfully?View.GONE:View.VISIBLE);
        storyboard.setVisibility(active||verifiedStop||visibleReview!=null?View.GONE:View.VISIBLE);
        boolean initialized=inspection!=null&&"initialized".equals(inspection.optString("status"))&&"idle".equals(inspection.optString("phase"));
        boolean reviewReady=imagesReady&&visibleReview!=null;boolean canApprove=!verifiedStop&&reviewReady&&"awaiting_storyboard_review".equals(visibleReview.optString("status"));
        boolean approved=inspection!=null&&"approved".equals(inspection.optString("status"))&&"approval_pending".equals(inspection.optString("phase"));
        boolean canProduce=approved&&c.capabilities.contains("video_production")&&op!=null&&"approve".equals(op.action)&&"succeeded".equals(op.status);
        storyboard.setText(activity.getString(canProduce?R.string.flow_video_production:R.string.flow_new_storyboard));
        storyboard.setVisibility(active||verifiedStop||(!initialized&&!canProduce)?View.GONE:View.VISIBLE);
        storyboard.setEnabled(cap&&!active&&!verifiedStop&&(initialized||canProduce));
        approve.setText(canApprove?activity.getString(R.string.flow_video_approve_revision,visibleReview.optInt("revision")):activity.getString(R.string.flow_approve_storyboard));
        approve.setVisibility(canApprove?View.VISIBLE:View.GONE);approve.setEnabled(cap&&imagesReady&&canApprove&&!active);
        boolean queued=op!=null&&"queued".equals(op.status)&&!op.operationId.isEmpty();cancel.setVisibility(queued?View.VISIBLE:View.GONE);cancel.setEnabled(cap);
        boolean stopSupported=op!=null&&("storyboard".equals(op.action)||"produce".equals(op.action))&&
                ("running".equals(op.status)||"produce".equals(op.action)&&"unknown".equals(op.status));
        boolean canStop=operationStatusFresh&&stopSupported&&op.cancelControl==null;
        boolean repeatedStop=operationStatusFresh&&stopSupported&&op.cancelControl!=null&&
                ("pending".equals(op.cancelControl.optString("status"))||"unconfirmed".equals(op.cancelControl.optString("status")));
        canStop=canStop||repeatedStop;
        stop.setText(repeatedStop?activity.getString(R.string.flow_stop_again):op!=null&&"produce".equals(op.action)?activity.getString(R.string.flow_stop_production):activity.getString(R.string.flow_stop_storyboard));stop.setVisibility(canStop?View.VISIBLE:View.GONE);stop.setEnabled(canStop);
        boolean canViewStop=op!=null&&op.cancelControl!=null&&("running".equals(op.status)||"unknown".equals(op.status));
        stopStatus.setVisibility(canViewStop?View.VISIBLE:View.GONE);stopStatus.setEnabled(canViewStop);
        if(op!=null&&op.cancelControl!=null){String control=op.cancelControl.optString("status");if("pending".equals(control))message.append(activity.getString(R.string.flow_video_stop_pending));else if("requested".equals(control))message.append(activity.getString(R.string.flow_video_stop_requested));else if("unconfirmed".equals(control))message.append(activity.getString(R.string.flow_video_stop_unconfirmed));}
        boolean canRetry=op!=null&&"submission_uncertain".equals(op.status)&&op.operationId.isEmpty();retry.setVisibility(canRetry?View.VISIBLE:View.GONE);retry.setEnabled(cap);
        boolean canReconcile=op!=null&&"unknown".equals(op.status)&&!op.operationId.isEmpty();
        reconcile.setText(op!=null&&op.cancelControl!=null?activity.getString(R.string.flow_reconcile_stop_result):activity.getString(R.string.flow_reconcile_result));reconcile.setVisibility(canReconcile?View.VISIBLE:View.GONE);reconcile.setEnabled(canReconcile);
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
                throw new IllegalStateException(activity.getString(R.string.flow_video_stop_result_done));
            JSONObject parameters=new JSONObject();
            if(!config.capabilities.contains("video_operations"))throw new IllegalStateException(activity.getString(R.string.flow_video_refresh_capability));
            if("storyboard".equals(action)){
                JSONObject review=displayedReview;if(review==null)review=store.review(task);
                if(review!=null){VideoTaskClient.validateReview(review,task);long revision=review.getLong("revision"),cursor=review.getLong("event_cursor");if(revision>=9007199254740991L||cursor>9007199254740989L)throw new IllegalStateException(activity.getString(R.string.flow_video_cursor_unsafe));parameters.put("revision",revision+1).put("event_cursor",cursor);}
                else {JSONObject inspection=latestInspection(task);if(inspection==null||!"initialized".equals(inspection.optString("status"))||!"idle".equals(inspection.optString("phase")))throw new IllegalStateException(activity.getString(R.string.flow_video_initialize_refresh));long cursor=inspection.getLong("event_cursor");if(cursor>9007199254740989L)throw new IllegalStateException(activity.getString(R.string.flow_video_cursor_range));parameters.put("revision",1).put("event_cursor",cursor);}
            } else if("approve".equals(action)){
                if(displayedReview==null)throw new IllegalStateException(activity.getString(R.string.flow_video_review_first));VideoTaskClient.validateReview(displayedReview,task);
                JSONObject saved=store.review(task);if(saved==null||!sameReview(saved,displayedReview))throw new IllegalStateException(activity.getString(R.string.flow_video_review_changed));
                long cursor=displayedReview.getLong("event_cursor");if(cursor>=9007199254740991L)throw new IllegalStateException(activity.getString(R.string.flow_video_cursor_range));
                parameters.put("revision",displayedReview.getLong("revision")).put("event_cursor",cursor)
                        .put("review_sha256",displayedReview.getString("review_sha256")).put("lesson_ir_sha256",displayedReview.getString("lesson_ir_sha256"));
            } else if("produce".equals(action)){
                JSONObject approved=latestInspection(task);JSONObject review=store.review(task);
                VideoTaskClient.validateReview(review,task);
                if(approved==null||!"approved".equals(approved.optString("status"))||!"approval_pending".equals(approved.optString("phase"))||
                        review==null||!"approved".equals(review.optString("status"))||
                        approved.optLong("revision",-1)!=review.optLong("revision",-2)||approved.optLong("event_cursor",-1)!=review.optLong("event_cursor",-2)||
                        !approved.optString("review_sha256").equals(review.optString("review_sha256"))||!approved.optString("lesson_ir_sha256").equals(review.optString("lesson_ir_sha256")))
                    throw new IllegalStateException(activity.getString(R.string.flow_video_approved_review));
                parameters.put("revision",approved.getLong("revision")).put("event_cursor",approved.getLong("event_cursor"))
                        .put("review_sha256",approved.getString("review_sha256")).put("lesson_ir_sha256",approved.getString("lesson_ir_sha256"))
                        .put("allow_cloud_tts",true);
            }
            VideoOperationStore.Operation operation=store.reserve(task,action,parameters,
                    "approve".equals(action)?displayedReview:null);
            intent[0]=operation;
            if("submission_uncertain".equals(operation.status)&&operation.operationId.isEmpty()){
                // Explicit retry reuses the exact key and body.
            }else if(!"prepared".equals(operation.status))throw new IllegalStateException(activity.getString(R.string.flow_video_duplicate_operation));
            JSONObject response=client.submit(task,current(task),operation);
            current(task);
            store.updateIfCurrent(task,operation,response.getString("operation_id"),response.getString("status"),
                    response.isNull("error")?"":response.getString("error"),response.isNull("result")?null:response.getJSONObject("result"),false);
        }catch(Exception e){try{if(intent[0]!=null&&intent[0].operationId.isEmpty())store.updateIfCurrent(task,intent[0],"","submission_uncertain","",null,true);}catch(Exception ignored){}
            main.post(()->toast(activity.getString(R.string.flow_video_uncertain_keep_key)+friendlyFailure(e,task)));}
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
                    throw new IllegalStateException(activity.getString(R.string.flow_video_no_unknown_op));
                JSONObject response=client.reconcile(currentTask,config,expected);
                current(currentTask);
                VideoOperationStore.Operation applied=store.updateReconciledIfCurrent(currentTask,expected,
                        response.getString("operation_id"),response.getString("status"),
                        response.isNull("error")?"":response.getString("error"),
                        response.isNull("result")?null:response.getJSONObject("result"));
                if(applied==null)notice=activity.getString(R.string.flow_video_reconcile_stale);
                else if("unknown".equals(applied.status))notice=activity.getString(R.string.flow_video_reconcile_unknown);
                else if("cancelled".equals(applied.status))notice=activity.getString(R.string.flow_video_verified_stop_notice);
            }catch(Exception error){
                if(!task.matches(connections.get(task.connectionId)))
                    notice=activity.getString(R.string.flow_video_identity_reconcile);
                else if(error instanceof VideoTaskClient.HttpError
                        &&(((VideoTaskClient.HttpError)error).status==409||((VideoTaskClient.HttpError)error).status==503))
                    notice=activity.getString(R.string.flow_video_reconcile_failed);
                else notice=activity.getString(R.string.flow_video_reconcile_incomplete);
            }
            final String message=notice;
            main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(!message.isEmpty())toast(message);reload.run();});
        });}catch(java.util.concurrent.RejectedExecutionException closed){busy.set(false);}
    }

    private void retrySubmission(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;worker.execute(()->{try{AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);AgentConnectionStore.Config c=current(currentTask);VideoOperationStore.Operation op=store.active(currentTask);if(op==null||!"submission_uncertain".equals(op.status)||!op.operationId.isEmpty())throw new IllegalStateException(activity.getString(R.string.flow_video_no_safe_retry));JSONObject response=client.submit(currentTask,c,op);current(currentTask);store.updateIfCurrent(currentTask,op,response.getString("operation_id"),response.getString("status"),response.isNull("error")?"":response.getString("error"),response.isNull("result")?null:response.getJSONObject("result"),false);}catch(Exception e){main.post(()->toast(activity.getString(R.string.flow_video_retry_still_unknown,friendlyFailure(e,task))));}main.post(()->{busy.set(false);if(dialog.isShowing())reload.run();});});
    }
    private void cancel(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;worker.execute(()->{try{AgentTaskStore.Task t=tasks.get(task.clientTaskId);AgentConnectionStore.Config c=current(t);VideoOperationStore.Operation op=store.active(t);if(op==null||!"queued".equals(op.status))throw new IllegalStateException(activity.getString(R.string.flow_video_cancel_queued_only));JSONObject response=client.cancel(t,c,op);current(t);store.updateIfCurrent(t,op,response.getString("operation_id"),response.getString("status"),response.isNull("error")?"":response.getString("error"),response.isNull("result")?null:response.getJSONObject("result"),false);}catch(Exception e){main.post(()->toast(activity.getString(R.string.flow_video_cancel_unconfirmed,friendlyFailure(e,task))));}main.post(()->{busy.set(false);if(dialog.isShowing())reload.run();});});
    }
    private void requestRunningStop(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean dialogActive,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{String notice="";try{
            if(!isDialogActive(dialog,dialogActive)){busy.set(false);return;}
            AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);AgentConnectionStore.Config config=current(currentTask);
            VideoOperationStore.Operation expected=store.latest(currentTask);
            if(expected==null||!("storyboard".equals(expected.action)||"produce".equals(expected.action))||
                    !("running".equals(expected.status)||"produce".equals(expected.action)&&"unknown".equals(expected.status)))throw new IllegalStateException(activity.getString(R.string.flow_video_no_running_op));
            boolean createdIntent=expected.cancelControl==null;
            VideoOperationStore.Operation intent=store.prepareRunningCancel(currentTask,expected);
            if(intent==null)throw new IllegalStateException(activity.getString(R.string.flow_video_operation_changed));
            if(!isDialogActive(dialog,dialogActive)){if(createdIntent)store.clearUnsentRunningCancel(currentTask,intent);busy.set(false);return;}
            JSONObject response=client.cancelRunning(currentTask,config,intent);
            current(currentTask);
            VideoOperationStore.Operation applied=store.applyCancelControl(currentTask,intent,response);
            if(applied==null)notice=activity.getString(R.string.flow_video_task_changed_stop);
            else if("verified_cancelled".equals(response.getString("status")))notice=activity.getString(R.string.flow_video_stop_success);
            else if("too_late".equals(response.getString("status")))notice=activity.getString(R.string.flow_video_stop_too_late);
            else if("unconfirmed".equals(response.getString("status")))notice=activity.getString(R.string.flow_video_stop_unconfirmed_full);
            else notice=activity.getString(R.string.flow_video_stop_requested_short);
            if(applied!=null&&"too_late".equals(response.getString("status"))){
                JSONObject operation=client.operation(currentTask,current(currentTask),applied);current(currentTask);
                store.updateIfCurrent(currentTask,applied,operation.getString("operation_id"),operation.getString("status"),
                        operation.isNull("error")?"":operation.getString("error"),operation.isNull("result")?null:operation.getJSONObject("result"),false);
            }
        }catch(Exception error){notice=error instanceof VideoTaskClient.HttpError&&((VideoTaskClient.HttpError)error).status==404?
                activity.getString(R.string.flow_video_stop_unsupported_running):activity.getString(R.string.flow_video_stop_pending_no_run);}
            final String message=notice;main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(!message.isEmpty())toast(message);reload.run();});
        });}catch(java.util.concurrent.RejectedExecutionException closed){busy.set(false);}
    }
    private void refreshRunningStop(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean dialogActive,AtomicBoolean busy,Runnable reload){
        if(!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{String notice="";try{
            if(!isDialogActive(dialog,dialogActive)){busy.set(false);return;}
            AgentTaskStore.Task currentTask=tasks.get(task.clientTaskId);AgentConnectionStore.Config config=current(currentTask);
            VideoOperationStore.Operation expected=store.latest(currentTask);
            if(expected==null||expected.cancelControl==null||expected.operationId.isEmpty())throw new IllegalStateException(activity.getString(R.string.flow_video_no_saved_stop));
            JSONObject response=client.cancelRequest(currentTask,config,expected);current(currentTask);
            VideoOperationStore.Operation applied=store.applyCancelControl(currentTask,expected,response);
            if(applied==null)notice=activity.getString(R.string.flow_video_old_stop_ignored);
            else switch(response.getString("status")){
                case "verified_cancelled":notice=activity.getString(R.string.flow_video_stop_verified_full);break;
                case "too_late":notice=activity.getString(R.string.flow_video_stop_too_late_full);break;
                case "unconfirmed":notice=activity.getString(R.string.flow_video_stop_unconfirmed_result);break;
                default:notice=activity.getString(R.string.flow_video_stop_recorded_full);
            }
            if(applied!=null&&"too_late".equals(response.getString("status"))&&"running".equals(applied.status)){
                JSONObject operation=client.operation(currentTask,current(currentTask),applied);current(currentTask);
                store.updateIfCurrent(currentTask,applied,operation.getString("operation_id"),operation.getString("status"),
                        operation.isNull("error")?"":operation.getString("error"),operation.isNull("result")?null:operation.getJSONObject("result"),false);
            }
        }catch(Exception error){
            if(error instanceof VideoTaskClient.HttpError&&((VideoTaskClient.HttpError)error).status==404)
                notice=activity.getString(R.string.flow_video_no_stop_support);
            else if(error instanceof VideoTaskClient.HttpError&&((VideoTaskClient.HttpError)error).status==409)
                notice=activity.getString(R.string.flow_video_stop_request_not_found);
            else notice=activity.getString(R.string.flow_video_stop_status_unavailable);
        }
            final String message=notice;main.post(()->{busy.set(false);if(!isDialogActive(dialog,dialogActive))return;if(!message.isEmpty())toast(message);reload.run();});
        });}catch(java.util.concurrent.RejectedExecutionException closed){busy.set(false);}
    }
    private void run(AgentTaskStore.Task task,AlertDialog dialog,AtomicBoolean busy,Throwing action,Runnable reload){if(!busy.compareAndSet(false,true))return;worker.execute(()->{Exception failure=null;try{current(task);action.run();}catch(Exception e){failure=e;}final Exception error=failure;main.post(()->{busy.set(false);if(error!=null){toast(friendlyFailure(error,task));if(dialog.isShowing())reload.run();}});});}
    private void renderReviewText(LinearLayout scenes,JSONObject review){try{
        TextView heading=new TextView(scenes.getContext());heading.setText(activity.getString(R.string.flow_video_review_revision,review.getInt("revision"),review.getJSONObject("episode").getString("title"))+"\n"+activity.getString(R.string.flow_cached_text_only)+"\n");scenes.addView(heading);
        JSONArray items=review.getJSONArray("scenes");for(int i=0;i<items.length();i++){JSONObject scene=items.getJSONObject(i);TextView text=new TextView(scenes.getContext());text.setText(activity.getString(R.string.flow_video_scene_line,i+1,scene.getString("visual_kind"))+"\n"+scene.getString("learning_objective")+"\n"+activity.getString(R.string.flow_video_narration_line,scene.getString("narration"))+"\n"+activity.getString(R.string.flow_video_screen_text_line,join(scene.getJSONArray("screen_text")))+"\n");scenes.addView(text);}
    }catch(Exception ignored){scenes.removeAllViews();}}
    private String friendlyFailure(Exception e,AgentTaskStore.Task task){
        if(!task.matches(connections.get(task.connectionId)))return activity.getString(R.string.flow_video_identity_revoked_full);
        if(e instanceof java.io.IOException)return activity.getString(R.string.flow_video_offline_full);
        if(e instanceof VideoTaskClient.HttpError)return activity.getString(R.string.flow_video_state_unverified_full);
        return activity.getString(R.string.flow_video_action_unconfirmed_full);
    }
    private JSONObject latestInspection(AgentTaskStore.Task task)throws Exception{JSONObject result=null;for(VideoOperationStore.Operation o:store.operations(task))if("succeeded".equals(o.status)&&o.result!=null)result=o.result;return result;}
    private void validateReviewMatchesInspection(JSONObject review,JSONObject inspection)throws Exception{
        if(inspection==null||!review.getString("task_id").equals(inspection.getString("task_id"))||
                !review.getString("status").equals(inspection.getString("status"))||review.getLong("event_cursor")!=inspection.getLong("event_cursor"))
            throw new IllegalStateException(activity.getString(R.string.flow_video_inconsistent_inspection));
        if(inspection.has("revision")&&(review.getLong("revision")!=inspection.getLong("revision")||
                !review.getString("review_sha256").equals(inspection.getString("review_sha256"))||
                !review.getString("lesson_ir_sha256").equals(inspection.getString("lesson_ir_sha256"))))
            throw new IllegalStateException(activity.getString(R.string.flow_video_inconsistent_revision));
    }
    private static boolean sameReview(JSONObject a,JSONObject b){return a.toString().equals(b.toString());}
    private AgentConnectionStore.Config current(AgentTaskStore.Task task){AgentConnectionStore.Config c=connections.get(task.connectionId);if(!task.matches(c))throw new IllegalStateException(activity.getString(R.string.flow_video_task_identity_changed));return c;}
    private String summary(AgentTaskStore.Task task,VideoOperationStore.Operation op,JSONObject diagnostics,JSONObject inspection,String error){StringBuilder s=new StringBuilder(activity.getString(R.string.flow_video_task_title,task.title)).append('\n');if(op!=null)s.append(activity.getString(R.string.flow_video_current_action)).append(actionLabel(op.action)).append(" · ").append(statusLabel(op.status)).append('\n');if(op!=null&&!op.error.isEmpty()&&!(("cancelled".equals(op.status))&&op.cancelControl!=null&&"verified_cancelled".equals(op.cancelControl.optString("status"))))s.append(errorLabel(op.error)).append('\n');if(op!=null&&"unknown".equals(op.status))s.append(activity.getString(R.string.flow_video_unknown_result_notice)+"\n");if(op!=null&&op.cancelControl!=null&&"pending".equals(op.cancelControl.optString("status"))){if("succeeded".equals(op.status)||"failed".equals(op.status))s.append(activity.getString(R.string.flow_video_stop_no_effect_notice)+"\n");else s.append(activity.getString(R.string.flow_video_stop_delivery_unknown)+"\n");}if(op!=null&&op.cancelControl!=null&&"requested".equals(op.cancelControl.optString("status")))s.append(activity.getString(R.string.flow_video_stop_pending_summary)+"\n");if(op!=null&&op.cancelControl!=null&&"unconfirmed".equals(op.cancelControl.optString("status")))s.append(activity.getString(R.string.flow_video_stop_unconfirmed_summary)+"\n");if(op!=null&&"cancelled".equals(op.status)&&op.cancelControl!=null&&"verified_cancelled".equals(op.cancelControl.optString("status")))s.append(activity.getString(R.string.flow_video_verified_stop_summary)+"\n");if(op!=null&&"submission_uncertain".equals(op.status))s.append(activity.getString(R.string.flow_video_retry_same_key_notice)+"\n");if(op!=null&&"failed".equals(op.status))s.append(activity.getString(R.string.flow_video_confirmed_failure_notice)+"\n");if(inspection!=null)s.append(activity.getString(R.string.flow_video_progress_label)).append(workerStatusLabel(inspection.optString("status"))).append(" · ").append(workerPhaseLabel(inspection.optString("phase"))).append('\n');if(diagnostics!=null)s.append(activity.getString(R.string.flow_video_runtime_notice)+"\n");if(!error.isEmpty())s.append(error).append('\n');return s.toString();}
    private String actionLabel(String action){switch(action){case"initialize":return activity.getString(R.string.flow_video_action_initialize);case"storyboard":return activity.getString(R.string.flow_video_action_storyboard);case"approve":return activity.getString(R.string.flow_video_action_approve);default:return activity.getString(R.string.flow_video_action_other);}}
    private String statusLabel(String status){switch(status){case"prepared":return activity.getString(R.string.flow_video_status_prepared);case"submission_uncertain":return activity.getString(R.string.flow_video_status_uncertain);case"queued":return activity.getString(R.string.flow_video_status_queued);case"running":return activity.getString(R.string.flow_video_status_running);case"succeeded":return activity.getString(R.string.flow_video_status_succeeded);case"failed":return activity.getString(R.string.flow_video_status_failed);case"unknown":return activity.getString(R.string.flow_video_status_unknown);case"cancelled":return activity.getString(R.string.flow_video_status_cancelled);default:return activity.getString(R.string.flow_video_status_default);}}
    private String errorLabel(String code){switch(code){case"worker_failed":return activity.getString(R.string.flow_video_error_worker_failed);case"worker_unknown":return activity.getString(R.string.flow_video_error_worker_unknown);case"worker_interrupted":return activity.getString(R.string.flow_video_error_worker_interrupted);case"worker_timeout":return activity.getString(R.string.flow_video_error_worker_timeout);case"worker_result_invalid":return activity.getString(R.string.flow_video_error_invalid_result);case"authorization_revoked":return activity.getString(R.string.flow_video_error_authorization_revoked);case"instance_retired":return activity.getString(R.string.flow_video_error_instance_retired);case"run_unavailable":return activity.getString(R.string.flow_video_error_run_unavailable);case"source_changed":return activity.getString(R.string.flow_video_error_source_changed);case"binding_changed":return activity.getString(R.string.flow_video_error_binding_changed);case"cancelled":return activity.getString(R.string.flow_video_error_cancelled);default:return activity.getString(R.string.flow_video_error_other);}}
    private String workerStatusLabel(String status){switch(status){case"initialized":return activity.getString(R.string.flow_video_worker_initialized);case"awaiting_storyboard_review":return activity.getString(R.string.flow_video_worker_awaiting_review);case"approved":return activity.getString(R.string.flow_video_worker_approved);case"ready_to_render":return activity.getString(R.string.flow_video_worker_ready);case"running":return activity.getString(R.string.flow_video_status_running);case"interrupted":return activity.getString(R.string.flow_video_worker_interrupted);case"failed":return activity.getString(R.string.flow_video_worker_failed);case"cancelling":return activity.getString(R.string.flow_video_worker_cancelling);case"cancelled":return activity.getString(R.string.flow_video_status_cancelled);case"completed":return activity.getString(R.string.flow_video_worker_completed);default:return activity.getString(R.string.flow_video_status_default);}}
    private String workerPhaseLabel(String phase){switch(phase){case"idle":return activity.getString(R.string.flow_video_phase_idle);case"storyboard":return activity.getString(R.string.flow_video_phase_storyboard);case"awaiting_approval":return activity.getString(R.string.flow_video_phase_approval);case"approval_pending":return activity.getString(R.string.flow_video_phase_approval_pending);case"approval_consumed":return activity.getString(R.string.flow_video_phase_approval_used);case"tts_starting":return activity.getString(R.string.flow_video_phase_tts);case"audio_ready":return activity.getString(R.string.flow_video_phase_audio);case"render_starting":return activity.getString(R.string.flow_video_phase_render);case"completed":return activity.getString(R.string.flow_video_status_succeeded);case"cancelling":return activity.getString(R.string.flow_video_worker_cancelling);case"cancelled":return activity.getString(R.string.flow_video_status_cancelled);default:return activity.getString(R.string.flow_video_phase_processing);}}
    private String diagnosticName(String key){switch(key){case"worker_modules":return activity.getString(R.string.flow_video_diagnostic_worker);case"storyboard_browser":return activity.getString(R.string.flow_video_diagnostic_storyboard_browser);case"render_browser":return activity.getString(R.string.flow_video_diagnostic_render_browser);case"ffmpeg":return activity.getString(R.string.flow_video_diagnostic_encoder);case"ffprobe":return activity.getString(R.string.flow_video_diagnostic_media);case"tts":return activity.getString(R.string.flow_video_diagnostic_voice);default:return activity.getString(R.string.flow_video_diagnostic_dependencies);}}
    private String diagnosticStatus(String status){switch(status){case"available":return activity.getString(R.string.flow_available);case"missing":return activity.getString(R.string.flow_video_diag_missing);case"not_configured":return activity.getString(R.string.flow_video_diag_not_configured);default:return activity.getString(R.string.flow_video_diag_not_checked);}}
    private static String join(JSONArray array){StringBuilder s=new StringBuilder();for(int i=0;i<array.length();i++){if(i>0)s.append(" / ");s.append(array.optString(i));}return s.toString();}
    private Button button(String text){Button b=new Button(activity);b.setText(text);return b;}
    private static void disable(Button...buttons){for(Button b:buttons)b.setEnabled(false);}
    private ScrollView scroll(View v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private interface Throwing{void run()throws Exception;}
}
