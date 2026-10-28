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
import java.lang.ref.WeakReference;

/** Persistent Agent task list/detail with bounded foreground polling. */
final class AgentTaskDialogs {
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
        TextView content=new TextView(activity);
        LinearLayout detailCard=paperCard();detailCard.addView(content,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));body.addView(detailCard);
        content.setTextIsSelectable(true);
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);
        Button openSource=button(activity.getString(R.string.flow_return_to_note));
        if(initial.noteId.isEmpty()||sourceNoteOpener==null)openSource.setVisibility(View.GONE);
        addAction(actions,openSource);
        Button openTaskList=button(activity.getString(R.string.flow_open_task_list));addAction(actions,openTaskList);
        Button copyDiagnostic=button(activity.getString(R.string.flow_copy_diagnostic));addAction(actions,copyDiagnostic);
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
        String[] displayInput={inputText(initial)};
        long[] observedVersion={AgentTaskSubmissionExecutor.stateVersion(clientId)};
        statusText.setText(statusCardText(initial));content.setText(detail(initial,displayInput[0]));
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
            if(version!=observedVersion[0]){AgentTaskStore.Task reloaded=tasks.get(clientId);if(reloaded!=null){latestTask[0]=reloaded;content.setText(detail(reloaded,displayInput[0]));observedVersion[0]=version;}}
            AgentTaskStore.Task task=latestTask[0];if(task==null||!dialog.isShowing())return;
            statusText.setText(statusCardText(task));
            retry.setVisibility(task.status==AgentTaskStore.Status.SUBMITTING&&
                    task.remoteTaskId.isEmpty()&&!AgentTaskSubmissionExecutor.isInFlight(task.clientTaskId)?View.VISIBLE:View.GONE);
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
            if(activity.getWindow().getDecorView().getWindowVisibility()!=View.VISIBLE){
                main.postDelayed(update[0],2500L);return;
            }
            if(!task.status.terminal()&&task.remoteTaskId.isEmpty()) {
                main.postDelayed(update[0],1500L);return;
            }
            if(!task.status.terminal()&&!task.remoteTaskId.isEmpty()&&polling.compareAndSet(false,true))
                worker.execute(()->{boolean success=refresh(task);AgentTaskStore.Task fresh=tasks.get(clientId);polling.set(false);
                    retryDelay[0]=success?2500L:Math.min(30000L,retryDelay[0]*2L);
                    main.post(()->{if(fresh!=null){latestTask[0]=fresh;content.setText(detail(fresh,displayInput[0]));}observedVersion[0]=AgentTaskSubmissionExecutor.stateVersion(clientId);main.postDelayed(update[0],retryDelay[0]);});});
        };
        refresh.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t!=null)worker.execute(()->{if(refresh(t))retryDelay[0]=2500L;AgentTaskStore.Task fresh=tasks.get(clientId);main.post(()->{if(fresh!=null){latestTask[0]=fresh;content.setText(detail(fresh,displayInput[0]));}observedVersion[0]=AgentTaskSubmissionExecutor.stateVersion(clientId);update[0].run();});});});
        openSource.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t!=null&&sourceNoteOpener.open(t.noteId))dialog.dismiss();else toast(activity.getString(R.string.flow_no_source_note));});
        openTaskList.setOnClickListener(v->{dialog.dismiss();showList();});
        retry.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t==null)return;ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_retry_submit)).setMessage(activity.getString(R.string.flow_retry_same_id_warning)).setNegativeButton(activity.getString(R.string.flow_cancel),null).setPositiveButton(activity.getString(R.string.flow_retry_submit),(d,w)->{dialog.dismiss();submit(t);}).show();});
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

    private String statusCardText(AgentTaskStore.Task task){AgentTaskSubmissionExecutor.ProgressInfo progress=AgentTaskSubmissionExecutor.progressInfo(task.clientTaskId);long updated=Math.max(task.updatedAt,progress==null?tasks.submissionAttemptTime(task.clientTaskId):progress.updatedAt);long age=Math.max(0,(System.currentTimeMillis()-updated)/1000L);return taskStatus(task)+"\n"+
            activity.getString(R.string.flow_target)+": "+AgentTaskDestinationLabel.forTask(activity,task)+"\n"+
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
            AgentTaskClient client=taskClientFactory.create();if("stop".equals(action))client.stop(task,c);
            else client.approve(task,c,action);
            AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException(activity.getString(R.string.flow_task_operation_changed));
            if("stop".equals(action))tasks.markLocal(id,AgentTaskStore.Status.STOPPING,"");
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
    private String taskStatus(AgentTaskStore.Task t){if(t.status==AgentTaskStore.Status.SUBMITTING&&t.remoteTaskId.isEmpty()){AgentTaskSubmissionExecutor.ProgressInfo p=AgentTaskSubmissionExecutor.progressInfo(t.clientTaskId);if(p!=null)switch(p.phase){case QUEUED:return activity.getString(R.string.flow_submission_queued);case CONNECTING:return activity.getString(R.string.flow_connecting_computer);case UPLOADING:return activity.getString(R.string.flow_upload_bytes,p.bytes());case WAITING_FOR_CONFIRMATION:return activity.getString(R.string.flow_waiting_computer_confirmation);case COMPUTER_ACCEPTED:break;case COMPUTER_REJECTED:case RESULT_UNCONFIRMED:return activity.getString(R.string.flow_submission_unconfirmed);}return tasks.submissionAttemptState(t.clientTaskId)==0?activity.getString(R.string.flow_saved_not_sent):activity.getString(R.string.flow_submission_unconfirmed);}return status(t.status);}
    private String diagnostic(AgentTaskStore.Task t){
        long bytes=base64PayloadBytes(t);
        String stage=t.remoteTaskId.isEmpty()?taskStatus(t):status(t.status);
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
                "\nbundleSha256="+bundleSha(t)+
                "\ntarget="+t.connectionName+"\ninstance="+instance+"\nerror="+error;
    }
    private long base64PayloadBytes(AgentTaskStore.Task task){int start=jsonStringStart(task.submissionJson,"bundle_base64");if(start<0)return 0;int end=task.submissionJson.indexOf('"',start);if(end<start)return 0;long encoded=end-start;int padding=0;if(end>start&&task.submissionJson.charAt(end-1)=='=')padding++;if(end-start>1&&task.submissionJson.charAt(end-2)=='=')padding++;return encoded*3/4-padding;}
    private String bundleSha(AgentTaskStore.Task task){int start=jsonStringStart(task.submissionJson,"bundle_sha256");if(start<0)return "";int end=task.submissionJson.indexOf('"',start);return end<start?"":task.submissionJson.substring(start,end);}
    private int jsonStringStart(String json,String key){String marker="\""+key+"\"";int field=json.indexOf(marker);if(field<0)return -1;int colon=json.indexOf(':',field+marker.length());if(colon<0)return -1;int quote=json.indexOf('"',colon+1);return quote<0?-1:quote+1;}
    private String inputText(AgentTaskStore.Task t){try{return new org.json.JSONObject(t.submissionJson).optString("input","");}catch(Exception ignored){return "";}}
    private int round(AgentTaskStore.Task t){int result=1;while(!t.parentClientTaskId.isEmpty()){result++;AgentTaskStore.Task p=tasks.get(t.parentClientTaskId);if(p==null)break;t=p;}return result;}
    private static String size(long bytes){if(bytes>=1024L*1024L)return String.format(java.util.Locale.getDefault(),"%.1f MiB",bytes/(1024f*1024f));if(bytes>=1024L)return String.format(java.util.Locale.getDefault(),"%.1f KiB",bytes/1024f);return bytes+" B";}
    private String status(AgentTaskStore.Status s){switch(s){case SUBMITTING:return activity.getString(R.string.flow_status_submitting);case RUNNING:return activity.getString(R.string.flow_status_running);case WAITING_FOR_APPROVAL:return activity.getString(R.string.flow_status_waiting);case STOPPING:return activity.getString(R.string.flow_status_stopping);case COMPLETED:return activity.getString(R.string.flow_status_completed);case FAILED:return activity.getString(R.string.flow_status_failed);case CANCELLED:return activity.getString(R.string.flow_status_cancelled);default:return activity.getString(R.string.flow_status_interrupted);}}
    private Button button(String text){Button b=new Button(activity);b.setText(text);return b;}
    private void addAction(LinearLayout parent,Button button){parent.addView(button,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));}
    private ScrollView scroll(View v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private void toast(String m){Toast.makeText(activity,m,Toast.LENGTH_LONG).show();}
    private String message(Throwable e){return e.getMessage()==null?activity.getString(R.string.flow_unknown_error):e.getMessage();}
}
