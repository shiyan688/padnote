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
        if(values.isEmpty()){new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_history_title))
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
            new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_computer_tasks)).setMessage(message)
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
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_computer_tasks))
                .setAdapter(multilineRows(labels),(d,w)->{if(w>=warningRows)
                    showDetail(values.get(w-warningRows).clientTaskId);})
                .setNegativeButton(activity.getString(R.string.flow_close),null).show();
    }

    private String historyRow(AgentTaskStore.Task task) {
        return task.title+"\n"+status(task.status)+"\n"+activity.getString(R.string.flow_task_target_line,
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
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_send_text_to,destinationLabel(activity, profile))).setView(input)
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
                    new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_confirm_package))
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
        worker.execute(()->{
            final AgentTaskStore.Task task;
            try{task=tasks.create(selected,title,input,noteId,frozen.noteRevision,
                    frozen.zip.clone(),"note_context_bundle");}
            catch(Exception error){main.post(()->toast(activity.getString(R.string.flow_task_create_failed,message(error))));return;}
            main.post(()->submit(task));
        });
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
        worker.execute(()->{
            final AgentConnectionStore.Config connection;
            try{connection=taskConnectionSource.get(task.connectionId);}
            catch(Exception error){
                boolean persisted=false;
                try{persisted=tasks.markFailed(task.clientTaskId,task.updatedAt,
                        activity.getString(R.string.flow_task_read_target_failed));}
                catch(Exception ignored){}
                final boolean failureRecorded=persisted;
                main.post(()->{
                    if(!failureRecorded){
                        toast(activity.getString(R.string.flow_task_state_save_failed));
                        return;
                    }
                    showDetail(task.clientTaskId);
                });return;
            }
            if(!task.matches(connection)){try{tasks.markLocal(task.clientTaskId,
                    AgentTaskStore.Status.INTERRUPTED,activity.getString(R.string.flow_identity_changed_new));}catch(Exception ignored){}
                main.post(()->showDetail(task.clientTaskId));return;}
            try{
                AgentTaskClient.Submission result=taskClientFactory.create().submit(task,connection);
                AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
                if(!task.matches(latest))throw new IllegalStateException(
                        activity.getString(R.string.flow_task_changed_during_submit));
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
                        activity.getString(R.string.flow_submit_unknown,message(e)));}catch(Exception ignored){}
            }
            main.post(()->showDetail(task.clientTaskId));
        });
    }

    private void askFollowup(String parentId,AlertDialog parentDialog) {
        AgentTaskStore.Task parent=tasks.get(parentId);if(parent==null)return;
        EditText input=new EditText(activity);input.setHint(activity.getString(R.string.flow_followup_hint));
        input.setMinLines(4);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_continue_chat)).setView(input)
                .setNegativeButton(activity.getString(R.string.flow_cancel),null).setPositiveButton(activity.getString(R.string.flow_send),(d,w)->{
                    String text=input.getText().toString().trim();if(text.isEmpty()){toast(activity.getString(R.string.flow_followup_empty));return;}
                    AgentConnectionStore.Config connection=taskConnectionSource.get(parent.connectionId);
                    if(!parent.matches(connection)){toast(activity.getString(R.string.flow_identity_changed_followup));return;}
                    try {
                        AgentTaskStore.Task child=tasks.createFollowup(parent,connection,text);
                        parentDialog.dismiss();
                        if(child.status==AgentTaskStore.Status.SUBMITTING)submit(child);
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
        TextView content=new TextView(activity);body.addView(content,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);
        Button openSource=button(activity.getString(R.string.flow_open_source_note));
        if(initial.noteId.isEmpty()||sourceNoteOpener==null)openSource.setVisibility(View.GONE);
        addAction(actions,openSource);
        Button refresh=button(activity.getString(R.string.flow_refresh)),retry=button(activity.getString(R.string.flow_retry_submit)),stop=button(activity.getString(R.string.flow_stop)),once=button(activity.getString(R.string.flow_closed_approval)),deny=button(activity.getString(R.string.flow_deny));
        addAction(actions,refresh);addAction(actions,retry);addAction(actions,stop);addAction(actions,once);addAction(actions,deny);body.addView(actions);
        Button continueTask=button(activity.getString(R.string.flow_continue_chat)),previous=button(activity.getString(R.string.flow_previous_round)),video=button(activity.getString(R.string.flow_video_storyboard));
        addAction(actions,continueTask);addAction(actions,previous);
        addAction(actions,video);
        LinearLayout history=new LinearLayout(activity);history.setOrientation(LinearLayout.VERTICAL);
        body.addView(history);
        LinearLayout artifacts=new LinearLayout(activity);artifacts.setOrientation(LinearLayout.VERTICAL);
        body.addView(artifacts);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(initial.title).setView(scroll(body))
                .setNegativeButton(activity.getString(R.string.flow_close),null).create();
        AtomicBoolean polling=new AtomicBoolean();
        long[] retryDelay={2500L};
        Runnable[] update=new Runnable[1];
        update[0]=()->{
            AgentTaskStore.Task task=tasks.get(clientId);if(task==null||!dialog.isShowing())return;
            content.setText(detail(task));retry.setVisibility(task.status==AgentTaskStore.Status.SUBMITTING?View.VISIBLE:View.GONE);
            video.setVisibility(videoBundleValid&&!task.remoteTaskId.isEmpty()?View.VISIBLE:View.GONE);
            AgentConnectionStore.Config bound=null;
            try{bound=taskConnectionSource.get(task.connectionId);}catch(Exception ignored){}
            boolean canContinue=task.transport==AgentConnectionStore.Transport.BRIDGE&&
                    task.status==AgentTaskStore.Status.COMPLETED&&task.followupAvailable&&
                    task.childClientTaskId.isEmpty()&&task.matches(bound);
            continueTask.setVisibility(canContinue?View.VISIBLE:View.GONE);
            previous.setVisibility(task.parentClientTaskId.isEmpty()?View.GONE:View.VISIBLE);
            history.removeAllViews();
            for(AgentTaskStore.Task child:tasks.children(clientId)){
                String input=inputText(child);String summary=input.length()>48?input.substring(0,48)+"…":input;
                Button open=button(activity.getString(R.string.flow_open_followup_round,status(child.status))+" · "+summary);
                open.setOnClickListener(v->{dialog.dismiss();showDetail(child.clientTaskId);});history.addView(open);
            }
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
            if(!task.status.terminal()&&!task.remoteTaskId.isEmpty()&&polling.compareAndSet(false,true))
                worker.execute(()->{boolean success=refresh(task);polling.set(false);
                    retryDelay[0]=success?2500L:Math.min(30000L,retryDelay[0]*2L);
                    main.postDelayed(update[0],retryDelay[0]);});
        };
        refresh.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)worker.execute(()->{if(refresh(t))retryDelay[0]=2500L;main.post(update[0]);});});
        openSource.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null&&sourceNoteOpener.open(t.noteId))dialog.dismiss();else toast(activity.getString(R.string.flow_no_source_note));});
        retry.setOnClickListener(v->{dialog.dismiss();AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)submit(t);});
        continueTask.setOnClickListener(v->askFollowup(clientId,dialog));
        previous.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null&&!t.parentClientTaskId.isEmpty()){dialog.dismiss();showDetail(t.parentClientTaskId);}});
        video.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)videoDialogFactory.create(activity,connections,tasks,worker).show(t);});
        stop.setOnClickListener(v->control(clientId,"stop",dialog));once.setOnClickListener(v->control(clientId,"once",dialog));deny.setOnClickListener(v->control(clientId,"deny",dialog));
        dialog.setOnDismissListener(d->main.removeCallbacks(update[0]));dialog.show();update[0].run();
    }

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

    private String detail(AgentTaskStore.Task t){StringBuilder s=new StringBuilder();s.append(activity.getString(R.string.flow_status_label)).append(": ").append(status(t.status)).append("\n").append(activity.getString(R.string.flow_target)).append(": ").append(AgentTaskDestinationLabel.forTask(activity,t)).append("\n").append(activity.getString(R.string.flow_connection_revision_label)).append(": ").append(t.connectionRevision);if(!t.remoteTaskId.isEmpty())s.append("\n").append(activity.getString(R.string.flow_task_id_label)).append(": ").append(t.remoteTaskId);if(!t.noteId.isEmpty()){s.append("\n").append(activity.getString(R.string.flow_source_note_label)).append(": ").append(t.noteId).append(" · ").append(activity.getString(R.string.flow_revision_label)).append(" ").append(t.noteRevision);String bundleSha=bundleSha(t);if(!bundleSha.isEmpty())s.append(" · ").append(activity.getString(R.string.flow_input_bundle_label)).append(" ").append(bundleSha);}if(!t.parentClientTaskId.isEmpty())s.append("\n").append(activity.getString(R.string.flow_followup_from_label,round(t)-1));if(t.transport==AgentConnectionStore.Transport.BRIDGE)s.append("\n").append(activity.getString(R.string.flow_continue_status_label)).append(": ").append(t.followupAvailable?activity.getString(R.string.flow_computer_can_continue):(t.followupReason.isEmpty()?activity.getString(R.string.flow_not_available):t.followupReason));s.append("\n\n").append(activity.getString(R.string.flow_round_note)).append("\n").append(inputText(t));if(!t.approvalTitle.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_pending_approval_label)).append(" ").append(t.approvalTitle).append("\n").append(t.approvalDescription);if(!t.output.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_result_label)).append("\n").append(t.output);if(!t.error.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_tip_label)).append(" ").append(t.error);if(!t.artifacts.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_artifacts_count,t.artifacts.size()));return s.toString();}
    private String bundleSha(AgentTaskStore.Task task){try{return new org.json.JSONObject(task.submissionJson).optString("bundle_sha256","");}catch(Exception ignored){return "";}}
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
