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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.ref.WeakReference;

/** Persistent Agent task list/detail with bounded foreground polling. */
final class AgentTaskDialogs {
    /** Status reads must not wait behind interactive AI work. */
    private static final ExecutorService STATUS_REFRESH_EXECUTOR = new ThreadPoolExecutor(2, 2,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), r -> {
                Thread thread = new Thread(r, "padnote-task-status");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    interface SourceNoteOpener { boolean open(String noteId); }
    interface TaskConnectionSource {
        List<AgentConnectionStore.Config> list();
        AgentConnectionStore.Config get(String id);
    }
    private static final class StoreTaskConnectionSource implements TaskConnectionSource {
        private final AgentConnectionStore store;
        StoreTaskConnectionSource(AgentConnectionStore store){this.store=store;}
        @Override public List<AgentConnectionStore.Config> list(){return store.list();}
        @Override public AgentConnectionStore.Config get(String id){return store.get(id);}
    }
    interface BundleFactory { byte[] make() throws Exception; }
    interface ArtifactSaveLauncher {
        void choose(AgentTaskStore.Task task, AgentTaskStore.Artifact artifact);
    }
    interface ResultAppender { void append(String clientTaskId); }
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
    private ResultAppender resultAppender;

    void setResultAppender(ResultAppender appender) { resultAppender = appender; }

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
        this.taskConnectionSource=taskConnectionSource==null?
                new StoreTaskConnectionSource(new AgentConnectionStore(activity.getApplicationContext())):taskConnectionSource;
    }

    void showForNote(String noteId) {
        List<AgentTaskStore.Task> values=new ArrayList<>();
        for(AgentTaskStore.Task task:tasks.list())if(noteId.equals(task.noteId))values.add(task);
        if(values.isEmpty()){ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_history_title))
                .setMessage(activity.getString(R.string.flow_no_video_tasks))
                .setNegativeButton(activity.getString(R.string.flow_close),null).show();return;}
        List<String> labels=new ArrayList<>();for(AgentTaskStore.Task t:values){
            labels.add(historyRow(t)+"\n"+activity.getString(R.string.flow_source_revision_value,t.noteRevision));}
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_history_title))
                .setAdapter(ChoiceRows.adapter(activity, labels.toArray(new String[0])),
                        (d,w)->showDetail(values.get(w).clientTaskId))
                .setNegativeButton(activity.getString(R.string.flow_close),null).show();
    }

    void showList() {
        List<AgentTaskStore.Task> values=tasks.list();
        int corruptCount=tasks.corruptCount();
        if(values.isEmpty()){
            String message=activity.getString(R.string.flow_task_list_empty);
            if(corruptCount>0)message+="\n"+activity.getString(R.string.flow_corrupt_records_tail,corruptCount);
            ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_computer_tasks)).setMessage(message)
                    .setNegativeButton(activity.getString(R.string.flow_close),null)
                    .setPositiveButton(activity.getString(R.string.flow_choose_add_connection),(dialog,which)->
                            new AgentConnectionDialogs(activity,connections,worker,()->{},null,
                                    artifactSaveLauncher).show())
                    .show();
            return;
        }
        List<String> labels=new ArrayList<>();
        if(corruptCount>0)labels.add(activity.getString(R.string.flow_corrupt_records_warning,corruptCount));
        int warningRows=labels.size();
        for(AgentTaskStore.Task task:values)labels.add(historyRow(task)+
                (task.error.isEmpty()?"":"\n"+task.error));
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_computer_tasks))
                .setAdapter(multilineRows(labels),(d,w)->{if(w>=warningRows)
                    showDetail(values.get(w-warningRows).clientTaskId);})
                .setNegativeButton(activity.getString(R.string.flow_close),null).show();
    }

    private String historyRow(AgentTaskStore.Task task) {
        return task.title+"\n"+taskStatus(task)+"\n"+activity.getString(R.string.flow_task_target_line,
                AgentTaskDestinationLabel.forTask(activity,task));
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
        EditText input=new EditText(activity);input.setHint(activity.getString(R.string.flow_text_task_hint));
        input.setMinLines(6);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_send_text_to,destinationLabel(activity, profile))).setView(input)
                .setNegativeButton(activity.getString(R.string.flow_cancel),null).setPositiveButton(activity.getString(R.string.flow_send),(d,w)->{
                    String text=input.getText().toString().trim();
                    if(text.isEmpty()){toast(activity.getString(R.string.flow_empty_task));return;}
                    createAndSubmit(profile,activity.getString(R.string.flow_plain_text_task),text,"",1,null);
                }).show();
    }

    void chooseAndSubmitBundle(String title,String input,String noteId,long noteRevision,
                               BundleFactory factory) {
        List<AgentConnectionStore.Config> profiles=new ArrayList<>();
        for(AgentConnectionStore.Config c:taskConnectionSource.list())if(c.verified()&&
                c.transport==AgentConnectionStore.Transport.BRIDGE&&c.capabilities.contains("task_bundle")&&
                (c.kind!=AgentConnectionStore.Kind.BUILTIN_VIDEO||c.capabilities.contains("video_task_submission")))profiles.add(c);
        if(profiles.isEmpty()){toast(activity.getString(R.string.flow_no_bridge));return;}
        String[] labels=new String[profiles.size()];for(int i=0;i<labels.length;i++)labels[i]=destinationLabel(activity, profiles.get(i));
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_choose_agent)).setAdapter(ChoiceRows.adapter(activity, labels),(d,w)->{
                    AgentConnectionStore.Config selected=profiles.get(w);
                    ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_confirm_package))
                            .setMessage(activity.getString(R.string.flow_target)+": "+destinationLabel(activity, selected)+"\n"+activity.getString(R.string.flow_material)+": "+title+
                                    activity.getString(R.string.flow_confirm_package_body))
                            .setNegativeButton(activity.getString(R.string.flow_cancel),null).setPositiveButton(activity.getString(R.string.flow_send_to_agent),(confirm,which)->
                                    worker.execute(()->{try{byte[] bundle=factory.make();main.post(()->
                                            createAndSubmit(selected,title,input,noteId,noteRevision,bundle));}
                                        catch(Exception e){main.post(()->toast(activity.getString(R.string.flow_package_failed,message(e))));}})).show();
                })
                .setNegativeButton(activity.getString(R.string.flow_cancel),null).show();
    }

    void sendNoteWork(AgentConnectionStore.Config selected,NoteWorkBundleIO.Frozen frozen,
                      String title,String input,String noteId) {
        if(selected==null||!selected.verified()||selected.transport!=AgentConnectionStore.Transport.BRIDGE||
                selected.featureMap==null||
                selected.kind==AgentConnectionStore.Kind.BUILTIN_VIDEO||
                !Boolean.TRUE.equals(selected.featureMap.get("task_bundle"))||
                !Boolean.TRUE.equals(selected.featureMap.get("note_context_bundle"))||
                !Boolean.TRUE.equals(selected.featureMap.get("run_submission"))) {
            toast(activity.getString(R.string.flow_task_package_unsupported));return;
        }
        WeakReference<AgentTaskDialogs> dialogRef=new WeakReference<>(this);
        AgentTaskStore taskStore=tasks;
        TaskConnectionSource connectionSource=taskConnectionSource;
        AgentTaskClient.Factory clients=taskClientFactory;
        android.content.Context appContext=activity.getApplicationContext();
        if(!AgentTaskSubmissionExecutor.prepare(()->{
            final AgentTaskStore.Task task;
            try{task=taskStore.create(selected,title,input,noteId,frozen.noteRevision,
                    frozen.zip.clone(),"note_context_bundle");}
            catch(Exception error){main.post(()->{AgentTaskDialogs d=dialogRef.get();if(d!=null)d.toast(appContext.getString(R.string.flow_task_create_failed,safeError(error)));});return;}
            AgentTaskSubmissionExecutor.submit(task,taskStore,connectionSource,clients,appContext);
            main.post(()->{AgentTaskDialogs d=dialogRef.get();if(d!=null)d.toast(appContext.getString(R.string.flow_task_submitting));});
        }))toast(activity.getString(R.string.flow_task_queue_busy));
    }

    static String destinationLabel(AgentConnectionStore.Config profile) {
        return AgentTaskDestinationLabel.forProfile(profile);
    }

    static String destinationLabel(android.content.Context context,
                                  AgentConnectionStore.Config profile) {
        return AgentTaskDestinationLabel.forProfile(context, profile);
    }

    private void createAndSubmit(AgentConnectionStore.Config profile,String title,String input,
                                 String noteId,long noteRevision,byte[] bundle) {
        createAndSubmit(profile,title,input,noteId,noteRevision,bundle,"");
    }

    private void createAndSubmit(AgentConnectionStore.Config profile,String title,String input,
                                 String noteId,long noteRevision,byte[] bundle,String requiredCapability) {
        final AgentTaskStore.Task task;
        try{task=tasks.create(profile,title,input,noteId,noteRevision,bundle,requiredCapability);}
        catch(Exception e){toast(activity.getString(R.string.flow_task_create_failed_short,message(e)));return;}
        submit(task);
    }

    private void submit(AgentTaskStore.Task task) {
        toast(activity.getString(R.string.flow_task_submitting));
        AgentTaskSubmissionExecutor.submit(task,tasks,taskConnectionSource,taskClientFactory,
                activity.getApplicationContext());
    }

    private static String safeError(Throwable error){String m=error.getMessage();return m==null?"Unknown error":m.replaceAll("[\\r\\n]"," ");}

    private void askFollowup(String parentId,AlertDialog parentDialog) {
        AgentTaskStore.Task parent=tasks.get(parentId);if(parent==null)return;
        EditText input=new EditText(activity);input.setHint(activity.getString(R.string.flow_followup_hint));
        input.setMinLines(4);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_continue_chat)).setView(input)
                .setNegativeButton(activity.getString(R.string.flow_cancel),null).setPositiveButton(activity.getString(R.string.flow_send),(d,w)->{
                    String text=input.getText().toString().trim();if(text.isEmpty()){toast(activity.getString(R.string.flow_followup_empty));return;}
                    AgentConnectionStore.Config connection=taskConnectionSource.get(parent.connectionId);
                    if(!parent.matches(connection)){toast(activity.getString(R.string.flow_identity_changed_followup));return;}
                    try {
                        boolean childAlreadySaved=!tasks.children(parentId).isEmpty();
                        AgentTaskStore.Task child=tasks.createFollowup(parent,connection,text);
                        parentDialog.dismiss();
                        if(child.status==AgentTaskStore.Status.SUBMITTING&&!childAlreadySaved)submit(child);
                        else showDetail(child.clientTaskId);
                    } catch(Exception e){
                        List<AgentTaskStore.Task> existing=tasks.children(parentId);
                        if(!existing.isEmpty()&&message(e).contains(activity.getString(R.string.flow_new_text_not_sent))){
                            toast(activity.getString(R.string.flow_followup_saved_not_sent));
                            parentDialog.dismiss();showDetail(existing.get(0).clientTaskId);
                        }else toast(activity.getString(R.string.flow_continue_failed,message(e)));
                    }
                }).show();
    }

    void showDetail(String clientId) {
        AgentTaskStore.Task initial=tasks.get(clientId);if(initial==null){toast(activity.getString(R.string.flow_record_missing));return;}
        final boolean videoBundleValid=VideoTaskClient.isVideoBundle(initial);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20),dp(8),dp(20),dp(8));
        LinearLayout statusCard=paperCard();
        TextView statusText=new TextView(activity);statusText.setTextColor(android.graphics.Color.rgb(31,41,51));
        statusText.setTextSize(15);statusText.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        statusCard.addView(statusText,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(statusCard,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView resultText=new TextView(activity);resultText.setTextIsSelectable(true);
        LinearLayout resultCard=paperCard();resultCard.addView(resultText,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(resultCard);
        TextView content=new TextView(activity);
        LinearLayout detailCard=paperCard();detailCard.addView(content,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        detailCard.setVisibility(View.GONE);
        Button toggleDetail=button(activity.getString(R.string.flow_expand_task_details));
        toggleDetail.setOnClickListener(v->{boolean show=detailCard.getVisibility()!=View.VISIBLE;
            detailCard.setVisibility(show?View.VISIBLE:View.GONE);
            toggleDetail.setText(activity.getString(show?R.string.flow_collapse_task_details:R.string.flow_expand_task_details));});
        body.addView(toggleDetail);body.addView(detailCard);
        content.setTextIsSelectable(true);
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);
        Button openSource=button(activity.getString(R.string.flow_return_to_note));
        if(initial.noteId.isEmpty()||sourceNoteOpener==null)openSource.setVisibility(View.GONE);
        addAction(actions,openSource);
        Button continueWriting=button(activity.getString(R.string.flow_return_to_note));
        if(initial.noteId.isEmpty()||sourceNoteOpener==null)continueWriting.setVisibility(View.VISIBLE);
        else continueWriting.setVisibility(View.GONE);
        addAction(actions,continueWriting);
        Button openTaskList=button(activity.getString(R.string.flow_open_task_list));addAction(actions,openTaskList);
        Button copyDiagnostic=button(activity.getString(R.string.flow_copy_diagnostic));addAction(actions,copyDiagnostic);
        Button appendResult=primaryButton(activity.getString(R.string.flow_append_result_to_note));
        appendResult.setVisibility(View.GONE);addAction(actions,appendResult);
        Button refresh=button(activity.getString(R.string.flow_refresh)),retry=button(activity.getString(R.string.flow_retry_submit)),stop=button(activity.getString(R.string.flow_stop)),once=button(activity.getString(R.string.flow_closed_approval)),deny=button(activity.getString(R.string.flow_deny));
        addAction(actions,refresh);addAction(actions,retry);addAction(actions,stop);addAction(actions,once);addAction(actions,deny);body.addView(actions);
        Button continueTask=button(activity.getString(R.string.flow_continue_chat)),previous=button(activity.getString(R.string.flow_previous_round)),video=button(activity.getString(R.string.flow_video_storyboard));
        addAction(actions,continueTask);addAction(actions,previous);
        addAction(actions,video);
        LinearLayout history=new LinearLayout(activity);history.setOrientation(LinearLayout.VERTICAL);
        body.addView(history);
        LinearLayout artifacts=new LinearLayout(activity);artifacts.setOrientation(LinearLayout.VERTICAL);
        body.addView(artifacts);
        AlertDialog dialog=ChoiceRows.builder(activity).setTitle(initial.title).setView(scroll(body))
                .setNegativeButton(activity.getString(R.string.flow_close),null).create();
        AgentTaskStore.Task[] latestTask={initial};
        continueWriting.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];
            if(t!=null&&!t.status.terminal())dialog.dismiss();});
        String[] displayInput={inputText(initial)};
        long[] observedVersion={AgentTaskSubmissionExecutor.stateVersion(clientId)};
        statusText.setText(statusCardText(initial));content.setText(detail(initial,displayInput[0]));
        updateResult(resultText,resultCard,initial);
        for(AgentTaskStore.Task child:tasks.children(clientId)){
            String input=inputText(child);String summary=input.length()>48?input.substring(0,48)+"…":input;
            Button open=button(activity.getString(R.string.flow_open_followup_round,status(child.status))+" · "+summary);
            open.setOnClickListener(v->{dialog.dismiss();showDetail(child.clientTaskId);});history.addView(open);
        }
        AtomicBoolean polling=new AtomicBoolean();
        long[] retryDelay={2500L};
        Runnable[] update=new Runnable[1];
        update[0]=()->{
            long version=AgentTaskSubmissionExecutor.stateVersion(clientId);
            if(version!=observedVersion[0]){AgentTaskStore.Task reloaded=tasks.get(clientId);if(reloaded!=null){latestTask[0]=reloaded;content.setText(detail(reloaded,displayInput[0]));updateResult(resultText,resultCard,reloaded);observedVersion[0]=version;}}
            AgentTaskStore.Task task=latestTask[0];if(task==null||!dialog.isShowing())return;
            boolean canReturnToWriting=(task.noteId==null||task.noteId.isEmpty()||sourceNoteOpener==null)
                    &&!task.status.terminal();
            continueWriting.setVisibility(canReturnToWriting?View.VISIBLE:View.GONE);
            statusText.setText(statusCardText(task));
            updateResult(resultText,resultCard,task);
            appendResult.setVisibility(task.status==AgentTaskStore.Status.COMPLETED
                    &&task.output!=null&&!task.output.trim().isEmpty()&&resultAppender!=null
                    ?View.VISIBLE:View.GONE);
            boolean inFlight=AgentTaskSubmissionExecutor.isInFlight(task.clientTaskId);
            boolean bridgeRecovery=canOfferSameTaskRecovery(task,
                    tasks.submissionAttemptState(task.clientTaskId),inFlight);
            boolean savedUnsent=canSendSavedTask(task,tasks.submissionAttemptState(task.clientTaskId),inFlight);
            retry.setVisibility(bridgeRecovery||savedUnsent?View.VISIBLE:View.GONE);
            retry.setText(bridgeRecovery?activity.getString(R.string.flow_recover_same_task):savedUnsent?
                    activity.getString(R.string.flow_send_saved_task):activity.getString(R.string.flow_retry_submit));
            refresh.setVisibility(canRefreshRemoteStatus(task)?View.VISIBLE:View.GONE);
            copyDiagnostic.setOnClickListener(v->{AgentTaskStore.Task latest=latestTask[0];if(latest!=null){
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PadNote task diagnostics",diagnostic(latest)));
                toast(activity.getString(R.string.flow_diagnostic_copied));}});
            video.setVisibility(videoBundleValid&&!task.remoteTaskId.isEmpty()?View.VISIBLE:View.GONE);
            AgentConnectionStore.Config bound=null;
            try{bound=taskConnectionSource.get(task.connectionId);}catch(Exception ignored){}
            boolean canContinue=task.transport==AgentConnectionStore.Transport.BRIDGE&&
                    task.status==AgentTaskStore.Status.COMPLETED&&task.followupAvailable&&
                    task.childClientTaskId.isEmpty()&&task.matches(bound);
            continueTask.setVisibility(canContinue?View.VISIBLE:View.GONE);
            previous.setVisibility(task.parentClientTaskId.isEmpty()?View.GONE:View.VISIBLE);
            stop.setVisibility(!task.status.terminal()&&!task.remoteTaskId.isEmpty()?View.VISIBLE:View.GONE);
            boolean approval=task.status==AgentTaskStore.Status.WAITING_FOR_APPROVAL&&!task.approvalId.isEmpty();
            once.setVisibility(approval?View.VISIBLE:View.GONE);deny.setVisibility(approval?View.VISIBLE:View.GONE);
            artifacts.removeAllViews();
            if(artifactSaveLauncher!=null)for(AgentTaskStore.Artifact artifact:task.artifacts){
                Button save=button(activity.getString(R.string.flow_save_artifact,artifact.name,size(artifact.sizeBytes)));
                save.setOnClickListener(v->artifactSaveLauncher.choose(task,artifact));artifacts.addView(save);
            }
            if(!shouldContinueObserving(task.status,task.remoteTaskId,inFlight))return;
            if(activity.getWindow().getDecorView().getWindowVisibility()!=View.VISIBLE){
                main.postDelayed(update[0],2500L);return;
            }
            if(task.remoteTaskId.isEmpty()&&inFlight) {
                main.postDelayed(update[0],1500L);return;
            }
        if(!task.remoteTaskId.isEmpty()&&polling.compareAndSet(false,true)){
            try {
                STATUS_REFRESH_EXECUTOR.execute(()->{
                    boolean success=false;AgentTaskStore.Task fresh=null;
                    try{success=refresh(task);fresh=tasks.get(clientId);}catch(RuntimeException ignored){}
                    finally{polling.set(false);}
                    final boolean refreshSucceeded=success;final AgentTaskStore.Task refreshed=fresh;
                    retryDelay[0]=refreshSucceeded?2500L:Math.min(30000L,retryDelay[0]*2L);
                    main.post(()->{if(refreshed!=null){latestTask[0]=refreshed;content.setText(detail(refreshed,displayInput[0]));}observedVersion[0]=AgentTaskSubmissionExecutor.stateVersion(clientId);main.postDelayed(update[0],retryDelay[0]);});
                });
            } catch(RejectedExecutionException rejected) {
                polling.set(false);
                if(dialog.isShowing())main.postDelayed(update[0],1500L);
            }
        }
        };
        refresh.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t==null)return;
            if(!polling.compareAndSet(false,true)){toast(activity.getString(R.string.flow_task_queue_busy));return;}
            try{STATUS_REFRESH_EXECUTOR.execute(()->{
                AgentTaskStore.Task fresh=null;
                try{refresh(t);fresh=tasks.get(clientId);}catch(RuntimeException ignored){}
                finally{polling.set(false);}
                final AgentTaskStore.Task refreshed=fresh;
                main.post(()->{if(refreshed!=null){latestTask[0]=refreshed;content.setText(detail(refreshed,displayInput[0]));}
                    observedVersion[0]=AgentTaskSubmissionExecutor.stateVersion(clientId);
                    update[0].run();});
            });}catch(RejectedExecutionException rejected){polling.set(false);toast(activity.getString(R.string.flow_task_queue_busy));}
        });
        openSource.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t!=null&&sourceNoteOpener.open(t.noteId))dialog.dismiss();else toast(activity.getString(R.string.flow_no_source_note));});
        openTaskList.setOnClickListener(v->{dialog.dismiss();showList();});
        appendResult.setOnClickListener(v->{AgentTaskStore.Task task=latestTask[0];
            ResultAppender appender=resultAppender;
            if(task!=null&&appender!=null&&task.status==AgentTaskStore.Status.COMPLETED
                    &&task.output!=null&&!task.output.trim().isEmpty())appender.append(task.clientTaskId);});
        retry.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t==null)return;
            int attempt=tasks.submissionAttemptState(t.clientTaskId);
            boolean bridgeRecovery=canOfferSameTaskRecovery(t,attempt,
                    AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId));
            boolean savedUnsent=canSendSavedTask(t,attempt,
                    AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId));
            int title=bridgeRecovery?R.string.flow_recover_same_task:savedUnsent?
                    R.string.flow_send_saved_task:R.string.flow_retry_submit;
            int message=bridgeRecovery?R.string.flow_recover_same_task_warning:savedUnsent?
                    R.string.flow_send_saved_task_warning:R.string.flow_retry_same_id_warning;
            ChoiceRows.builder(activity).setTitle(activity.getString(title))
                    .setMessage(activity.getString(message))
                    .setNegativeButton(activity.getString(R.string.flow_cancel),null)
                    .setPositiveButton(activity.getString(bridgeRecovery?
                                    R.string.flow_confirm_recover_same_task:savedUnsent?
                                            R.string.flow_send_saved_task_confirm:R.string.flow_retry_submit),(d,w)->{
                        AgentTaskStore.Task current=tasks.get(t.clientTaskId);
                        int latestAttempt=current==null?-1:tasks.submissionAttemptState(current.clientTaskId);
                        boolean stillEligible=AgentTaskSubmissionExecutor.sameSubmissionSnapshot(t,current)&&
                                (bridgeRecovery?canOfferSameTaskRecovery(current,latestAttempt,
                                        AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId)):
                                        canSendSavedTask(current,latestAttempt,
                                                AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId)));
                        AgentConnectionStore.Config latestConnection=null;
                        try{if(current!=null)latestConnection=taskConnectionSource.get(current.connectionId);}
                        catch(RuntimeException ignored){}
                        if(!stillEligible||!current.matches(latestConnection)){
                            if(current!=null){
                                latestTask[0]=current;
                                statusText.setText(statusCardText(current));
                                content.setText(detail(current,displayInput[0]));
                            }
                            toast(activity.getString(R.string.flow_task_changed_during_submit));
                            update[0].run();
                            return;
                        }
                        if(!AgentTaskSubmissionExecutor.recoverSameTask(current,bridgeRecovery,tasks,
                                taskConnectionSource,taskClientFactory,activity.getApplicationContext()))
                            toast(activity.getString(R.string.flow_task_queue_busy));
                        update[0].run();
                    }).show();});
        continueTask.setOnClickListener(v->askFollowup(clientId,dialog));
        previous.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null&&!t.parentClientTaskId.isEmpty()){dialog.dismiss();showDetail(t.parentClientTaskId);}});
        video.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)videoDialogFactory.create(activity,connections,tasks,worker).show(t);});
        stop.setOnClickListener(v->control(clientId,"stop",dialog));once.setOnClickListener(v->control(clientId,"once",dialog));deny.setOnClickListener(v->control(clientId,"deny",dialog));
        dialog.setOnDismissListener(d->main.removeCallbacks(update[0]));dialog.show();update[0].run();
    }

    private LinearLayout paperCard(){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(android.graphics.Color.rgb(255,254,250));background.setCornerRadius(dp(14));
        background.setStroke(dp(1),android.graphics.Color.rgb(224,220,211));card.setBackground(background);
        card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setElevation(dp(1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.bottomMargin=dp(12);card.setLayoutParams(lp);return card;
    }

    private void updateResult(TextView resultText,View resultCard,AgentTaskStore.Task task){
        String output=task.output==null?"":task.output;
        resultCard.setVisibility(output.trim().isEmpty()?View.GONE:View.VISIBLE);
        resultText.setText(output.isEmpty()?"":activity.getString(R.string.flow_result_label)+"\n"+output);
    }

    private String statusCardText(AgentTaskStore.Task task){AgentTaskSubmissionExecutor.ProgressInfo progress=AgentTaskSubmissionExecutor.progressInfo(task.clientTaskId);long updated=Math.max(task.updatedAt,progress==null?tasks.submissionAttemptTime(task.clientTaskId):progress.updatedAt);long age=Math.max(0,(System.currentTimeMillis()-updated)/1000L);return taskStatus(task)+"\n"+
            task.connectionName+"\n"+
            activity.getString(R.string.flow_stage_updated_ago,age);}

    private boolean refresh(AgentTaskStore.Task task) {
        try{AgentConnectionStore.Config c=taskConnectionSource.get(task.connectionId);if(!task.matches(c)){
            tasks.markLocal(task.clientTaskId,AgentTaskStore.Status.INTERRUPTED,activity.getString(R.string.flow_connection_revoked));return true;}
            AgentTaskClient.RemoteStatus remote=taskClientFactory.create().status(task,c);
            AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException(
                    activity.getString(R.string.flow_task_refresh_changed));
            tasks.applyStatus(task.clientTaskId,task.connectionId,task.connectionRevision,
                    task.remoteTaskId,remote);
            return true;
        }catch(Exception e){try{tasks.markError(task.clientTaskId,task.updatedAt,
                activity.getString(R.string.flow_task_refresh_error,message(e)));}catch(Exception ignored){}return false;}
    }

    private void control(String id,String action,AlertDialog dialog){
        AgentTaskStore.Task task=tasks.get(id);if(task==null)return;worker.execute(()->{try{
            AgentConnectionStore.Config c=taskConnectionSource.get(task.connectionId);if(!task.matches(c))throw new IllegalStateException(activity.getString(R.string.flow_connection_identity_changed));
            AgentTaskClient client=taskClientFactory.create();
            AgentTaskClient.RemoteStatus remote="stop".equals(action)
                    ?client.stop(task,c):client.approve(task,c,action);
            AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException(activity.getString(R.string.flow_task_operation_changed));
            tasks.applyControlStatus(task,remote);
        }catch(Exception e){try{tasks.markError(id,task.updatedAt,
                activity.getString(R.string.flow_task_operation_error,message(e)));}catch(Exception ignored){}}
            main.post(()->{dialog.dismiss();showDetail(id);});});
    }

    private String detail(AgentTaskStore.Task t,String input){
        StringBuilder s=new StringBuilder();
        if(!t.parentClientTaskId.isEmpty())s.append(activity.getString(R.string.flow_followup_from_label,round(t)-1));
        if(t.transport==AgentConnectionStore.Transport.BRIDGE){
            if(s.length()>0)s.append("\n");
            s.append(activity.getString(R.string.flow_continue_status_label)).append(": ")
                    .append(t.followupAvailable?activity.getString(R.string.flow_computer_can_continue):
                            (t.followupReason.isEmpty()?activity.getString(R.string.flow_not_available):t.followupReason));
        }
        if(!input.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_round_note)).append("\n").append(input);
        if(!t.approvalTitle.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_pending_approval_label))
                .append(" ").append(t.approvalTitle).append("\n").append(t.approvalDescription);
        if(!t.output.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_result_label)).append("\n").append(t.output);
        if(!t.error.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_tip_label)).append(" ").append(t.error);
        if(!t.artifacts.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_artifacts_count,t.artifacts.size()));
        return s.toString().trim();
    }
    static boolean computerHasReceipt(AgentTaskStore.Status status,String remoteTaskId){return status==AgentTaskStore.Status.SUBMITTING&&remoteTaskId!=null&&!remoteTaskId.isEmpty();}
    static boolean canRefreshRemoteStatus(AgentTaskStore.Task task){
        return task!=null&&!task.status.terminal()&&task.remoteTaskId!=null&&!task.remoteTaskId.isEmpty();
    }
    static boolean shouldContinueObserving(AgentTaskStore.Status status,String remoteTaskId,boolean inFlight){
        return status!=null&&!status.terminal()&&((remoteTaskId!=null&&!remoteTaskId.isEmpty())||inFlight);
    }
    static boolean canOfferSameTaskRecovery(AgentTaskStore.Task task,int attemptState,boolean inFlight){
        return task!=null&&task.transport==AgentConnectionStore.Transport.BRIDGE&&
                task.status==AgentTaskStore.Status.SUBMITTING&&task.remoteTaskId.isEmpty()&&
                attemptState!=0&&!inFlight;
    }
    static boolean canSendSavedTask(AgentTaskStore.Task task,int attemptState,boolean inFlight){
        return task!=null&&task.status==AgentTaskStore.Status.SUBMITTING&&task.remoteTaskId.isEmpty()&&
                attemptState==0&&!inFlight;
    }
    private String taskStatus(AgentTaskStore.Task t){if(computerHasReceipt(t.status,t.remoteTaskId))return activity.getString(R.string.flow_status_computer_received);if(t.status==AgentTaskStore.Status.SUBMITTING){AgentTaskSubmissionExecutor.ProgressInfo p=AgentTaskSubmissionExecutor.progressInfo(t.clientTaskId);AgentTaskSubmissionExecutor.Phase live=AgentTaskSubmissionExecutor.visibleProgressPhase(AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId),p==null?null:p.phase);if(live!=null)switch(live){case QUEUED:return activity.getString(R.string.flow_submission_queued);case CONNECTING:return activity.getString(R.string.flow_connecting_computer);case UPLOADING:return activity.getString(R.string.flow_upload_bytes,p.bytes());case WAITING_FOR_CONFIRMATION:return activity.getString(R.string.flow_waiting_computer_confirmation);default:break;}int attempt=tasks.submissionAttemptState(t.clientTaskId);if(attempt==0)return activity.getString(R.string.flow_saved_not_sent);String unknown=activity.getString(R.string.flow_submission_result_unknown);if(t.transport!=AgentConnectionStore.Transport.BRIDGE)return unknown+"\n"+activity.getString(R.string.flow_direct_unknown_check_computer);return unknown;}return status(t.status);}
    private String diagnostic(AgentTaskStore.Task t){
        long bytes=base64PayloadBytes(t);
        String stage=taskStatus(t);
        String error=t.error.replaceAll("(?i)(bearer\\s+)[^\\s,;]+","$1[redacted]")
                .replaceAll("(?i)(token|authorization|credential)([=: ]+)[^\\s,;]+","$1$2[redacted]")
                .replaceAll("https?://[^\\s]+","[url]")
                .replaceAll("(?i)(?:[A-Z]:\\\\|/(?:Users|home|private)/)[^\\s]+","[path]")
                .replaceAll("[\\r\\n]"," ");
        if(error.length()>240)error=error.substring(0,240);
        String instance=t.instanceId.length()>8?t.instanceId.substring(0,8):t.instanceId;
        AgentTaskSubmissionExecutor.ProgressInfo transfer=AgentTaskSubmissionExecutor.progressInfo(t.clientTaskId);
        String sent=transfer==null?"":transfer.bytes();
        long progressAt=Math.max(transfer==null?tasks.submissionAttemptTime(t.clientTaskId):transfer.updatedAt,t.updatedAt);
        int attemptState=tasks.submissionAttemptState(t.clientTaskId);
        String attempt=!t.remoteTaskId.isEmpty()?"accepted":(attemptState==1?"yes; receipt pending":(attemptState==0?"no":"unknown"));
        if(AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId))attempt+=AgentTaskSubmissionExecutor.isActive(t.clientTaskId)?"; active":"; queued";
        return "clientTaskId="+t.clientTaskId+"\nremoteTaskId="+t.remoteTaskId+"\nstage="+stage+"\nstageUpdatedAt="+(progressAt>0?progressAt:t.updatedAt)+
                "\npackageBytes="+bytes+"\nsubmissionAttempt="+attempt+
                "\nuploadBytes="+sent+
                "\nconnectionRevision="+t.connectionRevision+"\nnoteRevision="+t.noteRevision+
                "\nconnectionId="+t.connectionId+"\nbridgeId="+t.bridgeId+"\ninstanceId="+t.instanceId+
                "\nhost="+AgentTaskDestinationLabel.diagnosticHost(t)+
                "\nbundleSha256="+bundleSha(t)+
                "\ntarget="+t.connectionName+"\ninstanceShort="+instance+"\nerror="+error;
    }
    private long base64PayloadBytes(AgentTaskStore.Task task){int start=jsonStringStart(task.submissionJson,"bundle_base64");if(start<0)return 0;int end=task.submissionJson.indexOf('"',start);if(end<start)return 0;long encoded=end-start;int padding=0;if(end>start&&task.submissionJson.charAt(end-1)=='=')padding++;if(end-start>1&&task.submissionJson.charAt(end-2)=='=')padding++;return encoded*3/4-padding;}
    private String bundleSha(AgentTaskStore.Task task){int start=jsonStringStart(task.submissionJson,"bundle_sha256");if(start<0)return "";int end=task.submissionJson.indexOf('"',start);return end<start?"":task.submissionJson.substring(start,end);}
    private int jsonStringStart(String json,String key){String marker="\""+key+"\"";int field=json.indexOf(marker);if(field<0)return -1;int colon=json.indexOf(':',field+marker.length());if(colon<0)return -1;int quote=json.indexOf('"',colon+1);return quote<0?-1:quote+1;}
    private String inputText(AgentTaskStore.Task t){try{return new org.json.JSONObject(t.submissionJson).optString("input","");}catch(Exception ignored){return "";}}
    private int round(AgentTaskStore.Task t){int result=1;while(!t.parentClientTaskId.isEmpty()){result++;AgentTaskStore.Task p=tasks.get(t.parentClientTaskId);if(p==null)break;t=p;}return result;}
    private static String size(long bytes){if(bytes>=1024L*1024L)return String.format(java.util.Locale.getDefault(),"%.1f MiB",bytes/(1024f*1024f));if(bytes>=1024L)return String.format(java.util.Locale.getDefault(),"%.1f KiB",bytes/1024f);return bytes+" B";}
    private String status(AgentTaskStore.Status s){switch(s){case SUBMITTING:return activity.getString(R.string.flow_status_submitting);case RUNNING:return activity.getString(R.string.flow_status_running);case WAITING_FOR_APPROVAL:return activity.getString(R.string.flow_status_waiting);case STOPPING:return activity.getString(R.string.flow_status_stopping);case COMPLETED:return activity.getString(R.string.flow_status_completed);case FAILED:return activity.getString(R.string.flow_status_failed);case CANCELLED:return activity.getString(R.string.flow_status_cancelled);default:return activity.getString(R.string.flow_status_interrupted);}}
    private Button button(String text){Button b=new Button(activity);b.setText(text);return b;}
    private Button primaryButton(String text){
        Button button=new Button(activity);button.setText(text);button.setAllCaps(false);
        button.setTextColor(android.graphics.Color.WHITE);button.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        button.setTextSize(14);button.setMinHeight(dp(48));button.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(PadNoteUi.ACCENT);background.setCornerRadius(dp(19));button.setBackground(background);
        return button;
    }
    private void addAction(LinearLayout parent,Button button){parent.addView(button,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));}
    private ScrollView scroll(View v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private void toast(String m){Toast.makeText(activity,m,Toast.LENGTH_LONG).show();}
    private String message(Throwable e){return e.getMessage()==null?activity.getString(R.string.flow_unknown_error):e.getMessage();}
}
